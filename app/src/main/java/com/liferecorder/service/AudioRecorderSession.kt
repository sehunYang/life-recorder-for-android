package com.liferecorder.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.liferecorder.Config
import com.liferecorder.RecorderState
import com.liferecorder.SegmentClock
import com.liferecorder.Storage
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 마이크 녹음. 정각마다 파일을 끊는다.
 *
 * AudioRecord → AAC 인코더(MediaCodec) → ADTS 파일을 앱이 직접 잇는다. MediaRecorder 를 쓰면
 * 같은 일을 mediaserver 가 대신 하는데, 그 비용이 컸다 (실측 2026-09-27, Flip 5, 55초 동안:
 * MediaRecorder 18.3초 CPU vs 직접 11.9~13.4초 — 스테레오 256k 로 올리고도 적다).
 *
 * 마이크와 인코더는 정각에도 멈추지 않는다. ADTS 는 프레임마다 헤더가 붙어 자기완결적이라
 * 프레임 사이에서 파일만 바꾸면 되고, 세그먼트 사이에 빈틈이 생기지 않는다.
 * 프로세스가 죽어도 마지막으로 비운(flush) 곳까지 재생할 수 있다 ([FLUSH_MS] 마다 비운다).
 *
 * 녹음과 인코딩은 전용 스레드 하나에서만 한다. AudioRecord.read 가 막혀서 기다리므로 바쁜 대기가 없다.
 */
class AudioRecorderSession(
    private val ctx: Context,
    private val listener: Listener,
    /** true 를 주는 동안은 마이크를 놓고 기다린다 (알람이 울리게 — [AlarmGuard]). 조각마다 묻는다. */
    private val hold: () -> Boolean = { false },
) {
    interface Listener {
        /** 한 세그먼트(.aac.part)가 닫혔다. 녹음 스레드에서 호출되므로 무거운 일은 넘겨서 처리할 것. */
        fun onSegmentFinished(part: File)
        fun onError(message: String)
        /** 마이크를 놓았다(true) · 다시 잡았다(false). 녹음 스레드에서 호출된다. */
        fun onHold(held: Boolean) {}
    }

    @Volatile var running = false
        private set
    private var thread: Thread? = null
    private var onStopped: (() -> Unit)? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "audio-recorder").apply { start() }
    }

    /** 비동기. 마지막 세그먼트는 listener.onSegmentFinished로 전달된 뒤 onDone이 호출된다. */
    fun stop(onDone: (() -> Unit)? = null) {
        val t = thread
        if (!running || t == null) { onDone?.invoke(); return }
        onStopped = onDone
        running = false
        // read 가 막혀 있어도 한 조각([CHUNK_MS]) 안에 돌아오므로 깨울 필요가 없다.
    }

    /**
     * 실패하면 [RETRY_MS] 뒤 다시 연다. stop() 전까지 계속한다.
     * [hold] 가 true 면 마이크를 놓은 채 기다렸다가 false 가 되면 새 세그먼트로 다시 연다.
     */
    private fun loop() {
        while (running) {
            if (hold()) {
                Log.i(TAG, "holding: microphone released")
                RecorderState.update { it.copy(audioRecording = false) }
                listener.onHold(true)
                while (running && hold()) Thread.sleep(HOLD_POLL_MS)
                listener.onHold(false)
                Log.i(TAG, "hold over: reopening microphone")
                continue
            }
            try {
                record()
                // 정상으로 돌아왔다 = stop() 이거나 hold. 둘 다 곧바로 위에서 가른다.
                continue
            } catch (e: Exception) {
                Log.e(TAG, "recording failed", e)
                RecorderState.update { it.copy(audioRecording = false, audioError = "녹음 오류: ${e.message}") }
                listener.onError("녹음 오류: ${e.message}")
            }
            if (running) sleepWhileRunning(RETRY_MS)
        }
        RecorderState.update { it.copy(audioRecording = false) }
        val cb = onStopped
        onStopped = null
        // 그새 start() 가 새 스레드를 띄웠으면 그것을 지우지 않는다.
        if (thread === Thread.currentThread()) thread = null
        cb?.invoke()
    }

    /** 녹음 스레드에서만 쓴다. */
    private var seg: Segment? = null

    @SuppressLint("MissingPermission") // 권한은 서비스를 켜기 전에 앱이 받는다
    private fun record() {
        val ch = Config.AUDIO_CHANNELS
        val rate = Config.AUDIO_SAMPLE_RATE
        val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val chunk = rate * CHUNK_MS / 1000 * 2 * ch
        val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        var rec: AudioRecord? = null
        var enc: MediaCodec? = null
        try {
            // 무엇이 실패하든 finally 가 마이크를 놓는다. 놓지 않고 5초마다 다시 열면 마이크 핸들이 쌓인다.
            val r = AudioRecord(Config.AUDIO_SOURCE, rate, mask, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 4))
            rec = r
            check(r.state == AudioRecord.STATE_INITIALIZED) { "마이크를 열 수 없습니다" }
            val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc = e
            e.configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, Config.AUDIO_BITRATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, chunk)
                },
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            e.start()
            r.startRecording()
            Log.i(TAG, "capture started: ${e.name}, source=${Config.AUDIO_SOURCE}, ${rate}Hz x$ch, ${Config.AUDIO_BITRATE / 1000}kbps")
            val adts = Adts(rate, ch)
            val pcm = ByteArray(chunk)
            val info = MediaCodec.BufferInfo()
            var pts = 0L
            var eosQueued = false
            while (true) {
                if (!eosQueued) {
                    val got = readChunk(r, pcm)
                    // 세그먼트는 이 조각의 출력을 쓰기 전에 바꾼다. 정각을 넘긴 첫 조각부터 새 파일로 간다.
                    val now = System.currentTimeMillis()
                    val cur = seg
                    if (cur == null || now >= cur.end) {
                        cur?.let(::close)
                        seg = open(now)
                    }
                    // 멈출 때(stop · 알람)는 이 조각을 끝 표시로 넣어 인코더를 비우고 세그먼트를 닫는다.
                    val last = !running || hold()
                    if (!queue(e, pcm, got, pts, last, adts, info)) {
                        // 인코더가 끝내 받지 않았다. 그 조각은 잃지만 시각은 실제 흐름대로 앞으로 간다.
                        Log.w(TAG, "encoder input stalled, dropped ${got / (2 * ch) * 1000 / rate}ms")
                        if (last) break
                    }
                    pts += got / (2L * ch) * 1_000_000L / rate
                    eosQueued = last
                    seg?.maybeFlush(now)
                }
                when (drain(e, adts, info, if (eosQueued) EOS_TIMEOUT_US else 0)) {
                    Drain.END -> break
                    Drain.EMPTY -> if (eosQueued) break
                    Drain.MORE -> {}
                }
            }
        } finally {
            rec?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
            enc?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
            seg?.let(::close)
            seg = null
        }
    }

    /**
     * 한 조각을 채운다. 막혀서 기다리므로 평소에는 바쁜 대기가 없다. 드물게 0 이 계속 돌아오면
     * (경로가 바뀌는 순간 등) 잠깐씩 쉬고, 오래 이어지면 오류로 올려 처음부터 다시 연다.
     */
    private fun readChunk(r: AudioRecord, pcm: ByteArray): Int {
        var got = 0
        var zeros = 0
        while (got < pcm.size) {
            val n = r.read(pcm, got, pcm.size - got)
            if (n < 0) throw IllegalStateException("AudioRecord.read = $n")
            if (n == 0) {
                if (++zeros > MAX_ZERO_READS) throw IllegalStateException("AudioRecord.read 가 계속 0")
                Thread.sleep(ZERO_READ_SLEEP_MS)
                if (!running) break
                continue
            }
            got += n
        }
        return got
    }

    /** 입력 칸이 날 때까지 출력을 비우며 기다린다. [INPUT_WAIT_MS] 안에 못 넣으면 false. */
    private fun queue(
        e: MediaCodec, pcm: ByteArray, got: Int, pts: Long, last: Boolean, adts: Adts, info: MediaCodec.BufferInfo,
    ): Boolean {
        val until = System.currentTimeMillis() + INPUT_WAIT_MS
        while (true) {
            val i = e.dequeueInputBuffer(INPUT_POLL_US)
            if (i >= 0) {
                e.getInputBuffer(i)!!.apply { clear(); put(pcm, 0, got) }
                e.queueInputBuffer(i, 0, got, pts, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                return true
            }
            if (drain(e, adts, info, 0) == Drain.END) return false
            if (System.currentTimeMillis() >= until) return false
        }
    }

    private enum class Drain { MORE, EMPTY, END }

    /** 나와 있는 출력을 모두 지금 세그먼트에 쓴다. */
    private fun drain(e: MediaCodec, adts: Adts, info: MediaCodec.BufferInfo, timeoutUs: Long): Drain {
        var o = e.dequeueOutputBuffer(info, timeoutUs)
        var wrote = false
        while (o != MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (o >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val b = e.getOutputBuffer(o)!!
                    b.position(info.offset)
                    b.limit(info.offset + info.size)
                    seg?.write(adts.header(info.size), b, info.size)
                    wrote = true
                }
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                e.releaseOutputBuffer(o, false)
                if (end) return Drain.END
            }
            // 형식 변경(INFO_OUTPUT_FORMAT_CHANGED)은 ADTS 에 필요 없어 넘긴다.
            o = e.dequeueOutputBuffer(info, 0)
        }
        return if (wrote) Drain.MORE else Drain.EMPTY
    }

    private fun open(now: Long): Segment {
        val part = Storage.newAudioPart(ctx, now)
        val end = SegmentClock.nextBoundary(now)
        RecorderState.update { it.copy(audioRecording = true, audioError = null, currentSegmentStart = now) }
        Log.i(TAG, "segment started: ${part.name}, ends in ${(end - now) / 1000}s")
        return Segment(part, end)
    }

    private fun close(seg: Segment) {
        try { seg.out.close() } catch (e: Exception) { Log.w(TAG, "close failed", e) }
        if (seg.bytes > 0) listener.onSegmentFinished(seg.file) else seg.file.delete()
    }

    private fun sleepWhileRunning(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (running && System.currentTimeMillis() < until) Thread.sleep(200)
    }

    private class Segment(val file: File, val end: Long) {
        val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        var bytes = 0L
        private var flushedAt = System.currentTimeMillis()
        private val frame = ByteArray(8192)

        fun write(header: ByteArray, payload: java.nio.ByteBuffer, size: Int) {
            out.write(header)
            var left = size
            while (left > 0) {
                val n = minOf(left, frame.size)
                payload.get(frame, 0, n)
                out.write(frame, 0, n)
                left -= n
            }
            bytes += header.size + size
        }

        fun maybeFlush(now: Long) {
            if (now - flushedAt < FLUSH_MS) return
            out.flush()
            flushedAt = now
        }
    }

    /** ADTS 헤더 7바이트 (AAC-LC, CRC 없음). 프레임 길이만 바뀐다. */
    private class Adts(rate: Int, private val ch: Int) {
        private val freqIndex = RATES.indexOf(rate).also { require(it >= 0) { "ADTS 에 없는 샘플레이트 $rate" } }
        private val header = ByteArray(7)

        fun header(payload: Int): ByteArray {
            val len = payload + 7
            header[0] = 0xFF.toByte()
            header[1] = 0xF1.toByte() // MPEG-4, CRC 없음
            header[2] = ((1 shl 6) or (freqIndex shl 2) or (ch shr 2)).toByte() // profile LC(1)
            header[3] = (((ch and 3) shl 6) or (len shr 11)).toByte()
            header[4] = ((len shr 3) and 0xFF).toByte()
            header[5] = (((len and 7) shl 5) or 0x1F).toByte()
            header[6] = 0xFC.toByte()
            return header
        }

        companion object {
            private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        }
    }

    companion object {
        private const val TAG = "AudioRecorder"
        private const val RETRY_MS = 5_000L
        /** 한 번에 읽는 양. 길수록 덜 깨어나지만 멈출 때 그만큼 늦는다. */
        private const val CHUNK_MS = 100
        /** 버퍼를 파일로 비우는 간격. 프로세스가 죽으면 이만큼까지 잃는다. */
        private const val FLUSH_MS = 1_000L
        private const val INPUT_POLL_US = 50_000L
        /** 인코더가 이만큼 입력을 받지 않으면 그 조각을 버린다 (녹음은 계속). */
        private const val INPUT_WAIT_MS = 2_000L
        private const val EOS_TIMEOUT_US = 100_000L
        /** read 가 0 을 이만큼 연달아 주면 (약 2초) 처음부터 다시 연다. */
        private const val MAX_ZERO_READS = 100
        private const val ZERO_READ_SLEEP_MS = 20L
        /** 멈춘 동안 다시 열지 묻는 간격. 기기가 잠들면 더 늦어지므로 서비스가 신호 때 잠깐 깨운다. */
        private const val HOLD_POLL_MS = 500L
    }
}
