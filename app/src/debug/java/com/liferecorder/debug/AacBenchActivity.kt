package com.liferecorder.debug

import android.app.Activity
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AAC 인코더 비교 실험 (디버그 빌드 전용). 한 번에 한 방식으로 secs 초 녹음한다.
 *
 *   adb shell am start -n com.liferecorder/.debug.AacBenchActivity --es mode hw --ei secs 60
 *
 * mode:
 *   mr     MediaRecorder, VOICE_RECOGNITION 모노 160kbps (지금 앱과 같다)
 *   hw     AudioRecord CAMCORDER 스테레오 → c2.qti.aac.hw.encoder
 *   sw     AudioRecord CAMCORDER 스테레오 → c2.sec.aac.encoder
 *   caps   인코더 능력만 로그에 적는다
 * hw·sw 는 인코더에 넣은 PCM 을 그대로 WAV 로도 남겨, 디코딩한 결과와 견줄 수 있게 한다.
 */
class AacBenchActivity : Activity() {

    @Volatile private var stop = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val mode = intent.getStringExtra("mode") ?: "caps"
        val secs = intent.getIntExtra("secs", 60)
        val bitrate = intent.getIntExtra("bitrate", 256_000)
        setContentView(TextView(this).apply { textSize = 24f; text = "AAC bench: $mode ${secs}s" })
        Thread({
            try {
                logCaps()
                val dir = File(getExternalFilesDir(null), "aacbench").apply { mkdirs() }
                when (mode) {
                    "mr" -> mediaRecorder(
                        File(dir, "${intent.getStringExtra("tag") ?: "mr"}.aac"), secs,
                        intent.getIntExtra("src", MediaRecorder.AudioSource.VOICE_RECOGNITION),
                        intent.getIntExtra("ch", 1), intent.getIntExtra("bitrate", 160_000),
                    )
                    "lean" -> lean(
                        intent.getStringExtra("codec") ?: "c2.sec.aac.encoder",
                        File(dir, "${intent.getStringExtra("tag") ?: "lean"}.aac"), secs, bitrate,
                        intent.getIntExtra("src", MediaRecorder.AudioSource.CAMCORDER), intent.getIntExtra("ch", 2),
                    )
                    "file" -> encodeFile(
                        intent.getStringExtra("codec") ?: "c2.android.aac.encoder",
                        File(dir, intent.getStringExtra("in") ?: "in.wav"),
                        File(dir, "${intent.getStringExtra("tag") ?: "file"}.aac"), bitrate,
                    )
                    "hw" -> codec("c2.qti.aac.hw.encoder", dir, "hw", secs, bitrate, MediaRecorder.AudioSource.CAMCORDER, 2)
                    "sw" -> codec(
                        intent.getStringExtra("codec") ?: "c2.sec.aac.encoder", dir,
                        intent.getStringExtra("tag") ?: "sw", secs, bitrate,
                        intent.getIntExtra("src", MediaRecorder.AudioSource.CAMCORDER), intent.getIntExtra("ch", 2),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "bench failed", e)
            }
            Log.i(TAG, "done $mode")
            runOnUiThread { finish() }
        }, "aac-bench").start()
    }

    override fun onDestroy() { stop = true; super.onDestroy() }

    private fun logCaps() {
        for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (!info.isEncoder || MediaFormat.MIMETYPE_AUDIO_AAC !in info.supportedTypes) continue
            val c = info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val a = c.audioCapabilities
            Log.i(TAG, "caps ${info.name} hw=${info.isHardwareAccelerated} sw=${info.isSoftwareOnly} " +
                "channels<=${a.maxInputChannelCount} rates=${a.supportedSampleRates?.joinToString()} " +
                "bitrate=${a.bitrateRange} profiles=${c.profileLevels.map { it.profile }.distinct()}")
        }
    }

    private fun mediaRecorder(out: File, secs: Int, src: Int, ch: Int, bitrate: Int) {
        val r = MediaRecorder(this)
        r.setAudioSource(src)
        r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(ch)
        r.setAudioSamplingRate(RATE)
        r.setAudioEncodingBitRate(bitrate)
        r.setOutputFile(out.absolutePath)
        r.prepare(); r.start()
        val end = System.currentTimeMillis() + secs * 1000L
        while (System.currentTimeMillis() < end && !stop) Thread.sleep(200)
        r.stop(); r.release()
    }

    private fun codec(name: String, dir: File, tag: String, secs: Int, bitrate: Int, src: Int, ch: Int) {
        val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createByCodecName(name)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        Log.i(TAG, "$name configured, output=${enc.outputFormat}")
        enc.start()
        val min = AudioRecord.getMinBufferSize(RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            src, RATE, mask,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 4, RATE * 4),
        )
        val wav = RandomAccessFile(File(dir, "$tag.wav"), "rw").apply { setLength(0); write(ByteArray(44)) }
        val aac = FileOutputStream(File(dir, "$tag.aac"))
        val pcm = ByteArray(1024 * 2 * ch) // AAC 한 프레임 = 채널당 1024 샘플
        val info = MediaCodec.BufferInfo()
        var pcmBytes = 0L
        var pts = 0L
        var outBytes = 0L
        rec.startRecording()
        val end = System.currentTimeMillis() + secs * 1000L
        var eos = false
        while (true) {
            if (!eos) {
                val n = rec.read(pcm, 0, pcm.size)
                val done = System.currentTimeMillis() >= end || stop
                if (n > 0) { wav.write(pcm, 0, n); pcmBytes += n }
                val i = enc.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val b = enc.getInputBuffer(i)!!
                    b.clear()
                    if (n > 0) b.put(pcm, 0, n)
                    enc.queueInputBuffer(i, 0, maxOf(n, 0), pts, if (done) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    pts += n.coerceAtLeast(0) / (2L * ch) * 1_000_000L / RATE
                    if (done) eos = true
                }
            }
            var o = enc.dequeueOutputBuffer(info, 0)
            while (o >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val b = enc.getOutputBuffer(o)!!
                    val frame = ByteArray(info.size)
                    b.position(info.offset)
                    b.get(frame)
                    aac.write(adts(info.size, ch))
                    aac.write(frame)
                    outBytes += info.size
                }
                val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                enc.releaseOutputBuffer(o, false)
                if (last) {
                    finishUp(rec, enc, wav, aac, pcmBytes, ch)
                    Log.i(TAG, "$tag pcm=$pcmBytes aac=$outBytes (${outBytes * 8 / secs / 1000}kbps)")
                    return
                }
                o = enc.dequeueOutputBuffer(info, 0)
            }
        }
    }

    /**
     * 앱이 직접 녹음·인코딩하되 가볍게: 100ms 씩 모아 읽고, 입력은 막혀서 기다리고(바쁜 대기 없음),
     * 출력은 있는 만큼만 꺼낸다. WAV 는 남기지 않는다.
     */
    private fun lean(name: String, out: File, secs: Int, bitrate: Int, src: Int, ch: Int) {
        val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val chunk = RATE / 10 * 2 * ch
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, chunk)
        }
        val enc = MediaCodec.createByCodecName(name)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val min = AudioRecord.getMinBufferSize(RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(src, RATE, mask, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 4))
        val aac = java.io.BufferedOutputStream(FileOutputStream(out), 1 shl 16)
        val pcm = ByteArray(chunk)
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        var outBytes = 0L
        rec.startRecording()
        val end = System.currentTimeMillis() + secs * 1000L
        var eos = false
        while (true) {
            if (!eos) {
                var got = 0
                while (got < chunk) { val n = rec.read(pcm, got, chunk - got); if (n <= 0) break; got += n }
                val done = System.currentTimeMillis() >= end || stop
                val i = enc.dequeueInputBuffer(-1)
                enc.getInputBuffer(i)!!.apply { clear(); put(pcm, 0, got) }
                enc.queueInputBuffer(i, 0, got, pts, if (done) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                pts += got / (2L * ch) * 1_000_000L / RATE
                if (done) eos = true
            }
            var o = enc.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            while (o >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val b = enc.getOutputBuffer(o)!!
                    val frame = ByteArray(info.size)
                    b.position(info.offset); b.get(frame)
                    aac.write(adts(info.size, ch)); aac.write(frame); outBytes += info.size
                }
                val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                enc.releaseOutputBuffer(o, false)
                if (last) {
                    rec.stop(); rec.release(); enc.stop(); enc.release(); aac.close()
                    Log.i(TAG, "lean ${out.name} aac=$outBytes (${outBytes * 8 / secs / 1000}kbps)")
                    return
                }
                o = enc.dequeueOutputBuffer(info, 0)
            }
        }
    }

    /** WAV(48kHz 16bit) 파일을 그대로 인코딩한다. 원본과 견줘 인코더가 깎는 정도를 잰다. */
    private fun encodeFile(name: String, input: File, out: File, bitrate: Int) {
        val all = input.readBytes()
        val ch = all[22].toInt()
        val data = all.copyOfRange(44, all.size)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createByCodecName(name)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val aac = FileOutputStream(out)
        val info = MediaCodec.BufferInfo()
        var off = 0
        var pts = 0L
        var eos = false
        while (true) {
            if (!eos) {
                val i = enc.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = minOf(8192, data.size - off)
                    enc.getInputBuffer(i)!!.apply { clear(); put(data, off, n) }
                    off += n
                    eos = off >= data.size
                    enc.queueInputBuffer(i, 0, n, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    pts += n / (2L * ch) * 1_000_000L / RATE
                }
            }
            var o = enc.dequeueOutputBuffer(info, 10_000)
            while (o >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val b = enc.getOutputBuffer(o)!!
                    val frame = ByteArray(info.size)
                    b.position(info.offset); b.get(frame)
                    aac.write(adts(info.size, ch)); aac.write(frame)
                }
                val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                enc.releaseOutputBuffer(o, false)
                if (last) { enc.stop(); enc.release(); aac.close(); Log.i(TAG, "encoded ${input.name} -> ${out.name}"); return }
                o = enc.dequeueOutputBuffer(info, 0)
            }
        }
    }

    private fun finishUp(rec: AudioRecord, enc: MediaCodec, wav: RandomAccessFile, aac: FileOutputStream, pcmBytes: Long, ch: Int) {
        rec.stop(); rec.release(); enc.stop(); enc.release(); aac.close()
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + pcmBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort())
        b.putInt(RATE).putInt(RATE * 2 * ch).putShort((2 * ch).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(pcmBytes.toInt())
        wav.seek(0); wav.write(b.array()); wav.close()
    }

    /** ADTS 헤더 7바이트. AAC-LC, 48kHz(인덱스 3). */
    private fun adts(payload: Int, ch: Int): ByteArray {
        val len = payload + 7
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((1 shl 6) or (3 shl 2) or (ch shr 2)).toByte(),
            (((ch and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    companion object {
        private const val TAG = "AacBench"
        private const val RATE = 48_000
    }
}
