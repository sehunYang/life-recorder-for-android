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

/**
 * MediaProjection → VirtualDisplay → H.264 surface encoder → MediaMuxer.
 * 정각마다 키프레임을 요청해서 그 키프레임부터 새 mp4로 이어 쓴다 (프레임 손실 없음).
 * 모든 인코더/먹서 작업은 전용 HandlerThread에서만 한다.
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
            try {
                display?.setSurface(if (enabled) inputSurface else null)
            } catch (e: Exception) {
                Log.w(TAG, "setSurface failed", e)
            }
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
                if (!config && info.size > 0) {
                    if (rotatePending && key) {
                        rotatePending = false
                        closeMuxer()
                    }
                    if (muxer == null && key) openMuxer()
                    val m = muxer
                    val buf = codec.getOutputBuffer(index)
                    if (m != null && buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        m.writeSampleData(track, buf, info)
                    }
                }
                codec.releaseOutputBuffer(index, false)
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
