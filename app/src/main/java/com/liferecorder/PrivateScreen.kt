package com.liferecorder

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 지금 화면에 기록하지 않을 앱이 떠 있는지. 접근성 서비스(`ScreenTextService`)가 판정해 쓰고,
 * 기록 서비스가 읽어 화면 캡처 입력을 끊는다. null 이면 기록해도 되는 화면이다.
 *
 * 접근성 서비스가 꺼져 있으면 판정할 수 없어 늘 null 이다.
 *
 * 바뀔 때마다 시각과 이유를 내부 저장소에 적는다. 앱 사용 기록은 하루 지나 시스템 기록에서 뽑으므로
 * (`AppUsageExporter`) 그때 Chrome 시크릿 구간을 알아야 지울 수 있다. 업로드하지 않는다.
 */
object PrivateScreen {
    private const val TAG = "PrivateScreen"
    private const val FILE = "private_intervals.txt"
    private const val KEEP_MS = 14L * 24 * 60 * 60 * 1000

    /** Chrome 시크릿 탭 이유. 앱 사용 기록이 이 구간의 Chrome 사건을 지운다. */
    const val CHROME_INCOGNITO = "Chrome 시크릿 탭"

    private val _reason = MutableStateFlow<String?>(null)
    val reason: StateFlow<String?> = _reason

    fun set(ctx: Context, reason: String?) {
        if (_reason.value == reason) return
        _reason.value = reason
        try {
            File(ctx.filesDir, FILE).appendText("${System.currentTimeMillis()}\t${reason.orEmpty()}\n")
        } catch (e: Exception) {
            Log.w(TAG, "interval write failed", e)
        }
    }

    /**
     * [reason] 으로 비공개였던 구간들 [시작, 끝]. 끝을 적지 못하고 죽었으면 다음 기록(또는 지금)까지로
     * 본다 — 덜 지우는 것보다 더 지우는 쪽이 낫다. 2주 지난 줄은 이때 정리한다.
     */
    fun intervals(ctx: Context, reason: String): List<LongRange> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        val rows = f.readLines().mapNotNull { line ->
            val t = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
            t to line.substringAfter('\t', "")
        }
        val now = System.currentTimeMillis()
        val out = ArrayList<LongRange>()
        var start: Long? = null
        for ((t, r) in rows) {
            start?.let { out += it..t }
            start = if (r == reason) t else null
        }
        start?.let { out += it..now }
        val kept = rows.filter { it.first >= now - KEEP_MS }
        if (kept.size < rows.size) {
            f.writeText(kept.joinToString("") { "${it.first}\t${it.second}\n" })
        }
        return out
    }
}
