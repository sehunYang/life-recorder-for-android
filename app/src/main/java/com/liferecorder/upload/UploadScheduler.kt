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
import com.liferecorder.Prefs
import java.util.concurrent.TimeUnit

object UploadScheduler {
    private const val NOW = "upload-now"
    private const val PERIODIC = "upload-periodic"

    /** manual=true(지금 업로드 버튼)는 충전 조건을 무시하고 네트워크 조건만 본다. */
    private fun constraints(ctx: Context, manual: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (Prefs.isWifiOnly(ctx)) NetworkType.UNMETERED else NetworkType.CONNECTED)
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

    /** 놓친 파일이 있어도 30분마다 한 번은 시도하는 안전망. */
    fun ensurePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UploadWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints(ctx, manual = false))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Wi-Fi 전용 / 충전 중 설정이 바뀌면 제약 조건을 다시 건다. */
    fun reschedule(ctx: Context) {
        ensurePeriodic(ctx)
        enqueueNow(ctx, ExistingWorkPolicy.REPLACE)
    }
}
