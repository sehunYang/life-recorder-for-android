package com.liferecorder.service

import android.app.AlarmManager
import android.content.Context
import android.util.Log

/**
 * 알람이 울리는 동안 마이크를 놓아 준다.
 *
 * 마이크 녹음이 켜져 있으면 알람이 진동·화면은 되는데 **소리만 안 난다** (Flip 5 · One UI,
 * 2026-09-30 사용자 시험: 기록 켬 → 무음, 기록 끔 → 울림). 앱은 오디오 포커스·모드를 건드리지
 * 않으므로 시스템이 녹음 중인 상태에 반응하는 것이다. 그래서 알람 앞뒤로 녹음만 잠시 멈춘다.
 *
 *  - 다음 알람은 [AlarmManager.getNextAlarmClock] — 시계 앱이 켜 둔 알람만, 가장 가까운 하나.
 *    알람이 바뀌면(울림 · 다시 알림 · 편집 · 끔) 시스템이 ACTION_NEXT_ALARM_CLOCK_CHANGED 를 보낸다.
 *  - 멈춤: 알람 [LEAD_MS] 전부터. 판정은 녹음 스레드가 조각마다(0.1초) 벽시계로 한다 —
 *    녹음 중에는 기기가 깊이 잠들지 않으므로 권한(SCHEDULE_EXACT_ALARM) 없이 제때 멈춘다.
 *  - 다시 켬: 알람 시각이 지난 뒤 **화면이 꺼지거나**(알람을 끄고 내려놓음 · 다시 알림)
 *    **잠금이 풀리면**(일어나서 폰을 엶). 둘 다 없으면 [MAX_HOLD_MS] 뒤.
 *  - 다시 알림: 화면이 꺼지며 녹음이 돌아오고, 새 알람 시각이 잡히면 그 앞에서 또 멈춘다.
 *
 * 타이머 · 캘린더 알림 소리는 [AlarmManager.getNextAlarmClock] 에 없어 여기서 막지 못한다.
 *
 * 녹음 스레드([shouldHold])와 메인 스레드(나머지)가 함께 쓰므로 모든 상태는 이 객체의 락 안에서만 바꾼다.
 */
class AlarmGuard(private val ctx: Context) {

    /** 시스템이 알려 준 다음 알람 시각(벽시계 ms). 없으면 null. */
    private var upcoming: Long? = null
    /** 지금 멈춰 주고 있는 알람의 시각. 멈춘 동안만 null 이 아니다. */
    private var active: Long? = null
    /** [active] 알람이 끝났다는 신호(화면 꺼짐 · 잠금 해제)를 받았다. */
    private var released = false

    /** 다음 알람을 다시 읽는다. 서비스 시작 때와 ACTION_NEXT_ALARM_CLOCK_CHANGED 때 부른다. */
    fun refresh(now: Long = System.currentTimeMillis()) = synchronized(this) {
        val next = try {
            ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
        } catch (e: Exception) {
            Log.w(TAG, "nextAlarmClock failed", e); null
        }
        upcoming = next
        // 울리기 전에 알람이 꺼졌거나 옮겨졌다 — 멈춰 둘 이유가 없어졌다.
        val a = active
        if (a != null && now < a && next != a) {
            Log.i(TAG, "alarm at ${stamp(a)} cancelled before ringing, releasing")
            active = null
            released = false
        }
        Log.i(TAG, "next alarm: ${next?.let(::stamp) ?: "없음"}")
    }

    /**
     * 화면 꺼짐 · 잠금 해제. 알람 시각이 지난 뒤라면 그 알람은 끝난 것으로 본다.
     * 녹음을 다시 열어야 하면 true — 부른 쪽이 기기를 잠깐 깨워 녹음 스레드가 돌게 한다.
     */
    fun onUserSignal(now: Long = System.currentTimeMillis()): Boolean = synchronized(this) {
        val a = active ?: return false
        if (now < a || released) return false
        released = true
        Log.i(TAG, "alarm at ${stamp(a)} handled, resuming audio")
        true
    }

    /** 녹음 스레드가 조각마다 묻는다. true 면 마이크를 놓고 기다린다. */
    fun shouldHold(now: Long = System.currentTimeMillis()): Boolean = synchronized(this) {
        val a = active
        if (a != null) {
            if (!released && now < a + MAX_HOLD_MS) return true
            active = null
            released = false
            // 방금 끝난 알람이 아직 upcoming 에 남아 있으면 다시 잡지 않는다.
            if (upcoming == a) upcoming = null
            return false
        }
        val next = upcoming ?: return false
        if (now >= next - LEAD_MS && now < next + MAX_HOLD_MS) {
            active = next
            released = false
            Log.i(TAG, "holding audio for alarm at ${stamp(next)}")
            return true
        }
        return false
    }

    /** UI 에 보일 이유. 멈춰 있지 않으면 null. */
    fun reason(): String? = synchronized(this) {
        active?.let { "알람 ${clock(it)} — 알람이 끝나면 다시 녹음" }
    }

    companion object {
        private const val TAG = "AlarmGuard"
        /** 알람 이만큼 전에 멈춘다. 녹음 스레드가 멈추는 데 한 조각(0.1초)이면 되므로 넉넉하다. */
        const val LEAD_MS = 60_000L
        /** 끝났다는 신호가 없어도 이만큼 지나면 다시 녹음한다. */
        const val MAX_HOLD_MS = 30 * 60_000L

        private fun stamp(ms: Long) = com.liferecorder.SegmentClock.stamp(ms)

        private fun clock(ms: Long): String =
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))
    }
}
