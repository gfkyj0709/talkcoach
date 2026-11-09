package com.talkcoach.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talkcoach.core.prompt.AnalysisMode
import com.talkcoach.data.Preview
import com.talkcoach.data.RangeSpec
import com.talkcoach.data.TalkRepository
import com.talkcoach.ui.Bar
import com.talkcoach.ui.Section
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 분석 요청: 모드, 구간, 추가 질문, 전송 미리보기.
 * Analysis request: mode, range, extra question, send preview.
 *
 * @param repo       repository / 저장소
 * @param contactId  contact id / 상대 id
 * @param onBack     back / 뒤로
 * @param onDone     called with session id / 완료 시 세션 id
 * @param onSettings open settings / 설정 열기
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalyzeScreen(repo: TalkRepository, contactId: Long, onBack: () -> Unit, onDone: (Long) -> Unit, onSettings: () -> Unit) {
    val settings by repo.settings.settings.collectAsState()                     // Settings / 설정
    val scope = rememberCoroutineScope()                                       // Scope / 스코프
    var mode by remember { mutableStateOf(AnalysisMode.DAILY) }                // Mode / 모드
    var byCount by remember { mutableStateOf(true) }                           // Count vs days / 개수·일수
    var count by remember { mutableFloatStateOf(150f) }                        // Last N / 최근 N개
    var days by remember { mutableFloatStateOf(7f) }                           // Last N days / 최근 N일
    var ask by remember { mutableStateOf("") }                                 // Extra question / 추가 질문
    var busy by remember { mutableStateOf(false) }                             // Running / 진행 중
    var error by remember { mutableStateOf<String?>(null) }                    // Error / 오류
    var preview by remember { mutableStateOf<Preview?>(null) }                 // Preview / 미리보기

    /** Current range / 현재 구간 */
    fun range(): RangeSpec = if (byCount) RangeSpec.RecentCount(count.roundToInt()) else RangeSpec.RecentDays(days.roundToInt())

    Scaffold(topBar = { Bar("대화 분석", onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Section("상황") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnalysisMode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                    }
                }
            }
            Section("분석 구간") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = byCount, onClick = { byCount = true }, label = { Text("최근 N개") })
                    FilterChip(selected = !byCount, onClick = { byCount = false }, label = { Text("최근 N일") })
                }
                if (byCount) {
                    Text("최근 메시지 ${count.roundToInt()}개")
                    Slider(count, { count = it }, valueRange = 30f..500f, steps = 46)
                } else {
                    Text("마지막 메시지 기준 최근 ${days.roundToInt()}일")
                    Slider(days, { days = it }, valueRange = 1f..60f, steps = 58)
                }
                Text("너무 길면 오래된 메시지부터 잘려요 (약 12,000자)", style = MaterialTheme.typography.labelSmall)
            }
            Section("추가로 궁금한 점 (선택)") {
                OutlinedTextField(
                    ask, { ask = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("예: 내가 너무 늦게 답장해서 서운했을까?") }
                )
            }
            Section("AI 설정") {
                Text("${settings.provider.label} · ${settings.activeModel}" + if (settings.activeKey.isBlank()) " · 키 없음" else "")
                if (settings.activeKey.isBlank()) TextButton(onClick = onSettings) { Text("API 키 입력하러 가기") }
            }
            error?.let { Text(it, color = Color(0xFFD04A4A), modifier = Modifier.padding(16.dp)) }

            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy, modifier = Modifier.weight(1f),
                    onClick = { scope.launch { error = null; preview = runCatching { repo.preview(contactId, mode, range(), ask) }.onFailure { error = it.message }.getOrNull() } }
                ) { Text("전송 내용 보기") }
                Button(
                    enabled = !busy && settings.activeKey.isNotBlank(), modifier = Modifier.weight(1f),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching { repo.analyze(contactId, mode, range(), ask) }
                                .onSuccess { onDone(it) }
                                .onFailure { error = it.message ?: "분석 실패" }
                            busy = false
                        }
                    }
                ) {
                    if (busy) { CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.height(1.dp)) }
                    Text(if (busy) "  분석 중…" else "분석 요청")
                }
            }
        }
    }

    // Preview dialog: exactly what leaves the device / 미리보기: 기기 밖으로 나가는 내용 그대로
    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("AI에 전송될 내용") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "메시지 ${p.messageCount}개 · 마스킹 " +
                            (if (p.maskCounts.isEmpty()) "없음" else p.maskCounts.entries.joinToString { "${it.key.label} ${it.value}건" }),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text("상대 이름은 '상대'로 바뀌어 전송돼요.", style = MaterialTheme.typography.labelSmall)
                    Text(p.request.userPrompt, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("닫기") } }
        )
    }
}
