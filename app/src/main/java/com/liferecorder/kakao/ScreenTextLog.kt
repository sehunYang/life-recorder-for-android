package com.liferecorder.kakao

import android.content.Context
import android.util.Log
import com.liferecorder.Storage
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 접근성 서비스가 화면에서 읽은 글자를 하루 단위 JSONL로 그대로 쌓는다.
 *
 *   screentext/rawscreentext_yyyy-MM-dd.jsonl.part   ← 오늘치, 계속 이어 쓰는 중 (업로드 대상 아님)
 *   screentext/screentext_yyyy-MM-dd.jsonl           ← 날이 바뀌어 확정된 것, 업로드 대상
 *
 * 한 줄에 레코드 하나. `kind`로 종류를 구분한다.
 *  - screen  : 화면을 한 번 읽은 결과. 새로 나타난 글자 노드만 담는다
 *  - service : 접근성 서비스가 붙거나 끊긴 시점. 이 사이가 데이터 공백 구간이다
 */
object ScreenTextLog {
    private const val TAG = "ScreenTextLog"
    private const val RAW_PREFIX = "rawscreentext_"
    private const val DONE_PREFIX = "screentext_"
    private const val EXT = ".jsonl"

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val lock = Any()

    private fun rawFile(ctx: Context, dayMs: Long): File =
        File(Storage.screenTextDir(ctx), "$RAW_PREFIX${dayFormat.format(Date(dayMs))}$EXT${Storage.PART}")

    fun write(ctx: Context, timeMs: Long, record: JSONObject) {
        synchronized(lock) {
            try {
                rawFile(ctx, timeMs).appendText(record.toString() + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "append failed", e)
            }
        }
    }

    fun writeServiceEvent(ctx: Context, event: String) {
        val now = System.currentTimeMillis()
        write(ctx, now, JSONObject().put("kind", "service").put("event", event).put("t", now))
    }

    /** 날이 지난 로그를 업로드 대상으로 확정한다. 내용은 손대지 않고 이름만 바꾼다. */
    fun finalizeCompletedDays(ctx: Context): Int = synchronized(lock) {
        val today = dayFormat.format(Date())
        var count = 0
        val files = Storage.screenTextDir(ctx).listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(RAW_PREFIX) && it.name.endsWith("$EXT${Storage.PART}") }
        for (f in files) {
            val day = f.name.removePrefix(RAW_PREFIX).removeSuffix("$EXT${Storage.PART}")
            if (day.length != 10 || day >= today) continue
            if (f.length() == 0L) { f.delete(); continue }

            val done = File(f.parentFile, "$DONE_PREFIX$day$EXT")
            if (done.exists()) {
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
