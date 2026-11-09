package com.talkcoach.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.talkcoach.data.TalkRepository
import com.talkcoach.data.settings.Provider
import com.talkcoach.ui.Bar
import com.talkcoach.ui.Confirm
import com.talkcoach.ui.Section
import kotlinx.coroutines.launch

/**
 * 설정: AI 공급사·키·모델, 마스킹 표기, 전체 삭제.
 * Settings: provider, keys, models, mask style, wipe all.
 *
 * @param repo   repository / 저장소
 * @param onBack back / 뒤로
 */
@Composable
fun SettingsScreen(repo: TalkRepository, onBack: () -> Unit) {
    val ctx = LocalContext.current                                                   // Context / 컨텍스트
    val scope = rememberCoroutineScope()                                             // Scope / 스코프
    val initial = remember { repo.settings.settings.value }                          // Loaded once / 1회 로드
    var provider by remember { mutableStateOf(initial.provider) }                    // Provider / 공급사
    var gKey by remember { mutableStateOf(initial.geminiKey) }                       // Gemini key / 제미나이 키
    var oKey by remember { mutableStateOf(initial.openAiKey) }                       // OpenAI key / 오픈AI 키
    var gModel by remember { mutableStateOf(initial.geminiModel) }                   // Gemini model / 모델
    var oModel by remember { mutableStateOf(initial.openAiModel) }                   // OpenAI model / 모델
    var labeled by remember { mutableStateOf(initial.labeledMask) }                  // Mask style / 마스킹 표기
    var wipe by remember { mutableStateOf(false) }                                   // Wipe confirm / 전체 삭제 확인

    Scaffold(topBar = { Bar("설정", onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Section("사용할 AI") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Provider.entries.forEach { p ->
                        FilterChip(selected = provider == p, onClick = { provider = p }, label = { Text(p.label) })
                    }
                }
            }
            Section("Gemini") {
                OutlinedTextField(gKey, { gKey = it }, label = { Text("API 키") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(gModel, { gModel = it }, label = { Text("모델명") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("aistudio.google.com 에서 키 발급", style = MaterialTheme.typography.labelSmall)
            }
            Section("OpenAI") {
                OutlinedTextField(oKey, { oKey = it }, label = { Text("API 키") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(oModel, { oModel = it }, label = { Text("모델명") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("platform.openai.com 에서 키 발급", style = MaterialTheme.typography.labelSmall)
            }
            Section("개인정보") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("마스킹에 유형 표시")
                        Text(if (labeled) "예: [전화번호***] — AI가 맥락을 더 잘 이해" else "예: *** — 유형도 숨김", style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = labeled, onCheckedChange = { labeled = it })
                }
                Text(
                    "• API 키와 대화는 이 폰 안에만 암호화 저장돼요.\n• AI에는 분석 구간만, 전화·계좌·주민번호·카드·이메일·상세주소를 가리고 상대 이름을 '상대'로 바꿔 보내요.\n• 무료 API 등급은 입력 데이터가 모델 개선에 쓰일 수 있어요. 공급사 약관을 확인하세요.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)
                )
            }
            Button(
                onClick = {
                    repo.settings.save(initial.copy(provider = provider, geminiKey = gKey, openAiKey = oKey,
                        geminiModel = gModel, openAiModel = oModel, labeledMask = labeled))
                    Toast.makeText(ctx, "저장했어요", Toast.LENGTH_SHORT).show(); onBack()
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text("저장") }
            OutlinedButton(onClick = { wipe = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("모든 프로필·대화 삭제", color = Color(0xFFD04A4A))
            }
            Text("톡코치 0.1.0", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(16.dp))
        }
    }
    if (wipe) Confirm(
        "모든 데이터 삭제", "모든 프로필, 대화 원문, 맥락, 분석 이력을 영구 삭제해요. API 키는 유지돼요.", "전체 삭제",
        onConfirm = { scope.launch { repo.db.contactDao().deleteAll(); Toast.makeText(ctx, "삭제했어요", Toast.LENGTH_SHORT).show() } },
        onDismiss = { wipe = false }
    )
}
