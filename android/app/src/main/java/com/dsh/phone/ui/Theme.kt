package com.dsh.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF10233F),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF9FD3B8),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF161A20),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF1F242C),
    onSurfaceVariant = Color(0xFFB4BAC4),
    error = Color(0xFFFF8A80),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1B6CF0),
    secondary = Color(0xFF2E7D5B),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
)

/**
 * 「未读」统一用的绿色。
 *
 * 之前借用的是 colorScheme.error（红）—— 未读是"有新东西看"，
 * 不是出错，红色会让人以为哪里坏了。
 */
val UnreadGreen = Color(0xFF35C77A)

@Composable
fun DshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
