package com.liferecorder.service

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.liferecorder.AppScope
import com.liferecorder.Notifications
import com.liferecorder.Prefs
import com.liferecorder.RecorderState
import com.liferecorder.Storage
import com.liferecorder.upload.UploadScheduler
import kotlinx.coroutines.launch
import java.io.File

/**
 * 마이크 녹음 + 화면 녹화를 함께 붙들고 있는 포그라운드 서비스.
 * 사용자가 앱에서 OFF를 누르기 전까지 살아 있어야 한다 (START_STICKY).
 *
 * 전력 절약: WakeLock은 잡지 않는다. 마이크 녹음 경로가 어차피 CPU를 주기적으로 깨우고,
 * 그 사이에는 시스템이 더 깊은 절전으로 들어갈 수 있게 둔다.
 * 화면이 꺼지거나 기기가 뜨거우면 화면 캡처 입력만 끊어 GPU 합성/인코딩을 멈춘다.
 */
class RecordingService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var audio: AudioRecorderSession? = null
    private var screen: ScreenRecorderSession? = null
    /** 지금까지 startForeground에 넘긴 타입의 합집합. 타입 집합은 호출마다 교체되므로 항상 합쳐서 넘긴다. */
    private var fgTypes = 0

    private var screenOn = true
    private var thermalHot = false
    /** 자동 재개가 실패해도 잠금 해제마다 다시 달려들지 않도록 최소 간격을 둔다. */
    private var lastResumeAttempt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> { screenOn = true; maybeResumeScreen() }
                Intent.ACTION_USER_PRESENT -> { screenOn = true; maybeResumeScreen() }
                else -> return
            }
            applyCaptureGate()
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        val hot = status >= PowerManager.THERMAL_STATUS_MODERATE
        if (hot != thermalHot) {
            thermalHot = hot
            Log.i(TAG, "thermal status=$status hot=$hot")
            main.post { applyCaptureGate() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(PowerManager::class.java)
        screenOn = pm.isInteractive
        thermalHot = pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        pm.addThermalStatusListener(mainExecutor, thermalListener)
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            RECEIVER_NOT_EXPORTED,
        )
        // 죽은 .part 복구는 Application.onCreate에서 한 번만 한다 (여기서 또 하면 같은 파일을 두 번 remux).
        AppScope.launch { RecorderState.refreshPending(this@RecordingService) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START_AUDIO -> {
                if (!goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)) return START_NOT_STICKY
                startAudio()
            }
            ACTION_START_SCREEN -> {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (!goForeground(types)) return START_NOT_STICKY
                startAudio()
                startScreen(intent)
            }
            ACTION_STOP_ALL -> stopAll()
            else -> onRestartedBySystem()
        }
        return START_STICKY
    }

    /** startForeground는 상황에 따라 예외를 던질 수 있다 (백그라운드에서 마이크 타입 시작 제한 등). */
    private fun goForeground(types: Int): Boolean {
        return try {
            fgTypes = fgTypes or types
            // 세션 객체는 아직 안 만들어졌을 수 있으니 "곧 켜질 상태"를 기준으로 알림을 만든다.
            val screenOn = screen != null || (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0
            startForeground(Notifications.ID_ONGOING, Notifications.ongoing(this, true, screenOn), fgTypes)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            Notifications.showResumeNeeded(this, "기록 서비스를 시작하지 못했습니다")
            stopSelf()
            false
        }
    }

    /** 프로세스가 죽은 뒤 시스템이 다시 살렸을 때 (intent == null). */
    private fun onRestartedBySystem() {
        if (!Prefs.isRecordingEnabled(this)) { stopSelf(); return }
        Log.w(TAG, "restarted by system, resuming audio")
        if (!goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)) return
        startAudio()
        if (Prefs.wasScreenRecording(this)) {
            // MediaProjection 동의 토큰은 일회용이라 화면 녹화는 사용자가 다시 눌러야 한다.
            RecorderState.update { it.copy(screenStoppedReason = "앱이 재시작되었습니다") }
            Notifications.showScreenStopped(this, "앱이 재시작되었습니다")
        }
    }

    private fun startAudio() {
        Prefs.setRecordingEnabled(this, true)
        RecorderState.update { it.copy(recordingEnabled = true) }
        Notifications.cancel(this, Notifications.ID_RESUME)
        if (audio == null) {
            audio = AudioRecorderSession(this, audioListener).also { it.start() }
        }
        updateNotification()
    }

    private fun startScreen(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        if (code == Int.MIN_VALUE || data == null) return
        screen?.let { old -> screen = null; old.stop {} }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e); null
        }
        if (projection == null) {
            RecorderState.update { it.copy(screenStoppedReason = "화면 캡처 권한을 얻지 못했습니다") }
            updateNotification()
            return
        }
        screen = ScreenRecorderSession(this, projection, screenListener).also { it.start() }
        Prefs.setScreenWasRecording(this, true)
        Notifications.cancel(this, Notifications.ID_SCREEN_STOPPED)
        RecorderState.update { it.copy(screenRecording = true, screenStoppedReason = null) }
        applyCaptureGate()
        updateNotification()
    }

    /**
     * 잠금화면이 뜨면 시스템이 MediaProjection을 끊는다 (STOP_REASON_KEYGUARD).
     * 화면이 꺼진 동안은 어차피 담을 게 없으니, 잠금이 풀린 뒤 조용히 다시 붙인다.
     * PROJECT_MEDIA appop가 허용돼 있으면 동의 창 없이 즉시 재개된다.
     */
    private fun maybeResumeScreen() {
        if (screen != null) return
        if (!Prefs.isRecordingEnabled(this) || !Prefs.wasScreenRecording(this)) return
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastResumeAttempt < RESUME_MIN_INTERVAL_MS) return
        lastResumeAttempt = now
        Log.i(TAG, "resuming screen capture after unlock")
        try {
            ProjectionRequestActivity.start(this)
        } catch (e: Exception) {
            // 백그라운드 액티비티 시작이 막힌 경우. 알림으로 사용자가 직접 재개하게 둔다.
            Log.w(TAG, "auto resume blocked: ${e.message}")
            Notifications.showScreenStopped(this, "화면 녹화를 다시 시작하려면 눌러 주세요")
        }
    }

    /** 화면 꺼짐/발열 상태를 화면 캡처 입력에 반영한다. */
    private fun applyCaptureGate() {
        val reason = when {
            !screenOn -> "화면 꺼짐"
            thermalHot -> "발열"
            else -> null
        }
        val s = screen
        s?.setCaptureEnabled(reason == null)
        RecorderState.update { it.copy(screenPausedReason = if (s != null) reason else null) }
    }

    private fun stopAll() {
        Log.i(TAG, "stopAll")
        Prefs.setRecordingEnabled(this, false)
        Prefs.setScreenWasRecording(this, false)
        audio?.stop()
        audio = null
        val s = screen
        screen = null
        RecorderState.update {
            it.copy(
                recordingEnabled = false, audioRecording = false, screenRecording = false,
                screenStoppedReason = null, screenPausedReason = null,
            )
        }
        val finish = {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        if (s != null) s.stop(finish) else finish()
    }

    private fun updateNotification() {
        Notifications.updateOngoing(this, audio != null, screen != null)
    }

    private val audioListener = object : AudioRecorderSession.Listener {
        override fun onSegmentFinished(part: File) {
            AppScope.launch {
                Storage.finalizeAudioPart(part)
                RecorderState.refreshPending(this@RecordingService)
                UploadScheduler.enqueueNow(this@RecordingService)
            }
        }

        override fun onError(message: String) {
            Log.w(TAG, "audio error: $message")
        }
    }

    private val screenListener = object : ScreenRecorderSession.Listener {
        override fun onSegmentFinished(file: File) {
            AppScope.launch {
                RecorderState.refreshPending(this@RecordingService)
                UploadScheduler.enqueueNow(this@RecordingService)
            }
        }

        override fun onStopped(reason: String) {
            main.post {
                if (screen == null) return@post
                screen = null
                Log.w(TAG, "screen stopped: $reason")
                RecorderState.update { it.copy(screenPausedReason = null) }
                Notifications.showScreenStopped(this@RecordingService, reason)
                updateNotification()
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener) } catch (_: Exception) {}
        audio?.stop()
        audio = null
        screen?.let { s -> screen = null; s.stop {} }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val RESUME_MIN_INTERVAL_MS = 10_000L
        const val ACTION_START_AUDIO = "com.liferecorder.START_AUDIO"
        const val ACTION_START_SCREEN = "com.liferecorder.START_SCREEN"
        const val ACTION_STOP_ALL = "com.liferecorder.STOP_ALL"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private fun intent(ctx: Context, action: String) =
            Intent(ctx, RecordingService::class.java).setAction(action)

        fun startAudio(ctx: Context) = ctx.startForegroundService(intent(ctx, ACTION_START_AUDIO))

        fun startScreen(ctx: Context, resultCode: Int, data: Intent) =
            ctx.startForegroundService(
                intent(ctx, ACTION_START_SCREEN)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data)
            )

        fun stopAll(ctx: Context) = ctx.startForegroundService(intent(ctx, ACTION_STOP_ALL))
    }
}
