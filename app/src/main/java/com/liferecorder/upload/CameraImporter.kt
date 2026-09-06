package com.liferecorder.upload

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.liferecorder.Config
import com.liferecorder.Prefs
import com.liferecorder.SegmentClock
import com.liferecorder.Storage
import java.io.File

/**
 * 카메라로 찍은 사진·동영상을 MediaStore로 찾아 앱 폴더로 복사해 업로드 대기열에 넣는다.
 * **원본은 절대 지우지 않는다.** 한 번 가져온 것은 MediaStore ID로 기억해 두 번 올리지 않는다.
 *
 * 사진첩은 수십 GB가 될 수 있어 한 번에 다 복사하면 폰 저장공간이 찬다.
 * 그래서 실행 한 번에 [Config.CAMERA_BUDGET_BYTES]까지만 가져오고 나머지는 다음 실행으로 미룬다.
 * **최신 것부터** 가져오므로 과거분이 밀려 있어도 오늘 찍은 사진이 먼저 올라간다.
 */
object CameraImporter {
    private const val TAG = "CameraImporter"

    /** 촬영이 아직 안 끝났을 수 있으니(동영상) 이만큼 지난 것만 가져온다. */
    private const val SETTLE_MS = 60_000L

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED

    /** 이번에 새로 가져온 개수. 예산이 차면 남은 것은 다음 실행에서 가져온다. */
    fun importNew(ctx: Context): Int {
        if (!hasPermission(ctx)) return 0
        val imported = Prefs.importedCameraIds(ctx).toMutableSet()
        val seen = mutableSetOf<String>()
        val budget = Budget(Config.CAMERA_BUDGET_BYTES)

        var count = 0
        count += scan(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "img", imported, seen, budget)
        count += scan(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "vid", imported, seen, budget)

        // 사용자가 지운 사진의 ID는 더 기억할 필요가 없다. 다만 예산 때문에 훑다 만 경우에는
        // 못 본 것이 많으므로 지우지 않는다 (지웠다가 다음 실행에서 통째로 다시 가져오게 된다).
        val kept = if (budget.exhausted) imported else imported.filter { it in seen }.toSet()
        Prefs.setImportedCameraIds(ctx, kept)
        if (budget.exhausted) Log.i(TAG, "budget exhausted, ${count} imported this run")
        return count
    }

    private class Budget(private val limit: Long) {
        private var used = 0L
        var exhausted = false
            private set

        /** 이번 파일을 가져올 여유가 있는지. 첫 파일은 크기와 무관하게 통과시킨다. */
        fun allows(size: Long): Boolean {
            if (used > 0 && used + size > limit) {
                exhausted = true
                return false
            }
            return true
        }

        fun add(size: Long) {
            used += size
        }
    }

    private fun scan(
        ctx: Context,
        collection: Uri,
        kind: String,
        imported: MutableSet<String>,
        seen: MutableSet<String>,
        budget: Budget,
    ): Int {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
        )
        val selection = Config.CAMERA_PATHS.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" }
        val args = Config.CAMERA_PATHS.toTypedArray()
        // 최신 것부터. 예산이 차서 중간에 멈춰도 최근 사진은 이미 가져온 상태가 된다.
        val order = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        var count = 0
        val now = System.currentTimeMillis()

        ctx.contentResolver.query(collection, projection, selection, args, order)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            while (c.moveToNext()) {
                val rawId = c.getLong(idCol)
                val key = "$kind:$rawId"
                seen += key
                if (key in imported) continue

                val size = c.getLong(sizeCol)
                val modifiedMs = c.getLong(modCol) * 1000L
                if (size <= 0L || now - modifiedMs < SETTLE_MS) continue
                if (!budget.allows(size)) continue

                // DATE_TAKEN(촬영 시각)이 우선. 스캔으로 들어온 파일은 비어 있어 수정 시각으로 떨어진다.
                val takenMs = c.getLong(takenCol).takeIf { it > 0 } ?: modifiedMs
                val name = c.getString(nameCol) ?: "$kind.bin"
                val uri = ContentUris.withAppendedId(collection, rawId)
                val dest = File(
                    Storage.cameraDir(ctx),
                    "camera_${SegmentClock.stamp(takenMs)}_${sanitize(name)}${Storage.PART}"
                )
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
                    } ?: continue
                    if (dest.length() != size) {
                        Log.w(TAG, "size mismatch for $name (${dest.length()} != $size), will retry later")
                        dest.delete()
                        continue
                    }
                    val done = Storage.finishPart(dest)
                    // 업로드가 끝나면 이 값이 수집 기록에 남아, 재설치 후 복원의 근거가 된다.
                    Prefs.setFileSource(ctx, done.name, "camera:$key")
                    imported += key
                    budget.add(size)
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "copy failed: $name", e)
                    dest.delete()
                }
            }
        }
        return count
    }

    private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
