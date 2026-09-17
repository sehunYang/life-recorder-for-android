package com.liferecorder.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liferecorder.R
import com.liferecorder.Status
import com.liferecorder.Storage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiActions(
    val turnOn: () -> Unit,
    val turnOff: () -> Unit,
    val resumeScreen: () -> Unit,
    val linkDrive: () -> Unit,
    val uploadNow: () -> Unit,
    val setWifiOnly: (Boolean) -> Unit,
    val setChargingOnly: (Boolean) -> Unit,
    val setIncludeCalls: (Boolean) -> Unit,
    val setIncludeCamera: (Boolean) -> Unit,
    val setIncludeSms: (Boolean) -> Unit,
    val setIncludeApp: (Boolean) -> Unit,
    val openUsageAccess: () -> Unit,
    val setIncludeKakao: (Boolean) -> Unit,
    val openNotificationAccess: () -> Unit,
    val setIncludeScreenText: (Boolean) -> Unit,
    val openAccessibilitySettings: () -> Unit,
    val setKakaoDump: (Boolean) -> Unit,
    val importKakaoExport: () -> Unit,
    val importKakaoFolder: () -> Unit,
    val requestBatteryExemption: () -> Unit,
)

@Composable
fun MainScreen(
    status: Status,
    wifiOnly: Boolean,
    chargingOnly: Boolean,
    includeCalls: Boolean,
    includeCamera: Boolean,
    includeSms: Boolean,
    includeApp: Boolean,
    appAccessOn: Boolean,
    includeScreenText: Boolean,
    screenTextOn: Boolean,
    includeKakao: Boolean,
    kakaoAccessOn: Boolean,
    kakaoDump: Boolean,
    batteryExempt: Boolean,
    actions: UiActions,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(status)
            Hero(status, actions)
            UploadCard(status, wifiOnly, chargingOnly, actions)
            CollectCard(status, includeCalls, includeCamera, includeSms, includeApp, appAccessOn, includeScreenText, screenTextOn, actions)
            KakaoCard(status, includeKakao, kakaoAccessOn, kakaoDump, actions)
            KeepAliveCard(batteryExempt, actions)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Header(status: Status) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Life Recorder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (status.recordingEnabled) "소리와 화면을 기록해 Drive에 올리는 중" else "OFF 상태 · 아무것도 기록하지 않음",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 지금 무엇을 하고 있는지 한눈에 보이는 큰 카드와 ON/OFF 버튼. */
@Composable
private fun Hero(status: Status, actions: UiActions) {
    val on = status.recordingEnabled
    val container by animateColorAsState(
        if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "hero",
    )
    val onContainer = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    // 경과 시간은 1초마다 갱신. 꺼져 있으면 돌지 않는다.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.audioRecording) {
        while (status.audioRecording) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Surface(shape = RoundedCornerShape(28.dp), color = container, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(
                    color = if (status.audioRecording) Tone.Recording else MaterialTheme.colorScheme.outline,
                    pulsing = status.audioRecording,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            status.audioRecording -> "기록 중"
                            on -> "기록이 끊김"
                            else -> "대기 중"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = onContainer,
                    )
                    Text(
                        when {
                            status.audioRecording && status.currentSegmentStart > 0 ->
                                "${fmtClock(status.currentSegmentStart)}부터 · ${fmtElapsed(now - status.currentSegmentStart)}"
                            on -> "탭해서 다시 시작"
                            else -> "ON을 누르면 소리와 화면을 기록합니다"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.75f),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(
                    icon = R.drawable.ic_mic,
                    label = if (status.audioRecording) "녹음" else "녹음 꺼짐",
                    dot = if (status.audioRecording) Tone.Recording else null,
                    modifier = Modifier.weight(1f),
                )
                Chip(
                    icon = R.drawable.ic_screen,
                    label = when {
                        !status.screenRecording -> "화면 꺼짐"
                        status.screenPausedReason != null -> "화면 · ${status.screenPausedReason}"
                        else -> "화면 녹화"
                    },
                    dot = when {
                        !status.screenRecording -> null
                        status.screenPausedReason != null -> Tone.Paused
                        else -> Tone.Recording
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            status.audioError?.let { WarningText(it) }
            status.screenStoppedReason?.let { WarningText("화면 녹화 중단 · $it") }

            Button(
                onClick = { if (on) actions.turnOff() else actions.turnOn() },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (on) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (on) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                RecordGlyph(stop = on, color = if (on) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(12.dp))
                Text(if (on) "기록 중지" else "기록 시작", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            AnimatedVisibility(on && !status.audioRecording) {
                FilledTonalButton(onClick = actions.turnOn, Modifier.fillMaxWidth()) { Text("녹음 다시 시작") }
            }
            AnimatedVisibility(on && status.audioRecording && !status.screenRecording) {
                FilledTonalButton(onClick = actions.resumeScreen, Modifier.fillMaxWidth()) { Text("화면 녹화 다시 시작") }
            }
        }
    }
}

/** 녹음 시작(원) / 중지(둥근 사각형) 기호. */
@Composable
private fun RecordGlyph(stop: Boolean, color: Color) {
    Canvas(Modifier.size(16.dp)) {
        if (stop) drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx()))
        else drawCircle(color)
    }
}

@Composable
private fun Chip(icon: Int, label: String, dot: Color?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        if (dot != null) StatusDot(color = dot, pulsing = dot == Tone.Recording, modifier = Modifier.size(8.dp))
    }
}

@Composable
private fun UploadCard(status: Status, wifiOnly: Boolean, chargingOnly: Boolean, actions: UiActions) {
    SectionCard(
        icon = painterResource(R.drawable.ic_cloud),
        title = "Google Drive",
        trailing = {
            if (status.driveLinked) StatusPill("연결됨", PillTone.Good) else StatusPill("연결 필요", PillTone.Bad)
        },
    ) {
        AnimatedVisibility(status.uploading != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                Text("업로드 중 · ${status.uploading}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("대기 파일", "${status.pendingFiles}개", Modifier.weight(1f))
            StatTile("대기 용량", fmtBytes(status.pendingBytes), Modifier.weight(1f))
            StatTile("마지막 성공", fmtShort(status.lastUploadAt), Modifier.weight(1.2f))
        }
        status.lastUploadError?.let { WarningText(it) }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ToggleRow("Wi-Fi에서만 업로드", wifiOnly, actions.setWifiOnly)
        ToggleRow("충전 중에만 자동 업로드", chargingOnly, actions.setChargingOnly, subtitle = "\"지금 업로드\"는 조건과 상관없이 바로 올립니다")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.linkDrive, Modifier.weight(1f)) {
                Text(if (status.driveLinked) "계정 다시 연결" else "Google 계정 연결")
            }
            FilledTonalButton(onClick = actions.uploadNow, Modifier.weight(1f)) { Text("지금 업로드") }
        }
    }
}

@Composable
private fun CollectCard(
    status: Status,
    includeCalls: Boolean,
    includeCamera: Boolean,
    includeSms: Boolean,
    includeApp: Boolean,
    appAccessOn: Boolean,
    includeScreenText: Boolean,
    screenTextOn: Boolean,
    actions: UiActions,
) {
    SectionCard(icon = painterResource(R.drawable.ic_folder), title = "함께 모으기") {
        ToggleRow(
            "통화 녹음 파일",
            includeCalls,
            actions.setIncludeCalls,
            subtitle = if (includeCalls) status.callImportNote ?: "폰이 저장한 통화 녹음을 복사해 올립니다" else "꺼짐",
        )
        ToggleRow(
            "카메라 사진·동영상",
            includeCamera,
            actions.setIncludeCamera,
            subtitle = if (includeCamera) {
                status.cameraImportNote ?: "DCIM/Camera를 복사해 올립니다. 원본은 그대로 둡니다"
            } else "꺼짐",
        )
        if (includeCamera) Hint("사진첩이 크면 한 번에 다 올리지 않고 실행마다 1GB씩 나눠 올립니다. 최신 것부터 갑니다.")
        ToggleRow(
            "문자 메시지",
            includeSms,
            actions.setIncludeSms,
            subtitle = if (includeSms) status.smsExportNote ?: "어제까지의 SMS/MMS를 하루 단위로 올립니다" else "꺼짐",
        )
        ToggleRow(
            "앱 사용 기록",
            includeApp,
            actions.setIncludeApp,
            subtitle = if (includeApp) {
                status.appExportNote ?: "어느 앱이 앞에 떠 있었는지, 화면이 켜지고 잠긴 시각을 하루 단위로 올립니다"
            } else "꺼짐",
        )
        AnimatedVisibility(includeApp && !appAccessOn) {
            FilledTonalButton(onClick = actions.openUsageAccess, Modifier.fillMaxWidth()) { Text("사용 정보 접근 허용하기") }
        }
        ToggleRow(
            "화면 글자 읽기 (접근성)",
            includeScreenText,
            actions.setIncludeScreenText,
            subtitle = when {
                !includeScreenText -> "꺼짐"
                screenTextOn -> "모든 앱 화면에 보이는 글자를 그대로 모으는 중"
                else -> "설정 > 접근성 > 설치된 앱에서 Life Recorder를 켜야 합니다"
            },
        )
        AnimatedVisibility(includeScreenText && !screenTextOn) {
            FilledTonalButton(onClick = actions.openAccessibilitySettings, Modifier.fillMaxWidth()) { Text("접근성 설정 열기") }
        }
        Hint("화면 녹화를 OCR로 다시 읽지 않아도 되게, 뷰에 있는 글자를 원문 그대로 남깁니다. 카카오톡은 내 발화와 방 이름까지 옵니다. 비밀번호 칸은 남기지 않습니다.")
    }
}

@Composable
private fun KakaoCard(status: Status, includeKakao: Boolean, kakaoAccessOn: Boolean, kakaoDump: Boolean, actions: UiActions) {
    SectionCard(
        icon = painterResource(R.drawable.ic_chat),
        title = "카카오톡",
        trailing = {
            if (includeKakao) {
                if (kakaoAccessOn) StatusPill("알림 접근 허용됨", PillTone.Good) else StatusPill("알림 접근 필요", PillTone.Bad)
            }
        },
    ) {
        ToggleRow(
            "알림으로 대화 기록",
            includeKakao,
            actions.setIncludeKakao,
            subtitle = if (includeKakao) status.kakaoNote ?: "알림에 뜬 내용을 그대로 하루치 파일로 모읍니다" else "꺼짐",
        )
        AnimatedVisibility(includeKakao && !kakaoAccessOn) {
            FilledTonalButton(onClick = actions.openNotificationAccess, Modifier.fillMaxWidth()) { Text("알림 접근 허용하기") }
        }
        Hint(
            "내가 보낸 메시지와 채팅방을 열어둔 동안 받은 메시지는 알림이 없어 빠집니다. " +
                "위의 '화면 글자 읽기'를 켜면 화면에 보이는 동안은 그것도 남고, " +
                "전체 대화가 필요하면 채팅방 > 메뉴 > 대화 내용 > 내보내기 후 Life Recorder로 공유하세요."
        )
        Hint("텍스트만 내보냈으면 파일을, 미디어까지 저장했으면 그 폴더를 고르세요. 폴더는 안의 사진까지 통째로 가져옵니다.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.importKakaoExport, Modifier.weight(1f)) { Text("파일 고르기") }
            OutlinedButton(onClick = actions.importKakaoFolder, Modifier.weight(1f)) { Text("폴더 고르기") }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ToggleRow(
            "진단: 알림 원본 덤프",
            kakaoDump,
            actions.setKakaoDump,
            subtitle = if (kakaoDump) "알림 하나하나를 통째로 저장 중. 용량이 커지니 확인이 끝나면 끄세요" else "알림에 실제로 무엇이 오는지 확인할 때만",
        )
    }
}

@Composable
private fun KeepAliveCard(batteryExempt: Boolean, actions: UiActions) {
    SectionCard(
        icon = painterResource(R.drawable.ic_shield),
        title = "안 꺼지게 하기",
        trailing = { if (batteryExempt) StatusPill("배터리 최적화 제외", PillTone.Good) else StatusPill("설정 필요", PillTone.Bad) },
    ) {
        AnimatedVisibility(!batteryExempt) {
            FilledTonalButton(onClick = actions.requestBatteryExemption, Modifier.fillMaxWidth()) { Text("배터리 최적화 제외 요청") }
        }
        Hint(
            "삼성 기기: 설정 > 배터리 > 백그라운드 사용 제한 > '절전 예외 앱'에 추가하고, " +
                "앱 정보 > 배터리를 '제한 없음'으로 두세요."
        )
    }
}

private val clockFormat = SimpleDateFormat("HH:mm", Locale.KOREA)
private val shortFormat = SimpleDateFormat("M/d HH:mm", Locale.KOREA)

private fun fmtClock(ms: Long) = clockFormat.format(Date(ms))
private fun fmtShort(ms: Long) = if (ms == 0L) "없음" else shortFormat.format(Date(ms))

private fun fmtElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "${h}시간 ${m}분"
        m > 0 -> "${m}분 ${s % 60}초"
        else -> "${s}초"
    }
}

private fun fmtBytes(b: Long) = Storage.fmtBytes(b)
