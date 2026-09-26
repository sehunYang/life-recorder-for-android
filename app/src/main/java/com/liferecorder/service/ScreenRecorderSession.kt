package com.liferecorder.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.liferecorder.Config
import com.liferecorder.RecorderState
import com.liferecorder.SegmentClock
import com.liferecorder.Storage
import java.io.File
import java.nio.ByteBuffer

/**
 * MediaProjection → VirtualDisplay → H.264 surface encoder → (잠깐 붙들기) → MediaMuxer.
 * 정각마다 키프레임을 요청해서 그 키프레임부터 새 mp4로 이어 쓴다 (프레임 손실 없음).
 * 모든 인코더/먹서 작업은 전용 HandlerThread에서만 한다.
 *
 * 인코딩된 프레임은 [Config.SCREEN_HOLD_US] 만큼 붙들었다가 쓴다. 비공개 앱(Brave 등)은
 * 창이 뜬 **뒤에야** 판정되므로, 판정 순간 이미 인코딩된 여는 애니메이션 프레임을 되돌려
 * 버리려면 아직 파일에 쓰지 않은 채여야 한다.
 */
class ScreenRecorderSession(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val listener: Listener,
) {
    interface Listener {
        fun onSegmentFinished(file: File)
        /** 우리가 stop()한 게 아니라 외부 요인으로 끝났을 때만 호출된다. */
        fun onStopped(reason: String)
    }

    private val thread = HandlerThread("screen-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var display: VirtualDisplay? = null
    private var outputFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var muxerFile: File? = null
    private var track = -1
    private var rotatePending = false
    @Volatile private var finished = false
    /** 파일에 쓰기 전 붙들고 있는 프레임. 오래된 것이 앞이다. */
    private val held = ArrayDeque<Sample>()
    private var drainScheduled = false
    private var captureEnabled = true
    /** 비공개 앱이 떠 있는 동안. 캡처 입력을 끊고, 그래도 나오는 반복 프레임은 버린다. */
    private var privateMode = false
    /** 프레임을 버린 뒤다. 뒤 프레임이 버린 것을 참조하므로 다음 키프레임까지 쓰지 않는다. */
    private var needKey = false
    private var ptsChecked = false
    private var stopCallback: (() -> Unit)? = null

    /** 다음 정각(벽시계). postDelayed는 uptime 기준이라 시계 보정에 어긋날 수 있어 1분마다 벽시계로 다시 확인한다. */
    private var nextBoundary = 0L

    private val rotateRunnable = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= nextBoundary) {
                rotatePending = true
                requestSyncFrame()
                nextBoundary = SegmentClock.nextBoundary(System.currentTimeMillis())
            }
            scheduleRotation()
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post { finish("시스템이 화면 녹화를 종료했습니다") }
        }
    }

    fun start() {
        handler.post {
            try {
                startInternal()
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                finish("화면 녹화 시작 실패: ${e.message}")
            }
        }
    }

    /**
     * 화면 꺼짐/발열 시 가상 디스플레이에서 서피스를 떼어 GPU 합성과 인코딩을 멈춘다.
     * Android 14부터 가상 디스플레이를 다시 만들 수 없어서 setSurface로만 켜고 끈다.
     * 인코더와 먹서는 그대로라 세그먼트는 이어지고, 다시 붙이면 그 지점부터 프레임이 들어온다.
     */
    fun setCaptureEnabled(enabled: Boolean) {
        handler.post {
            if (finished) return@post
            captureEnabled = enabled
            applySurface()
        }
    }

    /**
     * 비공개 앱이 뜨면 붙들고 있던 프레임 가운데 최근 [Config.SCREEN_PRIVATE_LOOKBACK_US] 것을 버리고
     * (판정보다 먼저 찍힌 여는 애니메이션), 캡처 입력을 끊는다. 끊어도 인코더는 마지막 프레임을
     * 10초마다 반복해 내놓는데 그것이 비공개 앱의 화면일 수 있으므로 그동안 나오는 것은 모두 버린다.
     * 풀리면 키프레임을 요청하고 그 키프레임부터 다시 쓴다.
     */
    fun setPrivate(on: Boolean) {
        handler.post {
            if (finished || privateMode == on) return@post
            privateMode = on
            if (on) {
                val cut = nowUs() - Config.SCREEN_PRIVATE_LOOKBACK_US
                var dropped = 0
                while (held.isNotEmpty() && held.last().pts >= cut) { held.removeLast(); dropped++ }
                needKey = true
                Log.i(TAG, "private on: dropped $dropped held frames")
            } else {
                Log.i(TAG, "private off")
            }
            applySurface()
            if (!on) requestSyncFrame()
        }
    }

    private fun applySurface() {
        try {
            display?.setSurface(if (captureEnabled && !privateMode) inputSurface else null)
        } catch (e: Exception) {
            Log.w(TAG, "setSurface failed", e)
        }
    }

    /** 비동기. 완료되면 onDone이 메인 스레드에서 호출된다. */
    fun stop(onDone: () -> Unit) {
        if (finished) { main.post(onDone); return }
        val posted = handler.post {
            if (finished) { main.post(onDone); return@post }
            stopCallback = onDone
            display?.release()
            display = null
            try {
                codec?.signalEndOfInputStream()
            } catch (e: Exception) {
                finish(null)
                return@post
            }
            handler.postDelayed({ finish(null) }, EOS_TIMEOUT_MS)
        }
        // 루퍼가 이미 종료돼 post가 버려진 경우에도 호출자는 반드시 콜백을 받아야 한다.
        if (!posted) main.post(onDone)
    }

    private fun startInternal() {
        val (w, h, dpi) = computeSize()
        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, Config.SCREEN_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, Config.SCREEN_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Config.SCREEN_IFRAME_INTERVAL_SEC)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, Config.SCREEN_FPS.toFloat())
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, Config.SCREEN_REPEAT_FRAME_US)
            // 전력 힌트. 실시간이 아니라고(1) 알리고 처리율을 5fps로 못 박으면 코덱이 클럭을 낮춘다.
            // VBR은 정지 화면에서 비트를 덜 쓴다. 지원하지 않는 코덱은 무시할 뿐 실패하지 않는다.
            setInteger(MediaFormat.KEY_PRIORITY, 1)
            setInteger(MediaFormat.KEY_OPERATING_RATE, Config.SCREEN_FPS)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val c = MediaCodec.createEncoderByType(MIME)
        c.setCallback(codecCallback, handler)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        codec = c

        // Android 14+: createVirtualDisplay 전에 콜백을 등록해야 한다.
        projection.registerCallback(projectionCallback, handler)
        display = projection.createVirtualDisplay(
            "LifeRecorder", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, handler
        )
        scheduleRotation()
        Log.i(TAG, "screen capture started ${w}x$h @${Config.SCREEN_FPS}fps")
    }

    private fun computeSize(): Triple<Int, Int, Int> {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        val dpi = ctx.resources.configuration.densityDpi
        fun scaled(v: Int) = (((v * Config.SCREEN_SCALE).toInt() / 16) * 16).coerceAtLeast(160)
        return Triple(scaled(bounds.width()), scaled(bounds.height()), dpi)
    }

    private fun scheduleRotation() {
        handler.removeCallbacks(rotateRunnable)
        val now = System.currentTimeMillis()
        if (nextBoundary <= now) nextBoundary = SegmentClock.nextBoundary(now)
        handler.postDelayed(rotateRunnable, (nextBoundary - now).coerceIn(0L, ROTATION_POLL_MS))
    }

    private fun requestSyncFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w(TAG, "sync frame request failed", e)
        }
    }

    private fun nowUs() = System.nanoTime() / 1000

    /**
     * 되돌려 버리기는 프레임 시각이 [System.nanoTime] 과 같은 시계라는 데 기댄다 (VirtualDisplay 의
     * 서피스 시각은 CLOCK_MONOTONIC). 어긋난 기기에서는 로그로 드러나게 한 번만 적는다.
     */
    private fun checkPts(pts: Long) {
        if (ptsChecked) return
        ptsChecked = true
        Log.i(TAG, "pts - now = ${(pts - nowUs()) / 1000}ms")
    }

    private fun scheduleDrain() {
        if (drainScheduled || held.isEmpty()) return
        drainScheduled = true
        val wait = (held.first().pts + Config.SCREEN_HOLD_US - nowUs()) / 1000
        handler.postDelayed({ drainScheduled = false; drain(all = false) }, wait.coerceIn(0L, 5_000L))
    }

    /** 붙든 지 [Config.SCREEN_HOLD_US] 가 지난 프레임을 파일에 쓴다. all 이면 전부. */
    private fun drain(all: Boolean) {
        if (finished && !all) return
        val limit = nowUs() - Config.SCREEN_HOLD_US
        while (held.isNotEmpty() && (all || held.first().pts <= limit)) write(held.removeFirst())
        if (!all) scheduleDrain()
    }

    private fun write(s: Sample) {
        val key = s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (needKey) {
            if (!key) return
            needKey = false
        }
        if (rotatePending && key) {
            rotatePending = false
            closeMuxer()
        }
        if (muxer == null && key) openMuxer()
        val m = muxer ?: return
        val info = MediaCodec.BufferInfo().apply { set(0, s.data.size, s.pts, s.flags) }
        m.writeSampleData(track, ByteBuffer.wrap(s.data), info)
    }

    private class Sample(val data: ByteArray, val pts: Long, val flags: Int)

    private fun openMuxer() {
        val fmt = outputFormat ?: return
        val file = Storage.newScreenPart(ctx, System.currentTimeMillis())
        val m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        track = m.addTrack(fmt)
        m.start()
        muxer = m
        muxerFile = file
        RecorderState.update { it.copy(screenRecording = true, screenStoppedReason = null) }
        Log.i(TAG, "segment started: ${file.name}")
    }

    private fun closeMuxer() {
        val m = muxer ?: return
        val file = muxerFile
        muxer = null
        muxerFile = null
        track = -1
        var ok = true
        try { m.stop() } catch (e: Exception) { ok = false; Log.w(TAG, "muxer stop failed", e) }
        try { m.release() } catch (_: Exception) {}
        if (file == null) return
        if (ok && file.length() > 0) {
            val done = Storage.finishPart(file)
            main.post { listener.onSegmentFinished(done) }
        } else {
            file.delete()
        }
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { /* surface 입력이라 호출되지 않음 */ }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (finished) return
            try {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (!config && info.size > 0 && !privateMode) {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        held.addLast(Sample(data, info.presentationTimeUs, info.flags))
                        checkPts(info.presentationTimeUs)
                    }
                }
                codec.releaseOutputBuffer(index, false)
                if (eos) drain(all = true) else scheduleDrain()
                // codec.stop()을 코덱 콜백 안에서 부르면 멈출 수 있어 콜백 밖으로 미룬다.
                if (eos) handler.post { finish(null) }
            } catch (e: Exception) {
                Log.e(TAG, "encode loop failed", e)
                handler.post { finish("인코더 오류: ${e.message}") }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "codec error", e)
            handler.post { finish("인코더 오류: ${e.diagnosticInfo}") }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            outputFormat = format
        }
    }

    /** 인코더 스레드에서만 호출. reason이 null이면 우리가 의도적으로 멈춘 것. */
    private fun finish(reason: String?) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        display?.release()
        display = null
        codec?.let { c ->
            try { c.stop() } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
        }
        codec = null
        inputSurface?.release()
        inputSurface = null
        try { drain(all = true) } catch (e: Exception) { Log.w(TAG, "final drain failed", e) }
        closeMuxer()
        try { projection.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        // 시스템이 이미 끝낸 뒤라면 no-op. 오류 경로에서도 세션이 새지 않도록 항상 호출한다.
        try { projection.stop() } catch (_: Exception) {}
        val cb = stopCallback
        stopCallback = null
        RecorderState.update { it.copy(screenRecording = false, screenStoppedReason = reason) }
        main.post {
            if (reason != null) listener.onStopped(reason)
            cb?.invoke()
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "ScreenRecorder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val EOS_TIMEOUT_MS = 1_500L
        private const val ROTATION_POLL_MS = 60_000L
    }
}
