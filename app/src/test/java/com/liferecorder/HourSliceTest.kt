package com.liferecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class HourSliceTest {
    init {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    }

    private fun at(s: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s)!!.time

    @Test fun keyOrderIsTimeOrder() {
        val keys = listOf("2026-09-30", "2026-09-30_h00", "2026-09-30_h09", "2026-09-30_h13", "2026-10-01", "2026-10-01_h00")
        assertEquals(keys, keys.shuffled().sorted())
        assertEquals("2026-09-30_h13", HourSlice.key(at("2026-09-30 13:59:59")))
    }

    @Test fun lastHourClosesOnlyAfterGrace() {
        // 정각 30초 뒤에는 13시가 아직 열려 있다
        assertEquals("2026-09-30_h13", HourSlice.closedBefore(at("2026-09-30 14:00:30")))
        assertEquals(at("2026-09-30 13:00:00"), HourSlice.exportableUntil(at("2026-09-30 14:00:30")))
        // 1분이 지나면 13시가 닫힌다
        assertTrue("2026-09-30_h13" < HourSlice.closedBefore(at("2026-09-30 14:01:30")))
        assertEquals(at("2026-09-30 14:00:00"), HourSlice.exportableUntil(at("2026-09-30 14:01:30")))
    }

    @Test fun backfilledDaysAreWholeAndTodayIsHourly() {
        val w = HourSlice.windows(at("2026-09-28 00:00:00"), at("2026-09-30 03:00:00"))
        assertEquals(listOf("2026-09-28", "2026-09-29", "2026-09-30_h00", "2026-09-30_h01", "2026-09-30_h02"), w.map { it.key })
        // 구간이 빈틈없이 이어진다
        w.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        assertEquals(at("2026-09-30 03:00:00"), w.last().end)
    }

    @Test fun dayStartedHourlyStaysHourlyAcrossMidnight() {
        val w = HourSlice.windows(at("2026-09-30 22:00:00"), at("2026-10-02 00:00:00"))
        assertEquals(listOf("2026-09-30_h22", "2026-09-30_h23", "2026-10-01"), w.map { it.key })
    }

    @Test fun nothingToExportYet() {
        assertTrue(HourSlice.windows(at("2026-09-30 14:00:00"), at("2026-09-30 14:00:00")).isEmpty())
    }
}
