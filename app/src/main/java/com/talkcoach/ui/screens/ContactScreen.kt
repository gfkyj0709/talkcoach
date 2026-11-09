package com.talkcoach.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.talkcoach.core.prompt.AnalysisMode
import com.talkcoach.data.RELATIONS
import com.talkcoach.data.TalkRepository
import com.talkcoach.data.local.ContactProfileEntity
import com.talkcoach.data.local.ContextMemoryEntity
import com.talkcoach.data.relationLabel
import com.talkcoach.data.tags
import com.talkcoach.data.tagsJson
import com.talkcoach.data.toProfile
import com.talkcoach.ui.Bar
import com.talkcoach.ui.Confirm
import com.talkcoach.ui.Section
import com.talkcoach.ui.fmt
import com.talkcoach.ui.theme.Purple
import kotlinx.coroutines.launch

/** Memory category labels / 맥락 분류 표시명 */
private val CATEGORY = mapOf(
    "EVENT" to "사건", "EMOTION" to "감정", "PREFERENCE" to "선호", "PROMISE" to "약속", "SENSITIVE_TOPIC" to "민감 주제"
)

/**
 * 상대 프로필: 정보, 말투, 누적 맥락, 분석 이력, 삭제.
 * Contact profile: info, style, memories, history, deletion.
 *
 * @param repo          repository / 저장소
 * @param contactId     contact id / 상대 id
 * @param onBack        back / 뒤로
 * @param onAnalyze     open analysis / 분석 화면
 * @param onOpenSession open a past result / 지난 결과 열기
 * @param onDeleted     after profile deletion / 프로필 삭제 후
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContactScreen(
    repo: TalkRepository, contactId: Long, onBack: () -> Unit, onAnalyze: () -> Unit,
    onOpenSession: (Long) -> Unit, onDeleted: () -> Unit
) {
    val contact by repo.db.contactDao().observe(contactId).collectAsState(initial = null)           // Profile / 프로필
    val count by repo.db.chatDao().observeCount(contactId).collectAsState(initial = 0)              // Messages / 메시지 수
    val memories by repo.db.analysisDao().observeMemories(contactId).collectAsState(initial = emptyList()) // Memories / 맥락
    val sessions by repo.db.analysisDao().observeSessions(contactId).collectAsState(initial = emptyList()) // History / 이력
    val style by repo.db.analysisDao().observeStyle(contactId).collectAsState(initial = null)       // Style / 말투
    val scope = rememberCoroutineScope()                                                            // Scope / 스코프

    var menu by remember { mutableStateOf(false) }                // Overflow / 더보기
    var editing by remember { mutableStateOf(false) }             // Edit dialog / 편집 창
    var confirmConv by remember { mutableStateOf(false) }         // Delete conversation / 대화 삭제 확인
    var confirmAll by remember { mutableStateOf(false) }          // Delete profile / 프로필 삭제 확인
    var addMemory by remember { mutableStateOf(false) }           // Add memory dialog / 맥락 추가 창

    val c = contact
    Scaffold(topBar = {
        Bar(c?.displayName ?: "", onBack = onBack, actions = {
            IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "편집") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "더보기") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("대화 원문만 삭제") }, onClick = { menu = false; confirmConv = true })
                DropdownMenuItem(text = { Text("프로필 전체 삭제", color = Color(0xFFD04A4A)) }, onClick = { menu = false; confirmAll = true })
            }
        })
    }) { pad ->
        if (c == null) return@Scaffold                                                              // Deleted / 삭제됨
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                Section("프로필") {
                    Text("관계: ${relationLabel(c.relationType)}" + (c.mbti?.let { " · MBTI $it" } ?: ""))
                    val t = tags(c.traitTagsJson)
                    if (t.isNotEmpty()) Text("성향: " + t.joinToString(", "))
                    c.memo?.takeIf { it.isNotBlank() }?.let { Text("메모: $it") }
                    Text("저장된 메시지 ${count}개 · 내 이름 '${c.myExportName ?: "-"}'", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Button(onClick = onAnalyze, enabled = count > 0, modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
                    Text(if (count > 0) "대화 분석하기" else "먼저 대화를 가져와 주세요")
                }
            }
            style?.let { s ->
                item { Section("이 상대에게 쓰는 내 말투") { Text(s.toProfile().toPromptGuide(), style = MaterialTheme.typography.bodySmall) } }
            }
            item {
                Section("누적 맥락 (다음 분석에 자동 반영)") {
                    if (memories.isEmpty()) Text("아직 없어요. 분석하면 자동으로 쌓여요.", style = MaterialTheme.typography.bodySmall)
                    memories.forEach { m -> MemoryRow(m, repo, scope) }
                    TextButton(onClick = { addMemory = true }) { Text("+ 직접 추가") }
                }
            }
            item {
                Section("분석 이력") {
                    if (sessions.isEmpty()) Text("아직 없어요.", style = MaterialTheme.typography.bodySmall)
                    sessions.forEach { s ->
                        Column(Modifier.fillMaxWidth().clickable { onOpenSession(s.id) }.padding(vertical = 8.dp)) {
                            val mode = runCatching { AnalysisMode.valueOf(s.mode).label }.getOrDefault(s.mode)
                            Text("$mode · 분위기 ${s.moodScore ?: "-"}점", fontWeight = FontWeight.SemiBold)
                            Text("${fmt(s.createdAt)} · 메시지 ${s.messageCount}개 · ${s.modelName}", style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // Dialogs / 다이얼로그
    if (editing && c != null) EditDialog(c, onDismiss = { editing = false }) { updated ->
        scope.launch { repo.db.contactDao().update(updated.copy(updatedAt = System.currentTimeMillis())) }
    }
    if (confirmConv) Confirm(
        "대화 원문 삭제", "저장된 대화 원문을 모두 삭제해요. 프로필·누적 맥락·분석 이력은 남아요.", "삭제",
        onConfirm = { scope.launch { repo.db.chatDao().deleteConversation(contactId) } }, onDismiss = { confirmConv = false }
    )
    if (confirmAll) Confirm(
        "프로필 전체 삭제", "프로필과 대화 원문, 맥락, 분석 이력을 모두 영구 삭제해요.", "전체 삭제",
        onConfirm = { scope.launch { repo.db.contactDao().deleteWithAllData(contactId); onDeleted() } },
        onDismiss = { confirmAll = false }
    )
    if (addMemory) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addMemory = false },
            title = { Text("맥락 직접 추가") },
            text = { OutlinedTextField(text, { text = it }, placeholder = { Text("예: 지난주 약속을 내가 취소해서 서운해함") }) },
            confirmButton = {
                TextButton(onClick = {
                    addMemory = false
                    if (text.isNotBlank()) scope.launch {
                        val now = System.currentTimeMillis()
                        repo.db.analysisDao().insertMemory(
                            ContextMemoryEntity(
                                contactId = contactId, sourceSessionId = null, category = "EVENT",
                                summary = repo.masker().mask(text.trim()).text, importance = 4, eventDate = null,
                                isPinned = true, expiresAt = null, createdAt = now, updatedAt = now
                            )
                        )
                    }
                }) { Text("추가") }
            },
            dismissButton = { TextButton(onClick = { addMemory = false }) { Text("취소") } }
        )
    }
}

/**
 * 맥락 한 줄: 고정/활성 토글, 삭제.
 * One memory row with pin/active toggles and delete.
 *
 * @param m     memory / 맥락
 * @param repo  repository / 저장소
 * @param scope coroutine scope / 스코프
 */
@Composable
private fun MemoryRow(m: ContextMemoryEntity, repo: TalkRepository, scope: kotlinx.coroutines.CoroutineScope) {
    val dao = repo.db.analysisDao()                                                      // DAO / DAO
    val now = System.currentTimeMillis()                                                 // Now / 현재
    val expired = m.expiresAt != null && m.expiresAt < now && !m.isPinned                 // Expired / 만료
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                "[${CATEGORY[m.category] ?: m.category}] ${m.summary}",
                color = if (m.isActive && !expired) MaterialTheme.colorScheme.onSurface else Color.Gray
            )
            Text(
                listOfNotNull(m.eventDate, "중요도 ${m.importance}", if (!m.isActive) "해결됨" else null, if (expired) "만료" else null)
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall
            )
        }
        IconButton(onClick = { scope.launch { dao.setPinned(m.id, !m.isPinned, now) } }) {
            Icon(Icons.Default.Star, "고정", tint = if (m.isPinned) Purple else Color.LightGray)
        }
        Switch(checked = m.isActive, onCheckedChange = { scope.launch { dao.setActive(m.id, it, now) } })
        IconButton(onClick = { scope.launch { dao.deleteMemory(m.id) } }) { Icon(Icons.Default.Delete, "삭제", tint = Color.Gray) }
    }
}

/**
 * 프로필 편집 다이얼로그.
 * Profile edit dialog.
 *
 * @param c         current profile / 현재 프로필
 * @param onDismiss close / 닫기
 * @param onSave    save callback / 저장 콜백
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(c: ContactProfileEntity, onDismiss: () -> Unit, onSave: (ContactProfileEntity) -> Unit) {
    var name by remember { mutableStateOf(c.displayName) }                          // Name / 이름
    var relation by remember { mutableStateOf(c.relationType) }                     // Relation / 관계
    var mbti by remember { mutableStateOf(c.mbti ?: "") }                           // MBTI
    var traits by remember { mutableStateOf(tags(c.traitTagsJson).joinToString(", ")) } // Tags / 태그
    var memo by remember { mutableStateOf(c.memo ?: "") }                           // Memo / 메모
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("프로필 편집") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("이름") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RELATIONS.forEach { (code, label) ->
                        FilterChip(selected = relation == code, onClick = { relation = code }, label = { Text(label) })
                    }
                }
                OutlinedTextField(mbti, { mbti = it.uppercase().take(4) }, label = { Text("MBTI (선택)") })
                OutlinedTextField(traits, { traits = it }, label = { Text("성향 태그 (쉼표 구분)") }, placeholder = { Text("직설적, 감성적") })
                OutlinedTextField(memo, { memo = it }, label = { Text("메모") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    c.copy(
                        displayName = name.trim().ifEmpty { c.displayName }, relationType = relation,
                        mbti = mbti.trim().ifEmpty { null },
                        traitTagsJson = tagsJson(traits.split(',').map { it.trim() }.filter { it.isNotEmpty() }),
                        memo = memo.trim().ifEmpty { null }
                    )
                ); onDismiss()
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
