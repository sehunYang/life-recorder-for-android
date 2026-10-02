package com.liferecorder

import com.liferecorder.service.AlarmGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.8.3 — 몇 초 앞에 잡힌 "알람"(10/2 08시대, 2초 예고)은 녹음을 끊지 않는다. */
class AlarmGuardNoticeTest {
    private val t = 1_790_896_382_000L

    @Test fun twoSecondsAheadIsNotAnAlarm() = assertTrue(AlarmGuard.isShortNotice(t + 2_000, t))
    @Test fun snoozeFiveMinutesAheadIsAnAlarm() = assertFalse(AlarmGuard.isShortNotice(t + 5 * 60_000, t))
    @Test fun oneMinuteAheadIsAnAlarm() = assertFalse(AlarmGuard.isShortNotice(t + 60_000, t))
    @Test fun knownSinceServiceStartIsTrusted() = assertFalse(AlarmGuard.isShortNotice(t + 2_000, 0L))
    @Test fun boundaryIsThirtySeconds() {
        assertTrue(AlarmGuard.isShortNotice(t + 29_999, t))
        assertFalse(AlarmGuard.isShortNotice(t + 30_000, t))
    }
}
