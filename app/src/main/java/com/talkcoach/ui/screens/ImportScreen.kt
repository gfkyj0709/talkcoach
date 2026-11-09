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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.talkcoach.core.parser.KakaoChatParser
import com.talkcoach.core.parser.KakaoFormat
import com.talkcoach.core.parser.ParseResult
import com.talkcoach.data.ImportTarget
import com.talkcoach.data.RELATIONS
import com.talkcoach.data.TalkRepository
import com.talkcoach.data.local.ContactProfileEntity
import com.talkcoach.ui.Bar
import com.talkcoach.ui.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 가져오기: 파싱 결과 확인 → '나' 선택 → 저장할 프로필 선택.
 * Import: review parse result → pick "me" → pick target profile.
 *
 * @param repo     repository / 저장소
 * @param text     shared text or null / 공유된 텍스트
 * @param onDone   called with contact id / 완료 시 상대 id
 * @param onCancel cancel / 취소
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportScreen(repo: TalkRepository, text: String?, onDone: (Long) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current                                                         // Context / 컨텍스트
    val scope = rememberCoroutineScope()                                                   // Scope / 코루틴 스코프

    // Parse off the main thread / 메인 스레드 밖에서 파싱
    val parsed by produceState<ParseResult?>(null, text) {
        value = text?.let { withContext(Dispatchers.Default) { KakaoChatParser.parse(it) } }
    }
    val profiles by produceState(emptyList<ContactProfileEntity>()) { value = repo.db.contactDao().all() }

    var self by remember { mutableStateOf<String?>(null) }                                 // My name / 내 이름
    var target by remember { mutableStateOf<Long?>(null) }                                 // Existing id or null=new / 기존 id (null=신규)
    var newName by remember { mutableStateOf("") }                                         // New profile name / 신규 이름
    var relation by remember { mutableStateOf("SOME") }                                    // Relation / 관계
    var saving by remember { mutableStateOf(false) }                                       // Busy / 저장 중

    // Defaults once parsed: self guess, matching profile, new name / 파싱 후 기본값 설정
    LaunchedEffect(parsed, profiles) {
        val p = parsed ?: return@LaunchedEffect
        val saved = profiles.firstOrNull { it.exportName != null && it.exportName == p.chatTitleName } // Same title / 같은 제목
        if (self == null) self = saved?.myExportName?.takeIf { it in p.participants }      // Remembered self / 기억된 내 이름
            ?: p.guessSelfName()                                                           // Title-based guess / 제목 기반 추정
            ?: p.participants.keys.firstOrNull()                                           // Fallback / 기본값
        if (target == null && saved != null) target = saved.id                             // Auto-match / 자동 매칭
        if (newName.isBlank()) newName = p.chatTitleName ?: p.participants.keys.firstOrNull { it != self } ?: ""
    }

    Scaffold(topBar = { Bar("대화 가져오기", onBack = onCancel) }) { pad ->
        val p = parsed
        when {
            text == null -> Box(pad) { Text("가져올 대화가 없어요. 홈에서 다시 시도해 주세요.", Modifier.padding(24.dp)) }
            p == null -> Box(pad) { CircularProgressIndicator(Modifier.padding(24.dp)) }
            p.messages.isEmpty() -> Box(pad) {
                Text(
                    "카카오톡 대화 형식을 인식하지 못했어요.\n내보내기 파일 또는 '[이름] [오후 3:24] 메시지' 형식인지 확인해 주세요.\n(영문 설정 내보내기는 아직 지원하지 않아요)",
                    Modifier.padding(24.dp)
                )
            }
            else -> Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
                // Summary / 요약
                Section("인식 결과") {
                    val fmtName = when (p.format) {
                        KakaoFormat.ANDROID -> "Android 내보내기"; KakaoFormat.IOS -> "iOS 내보내기"
                        KakaoFormat.BRACKET -> "PC 내보내기 / 복사 텍스트"; KakaoFormat.UNKNOWN -> "알 수 없음"
                    }
                    Text("형식: $fmtName")
                    Text("메시지 ${p.messages.size}개 · 시스템 메시지 ${p.skippedSystemLines}개 제외")
                    Text("기간: ${p.messages.first().sentAt.toLocalDate()} ~ ${p.messages.last().sentAt.toLocalDate()}")
                }
                // Who am I / 내 이름 선택
                Section("이 중에 '나'는 누구예요?") {
                    p.participants.entries.sortedByDescending { it.value }.forEach { (name, count) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = self == name, onClick = { self = name })
                            Text("$name  (${count}개)")
                        }
                    }
                }
                // Target profile / 저장할 프로필
                Section("어느 프로필에 저장할까요?") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = target == null, onClick = { target = null })
                        Text("새 프로필 만들기")
                    }
                    if (target == null) {
                        OutlinedTextField(newName, { newName = it }, label = { Text("표시 이름") }, modifier = Modifier.fillMaxWidth())
                        Text("관계", Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RELATIONS.forEach { (code, label) ->
                                FilterChip(selected = relation == code, onClick = { relation = code }, label = { Text(label) })
                            }
                        }
                    }
                    profiles.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = target == c.id, onClick = { target = c.id })
                            Text("기존: ${c.displayName}")
                        }
                    }
                }
                Button(
                    enabled = !saving && self != null && (target != null || newName.isNotBlank()),
                    onClick = {
                        saving = true
                        scope.launch {
                            val r = runCatching {
                                repo.importChat(
                                    text.orEmpty(), p, self!!,
                                    target?.let { ImportTarget.Existing(it) } ?: ImportTarget.New(newName.trim(), relation)
                                )
                            }
                            saving = false
                            r.onSuccess {
                                val msg = if (it.sameFile) "이미 가져온 파일이에요" else "새 메시지 ${it.inserted}개 저장 (중복 ${it.duplicates}개 건너뜀)"
                                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                onDone(it.contactId)
                            }.onFailure { Toast.makeText(ctx, "저장 실패: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { Text(if (saving) "저장 중…" else "가져오기") }
            }
        }
    }
}

/**
 * 패딩만 적용하는 단순 컨테이너.
 * Simple container applying padding.
 *
 * @param pad     padding / 패딩
 * @param content content / 내용
 */
@Composable
private fun Box(pad: androidx.compose.foundation.layout.PaddingValues, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(pad)) { content() }
}
