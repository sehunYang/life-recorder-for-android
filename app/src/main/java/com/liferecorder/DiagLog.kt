package com.liferecorder

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 녹음이 왜 끊기고 다시 열렸는지를 남기는 진단 기록. 시각과 이유만 담는다 — 소리·화면·대화 내용은 없다.
 *
 *   index/rawdiag_yyyy-MM-dd_hHH.jsonl.part   ← 지금 시간치, 이어 쓰는 중 (업로드 대상 아님)
 *   index/diag_yyyy-MM-dd_hHH.jsonl           ← 그 시간이 끝나 확정된 것, Drive `index/` 로 업로드
 *
 * 폰의 시스템 기록(logcat)은 5MB 상한이라 몇 시간이면 밀려난다 (Flip 5, 2026-10-01). 아침에 생긴 일을
 * 저녁에 볼 수 없어서 앱이 따로 남긴다. 한 줄에 사건 하나:
 *   {"t":…, "kind":"segment_open"|"segment_close"|"hold"|"hold_end"|"error"|"service"|"alarm", …}
 *
 * 맥 쪽 배치는 이 파일을 읽지 않는다 (사람이 원인을 볼 때만 연다).
 */
object DiagLog {
    private const val TAG = "DiagLog"
    private const val RAW_PREFIX = "rawdiag_"
    private const val DONE_PREFIX = "diag_"
    private const val EXT = ".jsonl"
    private val lock = Any()

    private fun rawFile(ctx: Context, timeMs: Long): File =
        File(Storage.indexDir(ctx), "$RAW_PREFIX${HourSlice.key(timeMs)}$EXT${Storage.PART}")

    /** 한 줄 남긴다. 실패해도 조용히 넘어간다 — 진단 기록 때문에 녹음이 멈추면 안 된다. */
    fun write(ctx: Context, kind: String, vararg fields: Pair<String, Any?>) {
        val now = System.currentTimeMillis()
        val o = JSONObject().put("t", now).put("kind", kind)
        for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
        synchronized(lock) {
            try {
                rawFile(ctx, now).appendText(o.toString() + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "append failed", e)
            }
        }
    }

    /** 끝난 시간의 기록을 업로드 대상으로 확정한다 (`KakaoLog.finalizeCompleted` 와 같은 규칙). */
    fun finalizeCompleted(ctx: Context): Int = synchronized(lock) {
        val closedBefore = HourSlice.closedBefore()
        var count = 0
        val files = Storage.indexDir(ctx).listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(RAW_PREFIX) && it.name.endsWith("$EXT${Storage.PART}") }
        for (f in files) {
            val key = f.name.removePrefix(RAW_PREFIX).removeSuffix("$EXT${Storage.PART}")
            if (key.length != 14 || key >= closedBefore) continue
            if (f.length() == 0L) { f.delete(); continue }
            val done = File(f.parentFile, "$DONE_PREFIX$key$EXT")
            if (done.exists()) {
                try {
                    done.appendBytes(f.readBytes()); f.delete(); count++
                } catch (e: Exception) {
                    Log.w(TAG, "merge into existing failed for $key", e)
                }
                continue
            }
            if (f.renameTo(done)) count++ else Log.w(TAG, "rename failed for ${f.name}")
        }
        return count
    }
}
