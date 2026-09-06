package com.liferecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.liferecorder.service.RecordingService

object Notifications {
    const val CH_RECORDING = "recording"
    const val CH_ALERTS = "alerts"
    const val CH_UPLOAD = "upload"

    const val ID_ONGOING = 1
    const val ID_SCREEN_STOPPED = 2
    const val ID_RESUME = 3
    const val ID_DRIVE = 4

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun createChannels(ctx: Context) {
        nm(ctx).createNotificationChannel(
            NotificationChannel(CH_RECORDING, "기록 중 상태", NotificationManager.IMPORTANCE_LOW).apply {
                description = "녹음/녹화가 켜져 있는 동안 항상 표시됩니다"
                setShowBadge(false)
            }
        )
        nm(ctx).createNotificationChannel(
            NotificationChannel(CH_ALERTS, "중단 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "기록이 끊겼거나 조치가 필요할 때"
            }
        )
        // 업로드는 급한 일이 아니다. 워커가 30분마다 + 재시도마다 돌기 때문에
        // 소리가 나는 채널에 두면 하루 종일 울린다.
        nm(ctx).createNotificationChannel(
            NotificationChannel(CH_UPLOAD, "업로드 알림", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Drive 연결이 필요하거나 업로드가 밀렸을 때"
            }
        )
    }

    fun ongoing(ctx: Context, audioOn: Boolean, screenOn: Boolean): Notification {
        val text = (if (audioOn) "녹음 중" else "녹음 꺼짐") + " · " + (if (screenOn) "화면 녹화 중" else "화면 녹화 꺼짐")
        val b = Notification.Builder(ctx, CH_RECORDING)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle("Life Recorder")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx, null))
        val icon = Icon.createWithResource(ctx, R.drawable.ic_rec)
        if (!screenOn) {
            b.addAction(Notification.Action.Builder(icon, "화면 녹화 재개", openApp(ctx, MainActivity.ACT_RESUME_SCREEN)).build())
        }
        b.addAction(Notification.Action.Builder(icon, "중지", stopService(ctx)).build())
        return b.build()
    }

    fun updateOngoing(ctx: Context, audioOn: Boolean, screenOn: Boolean) =
        nm(ctx).notify(ID_ONGOING, ongoing(ctx, audioOn, screenOn))

    fun showScreenStopped(ctx: Context, reason: String) =
        alert(ctx, ID_SCREEN_STOPPED, "화면 녹화가 중단됨", "$reason · 탭하여 재개", MainActivity.ACT_RESUME_SCREEN)

    fun showResumeNeeded(ctx: Context, reason: String) =
        alert(ctx, ID_RESUME, "기록이 중단됨", "$reason · 탭하여 재개", MainActivity.ACT_RESUME_ALL)

    fun showDriveLoginNeeded(ctx: Context) =
        alert(ctx, ID_DRIVE, "Google Drive 연결 필요", "업로드가 멈춰 있습니다 · 탭하여 연결", MainActivity.ACT_LINK_DRIVE, CH_UPLOAD)

    fun cancel(ctx: Context, id: Int) = nm(ctx).cancel(id)

    /** 같은 id로 다시 띄워도 소리를 내지 않는다. 워커가 반복해서 부르는 알림이 있기 때문이다. */
    private fun alert(ctx: Context, id: Int, title: String, text: String, action: String, channel: String = CH_ALERTS) {
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx, action))
            .build()
        nm(ctx).notify(id, n)
    }

    private fun openApp(ctx: Context, action: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (action != null) putExtra(MainActivity.EXTRA_ACTION, action)
        }
        return PendingIntent.getActivity(
            ctx, action?.hashCode() ?: 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopService(ctx: Context): PendingIntent {
        val i = Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_STOP_ALL)
        return PendingIntent.getForegroundService(ctx, 100, i, PendingIntent.FLAG_IMMUTABLE)
    }
}
