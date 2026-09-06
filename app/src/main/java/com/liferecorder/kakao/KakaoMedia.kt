package com.liferecorder.kakao

import android.content.Context
import android.net.Uri
import android.util.Log
import com.liferecorder.SegmentClock
import com.liferecorder.Storage
import java.io.File

/**
 * 카카오톡 알림에 실려 온 사진을 그 자리에서 받아 둔다.
 *
 * 알림의 dataUri는 카카오톡 FileProvider를 가리키고, 읽기 권한은 그 알림이 살아 있는 동안
 * 알림 리스너에게만 잠깐 주어진다. 알림이 사라진 뒤에 그 URI로 다시 열면 실패한다.
 * 그래서 URI 문자열만 적어 두면 나중에 쓸 수 없고, 받은 순간에 바이트를 복사해야 한다.
 *
 * 동영상은 애초에 dataUri가 오지 않아서 이 경로로는 담을 수 없다 (본문만 "동영상을 보냈습니다").
 */
object KakaoMedia {
    private const val TAG = "KakaoMedia"

    /** 알림 하나에 딸려 오는 사진 크기 한도. 이보다 크면 무언가 잘못된 것으로 보고 건너뛴다. */
    private const val MAX_BYTES = 32L * 1024 * 1024

    /**
     * 사진을 받아 파일 이름을 돌려준다. 못 받으면 null.
     * 같은 사진이 여러 알림에 반복해서 실려 오므로 이름을 내용 기준으로 정해 한 번만 받는다.
     */
    fun save(ctx: Context, uri: Uri, timeMs: Long, user: String, roomId: String): String? {
        val dir = Storage.kakaoMediaDir(ctx)
        val stem = "kakaoimg_${SegmentClock.stamp(timeMs)}_${user}_${sanitize(roomId)}_${tag(uri)}"

        // 확장자는 실제 내용을 보고 정하므로, 이미 받아 둔 게 있는지는 stem으로 찾는다.
        dir.listFiles()?.firstOrNull { it.name.startsWith(stem) && !it.name.endsWith(Storage.PART) }
            ?.let { return it.name }

        val part = File(dir, "$stem.bin${Storage.PART}")
        val copied = try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                part.outputStream().use { out -> input.copyTo(out, 128 * 1024) }
            } ?: run {
                Log.w(TAG, "openInputStream returned null: $uri")
                return null
            }
            part.length()
        } catch (e: Exception) {
            // 권한이 이미 회수됐거나(알림이 사라짐) 원본이 없는 경우.
            Log.w(TAG, "copy failed: ${e.message}")
            part.delete()
            return null
        }

        if (copied <= 0L || copied > MAX_BYTES) {
            Log.w(TAG, "unexpected size $copied, dropping")
            part.delete()
            return null
        }

        // 카카오톡은 dataMimeType을 "image/"처럼 하위 타입 없이 준다. 내용으로 확장자를 정한다.
        val renamed = File(dir, "$stem.${extensionOf(part)}${Storage.PART}")
        if (!part.renameTo(renamed)) {
            Log.w(TAG, "rename failed, keeping .bin")
            return Storage.finishPart(part).name
        }
        val done = Storage.finishPart(renamed)
        Log.i(TAG, "saved ${done.name} ($copied bytes)")
        return done.name
    }

    /** 같은 사진이면 같은 값이 나오도록 URI에서 뽑아 쓴다. 카카오톡 URI 끝은 내용 해시다. */
    private fun tag(uri: Uri): String {
        val last = uri.lastPathSegment?.takeLast(16)
        return if (!last.isNullOrBlank()) sanitize(last) else Integer.toHexString(uri.toString().hashCode())
    }

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun extensionOf(f: File): String {
        val head = ByteArray(12)
        val n = try {
            f.inputStream().use { it.read(head) }
        } catch (e: Exception) {
            return "jpg"
        }
        fun at(i: Int) = if (i < n) head[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> "jpg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "gif"
            at(0) == 0x52 && at(1) == 0x49 && at(8) == 0x57 && at(9) == 0x45 -> "webp"
            else -> "jpg"
        }
    }
}
