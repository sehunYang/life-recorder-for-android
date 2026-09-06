package com.liferecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android 15부터 부팅 직후에는 마이크/화면 캡처 타입의 포그라운드 서비스를 시작할 수 없다.
 * 대신 "탭하여 재개" 알림만 띄운다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.isRecordingEnabled(context)) {
            Notifications.showResumeNeeded(context, "기기가 재부팅되었습니다")
        }
    }
}
