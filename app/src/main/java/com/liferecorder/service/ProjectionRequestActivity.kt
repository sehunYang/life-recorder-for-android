package com.liferecorder.service

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.liferecorder.Notifications
import com.liferecorder.Prefs

/**
 * 화면 녹화 동의만 받아 서비스에 넘기고 곧바로 사라지는 투명 액티비티.
 * 잠금이 풀린 뒤 서비스가 사람 손 없이 화면 녹화를 되살릴 때 쓴다.
 *
 * 이 기기에서 아래를 한 번 해 두면 동의 창이 뜨지 않고 즉시 승인된다.
 *   adb shell appops set com.liferecorder PROJECT_MEDIA allow
 * 해 두지 않았으면 평소처럼 동의 창이 뜬다. 거부하면 자동 재개를 멈추고
 * 알림만 남겨서, 사용자가 직접 누르기 전까지 다시 묻지 않는다.
 */
class ProjectionRequestActivity : ComponentActivity() {

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                RecordingService.startScreen(this, res.resultCode, data)
            } else {
                Log.w(TAG, "projection request denied")
                Prefs.setScreenWasRecording(this, false)
                Notifications.showScreenStopped(this, "화면 녹화 권한이 거부되었습니다")
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        launcher.launch(
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        )
    }

    companion object {
        private const val TAG = "ProjectionRequest"

        fun start(ctx: Context) {
            ctx.startActivity(
                Intent(ctx, ProjectionRequestActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            )
        }
    }
}
