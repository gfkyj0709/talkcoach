package com.talkcoach.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Brand purple / 브랜드 보라 */
val Purple = Color(0xFF7452C0)

/** Deep navy for headings / 제목용 딥네이비 */
val Navy = Color(0xFF303B4A)

/** Lavender tint for cards / 카드용 라벤더 */
val Lavender = Color(0xFFECEEF4)

/** Mood colors / 분위기 색상 */
val Warm = Color(0xFFE8833A)
val Cool = Color(0xFF4A7BD0)
val Tense = Color(0xFFD04A4A)

/**
 * 앱 테마 (라이트 전용 MVP).
 * App theme (light-only MVP).
 *
 * @param content screen content / 화면 내용
 */
@Composable
fun TalkCoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Purple,                    // Buttons, accents / 버튼·강조
            onPrimary = Color.White,             // Text on purple / 보라 위 글자
            primaryContainer = Color(0xFFE6DDFB),// Chips selected / 선택 칩
            onPrimaryContainer = Navy,           // Text on container / 컨테이너 글자
            secondaryContainer = Lavender,       // Secondary surfaces / 보조 면
            surfaceVariant = Lavender,           // Cards / 카드
            onSurface = Navy,                    // Body text / 본문
            background = Color.White,            // Background / 배경
            surface = Color.White                // Surface / 표면
        ),
        content = content
    )
}
