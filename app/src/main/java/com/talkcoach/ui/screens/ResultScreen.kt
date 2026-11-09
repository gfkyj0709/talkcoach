package com.talkcoach.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.talkcoach.core.parser.ChatMessage
import com.talkcoach.core.parser.SpeakerRole
import com.talkcoach.core.prompt.AnalysisMode
import com.talkcoach.data.TalkRepository
import com.talkcoach.data.llm.AnalysisResult
import com.talkcoach.data.local.AnalysisSessionEntity
import com.talkcoach.data.local.ReplySuggestionEntity
import com.talkcoach.ui.Bar
import com.talkcoach.ui.Section
import com.talkcoach.ui.fmt
import com.talkcoach.ui.theme.Cool
import com.talkcoach.ui.theme.Purple
import com.talkcoach.ui.theme.Tense
import com.talkcoach.ui.theme.Warm
import kotlinx.coroutines.launch

/**
 * 결과 화면에 필요한 데이터 묶음.
 * Data bundle for the result screen.
 *
 * @property session     session / 세션
 * @property result      parsed result / 파싱 결과
 * @property suggestions stored replies / 저장된 답장
 * @property messages    analyzed messages (for evidence) / 분석 메시지 (근거용)
 */
private data class ResultData(
    val session: AnalysisSessionEntity, val result: AnalysisResult?,
    val suggestions: List<ReplySuggestionEntity>, val messages: List<ChatMessage>
)

/** Tone labels / 톤 표시명 */
private val TONE = mapOf("EMPATHETIC" to "부드러운 공감형", "LIGHT_HUMOR" to "가벼운 유머형", "FIRM_POLITE" to "단호하고 정중한 톤")

/** Temperature labels / 분위기 표시명 */
private val TEMP = mapOf("WARM" to "따뜻함", "NEUTRAL" to "무난함", "COOL" to "미지근함", "TENSE" to "긴장됨")

/**
 * 분석 결과: 분위기, 숨은 의도(근거), 주의점, 추천 답장 3종, 모드별 정보.
 * Result: mood, hidden intent with evidence, cautions, 3 replies, mode-specific info.
 *
 * @param repo      repository / 저장소
 * @param sessionId session id / 세션 id
 * @param onBack    back / 뒤로
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultScreen(repo: TalkRepository, sessionId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current                                                       // Context / 컨텍스트
    val clipboard = LocalClipboardManager.current                                        // Clipboard / 클립보드
    val scope = rememberCoroutineScope()                                                 // Scope / 스코프
    var refresh by remember { mutableStateOf(0) }                                        // Reload trigger / 재조회 트리거
    var evidence by remember { mutableStateOf<ChatMessage?>(null) }                      // Evidence popup / 근거 팝업

    // Load session, parse, fetch range messages / 세션 조회·파싱·구간 메시지
    val data by produceState<ResultData?>(null, sessionId, refresh) {
        val dao = repo.db.analysisDao()
        val s = dao.session(sessionId) ?: return@produceState
        value = ResultData(
            s, s.resultJson?.let { runCatching { AnalysisResult.parse(it) }.getOrNull() },
            dao.suggestions(sessionId), repo.messagesBetween(s.contactId, s.rangeStartAt, s.rangeEndAt)
        )
    }

    Scaffold(topBar = { Bar("분석 결과", onBack = onBack) }) { pad ->
        val d = data
        if (d == null) { CircularProgressIndicator(Modifier.padding(pad).padding(24.dp)); return@Scaffold }
        val r = d.result
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                val mode = runCatching { AnalysisMode.valueOf(d.session.mode).label }.getOrDefault(d.session.mode)
                Text(
                    "$mode · ${fmt(d.session.createdAt)} · 메시지 ${d.session.messageCount}개 · 마스킹 ${d.session.maskedCount}건",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(16.dp, 8.dp)
                )
            }
            if (r == null) { item { Text("결과를 해석하지 못했어요.", Modifier.padding(16.dp)) }; return@LazyColumn }

            // Safety first / 안전 경고 우선
            if (r.safetyFlag) item {
                Card(Modifier.fillMaxWidth().padding(16.dp, 6.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFBEBEB))) {
                    Text(
                        "대화에서 위협·스토킹·폭력과 관련된 신호가 보여요. 답장보다 본인의 안전을 먼저 챙기고, 필요하면 112 등 도움을 요청하세요.",
                        Modifier.padding(14.dp), color = Tense
                    )
                }
            }
            item {
                Section("① 대화 분위기") {
                    val color = when (r.mood.temperature) { "WARM" -> Warm; "COOL" -> Cool; "TENSE" -> Tense; else -> Purple }
                    Text("${TEMP[r.mood.temperature] ?: r.mood.temperature} · ${r.mood.score}점", color = color, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { r.mood.score / 100f }, color = color, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    Text(r.mood.summary)
                }
            }
            item {
                Section("상대의 숨은 의도·심리") {
                    Text(r.hiddenIntent.summary)
                    if (r.hiddenIntent.evidence.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        r.hiddenIntent.evidence.forEach { n ->
                            AssistChip(onClick = { evidence = d.messages.getOrNull(n - 1) }, label = { Text("근거 #$n") })
                        }
                    }
                    Text("신뢰도 ${(r.confidence * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (r.cautions.isNotEmpty()) item {
                Section("② 주의할 점") {
                    r.cautions.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text("• ${c.point}", fontWeight = FontWeight.SemiBold)
                        Text(c.reason, style = MaterialTheme.typography.bodySmall)
                        if (c.avoidExample.isNotBlank()) Text("피할 말: \"${c.avoidExample}\"", style = MaterialTheme.typography.bodySmall, color = Tense)
                    }
                }
            }
            item {
                Section("③ 추천 답장") {
                    d.suggestions.forEach { s ->
                        val extra = r.replies.firstOrNull { it.text == s.text }                      // Intent/risk / 의도·주의
                        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(TONE[s.tone] ?: s.tone, color = Purple, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                Text(s.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
                                extra?.let {
                                    Text("의도: ${it.intent}", style = MaterialTheme.typography.bodySmall)
                                    Text("주의: ${it.risk}", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = {
                                        clipboard.setText(AnnotatedString(s.text))                          // Copy / 복사
                                        scope.launch { repo.db.analysisDao().markSuggestion(s.id, true, s.feedback) }
                                        Toast.makeText(ctx, "복사했어요", Toast.LENGTH_SHORT).show()
                                    }) { Text(if (s.isCopied) "복사됨 ✓" else "복사") }
                                    TextButton(onClick = {
                                        scope.launch { repo.db.analysisDao().markSuggestion(s.id, s.isCopied, "GOOD"); refresh++ }
                                    }) { Text(if (s.feedback == "GOOD") "도움됨 ✓" else "도움됨") }
                                    TextButton(onClick = {
                                        scope.launch { repo.db.analysisDao().markSuggestion(s.id, s.isCopied, "BAD"); refresh++ }
                                    }) { Text(if (s.feedback == "BAD") "별로 ✓" else "별로") }
                                }
                            }
                        }
                    }
                }
            }
            if (r.modeSpecific.isNotEmpty()) item {
                Section("상황별 상세") {
                    r.modeSpecific.forEach { (k, v) ->
                        Text(k, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        Text(v, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Section("참고") {
                    if (r.dataLimitations.isNotBlank()) Text("분석 한계: ${r.dataLimitations}", style = MaterialTheme.typography.bodySmall)
                    if (r.memoryAdds.isNotEmpty()) Text("누적 맥락 ${r.memoryAdds.size}건 추가됨 (프로필에서 확인·수정)", style = MaterialTheme.typography.bodySmall)
                    Text("AI 분석은 참고용이에요. 상대의 마음을 단정하지 말고 직접 대화로 확인하세요.", style = MaterialTheme.typography.labelSmall)
                    Text("모델: ${d.session.modelName} · 프롬프트 ${d.session.promptVersion}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    // Evidence message popup / 근거 메시지 팝업
    evidence?.let { m ->
        AlertDialog(
            onDismissRequest = { evidence = null },
            title = { Text("#${m.index} · ${if (m.role == SpeakerRole.ME) "나" else m.speakerName}") },
            text = { Column { Text(m.sentAt.toString().replace('T', ' '), style = MaterialTheme.typography.labelSmall); Text(m.content) } },
            confirmButton = { TextButton(onClick = { evidence = null }) { Text("닫기") } }
        )
    }
}
