package com.liferecorder.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.liferecorder.HourSlice
import com.liferecorder.Prefs
import java.util.concurrent.TimeUnit

/**
 * 업로드를 언제 돌릴지. 작업은 두 갈래다.
 *
 *  - 전부: 녹음 · 화면 · 사진처럼 큰 것까지. "Wi-Fi 에서만" · "충전 중에만" 설정을 따른다.
 *  - 글자만 ([UploadWorker.SCOPE_TEXT]): 카톡 · 화면 글자 · 앱 사용 · 문자의 한 시간 조각.
 *    `Prefs.isTextAnyNetwork` 이면 모바일 데이터에서도 돈다. 충전 조건은 그대로 따른다.
 */
object UploadScheduler {
    private const val NOW = "upload-now"
    private const val PERIODIC = "upload-periodic"
    private const val TAIL = "upload-hour-tail"
    private const val TEXT_PERIODIC = "upload-text-periodic"

    /** manual=true(지금 업로드 버튼)는 충전 조건을 무시하고 네트워크 조건만 본다. */
    private fun constraints(ctx: Context, manual: Boolean, text: Boolean = false) = Constraints.Builder()
        .setRequiredNetworkType(
            if (Prefs.isWifiOnly(ctx) && !(text && Prefs.isTextAnyNetwork(ctx))) NetworkType.UNMETERED
            else NetworkType.CONNECTED
        )
        .setRequiresCharging(!manual && Prefs.isUploadOnlyCharging(ctx))
        .build()

    /**
     * 세그먼트가 하나 닫힐 때마다 호출. KEEP은 백오프 대기 중인 재시도가 있으면 새 요청을 버리므로
     * 기본은 APPEND_OR_REPLACE(뒤에 이어 붙임), 수동 버튼은 REPLACE(즉시)로 쓴다.
     */
    fun enqueueNow(
        ctx: Context,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE,
        manual: Boolean = false,
    ) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints(ctx, manual))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, policy, req)
    }

    /**
     * 정각 녹음 세그먼트가 닫힐 때 부른다. 글자 자료의 지난 한 시간은 정각에서 [HourSlice.GRACE_MS] 가
     * 지나야 닫히므로, 정각에 바로 도는 [enqueueNow] 로는 올라가지 않는다. 몇 분 뒤 **글자만** 한 번 더 돌린다.
     * 같은 이름으로 다시 걸면 앞의 예약을 바꾼다 (한 시간에 한 번이면 된다).
     */
    fun enqueueTail(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints(ctx, manual = false, text = true))
            .setInputData(workDataOf(UploadWorker.KEY_SCOPE to UploadWorker.SCOPE_TEXT))
            .setInitialDelay(HourSlice.TAIL_DELAY_MIN, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(TAIL, ExistingWorkPolicy.REPLACE, req)
    }

    /** 놓친 파일이 있어도 30분마다 한 번은 시도하는 안전망. 글자만 도는 것도 따로 하나 — 녹음이 꺼져 있어도 가게. */
    fun ensurePeriodic(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val req = PeriodicWorkRequestBuilder<UploadWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints(ctx, manual = false))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        val text = PeriodicWorkRequestBuilder<UploadWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints(ctx, manual = false, text = true))
            .setInputData(workDataOf(UploadWorker.KEY_SCOPE to UploadWorker.SCOPE_TEXT))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(TEXT_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, text)
    }

    /** Wi-Fi 전용 / 충전 중 / 글자는 아무 망 설정이 바뀌면 제약 조건을 다시 건다. */
    fun reschedule(ctx: Context) {
        ensurePeriodic(ctx)
        enqueueNow(ctx, ExistingWorkPolicy.REPLACE)
    }
}
