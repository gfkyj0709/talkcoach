package com.talkcoach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.talkcoach.ui.theme.Purple
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 공통 상단 바.
 * Shared top app bar.
 *
 * @param title   title / 제목
 * @param onBack  back action or null for none / 뒤로가기 (없으면 null)
 * @param actions trailing actions / 우측 액션
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Purple, titleContentColor = Color.White,
            navigationIconContentColor = Color.White, actionIconContentColor = Color.White
        )
    )
}

/**
 * 제목이 있는 카드 섹션.
 * Card section with a title.
 *
 * @param title   section title / 섹션 제목
 * @param content body / 본문
 */
@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Purple)
            content()
        }
    }
}

/**
 * 확인 다이얼로그.
 * Confirmation dialog.
 *
 * @param title     title / 제목
 * @param text      body / 본문
 * @param confirm   confirm label / 확인 버튼명
 * @param onConfirm confirm action / 확인 동작
 * @param onDismiss dismiss action / 닫기 동작
 */
@Composable
fun Confirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = Color(0xFFD04A4A)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

/** Date-time formatter / 날짜 포맷 */
private val FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

/**
 * epoch millis → "yyyy.MM.dd HH:mm".
 *
 * @param millis epoch millis / 밀리초
 * @return formatted / 포맷 문자열
 */
fun fmt(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(FMT)
