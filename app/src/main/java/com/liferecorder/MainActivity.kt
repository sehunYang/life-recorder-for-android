package com.liferecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import com.google.android.gms.common.api.ApiException
import com.liferecorder.kakao.KakaoAccessibilityService
import com.liferecorder.kakao.KakaoImport
import com.liferecorder.kakao.KakaoNotificationListener
import com.liferecorder.service.RecordingService
import com.liferecorder.ui.LifeRecorderTheme
import com.liferecorder.ui.MainScreen
import com.liferecorder.ui.UiActions
import com.liferecorder.upload.AppUsageExporter
import com.liferecorder.upload.DriveAuth
import com.liferecorder.upload.UploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val batteryExempt = mutableStateOf(false)
    private val wifiOnly = mutableStateOf(true)
    private val chargingOnly = mutableStateOf(true)
    private val includeCalls = mutableStateOf(true)
    private val includeCamera = mutableStateOf(false)
    private val includeSms = mutableStateOf(true)
    private val includeApp = mutableStateOf(true)
    private val appAccessOn = mutableStateOf(false)
    private val includeKakao = mutableStateOf(true)
    private val kakaoAccessOn = mutableStateOf(false)
    private val includeKakaoScreen = mutableStateOf(true)
    private val kakaoScreenOn = mutableStateOf(false)
    private val kakaoDump = mutableStateOf(false)

    private val kakaoFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            toast("카카오톡 대화 ${uris.size}개 가져오는 중…")
            lifecycleScope.launch {
                val summary = withContext(Dispatchers.IO) {
                    var ok = 0
                    var lastError: String? = null
                    for (uri in uris) {
                        when (val r = KakaoImport.importUri(this@MainActivity, uri)) {
                            is KakaoImport.Outcome.Ok -> ok++
                            is KakaoImport.Outcome.Failed -> lastError = r.reason
                        }
                    }
                    if (ok > 0) "파일 ${ok}개를 업로드 대기열에 넣었습니다"
                    else lastError ?: "가져오지 못했습니다"
                }
                toast(summary)
                UploadScheduler.enqueueNow(this@MainActivity, ExistingWorkPolicy.REPLACE, manual = true)
            }
        }

    /** "미디어 포함 저장"이 만든 폴더를 통째로 가져온다. 사진이 많아 하나씩 고를 수 없기 때문. */
    private val kakaoFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            toast("폴더를 읽는 중…")
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { KakaoImport.importTree(this@MainActivity, uri) }
                val title =
                    if (r.total > 0) "카카오톡 폴더에서 ${r.total}개 가져옴" else "가져오지 못했습니다"
                val detail = if (r.total > 0) {
                    buildString {
                        append("대화록 ${r.text}개, 미디어 ${r.media}개 · ${Storage.fmtBytes(r.bytes)}")
                        if (r.failed > 0) append("\n실패 ${r.failed}개 (${r.error})")
                        append("\n업로드 대기열에 넣었습니다")
                    }
                } else {
                    r.error ?: "폴더에 가져올 파일이 없습니다"
                }
                Notifications.showImportResult(this@MainActivity, title, detail)
                toast(title)
                if (r.total > 0) {
                    withContext(Dispatchers.IO) { RecorderState.refreshPending(this@MainActivity) }
                    UploadScheduler.enqueueNow(this@MainActivity, ExistingWorkPolicy.REPLACE, manual = true)
                }
            }
        }

    private val smsReadLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermission(Manifest.permission.READ_SMS)) {
                Prefs.setIncludeSms(this, true)
                includeSms.value = true
                UploadScheduler.enqueueNow(this, ExistingWorkPolicy.REPLACE, manual = true)
            } else {
                Prefs.setIncludeSms(this, false)
                includeSms.value = false
                toast("문자 읽기 권한이 없으면 문자를 내보낼 수 없습니다")
            }
        }

    private val audioReadLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Prefs.setIncludeCalls(this, true)
                includeCalls.value = true
                UploadScheduler.enqueueNow(this, ExistingWorkPolicy.REPLACE, manual = true)
            } else {
                Prefs.setIncludeCalls(this, false)
                includeCalls.value = false
                toast("오디오 읽기 권한이 없으면 통화 녹음을 가져올 수 없습니다")
            }
        }

    private val cameraReadLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val ok = hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
                hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
            Prefs.setIncludeCamera(this, ok)
            includeCamera.value = ok
            if (ok) UploadScheduler.enqueueNow(this, ExistingWorkPolicy.REPLACE, manual = true)
            else toast("사진 읽기 권한이 없으면 카메라 사진을 가져올 수 없습니다")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                RecordingService.startScreen(this, res.resultCode, data)
            } else {
                toast("화면 녹화 권한이 거부되어 소리만 기록합니다")
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // 결과 맵에는 이번에 요청한 권한만 들어 있으므로 실제 보유 여부를 다시 확인한다.
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) startEverything()
            else toast("마이크 권한이 없으면 기록할 수 없습니다")
        }

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                try {
                    DriveAuth.resultFromIntent(this, data)
                    onDriveLinked()
                } catch (e: ApiException) {
                    toast("Google 연결 실패 (${e.statusCode})")
                }
            } else {
                toast("Google 연결이 취소되었습니다")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        wifiOnly.value = Prefs.isWifiOnly(this)
        chargingOnly.value = Prefs.isUploadOnlyCharging(this)
        includeCalls.value = Prefs.isIncludeCalls(this)
        includeCamera.value = Prefs.isIncludeCamera(this)
        includeSms.value = Prefs.isIncludeSms(this)
        includeApp.value = Prefs.isIncludeApp(this)
        includeKakao.value = Prefs.isIncludeKakao(this)
        includeKakaoScreen.value = Prefs.isIncludeKakaoScreen(this)
        kakaoDump.value = Prefs.isKakaoDump(this)
        setContent {
            val status by RecorderState.status.collectAsStateWithLifecycle()
            LifeRecorderTheme {
                MainScreen(
                    status = status,
                    wifiOnly = wifiOnly.value,
                    chargingOnly = chargingOnly.value,
                    includeCalls = includeCalls.value,
                    includeCamera = includeCamera.value,
                    includeSms = includeSms.value,
                    includeApp = includeApp.value,
                    appAccessOn = appAccessOn.value,
                    includeKakao = includeKakao.value,
                    kakaoAccessOn = kakaoAccessOn.value,
                    includeKakaoScreen = includeKakaoScreen.value,
                    kakaoScreenOn = kakaoScreenOn.value,
                    kakaoDump = kakaoDump.value,
                    batteryExempt = batteryExempt.value,
                    actions = UiActions(
                        turnOn = ::turnOn,
                        turnOff = ::turnOff,
                        resumeScreen = ::requestProjection,
                        linkDrive = ::linkDrive,
                        // 수동 버튼: 충전 조건 무시, REPLACE로 백오프 대기 중인 재시도도 밀어내고 즉시 실행.
                        uploadNow = { UploadScheduler.enqueueNow(this, ExistingWorkPolicy.REPLACE, manual = true) },
                        setWifiOnly = { v ->
                            Prefs.setWifiOnly(this, v)
                            wifiOnly.value = v
                            UploadScheduler.reschedule(this)
                        },
                        setChargingOnly = { v ->
                            Prefs.setUploadOnlyCharging(this, v)
                            chargingOnly.value = v
                            UploadScheduler.reschedule(this)
                        },
                        setIncludeCalls = { v ->
                            if (v && !hasPermission(Manifest.permission.READ_MEDIA_AUDIO)) {
                                audioReadLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                            } else {
                                Prefs.setIncludeCalls(this, v)
                                includeCalls.value = v
                                if (v) UploadScheduler.enqueueNow(this, ExistingWorkPolicy.REPLACE, manual = true)
                            }
                        },
                        setIncludeCamera = { v ->
                            val granted = hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
                                hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
                            if (v && !granted) {
                                cameraReadLauncher.launch(
                                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                                )
                            } else {
                                Prefs.setIncludeCamera(this, v)
                                includeCamera.value = v
                                if (v) UploadScheduler.enqueueNow(this, ExistingWorkPolicy.REPLACE, manual = true)
                            }
                        },
                        setIncludeApp = { v ->
                            Prefs.setIncludeApp(this, v)
                            includeApp.value = v
                            if (v && !AppUsageExporter.hasPermission(this)) openUsageAccess()
                            else if (v) UploadScheduler.enqueueNow(this, ExistingWorkPolicy.REPLACE, manual = true)
                        },
                        openUsageAccess = ::openUsageAccess,
                        setIncludeKakao = { v ->
                            Prefs.setIncludeKakao(this, v)
                            includeKakao.value = v
                            if (v && !KakaoNotificationListener.isEnabled(this)) openNotificationAccess()
                        },
                        openNotificationAccess = ::openNotificationAccess,
                        setIncludeKakaoScreen = { v ->
                            Prefs.setIncludeKakaoScreen(this, v)
                            includeKakaoScreen.value = v
                            if (v && !KakaoAccessibilityService.isEnabled(this)) openAccessibilitySettings()
                        },
                        openAccessibilitySettings = ::openAccessibilitySettings,
                        setKakaoDump = { v ->
                            Prefs.setKakaoDump(this, v)
                            kakaoDump.value = v
                        },
                        importKakaoExport = {
                            // 카카오톡 내보내기는 .txt, 미디어 포함 시 .zip으로 나오는데
                            // 파일 앱이 매기는 MIME 타입이 제각각이라 좁혀 두면 선택이 막힌다.
                            kakaoFileLauncher.launch(arrayOf("*/*"))
                        },
                        // 시작 위치를 지정할 수 없어 사용자가 Documents/KakaoTalk/Chats로 직접 들어가야 한다.
                        importKakaoFolder = { kakaoFolderLauncher.launch(null) },
                        setIncludeSms = { v ->
                            if (v && !hasPermission(Manifest.permission.READ_SMS)) {
                                smsReadLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS))
                            } else {
                                Prefs.setIncludeSms(this, v)
                                includeSms.value = v
                                if (v) UploadScheduler.enqueueNow(this, ExistingWorkPolicy.REPLACE, manual = true)
                            }
                        },
                        requestBatteryExemption = ::requestBatteryExemption,
                    ),
                )
            }
        }
        handleIntent(intent)
        lifecycleScope.launch {
            val linked = DriveAuth.silentToken(this@MainActivity) != null
            RecorderState.update { it.copy(driveLinked = linked) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(PowerManager::class.java)
        batteryExempt.value = pm.isIgnoringBatteryOptimizations(packageName)
        kakaoAccessOn.value = KakaoNotificationListener.isEnabled(this)
        kakaoScreenOn.value = KakaoAccessibilityService.isEnabled(this)
        appAccessOn.value = AppUsageExporter.hasPermission(this)
        RecorderState.update { it.copy(recordingEnabled = Prefs.isRecordingEnabled(this)) }
        lifecycleScope.launch { withContext(Dispatchers.IO) { RecorderState.refreshPending(this@MainActivity) } }
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: return
        intent.removeExtra(EXTRA_ACTION)
        when (action) {
            ACT_RESUME_SCREEN -> { RecordingService.startAudio(this); requestProjection() }
            ACT_RESUME_ALL -> turnOn()
            ACT_LINK_DRIVE -> linkDrive()
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun turnOn() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        if (Prefs.isIncludeCalls(this)) wanted += Manifest.permission.READ_MEDIA_AUDIO
        if (Prefs.isIncludeCamera(this)) {
            wanted += Manifest.permission.READ_MEDIA_IMAGES
            wanted += Manifest.permission.READ_MEDIA_VIDEO
        }
        if (Prefs.isIncludeSms(this)) { wanted += Manifest.permission.READ_SMS; wanted += Manifest.permission.READ_CONTACTS }
        val needed = wanted.filterNot(::hasPermission)
        if (needed.isEmpty()) startEverything() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startEverything() {
        RecordingService.startAudio(this)
        requestProjection()
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        )
    }

    private fun turnOff() = RecordingService.stopAll(this)

    private fun linkDrive() {
        DriveAuth.authorizeTask(this)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    onDriveLinked()
                }
            }
            .addOnFailureListener { e -> toast("Google 연결 실패: ${e.message}") }
    }

    private fun onDriveLinked() {
        RecorderState.update { it.copy(driveLinked = true, lastUploadError = null) }
        Notifications.cancel(this, Notifications.ID_DRIVE)
        UploadScheduler.enqueueNow(this)
        toast("Google Drive에 연결되었습니다")
    }

    private fun openNotificationAccess() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            toast("목록에서 Life Recorder를 켜 주세요")
        } catch (e: Exception) {
            toast("알림 접근 설정을 열지 못했습니다")
        }
    }

    private fun openUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            toast("목록에서 Life Recorder를 켜 주세요")
        } catch (e: Exception) {
            toast("사용 정보 접근 설정을 열지 못했습니다")
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("설치된 앱 > Life Recorder를 켜 주세요")
        } catch (e: Exception) {
            toast("접근성 설정을 열지 못했습니다")
        }
    }

    private fun requestBatteryExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_ACTION = "ui_action"
        const val ACT_RESUME_SCREEN = "resume_screen"
        const val ACT_RESUME_ALL = "resume_all"
        const val ACT_LINK_DRIVE = "link_drive"
    }
}
