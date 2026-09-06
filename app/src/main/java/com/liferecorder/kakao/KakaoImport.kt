package com.liferecorder.kakao

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.liferecorder.SegmentClock
import com.liferecorder.Storage
import java.io.File

/**
 * 카카오톡 "대화 내용 내보내기" 결과를 업로드 대기열에 넣는다.
 * 파싱하거나 형식을 바꾸지 않고 원본 바이트 그대로 복사한다.
 *
 * 카카오톡의 내보내기는 두 가지다.
 *  - 텍스트만: 공유 시트로 .txt 하나가 온다 → [importUri]
 *  - 미디어 포함: `Documents/KakaoTalk/Chats/<대화방>/`에 .txt와 사진·동영상이 저장된다 → [importTree]
 */
object KakaoImport {
    private const val TAG = "KakaoImport"

    /** 폴더 안이 아무리 깊어도 이만큼만 훑는다. 순환 링크나 엉뚱한 폴더를 골랐을 때의 안전장치. */
    private const val MAX_ENTRIES = 5000

    sealed class Outcome {
        data class Ok(val fileName: String, val bytes: Long) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /** 폴더 하나를 통째로 가져온 결과. */
    data class TreeResult(
        val text: Int,
        val media: Int,
        val bytes: Long,
        val failed: Int,
        val error: String?,
    ) {
        val total get() = text + media
    }

    fun importUri(ctx: Context, uri: Uri): Outcome {
        val name = displayName(ctx, uri) ?: "export.txt"
        return copyTo(ctx, uri, textDest(ctx, name))
    }

    /**
     * 파일이 아니라 본문 텍스트로 공유돼 온 경우.
     * 짧은 대화는 카카오톡이 첨부 없이 EXTRA_TEXT에 그대로 실어 보낸다.
     * 이것도 손대지 않고 받은 문자열 그대로 .txt로 남긴다.
     */
    fun importText(ctx: Context, text: String, subject: String?): Outcome {
        if (text.isBlank()) return Outcome.Failed("내용이 비어 있습니다")
        val name = (subject?.takeIf { it.isNotBlank() } ?: "share") + ".txt"
        val dest = textDest(ctx, name)
        return try {
            dest.writeText(text, Charsets.UTF_8)
            finish(dest)
        } catch (e: Exception) {
            Log.w(TAG, "text import failed", e)
            dest.delete()
            Outcome.Failed("가져오지 못했습니다: ${e.message}")
        }
    }

    /**
     * "미디어 포함 저장"이 만든 폴더를 통째로 가져온다.
     * 대화록은 kakao 폴더로, 사진·동영상은 kakao-media 폴더로 나눠 넣는다.
     * 하위 폴더가 있으면 따라 들어간다. 오래 걸릴 수 있으니 백그라운드에서 부를 것.
     */
    fun importTree(ctx: Context, treeUri: Uri): TreeResult {
        var text = 0
        var media = 0
        var bytes = 0L
        var failed = 0
        var error: String? = null
        val stamp = SegmentClock.stamp(System.currentTimeMillis())

        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            Log.w(TAG, "bad tree uri", e)
            return TreeResult(0, 0, 0L, 0, "폴더를 열지 못했습니다")
        }

        val pending = ArrayDeque<String>().apply { add(rootId) }
        var seen = 0
        while (pending.isNotEmpty() && seen < MAX_ENTRIES) {
            val parent = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
            try {
                ctx.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext() && seen < MAX_ENTRIES) {
                        seen++
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(id)
                            continue
                        }
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        val isMedia = isMedia(mime, name)
                        val dest = if (isMedia) mediaDest(ctx, stamp, name) else textDest(ctx, name)
                        when (val r = copyTo(ctx, uri, dest)) {
                            is Outcome.Ok -> {
                                if (isMedia) media++ else text++
                                bytes += r.bytes
                            }
                            is Outcome.Failed -> {
                                failed++
                                error = r.reason
                                Log.w(TAG, "skip $name: ${r.reason}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "list failed for $parent", e)
                error = e.message
            }
        }
        Log.i(TAG, "tree import: text=$text media=$media failed=$failed")
        return TreeResult(text, media, bytes, failed, error)
    }

    /** MIME을 못 믿는 제공자가 있어 확장자도 같이 본다. */
    private fun isMedia(mime: String, name: String): Boolean {
        if (mime.startsWith("image", true) || mime.startsWith("video", true) || mime.startsWith("audio", true)) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "mp4", "mov", "avi", "mkv", "webm", "m4a", "aac", "mp3", "ogg", "amr")
    }

    private fun copyTo(ctx: Context, uri: Uri, dest: File): Outcome {
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
            Log.w(TAG, "copy failed", e)
            dest.delete()
            Outcome.Failed("가져오지 못했습니다: ${e.message}")
        }
    }

    /** `kakao_` 접두어라서 Storage.folderKeyOf가 kakao 폴더로 보낸다. */
    private fun textDest(ctx: Context, name: String): File {
        val stamp = SegmentClock.stamp(System.currentTimeMillis())
        return File(Storage.kakaoDir(ctx), "kakao_${stamp}_export_${sanitize(name)}${Storage.PART}")
    }

    /**
     * 내보내기 폴더에서 가져온 사진·동영상. `kakaoexp_` 접두어라 kakao-media 폴더로 간다.
     * 한 번의 가져오기에 같은 stamp를 써서 어느 폴더에서 함께 온 것인지 묶인다.
     * 알림에서 받은 사진(`kakaoimg_`)과는 출처가 다르므로 접두어로 구분한다.
     */
    private fun mediaDest(ctx: Context, stamp: String, name: String): File =
        File(Storage.kakaoMediaDir(ctx), "kakaoexp_${stamp}_${sanitize(name)}${Storage.PART}")

    private fun finish(dest: File): Outcome {
        val size = dest.length()
        Storage.finishPart(dest)
        return Outcome.Ok(dest.name.removeSuffix(Storage.PART), size)
    }

    private fun sanitize(name: String) =
        name.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(60)

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
