package com.liferecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android 15부터 부팅 직후에는 마이크/화면 캡처 타입의 포그라운드 서비스를 시작할 수 없다.
 * 대신 "탭하여 재개" 알림만 띄운다. 앱을 업데이트한 직후도 같다 — 업데이트가 프로세스를 끝내
 * 녹음이 멈추는데, 백그라운드에서는 마이크 서비스를 다시 켤 수 없다 (2026-10-01 v0.8 설치 때 겪음).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val why = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "기기가 재부팅되었습니다"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "앱이 업데이트되었습니다"
            else -> return
        }
        if (Prefs.isRecordingEnabled(context)) {
            Notifications.showResumeNeeded(context, why)
        }
    }
}
