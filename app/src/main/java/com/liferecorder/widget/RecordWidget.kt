package com.liferecorder.widget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.liferecorder.MainActivity
import com.liferecorder.Prefs
import com.liferecorder.R
import com.liferecorder.service.ProjectionRequestActivity
import com.liferecorder.service.RecordingService

/**
 * 홈 화면 2x1 위젯. 위젯 전체가 버튼 하나이고, 누를 때마다 기록이 켜지고 꺼진다.
 *
 * 끄기는 곧바로 된다 (서비스에 중지 신호만 보내면 된다).
 * 켜기는 소리 녹음을 바로 시작한 뒤 화면 녹화 동의 창을 띄운다.
 * MediaProjection 동의 토큰은 일회용이라 이 창은 건너뛸 수 없다.
 * (PROJECT_MEDIA appop를 허용해 둔 기기에서는 창 없이 바로 승인된다.
 *  [ProjectionRequestActivity] 주석 참고.)
 *
 * 위젯 클릭은 백그라운드 포그라운드 서비스 시작 제한과 마이크 같은 "사용 중" 권한 제한
 * 양쪽 모두에서 면제 대상이다. 그래서 앱 화면을 열지 않고도 녹음을 시작할 수 있다.
 * 다만 마이크·알림 권한이 아직 없으면 위젯에서 물어볼 수 없으므로 앱을 연다.
 */
class RecordWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = render(ctx)
        ids.forEach { mgr.updateAppWidget(it, views) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_TOGGLE) {
            toggle(ctx)
            // 서비스가 상태를 바꾸면 다시 갱신하지만, 앱을 여는 등 서비스를 거치지 않는
            // 경로도 있어서 여기서도 한 번 그린다.
            refresh(ctx)
        }
    }

    private fun toggle(ctx: Context) {
        if (Prefs.isRecordingEnabled(ctx)) {
            try {
                RecordingService.stopAll(ctx)
            } catch (e: Exception) {
                // 서비스가 이미 죽어 있고 시작조차 막힌 경우. ON 표시만 남지 않도록 직접 내린다.
                Log.w(TAG, "stopAll from widget failed", e)
                Prefs.setRecordingEnabled(ctx, false)
                Prefs.setScreenWasRecording(ctx, false)
            }
            return
        }
        if (!has(ctx, Manifest.permission.RECORD_AUDIO) || !has(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
            // 권한 요청은 화면이 있어야 한다. 앱을 열어 평소의 ON 흐름에 맡긴다.
            openApp(ctx)
            return
        }
        try {
            RecordingService.startAudio(ctx)
        } catch (e: Exception) {
            // 면제가 통하지 않는 드문 경우 (예: 기기 제조사 제한). 앱을 열어 사람 손으로 켜게 둔다.
            Log.w(TAG, "startAudio from widget failed", e)
            openApp(ctx)
            return
        }
        try {
            ProjectionRequestActivity.start(ctx)
        } catch (e: Exception) {
            // 화면 녹화만 못 켠 것이다. 소리는 이미 기록 중이니 그대로 둔다.
            Log.w(TAG, "projection request from widget blocked", e)
        }
    }

    private fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context) {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_ACTION, MainActivity.ACT_RESUME_ALL)
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            Log.w(TAG, "openApp from widget failed", e)
        }
    }

    companion object {
        private const val TAG = "RecordWidget"
        private const val ACTION_TOGGLE = "com.liferecorder.WIDGET_TOGGLE"

        /**
         * 위젯을 지금 상태로 다시 그린다. 기록 상태가 바뀌는 곳에서 부른다.
         * 위젯이 하나도 없으면 아무 일도 하지 않는다.
         */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RecordWidget::class.java))
            if (ids.isEmpty()) return
            val views = render(ctx)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        /**
         * 화면에 보일 내용. 프로세스가 죽었다 살아난 직후에도 맞아야 하므로
         * 메모리에 있는 [com.liferecorder.RecorderState]가 아니라 디스크에 남는 [Prefs]를 본다.
         */
        private fun render(ctx: Context): RemoteViews {
            val on = Prefs.isRecordingEnabled(ctx)
            val screen = on && Prefs.wasScreenRecording(ctx)
            return RemoteViews(ctx.packageName, R.layout.widget_record).apply {
                setImageViewResource(R.id.widget_bg, if (on) R.drawable.widget_bg_on else R.drawable.widget_bg_off)
                setImageViewResource(R.id.widget_icon, if (on) R.drawable.ic_rec else R.drawable.ic_mic_dim)
                setTextViewText(R.id.widget_title, ctx.getString(if (on) R.string.widget_on_title else R.string.widget_off_title))
                setTextColor(R.id.widget_title, COLOR_TITLE)
                val sub = when {
                    !on -> R.string.widget_off_sub
                    screen -> R.string.widget_on_sub_both
                    else -> R.string.widget_on_sub_audio
                }
                setTextViewText(R.id.widget_sub, ctx.getString(sub))
                setTextColor(R.id.widget_sub, if (on) COLOR_ON_SUB else COLOR_OFF_SUB)
                setOnClickPendingIntent(R.id.widget_root, togglePendingIntent(ctx))
            }
        }

        private fun togglePendingIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, RecordWidget::class.java).setAction(ACTION_TOGGLE)
            return PendingIntent.getBroadcast(
                ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val COLOR_TITLE = 0xFFFFFFFF.toInt()
        private const val COLOR_ON_SUB = 0xCCFFFFFF.toInt()
        private const val COLOR_OFF_SUB = 0xFF9AA0A6.toInt()
    }
}
