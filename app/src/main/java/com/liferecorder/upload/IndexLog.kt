package com.liferecorder.upload

import android.content.Context
import android.util.Log
import com.liferecorder.Storage
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drive에 올린 파일을 하루 단위 JSONL로 남기는 수집 기록.
 *
 *   index/rawindex_yyyy-MM-dd.jsonl.part   ← 오늘치, 계속 이어 쓰는 중 (업로드 대상 아님)
 *   index/index_yyyy-MM-dd.jsonl           ← 날이 바뀌어 확정된 것, 업로드 대상
 *
 * 목적은 **무엇이 언제 수집됐는지를 파일이 지워진 뒤에도 남기는 것**이다.
 * 보관 기간이 지나 Drive에서 원본을 지워도 이 기록은 남으므로,
 * 나중에 데이터를 처리하는 쪽이 "그 시기에 무엇이 있었는지"를 알 수 있다.
 *
 * 각 줄에는 그 파일이 어디서 왔는지(`src`)도 함께 남긴다.
 * 앱을 지웠다 다시 깔면 [IndexRestore]가 이 값을 읽어 "이미 올린 것" 목록을 되살린다.
 * 그게 없으면 재설치할 때마다 소급분(사진 11GB, 통화 4.4GB)이 통째로 다시 올라간다.
 */
object IndexLog {
    private const val TAG = "IndexLog"
    private const val RAW_PREFIX = "rawindex_"
    private const val DONE_PREFIX = "index_"
    private const val EXT = ".jsonl"

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val lock = Any()

    /**
     * 업로드 하나가 끝날 때마다 한 줄 남긴다. 실패해도 업로드는 계속되어야 하므로 조용히 넘어간다.
     * @param src 원본 식별자. `call:<MediaStore ID>` / `camera:<img|vid>:<MediaStore ID>` 꼴이고,
     *            앱이 직접 만든 파일(녹음·화면·문자·카카오톡)은 null이다.
     */
    fun record(ctx: Context, name: String, folder: String, bytes: Long, driveId: String, md5: String?, src: String?) {
        val now = System.currentTimeMillis()
        val record = JSONObject()
            .put("t", now)
            .put("name", name)
            .put("folder", folder)
            .put("bytes", bytes)
            .put("driveId", driveId)
            .put("md5", md5 ?: JSONObject.NULL)
            .put("src", src ?: JSONObject.NULL)
        synchronized(lock) {
            try {
                val f = File(Storage.indexDir(ctx), "$RAW_PREFIX${dayFormat.format(Date(now))}$EXT${Storage.PART}")
                f.appendText(record.toString() + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "append failed", e)
            }
        }
    }

    /**
     * 날이 지난 기록을 업로드 대상으로 확정한다. 내용은 손대지 않고 이름만 바꾼다.
     * @return 확정한 파일 수
     */
    fun finalizeCompletedDays(ctx: Context): Int = synchronized(lock) {
        val today = dayFormat.format(Date())
        var count = 0
        val files = Storage.indexDir(ctx).listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(RAW_PREFIX) && it.name.endsWith("$EXT${Storage.PART}") }
        for (f in files) {
            val day = f.name.removePrefix(RAW_PREFIX).removeSuffix("$EXT${Storage.PART}")
            if (day.length != 10 || day >= today) continue
            if (f.length() == 0L) { f.delete(); continue }

            val done = File(f.parentFile, "$DONE_PREFIX$day$EXT")
            if (done.exists()) {
                // 이미 확정된 날에 뒤늦게 더 붙은 경우. 뒤에 이어 붙인다.
                try {
                    done.appendBytes(f.readBytes())
                    f.delete()
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "merge into existing failed for $day", e)
                }
                continue
            }
            if (f.renameTo(done)) count++ else Log.w(TAG, "rename failed for ${f.name}")
        }
        return count
    }
}
