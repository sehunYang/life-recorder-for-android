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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import com.google.android.gms.common.api.ApiException
import com.liferecorder.kakao.KakaoImport
import com.liferecorder.kakao.KakaoNotificationListener
import com.liferecorder.service.RecordingService
import com.liferecorder.upload.DriveAuth
import com.liferecorder.upload.UploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val batteryExempt = mutableStateOf(false)
    private val wifiOnly = mutableStateOf(true)
    private val chargingOnly = mutableStateOf(true)
    private val includeCalls = mutableStateOf(true)
    private val includeSms = mutableStateOf(true)
    private val includeKakao = mutableStateOf(true)
    private val kakaoAccessOn = mutableStateOf(false)
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
        wifiOnly.value = Prefs.isWifiOnly(this)
        chargingOnly.value = Prefs.isUploadOnlyCharging(this)
        includeCalls.value = Prefs.isIncludeCalls(this)
        includeSms.value = Prefs.isIncludeSms(this)
        includeKakao.value = Prefs.isIncludeKakao(this)
        kakaoDump.value = Prefs.isKakaoDump(this)
        setContent {
            val status by RecorderState.status.collectAsStateWithLifecycle()
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    MainScreen(
                        status = status,
                        wifiOnly = wifiOnly.value,
                        chargingOnly = chargingOnly.value,
                        includeCalls = includeCalls.value,
                        includeSms = includeSms.value,
                        includeKakao = includeKakao.value,
                        kakaoAccessOn = kakaoAccessOn.value,
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
                            setIncludeKakao = { v ->
                                Prefs.setIncludeKakao(this, v)
                                includeKakao.value = v
                                if (v && !KakaoNotificationListener.isEnabled(this)) openNotificationAccess()
                            },
                            openNotificationAccess = ::openNotificationAccess,
                            setKakaoDump = { v ->
                                Prefs.setKakaoDump(this, v)
                                kakaoDump.value = v
                            },
                            importKakaoExport = {
                                // 카카오톡 내보내기는 .txt, 미디어 포함 시 .zip으로 나오는데
                                // 파일 앱이 매기는 MIME 타입이 제각각이라 좁혀 두면 선택이 막힌다.
                                kakaoFileLauncher.launch(arrayOf("*/*"))
                            },
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

data class UiActions(
    val turnOn: () -> Unit,
    val turnOff: () -> Unit,
    val resumeScreen: () -> Unit,
    val linkDrive: () -> Unit,
    val uploadNow: () -> Unit,
    val setWifiOnly: (Boolean) -> Unit,
    val setChargingOnly: (Boolean) -> Unit,
    val setIncludeCalls: (Boolean) -> Unit,
    val setIncludeSms: (Boolean) -> Unit,
    val setIncludeKakao: (Boolean) -> Unit,
    val openNotificationAccess: () -> Unit,
    val setKakaoDump: (Boolean) -> Unit,
    val importKakaoExport: () -> Unit,
    val requestBatteryExemption: () -> Unit,
)

private val timeFormat = SimpleDateFormat("MM/dd HH:mm:ss", Locale.KOREA)
private fun fmtTime(ms: Long) = if (ms == 0L) "-" else timeFormat.format(Date(ms))
private fun fmtBytes(b: Long) = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

@Composable
fun MainScreen(
    status: Status,
    wifiOnly: Boolean,
    chargingOnly: Boolean,
    includeCalls: Boolean,
    includeSms: Boolean,
    includeKakao: Boolean,
    kakaoAccessOn: Boolean,
    kakaoDump: Boolean,
    batteryExempt: Boolean,
    actions: UiActions,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Life Recorder", style = MaterialTheme.typography.headlineMedium)

        val on = status.recordingEnabled
        Button(
            onClick = { if (on) actions.turnOff() else actions.turnOn() },
            modifier = Modifier.fillMaxWidth().height(80.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (on) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
        ) {
            Text(if (on) "기록 중지 (OFF)" else "기록 시작 (ON)", fontSize = 22.sp)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("상태", style = MaterialTheme.typography.titleMedium)
                StatusRow("녹음", if (status.audioRecording) "● 녹음 중" else "꺼짐")
                status.audioError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                StatusRow(
                    "화면 녹화",
                    when {
                        !status.screenRecording -> "꺼짐"
                        status.screenPausedReason != null -> "● 녹화 중 (일시정지: ${status.screenPausedReason})"
                        else -> "● 녹화 중"
                    },
                )
                status.screenStoppedReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                StatusRow("현재 세그먼트 시작", fmtTime(status.currentSegmentStart))
                if (on && !status.audioRecording) {
                    OutlinedButton(onClick = actions.turnOn, Modifier.fillMaxWidth()) { Text("기록 재개") }
                } else if (on && !status.screenRecording) {
                    OutlinedButton(onClick = actions.resumeScreen, Modifier.fillMaxWidth()) { Text("화면 녹화 재개") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("업로드 (Google Drive)", style = MaterialTheme.typography.titleMedium)
                StatusRow("계정", if (status.driveLinked) "연결됨" else "연결 필요")
                StatusRow("대기 파일", "${status.pendingFiles}개 · ${fmtBytes(status.pendingBytes)}")
                StatusRow("업로드 중", status.uploading ?: "-")
                StatusRow("마지막 성공", fmtTime(status.lastUploadAt))
                status.lastUploadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wi-Fi에서만 업로드", Modifier.weight(1f))
                    Switch(checked = wifiOnly, onCheckedChange = actions.setWifiOnly)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("충전 중에만 자동 업로드", Modifier.weight(1f))
                    Switch(checked = chargingOnly, onCheckedChange = actions.setChargingOnly)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("전화 녹음 파일도 업로드", Modifier.weight(1f))
                    Switch(checked = includeCalls, onCheckedChange = actions.setIncludeCalls)
                }
                if (includeCalls) StatusRow("통화 녹음", status.callImportNote ?: "-")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("문자 메시지 하루치 업로드", Modifier.weight(1f))
                    Switch(checked = includeSms, onCheckedChange = actions.setIncludeSms)
                }
                if (includeSms) StatusRow("문자", status.smsExportNote ?: "-")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.linkDrive, Modifier.weight(1f)) {
                        Text(if (status.driveLinked) "계정 다시 연결" else "Google 계정 연결")
                    }
                    OutlinedButton(onClick = actions.uploadNow, Modifier.weight(1f)) { Text("지금 업로드") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("카카오톡", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("알림으로 대화 기록", Modifier.weight(1f))
                    Switch(checked = includeKakao, onCheckedChange = actions.setIncludeKakao)
                }
                if (includeKakao) {
                    StatusRow("알림 접근", if (kakaoAccessOn) "허용됨" else "허용 필요")
                    StatusRow("상태", status.kakaoNote ?: "-")
                    if (!kakaoAccessOn) {
                        OutlinedButton(onClick = actions.openNotificationAccess, Modifier.fillMaxWidth()) {
                            Text("알림 접근 허용하기")
                        }
                    }
                }
                Text(
                    "알림에 뜬 내용을 그대로 JSONL로 쌓아 올립니다. 내가 보낸 메시지와 " +
                        "채팅방을 열어둔 동안 받은 메시지는 알림이 없어 빠집니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "전체 대화가 필요하면 카카오톡에서 채팅방 > 메뉴 > 대화 내용 > 내보내기 후 " +
                        "Life Recorder로 공유하세요. 파일을 그대로 올립니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("진단: 알림 원본 덤프", Modifier.weight(1f))
                    Switch(checked = kakaoDump, onCheckedChange = actions.setKakaoDump)
                }
                if (kakaoDump) {
                    Text(
                        "알림 하나하나를 통째로 kakao_dump_<날짜>.jsonl에 남깁니다. " +
                            "무엇이 실제로 들어오는지 확인할 때만 잠깐 켜세요. 용량이 큽니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = actions.importKakaoExport, Modifier.fillMaxWidth()) {
                    Text("내보낸 대화 파일 가져오기")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("안 꺼지게 하기", style = MaterialTheme.typography.titleMedium)
                StatusRow("배터리 최적화 제외", if (batteryExempt) "적용됨" else "필요")
                if (!batteryExempt) {
                    OutlinedButton(onClick = actions.requestBatteryExemption, Modifier.fillMaxWidth()) {
                        Text("배터리 최적화 제외 요청")
                    }
                }
                Text(
                    "삼성 기기: 설정 > 배터리 > 백그라운드 사용 제한 > '절전 예외 앱'에 이 앱을 추가하고, " +
                        "앱 정보 > 배터리를 '제한 없음'으로 두세요.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(140.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
