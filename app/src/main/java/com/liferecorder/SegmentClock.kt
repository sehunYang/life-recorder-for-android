package com.liferecorder

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 세그먼트 경계는 벽시계 정각(HH:00:00)에 맞춘다. 첫 세그먼트만 짧고 이후는 한 시간 단위다. */
object SegmentClock {
    fun nextBoundary(nowMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, 1)
        }
        var boundary = cal.timeInMillis
        if (boundary - nowMs < Config.MIN_SEGMENT_MS) boundary += 60L * 60L * 1000L
        return boundary
    }

    private val stampFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun stamp(ms: Long): String = synchronized(stampFormat) { stampFormat.format(Date(ms)) }
}
