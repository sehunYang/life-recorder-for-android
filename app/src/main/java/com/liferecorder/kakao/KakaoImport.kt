package com.liferecorder.kakao

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.liferecorder.SegmentClock
import com.liferecorder.Storage
import java.io.File

/**
 * 카카오톡 "대화 내용 내보내기" 파일을 업로드 대기열에 넣는다.
 * 파싱하거나 형식을 바꾸지 않고 원본 바이트 그대로 복사한다.
 */
object KakaoImport {
    private const val TAG = "KakaoImport"

    sealed class Outcome {
        data class Ok(val fileName: String, val bytes: Long) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    fun importUri(ctx: Context, uri: Uri): Outcome {
        val name = displayName(ctx, uri) ?: "export.txt"
        val dest = destFile(ctx, name)
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
            } ?: return Outcome.Failed("파일을 열지 못했습니다")
            if (dest.length() == 0L) {
                dest.delete()
                return Outcome.Failed("파일이 비어 있습니다")
            }
            finish(dest)
        } catch (e: Exception) {
            Log.w(TAG, "import failed", e)
            dest.delete()
            Outcome.Failed("가져오지 못했습니다: ${e.message}")
        }
    }

    /**
     * 파일이 아니라 본문 텍스트로 공유돼 온 경우.
     * 짧은 대화는 카카오톡이 첨부 없이 EXTRA_TEXT에 그대로 실어 보낸다.
     * 이것도 손대지 않고 받은 문자열 그대로 .txt로 남긴다.
     */
    fun importText(ctx: Context, text: String, subject: String?): Outcome {
        if (text.isBlank()) return Outcome.Failed("내용이 비어 있습니다")
        val name = (subject?.takeIf { it.isNotBlank() } ?: "share") + ".txt"
        val dest = destFile(ctx, name)
        return try {
            dest.writeText(text, Charsets.UTF_8)
            finish(dest)
        } catch (e: Exception) {
            Log.w(TAG, "text import failed", e)
            dest.delete()
            Outcome.Failed("가져오지 못했습니다: ${e.message}")
        }
    }

    /** `kakao_` 접두어라서 Storage.folderKeyOf가 kakao 폴더로 보낸다. */
    private fun destFile(ctx: Context, name: String): File {
        val stamp = SegmentClock.stamp(System.currentTimeMillis())
        return File(Storage.kakaoDir(ctx), "kakao_${stamp}_export_${sanitize(name)}${Storage.PART}")
    }

    private fun finish(dest: File): Outcome {
        val size = dest.length()
        Storage.finishPart(dest)
        return Outcome.Ok(dest.name.removeSuffix(Storage.PART), size)
    }

    private fun sanitize(name: String) =
        name.replace(Regex("""[\/:*?"<>|\s]+"""), "_").take(60)

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
