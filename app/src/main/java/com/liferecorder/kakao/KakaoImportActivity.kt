package com.liferecorder.kakao

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.work.ExistingWorkPolicy
import com.liferecorder.upload.UploadScheduler
import kotlin.concurrent.thread

/**
 * 카카오톡에서 "대화 내용 내보내기 → 다른 앱으로 공유 → Life Recorder"로 들어오는 입구.
 * UI 없이 처리하고 토스트만 띄운 뒤 닫힌다.
 *
 * 내보내기 결과는 카카오톡 버전과 선택지에 따라 파일 한 개, 여러 개, 또는
 * 첨부 없이 본문 텍스트로 온다. 세 경우를 모두 받는다.
 */
class KakaoImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = collectUris(intent)
        val body = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT)
        if (uris.isEmpty() && body.isNullOrBlank()) {
            toast("가져올 내용이 없습니다")
            finish()
            return
        }
        toast("카카오톡 대화 ${if (uris.isEmpty()) 1 else uris.size}개 가져오는 중…")
        val ctx = applicationContext
        thread {
            val results =
                if (uris.isNotEmpty()) uris.map { KakaoImport.importUri(ctx, it) }
                else listOf(KakaoImport.importText(ctx, body.orEmpty(), subject))
            var ok = 0
            var lastError: String? = null
            for (r in results) {
                when (r) {
                    is KakaoImport.Outcome.Ok -> ok++
                    is KakaoImport.Outcome.Failed -> lastError = r.reason
                }
            }
            if (ok > 0) UploadScheduler.enqueueNow(ctx, ExistingWorkPolicy.REPLACE, manual = true)
            runOnUiThread {
                val msg = when {
                    ok > 0 -> "파일 ${ok}개를 업로드 대기열에 넣었습니다"
                    else -> lastError ?: "가져오지 못했습니다"
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun collectUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
