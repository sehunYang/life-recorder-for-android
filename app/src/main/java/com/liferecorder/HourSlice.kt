package com.liferecorder

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 글자 자료(카카오톡 · 화면 글자 · 앱 사용 · 문자)를 **한 시간 조각**으로 올리기 위한 이름과 구간.
 *
 * 전에는 날이 바뀌어야 하루치 파일로 확정했다. 그래서 그날 카톡으로 잡힌 약속을 맥이 다음 날에야 알았다.
 * v0.8 부터 오늘치는 정각마다 지난 한 시간을 `<접두어>_yyyy-MM-dd_hHH.jsonl` 로 확정한다
 * (`kakao_2026-09-30_h13.jsonl` = 13:00~14:00). 지난 날을 소급할 때만 하루 파일(`_yyyy-MM-dd.jsonl`)을 쓴다.
 *
 * 조각 키는 글자 순서가 곧 시간 순서다 (`2026-09-30` < `2026-09-30_h00` < `2026-09-30_h13` < `2026-10-01`).
 *
 * 정각 직후 [GRACE_MS] 동안은 지난 시간을 아직 닫지 않는다. 그 시간 끝에 찍힌 알림·문자가
 * 조금 늦게 기록되는 것을 기다린다. 이 때문에 정각에 도는 업로드는 지난 시간을 못 올리므로,
 * 녹음 세그먼트가 닫힐 때 [TAIL_DELAY_MIN] 분 뒤 업로드를 한 번 더 건다 (`UploadScheduler.enqueueTail`).
 */
object HourSlice {
    const val GRACE_MS = 60_000L
    const val TAIL_DELAY_MIN = 3L

    private val hourFormat = SimpleDateFormat("yyyy-MM-dd'_h'HH", Locale.US)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 그 시각이 속한 한 시간 조각의 키. `2026-09-30_h13` */
    fun key(ms: Long): String = synchronized(hourFormat) { hourFormat.format(Date(ms)) }

    fun dayKey(ms: Long): String = synchronized(dayFormat) { dayFormat.format(Date(ms)) }

    /** 이 키보다 앞선 조각은 닫혔다 — 확정해 올려도 된다. */
    fun closedBefore(now: Long = System.currentTimeMillis()): String = key(now - GRACE_MS)

    /** 내보내기를 여기까지 해도 된다 (정각). 이 앞의 구간은 닫혔다. */
    fun exportableUntil(now: Long = System.currentTimeMillis()): Long = hourStart(now - GRACE_MS)

    fun hourStart(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun addDays(ms: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis

    private fun addHour(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        add(Calendar.HOUR_OF_DAY, 1)
    }.timeInMillis

    /** 내보낼 구간 하나. [key] 는 파일 이름에 붙는다 — 하루면 `2026-09-29`, 한 시간이면 `2026-09-30_h13`. */
    data class Window(val start: Long, val end: Long, val key: String)

    /**
     * [from] 부터 [until] 까지를 내보낼 구간으로 자른다. 하루가 통째로 닫혔고 자정에서 시작하면
     * 하루 하나(소급분), 아니면 한 시간씩(오늘). 앞에서 한 시간씩 내보내기 시작한 날은 끝까지 한 시간씩 간다.
     */
    fun windows(from: Long, until: Long): List<Window> {
        val out = ArrayList<Window>()
        var c = hourStart(from)
        while (c < until) {
            val nextDay = addDays(dayStart(c), 1)
            if (c == dayStart(c) && nextDay <= until) {
                out += Window(c, nextDay, dayKey(c))
                c = nextDay
            } else {
                val e = addHour(c)
                out += Window(c, e, key(c))
                c = e
            }
        }
        return out
    }
}
