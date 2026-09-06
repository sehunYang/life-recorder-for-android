package com.liferecorder.service

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.liferecorder.Config
import com.liferecorder.RecorderState
import com.liferecorder.SegmentClock
import com.liferecorder.Storage
import java.io.File

/**
 * 마이크 녹음. 정각마다 파일을 끊는다.
 * MediaRecorder는 prepare/stop이 수백 ms씩 막힐 수 있어 전용 HandlerThread에서만 다룬다.
 * (MediaRecorder 콜백은 생성한 스레드의 Looper로 오므로 생성도 그 스레드에서 한다.)
 */
class AudioRecorderSession(
    private val ctx: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** 한 세그먼트(.aac.part)가 닫혔다. 녹음 스레드에서 호출되므로 무거운 일은 넘겨서 처리할 것. */
        fun onSegmentFinished(part: File)
        fun onError(message: String)
    }

    private val thread = HandlerThread("audio-recorder").apply { start() }
    private val handler = Handler(thread.looper)
    private var recorder: MediaRecorder? = null
    private var currentPart: File? = null
    @Volatile var running = false
        private set

    private val retryRunnable = Runnable { if (running && recorder == null) startSegment() }

    fun start() {
        if (running) return
        running = true
        handler.post { startSegment() }
    }

    /** 비동기. 마지막 세그먼트는 listener.onSegmentFinished로 전달된 뒤 onDone이 호출된다. */
    fun stop(onDone: (() -> Unit)? = null) {
        running = false
        val posted = handler.post {
            handler.removeCallbacks(retryRunnable)
            val r = recorder
            val part = currentPart
            recorder = null
            currentPart = null
            if (r != null) {
                try { r.stop() } catch (e: Exception) { Log.w(TAG, "stop failed", e) }
                release(r)
            }
            RecorderState.update { it.copy(audioRecording = false) }
            part?.let(listener::onSegmentFinished)
            thread.quitSafely()
            onDone?.invoke()
        }
        if (!posted) onDone?.invoke()
    }

    private fun startSegment() {
        if (!running) return
        val now = System.currentTimeMillis()
        val end = SegmentClock.nextBoundary(now)
        val part = Storage.newAudioPart(ctx, now)
        val r = MediaRecorder(ctx)
        try {
            r.setAudioSource(Config.AUDIO_SOURCE)
            // ADTS: 프레임 단위로 자기완결적이라 프로세스가 죽어도 그 시점까지 살아남는다.
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(Config.AUDIO_CHANNELS)
            r.setAudioSamplingRate(Config.AUDIO_SAMPLE_RATE)
            r.setAudioEncodingBitRate(Config.AUDIO_BITRATE)
            r.setOutputFile(part.absolutePath)
            r.setMaxDuration((end - now).toInt())
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxDuration(r)
            }
            r.setOnErrorListener { _, what, extra -> onRecorderError(r, "MediaRecorder 오류 $what/$extra") }
            r.prepare()
            r.start()
            recorder = r
            currentPart = part
            RecorderState.update { it.copy(audioRecording = true, audioError = null, currentSegmentStart = now) }
            Log.i(TAG, "segment started: ${part.name}, ends in ${(end - now) / 1000}s")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            release(r)
            part.delete()
            RecorderState.update { it.copy(audioRecording = false, audioError = "녹음 시작 실패: ${e.message}") }
            listener.onError("녹음 시작 실패: ${e.message}")
            scheduleRetry()
        }
    }

    /**
     * 최대 길이 도달. 정지는 비동기로 일어나므로 stop()으로 마무리를 기다린 뒤 마이크를 놓고,
     * 그 다음에 새 세그먼트를 연다. 공백은 수십 ms 수준.
     */
    private fun onMaxDuration(r: MediaRecorder) {
        if (r !== recorder) return
        val part = currentPart
        recorder = null
        currentPart = null
        try { r.stop() } catch (e: Exception) { Log.w(TAG, "stop after max duration failed", e) }
        release(r)
        startSegment()
        part?.let(listener::onSegmentFinished)
    }

    private fun onRecorderError(r: MediaRecorder, message: String) {
        if (r !== recorder) return
        Log.e(TAG, message)
        recorder = null
        val part = currentPart
        currentPart = null
        try { r.reset() } catch (_: Exception) {}
        release(r)
        RecorderState.update { it.copy(audioRecording = false, audioError = message) }
        part?.let(listener::onSegmentFinished)
        listener.onError(message)
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (!running) return
        handler.postDelayed(retryRunnable, RETRY_MS)
    }

    private fun release(r: MediaRecorder) {
        try { r.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "AudioRecorder"
        private const val RETRY_MS = 5_000L
    }
}
