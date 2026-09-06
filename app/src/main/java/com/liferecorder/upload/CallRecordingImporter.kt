package com.liferecorder.upload

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import com.liferecorder.Config
import com.liferecorder.Prefs
import com.liferecorder.SegmentClock
import com.liferecorder.Storage
import java.io.File

/**
 * 삼성 통화 녹음(공용 저장소 `Recordings/Call/…`, 구형은 `Call/…`)을 MediaStore로 찾아
 * 앱 폴더로 복사해 업로드 대기열에 넣는다. 원본은 절대 지우지 않는다.
 * 한 번 가져온 파일은 MediaStore ID로 기억해 두 번 올리지 않는다.
 */
object CallRecordingImporter {
    private const val TAG = "CallImporter"
    /** 통화가 아직 진행 중이라 파일이 커지고 있을 수 있으니 이만큼 지난 파일만 가져온다. */
    private const val SETTLE_MS = 60_000L

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 새로 가져온 파일 수를 돌려준다. */
    fun importNew(ctx: Context): Int {
        if (!hasPermission(ctx)) return 0
        val resolver = ctx.contentResolver
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )
        val selection = Config.CALL_RECORDING_PATHS.joinToString(" OR ") { "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" }
        val args = Config.CALL_RECORDING_PATHS.toTypedArray()
        val imported = Prefs.importedCallIds(ctx).toMutableSet()
        val seen = mutableSetOf<String>()
        var count = 0
        val now = System.currentTimeMillis()

        resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol).toString()
                seen += id
                if (id in imported) continue
                val size = c.getLong(sizeCol)
                val modifiedMs = c.getLong(dateCol) * 1000L
                if (size <= 0L || now - modifiedMs < SETTLE_MS) continue
                val name = c.getString(nameCol) ?: "call.m4a"
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol))
                val dest = File(Storage.callDir(ctx), "call_${SegmentClock.stamp(modifiedMs)}_${sanitize(name)}${Storage.PART}")
                try {
                    resolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
                    } ?: continue
                    if (dest.length() != size) {
                        Log.w(TAG, "size mismatch while copying $name (${dest.length()} != $size), will retry later")
                        dest.delete()
                        continue
                    }
                    val done = Storage.finishPart(dest)
                    // 업로드가 끝나면 이 값이 수집 기록에 남아, 재설치 후 복원의 근거가 된다.
                    Prefs.setFileSource(ctx, done.name, "call:$id")
                    imported += id
                    count++
                    Log.i(TAG, "imported $name")
                } catch (e: Exception) {
                    Log.w(TAG, "copy failed: $name", e)
                    dest.delete()
                }
            }
        }
        // 사용자가 지운 녹음의 ID는 더 기억할 필요가 없다.
        Prefs.setImportedCallIds(ctx, imported.filter { it in seen }.toSet())
        return count
    }

    private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
