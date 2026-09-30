package com.liferecorder.service

import android.app.AlarmManager
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
import com.liferecorder.PrivateScreen
import com.liferecorder.RecorderState
import com.liferecorder.Storage
import com.liferecorder.upload.UploadScheduler
import com.liferecorder.widget.RecordWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 마이크 녹음 + 화면 녹화를 함께 붙들고 있는 포그라운드 서비스.
 * 사용자가 앱에서 OFF를 누르기 전까지 살아 있어야 한다 (START_STICKY).
 *
 * 전력 절약: WakeLock은 잡지 않는다. 마이크 녹음 경로가 어차피 CPU를 주기적으로 깨우고,
 * 그 사이에는 시스템이 더 깊은 절전으로 들어갈 수 있게 둔다. 예외 하나 — 알람 때문에 마이크를 놓은 뒤
 * 다시 잡을 때만 몇 초 깨운다 ([AlarmGuard]. 마이크를 놓으면 녹음 스레드를 깨울 것이 없다).
 * 화면이 꺼지면 화면 캡처 입력만 끊어 GPU 합성/인코딩을 멈춘다.
 * (발열로 끊는 기능은 있었다가 뺐다. 무거운 앱에 들어갈 때마다 화면 기록이 멈춰 공백이 생겼다.)
 */
class RecordingService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var audio: AudioRecorderSession? = null
    private var screen: ScreenRecorderSession? = null
    /** 지금까지 startForeground에 넘긴 타입의 합집합. 타입 집합은 호출마다 교체되므로 항상 합쳐서 넘긴다. */
    private var fgTypes = 0

    private var screenOn = true
    /** 서비스와 수명을 같이하는 메인 스레드 스코프. 비공개 앱 판정을 받아 캡처 입력에 반영한다. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 자동 재개가 실패해도 잠금 해제마다 다시 달려들지 않도록 최소 간격을 둔다. */
    private var lastResumeAttempt = 0L

    /** 알람이 울리는 동안 마이크를 놓아 준다 (녹음 중이면 알람 소리가 안 난다). */
    private lateinit var alarmGuard: AlarmGuard

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; alarmSignal() }
                Intent.ACTION_SCREEN_ON -> { screenOn = true; maybeResumeScreen() }
                Intent.ACTION_USER_PRESENT -> { screenOn = true; alarmSignal(); maybeResumeScreen() }
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> { alarmGuard.refresh(); return }
                else -> return
            }
            applyCaptureGate()
        }
    }

    /** 화면 꺼짐 · 잠금 해제 — 알람이 끝났으면 녹음 스레드가 마이크를 다시 잡도록 잠깐 깨운다. */
    private fun alarmSignal() {
        if (!alarmGuard.onUserSignal()) return
        try {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "liferecorder:alarm-resume")
                .acquire(RESUME_WAKE_MS)
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(PowerManager::class.java)
        screenOn = pm.isInteractive
        alarmGuard = AlarmGuard(this).also { it.refresh() }
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            },
            RECEIVER_NOT_EXPORTED,
        )
        scope.launch { PrivateScreen.reason.collect { applyCaptureGate() } }
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
            audio = AudioRecorderSession(this, audioListener, alarmGuard::shouldHold).also { it.start() }
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

    /**
     * 화면 꺼짐·비공개 앱(Brave, Chrome 시크릿 탭)을 화면 캡처 입력에 반영한다.
     * 비공개 앱은 판정 직전에 찍힌 프레임까지 되돌려 버린다 (`ScreenRecorderSession.setPrivate`).
     * 영상에는 그 사이가 빠진다 (재생하면 직전 화면에 멈춰 있다).
     */
    private fun applyCaptureGate() {
        val private = PrivateScreen.reason.value
        val reason = if (!screenOn) "화면 꺼짐" else private
        val s = screen
        s?.setCaptureEnabled(screenOn)
        s?.setPrivate(private != null)
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
                recordingEnabled = false, audioRecording = false, audioPausedReason = null, screenRecording = false,
                screenStoppedReason = null, screenPausedReason = null,
            )
        }
        RecordWidget.refresh(this)
        val finish = {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        if (s != null) s.stop(finish) else finish()
    }

    /** 알림과 홈 화면 위젯을 지금 상태에 맞춘다. 기록 상태가 바뀔 때마다 부른다. */
    private fun updateNotification() {
        Notifications.updateOngoing(this, audio != null, screen != null)
        RecordWidget.refresh(this)
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

        override fun onHold(held: Boolean) {
            val reason = if (held) alarmGuard.reason() else null
            RecorderState.update { it.copy(audioPausedReason = reason) }
            main.post { updateNotification() }
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
        scope.cancel()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        audio?.stop()
        audio = null
        screen?.let { s -> screen = null; s.stop {} }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val RESUME_MIN_INTERVAL_MS = 10_000L
        /** 알람 뒤 마이크를 다시 잡을 동안 깨워 두는 시간. 다시 잡으면 녹음 경로가 깨워 둔다. */
        private const val RESUME_WAKE_MS = 10_000L
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
