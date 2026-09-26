package com.liferecorder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 지금 화면에 기록하지 않을 앱이 떠 있는지. 접근성 서비스(`ScreenTextService`)가 판정해 쓰고,
 * 기록 서비스가 읽어 화면 캡처 입력을 끊는다. null 이면 기록해도 되는 화면이다.
 *
 * 접근성 서비스가 꺼져 있으면 판정할 수 없어 늘 null 이다.
 */
object PrivateScreen {
    private val _reason = MutableStateFlow<String?>(null)
    val reason: StateFlow<String?> = _reason

    fun set(reason: String?) {
        _reason.value = reason
    }
}
