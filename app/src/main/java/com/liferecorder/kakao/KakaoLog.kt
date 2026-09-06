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
 * 카카오톡 알림에서 나온 것을 하루 단위 JSONL로 그대로 쌓는다. 해석하거나 합치지 않는다.
 *
 *   kakao/rawkakao_yyyy-MM-dd.jsonl.part   ← 오늘치, 계속 이어 쓰는 중 (업로드 대상 아님)
 *   kakao/kakao_yyyy-MM-dd.jsonl           ← 날이 바뀌어 확정된 것, 업로드 대상
 *   kakao/kakao_dump_yyyy-MM-dd.jsonl      ← 진단 덤프를 켰을 때만. 알림 원본 통째로
 *
 * 한 줄에 레코드 하나. `kind`로 종류를 구분한다.
 *  - message  : 메시지 한 건
 *  - listener : 알림 접근이 붙거나 끊긴 시점. 이 사이가 데이터 공백 구간이다
 */
object KakaoLog {
    private const val TAG = "KakaoLog"
    private const val RAW_PREFIX = "rawkakao_"
    private const val DONE_PREFIX = "kakao_"
    private const val DUMP_PREFIX = "kakao_dump_"
    private const val EXT = ".jsonl"

    const val STYLE_MESSAGING = "messaging"
    const val STYLE_PLAIN = "plain"

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val lock = Any()

    /** 카카오톡이 같은 메시지를 알림마다 다시 실어 보내므로 최근 키를 기억해 걸러낸다. */
    private val recentKeys = LinkedHashSet<String>()

    private fun remember(key: String): Boolean {
        if (!recentKeys.add(key)) return false
        while (recentKeys.size > 2000) {
            val it = recentKeys.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
        return true
    }

    private fun rawFile(ctx: Context, dayMs: Long): File =
        File(Storage.kakaoDir(ctx), "$RAW_PREFIX${dayFormat.format(Date(dayMs))}$EXT${Storage.PART}")

    /**
     * 레코드 한 줄을 그날 파일에 덧붙인다.
     * @param dedupeKey 같은 키가 최근에 들어왔으면 건너뛴다. null이면 항상 쓴다.
     */
    fun write(ctx: Context, timeMs: Long, record: JSONObject, dedupeKey: String? = null) {
        synchronized(lock) {
            if (dedupeKey != null && !remember(dedupeKey)) return
            try {
                rawFile(ctx, timeMs).appendText(record.toString() + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "append failed", e)
            }
        }
    }

    /** 알림 접근이 붙거나 끊긴 시점. 서버가 데이터 공백 구간을 알 수 있게 남긴다. */
    fun writeListenerEvent(ctx: Context, event: String) {
        val now = System.currentTimeMillis()
        val o = JSONObject().put("kind", "listener").put("event", event).put("t", now)
        write(ctx, now, o)
    }

    /** 진단 덤프는 별도 파일로 쌓는다. 용량이 커서 평소 로그와 섞지 않는다. */
    fun writeDump(ctx: Context, record: JSONObject) {
        synchronized(lock) {
            try {
                val f = File(
                    Storage.kakaoDir(ctx),
                    "$DUMP_PREFIX${dayFormat.format(Date())}$EXT${Storage.PART}"
                )
                f.appendText(record.toString() + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "dump append failed", e)
            }
        }
    }

    /**
     * 날이 지난 로그를 업로드 대상으로 확정한다. 내용은 손대지 않고 이름만 바꾼다.
     * 진단 덤프 파일도 같이 확정한다.
     * @return 확정한 파일 수
     */
    fun finalizeCompletedDays(ctx: Context): Int = synchronized(lock) {
        val today = dayFormat.format(Date())
        var count = 0
        val files = Storage.kakaoDir(ctx).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith("$EXT${Storage.PART}") }
        for (f in files) {
            val isDump = f.name.startsWith(DUMP_PREFIX)
            val isRaw = f.name.startsWith(RAW_PREFIX)
            if (!isDump && !isRaw) continue
            val prefix = if (isDump) DUMP_PREFIX else RAW_PREFIX
            val day = f.name.removePrefix(prefix).removeSuffix("$EXT${Storage.PART}")
            if (day.length != 10 || day >= today) continue
            if (f.length() == 0L) { f.delete(); continue }

            val doneName = if (isDump) "$DUMP_PREFIX$day$EXT" else "$DONE_PREFIX$day$EXT"
            val done = File(f.parentFile, doneName)
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
