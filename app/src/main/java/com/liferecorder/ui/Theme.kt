package com.liferecorder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material You. minSdk 34라 다이나믹 컬러는 항상 있다. 배경화면 색을 따라가므로
 * 앱 고유 색은 두지 않고, "기록 중"처럼 의미가 고정된 색만 [Tone]에 둔다.
 */
@Composable
fun LifeRecorderTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 배경화면 색과 무관하게 뜻이 고정돼야 하는 색. */
object Tone {
    val Recording = Color(0xFFE53935)
    val Paused = Color(0xFFFFB300)
    val Ok = Color(0xFF43A047)
}
