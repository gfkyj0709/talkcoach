package com.talkcoach.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.talkcoach.ShareReader
import com.talkcoach.data.TalkRepository
import com.talkcoach.data.relationLabel
import com.talkcoach.ui.Bar
import com.talkcoach.ui.fmt
import com.talkcoach.ui.theme.Purple

/**
 * 홈: 상대 프로필 목록 + 대화 가져오기.
 * Home: contact list + chat import.
 *
 * @param repo          repository / 저장소
 * @param onImportText  queue text for import / 가져올 텍스트 전달
 * @param onOpenContact open a profile / 프로필 열기
 * @param onSettings    open settings / 설정 열기
 */
@Composable
fun HomeScreen(repo: TalkRepository, onImportText: (String) -> Unit, onOpenContact: (Long) -> Unit, onSettings: () -> Unit) {
    val contacts by repo.db.contactDao().observeAll().collectAsState(initial = emptyList()) // Profiles / 프로필
    val settings by repo.settings.settings.collectAsState()                                // Settings / 설정
    val ctx = LocalContext.current                                                         // Context / 컨텍스트
    val clipboard = LocalClipboardManager.current                                          // Clipboard / 클립보드
    var menu by remember { mutableStateOf(false) }                                         // FAB menu / 메뉴
    var pasteDialog by remember { mutableStateOf(false) }                                  // Paste dialog / 붙여넣기 창

    // File picker for exported .txt / .zip / 내보낸 .txt·.zip 선택기
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult                                    // Cancelled / 취소
        val text = ShareReader.fromUri(uri, ctx.contentResolver)                           // Read / 읽기
        if (text.isNullOrBlank()) Toast.makeText(ctx, "파일을 읽지 못했어요", Toast.LENGTH_SHORT).show()
        else onImportText(text)                                                            // Import / 가져오기
    }

    Scaffold(
        topBar = {
            Bar("톡코치", actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "설정") } })
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("파일 선택 (.txt / .zip)") }, onClick = {
                        menu = false; picker.launch(arrayOf("text/plain", "application/zip", "application/octet-stream", "*/*"))
                    })
                    DropdownMenuItem(text = { Text("클립보드에서 붙여넣기") }, onClick = {
                        menu = false
                        val t = clipboard.getText()?.text                                  // Clipboard text / 클립보드 텍스트
                        if (t.isNullOrBlank()) Toast.makeText(ctx, "클립보드가 비어 있어요", Toast.LENGTH_SHORT).show()
                        else onImportText(t)
                    })
                    DropdownMenuItem(text = { Text("직접 입력") }, onClick = { menu = false; pasteDialog = true })
                }
                ExtendedFloatingActionButton(
                    onClick = { menu = true }, containerColor = Purple, contentColor = androidx.compose.ui.graphics.Color.White,
                    icon = { Icon(Icons.Default.Add, null) }, text = { Text("대화 가져오기") }
                )
            }
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // API key reminder / API 키 안내
            if (settings.activeKey.isBlank()) item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp).clickable(onClick = onSettings),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text("AI 분석을 쓰려면 설정에서 ${settings.provider.label} API 키를 입력해 주세요 →", Modifier.padding(16.dp))
                }
            }
            // Empty state with how-to / 빈 화면 사용법
            if (contacts.isEmpty()) item { HowTo() }
            items(contacts, key = { it.id }) { c ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onOpenContact(c.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                relationLabel(c.relationType) + (c.mbti?.let { " · $it" } ?: "") + if (c.isGroup) " · 단체방" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            c.lastAnalyzedAt?.let { "최근 분석\n" + fmt(it) } ?: "분석 전",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            item { Spacer(Modifier.width(1.dp).heightIn(min = 96.dp)) }                      // FAB clearance / FAB 여백
        }
    }

    // Manual paste dialog / 직접 입력 다이얼로그
    if (pasteDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasteDialog = false },
            title = { Text("대화 붙여넣기") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                    placeholder = { Text("[이름] [오후 3:24] 메시지\n형식의 텍스트를 붙여넣으세요") }
                )
            },
            confirmButton = { TextButton(onClick = { pasteDialog = false; if (text.isNotBlank()) onImportText(text) }) { Text("다음") } },
            dismissButton = { TextButton(onClick = { pasteDialog = false }) { Text("취소") } }
        )
    }
}

/**
 * 첫 사용 안내.
 * First-use guide.
 */
@Composable
private fun HowTo() {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("대화를 가져오는 방법", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("1. 카카오톡 대화방 → 오른쪽 위 ≡ 메뉴 → 설정(톱니바퀴)")
        Text("2. '대화 내용 내보내기' → '텍스트만 보내기'")
        Text("3. 공유 목록에서 '톡코치' 선택")
        Text("또는 말풍선을 길게 눌러 여러 개 선택 → 복사 → 여기서 '클립보드에서 붙여넣기'", style = MaterialTheme.typography.bodySmall)
        Text("원본 대화는 이 폰 안의 암호화 DB에만 저장돼요. AI에는 개인정보를 가린 분석 구간만 전송돼요.", style = MaterialTheme.typography.bodySmall)
    }
}
