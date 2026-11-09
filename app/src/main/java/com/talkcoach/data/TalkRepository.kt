package com.talkcoach.data

import com.talkcoach.core.parser.ChatMessage
import com.talkcoach.core.parser.MessageType
import com.talkcoach.core.parser.ParseResult
import com.talkcoach.core.parser.SpeakerRole
import com.talkcoach.core.privacy.MaskStyle
import com.talkcoach.core.privacy.PiiType
import com.talkcoach.core.privacy.PrivacyMasker
import com.talkcoach.core.prompt.AnalysisMode
import com.talkcoach.core.prompt.ContactContext
import com.talkcoach.core.prompt.LlmRequest
import com.talkcoach.core.prompt.MemoryItem
import com.talkcoach.core.prompt.PromptBuilder
import com.talkcoach.core.style.SpeechStyleAnalyzer
import com.talkcoach.core.style.StyleProfile
import com.talkcoach.data.llm.AnalysisResult
import com.talkcoach.data.llm.LlmClient
import com.talkcoach.data.llm.LlmException
import com.talkcoach.data.local.AnalysisSessionEntity
import com.talkcoach.data.local.AppDatabase
import com.talkcoach.data.local.ChatImportEntity
import com.talkcoach.data.local.ChatMessageEntity
import com.talkcoach.data.local.ContactProfileEntity
import com.talkcoach.data.local.ContextMemoryEntity
import com.talkcoach.data.local.ReplySuggestionEntity
import com.talkcoach.data.local.UserStyleProfileEntity
import com.talkcoach.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 관계 유형 (DB 저장값 → 표시명).
 * Relation types (stored value → label).
 */
val RELATIONS: List<Pair<String, String>> = listOf(
    "SOME" to "썸", "LOVER" to "연인", "BOSS" to "직장 상사", "COWORKER" to "직장 동료",
    "CLIENT" to "거래처/고객", "FRIEND" to "친구", "FAMILY" to "가족", "ETC" to "기타"
)

/**
 * 관계 저장값을 표시명으로 바꾼다.
 * Maps a stored relation value to its label.
 *
 * @param code stored value / 저장값
 * @return label / 표시명
 */
fun relationLabel(code: String): String = RELATIONS.firstOrNull { it.first == code }?.second ?: code

/**
 * 가져오기 대상.
 * Import target.
 */
sealed interface ImportTarget {
    /** Existing profile / 기존 프로필 @property id contact id / 상대 id */
    data class Existing(val id: Long) : ImportTarget

    /** New profile / 신규 프로필 @property name display name / 표시명 @property relation relation code / 관계 */
    data class New(val name: String, val relation: String) : ImportTarget
}

/**
 * 가져오기 결과.
 * Import outcome.
 *
 * @property contactId     target contact / 대상 상대
 * @property inserted      newly stored messages / 새로 저장된 메시지 수
 * @property duplicates    skipped duplicates / 중복으로 건너뛴 수
 * @property sameFile      the exact same file was imported before / 동일 파일 재가져오기
 */
data class ImportOutcome(val contactId: Long, val inserted: Int, val duplicates: Int, val sameFile: Boolean)

/**
 * 분석 구간 지정.
 * Analysis range spec.
 */
sealed interface RangeSpec {
    /** Last N messages / 최근 N개 @property n count / 개수 */
    data class RecentCount(val n: Int) : RangeSpec

    /** Last N days from the latest message / 마지막 메시지 기준 최근 N일 @property days days / 일수 */
    data class RecentDays(val days: Int) : RangeSpec
}

/**
 * 전송 전 미리보기.
 * Pre-send preview.
 *
 * @property request      masked request / 마스킹된 요청
 * @property messageCount messages in range / 구간 메시지 수
 * @property maskCounts   PII hits per type / 유형별 마스킹 건수
 */
data class Preview(val request: LlmRequest, val messageCount: Int, val maskCounts: Map<PiiType, Int>)

/**
 * 앱의 단일 데이터 진입점: 가져오기, 분석, 삭제.
 * Single data entry point: import, analysis, deletion.
 *
 * @property db       encrypted database / 암호화 DB
 * @property settings settings store / 설정 저장소
 * @property llm      LLM client / LLM 클라이언트
 */
class TalkRepository(val db: AppDatabase, val settings: SettingsStore, private val llm: LlmClient) {

    private val zone: ZoneId = ZoneId.systemDefault()   // Device time zone / 기기 시간대

    /**
     * 현재 설정 기준 마스커.
     * Masker for the current settings.
     *
     * @return masker / 마스커
     */
    fun masker(): PrivacyMasker =
        PrivacyMasker(if (settings.settings.value.labeledMask) MaskStyle.LABELED else MaskStyle.PLAIN)

    // ── Import / 가져오기 ─────────────────────────────────────────────────

    /**
     * 파싱 결과를 저장한다. 같은 파일·겹치는 메시지는 건너뛴다.
     * Stores a parse result, skipping the same file and overlapping messages.
     *
     * @param rawText  original text (for file hash) / 원문 (파일 해시용)
     * @param parsed   parse result / 파싱 결과
     * @param selfName the user's name in the chat / 대화방 내 내 이름
     * @param target   where to store / 저장 대상
     * @return outcome / 결과
     */
    suspend fun importChat(rawText: String, parsed: ParseResult, selfName: String, target: ImportTarget): ImportOutcome =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()                                            // Now / 현재
            val others = parsed.participants.keys.filter { it != selfName }                // Counterparts / 상대 목록
            val contactId = when (target) {
                is ImportTarget.Existing -> target.id                                       // Existing / 기존
                is ImportTarget.New -> db.contactDao().insert(
                    ContactProfileEntity(
                        displayName = target.name.ifBlank { others.firstOrNull() ?: "이름 없음" },
                        exportName = parsed.chatTitleName ?: others.firstOrNull(),
                        myExportName = selfName, relationType = target.relation, mbti = null,
                        memo = null, isGroup = others.size > 1, createdAt = now, updatedAt = now
                    )
                )
            }
            val millis = parsed.messages.map { it.sentAt.atZone(zone).toInstant().toEpochMilli() } // Times / 시각
            val importId = db.chatDao().insertImport(
                ChatImportEntity(
                    contactId = contactId, sourceType = "SHARE", format = parsed.format.name,
                    fileHash = sha256(rawText), messageCount = parsed.messages.size,
                    firstMsgAt = millis.minOrNull(), lastMsgAt = millis.maxOrNull(), importedAt = now
                )
            )
            if (importId == -1L) return@withContext ImportOutcome(contactId, 0, parsed.messages.size, true) // Same file / 동일 파일

            val seen = HashMap<String, Int>()                                               // Ordinal per identical key / 동일 키 순번
            val rows = parsed.messages.mapIndexed { i, m ->
                val base = "${millis[i]}|${m.speakerName}|${m.content}"                     // Identity / 식별 키
                val ord = seen.getOrDefault(base, 0).also { seen[base] = it + 1 }           // Same minute repeats get 0,1,2… / 같은 분 반복은 0,1,2…
                ChatMessageEntity(
                    contactId = contactId, importId = importId, sentAt = millis[i],
                    speakerRole = if (m.speakerName == selfName) "ME" else "OTHER",
                    speakerName = m.speakerName, content = m.content, msgType = m.type.name,
                    dedupKey = sha256("$base|$ord")                                         // Stable across re-imports / 재가져오기에도 동일
                )
            }
            val ids = rows.chunked(500).flatMap { db.chatDao().insertMessages(it) }        // Batch insert / 분할 저장
            val inserted = ids.count { it != -1L }                                          // New rows / 신규 건수

            db.contactDao().get(contactId)?.let {                                          // Touch profile / 프로필 갱신
                db.contactDao().update(it.copy(myExportName = selfName, updatedAt = now))
            }
            recomputeStyle(contactId)                                                       // Refresh tone / 말투 갱신
            ImportOutcome(contactId, inserted, rows.size - inserted, false)
        }

    /**
     * 내 메시지로 말투 프로필을 다시 계산한다.
     * Recomputes the style profile from my messages.
     *
     * @param contactId contact id / 상대 id
     * @return computed profile / 계산된 프로필
     */
    suspend fun recomputeStyle(contactId: Long): StyleProfile = withContext(Dispatchers.IO) {
        val mine = db.chatDao().myRecentTexts(contactId).reversed()                         // Chronological / 시간순
        val p = SpeechStyleAnalyzer.analyze(mine.map { it.content }, mine.map { it.sentAt }) // Analyze / 분석
        db.analysisDao().upsertStyle(p.toEntity(contactId))                                 // Save / 저장
        p
    }

    // ── Analysis / 분석 ──────────────────────────────────────────────────

    /**
     * 구간 메시지를 불러와 순번을 붙인다.
     * Loads range messages and assigns indices.
     *
     * @param contactId contact id / 상대 id
     * @param range     range spec / 구간
     * @return messages / 메시지
     */
    suspend fun loadMessages(contactId: Long, range: RangeSpec): List<ChatMessage> = withContext(Dispatchers.IO) {
        val rows = when (range) {
            is RangeSpec.RecentCount -> db.chatDao().recent(contactId, range.n)             // Last N / 최근 N개
            is RangeSpec.RecentDays -> {
                val last = db.chatDao().lastSentAt(contactId) ?: return@withContext emptyList()
                db.chatDao().range(contactId, last - range.days * 86_400_000L, last)        // Last N days / 최근 N일
            }
        }
        toChat(rows)
    }

    /**
     * 기간 [from, to] 메시지 (결과 화면의 근거 확인용).
     * Messages within [from, to] (for evidence lookup on the result screen).
     *
     * @param contactId contact id / 상대 id
     * @param from      start / 시작
     * @param to        end / 끝
     * @return messages / 메시지
     */
    suspend fun messagesBetween(contactId: Long, from: Long, to: Long): List<ChatMessage> =
        withContext(Dispatchers.IO) { toChat(db.chatDao().range(contactId, from, to)) }

    /**
     * 전송될 프롬프트를 미리 만든다 (네트워크 호출 없음).
     * Builds the exact prompt to be sent (no network).
     *
     * @param contactId contact id / 상대 id
     * @param mode      mode / 모드
     * @param range     range / 구간
     * @param userAsk   extra question / 추가 질문
     * @return preview / 미리보기
     */
    suspend fun preview(contactId: Long, mode: AnalysisMode, range: RangeSpec, userAsk: String?): Preview =
        withContext(Dispatchers.IO) {
            val contact = db.contactDao().get(contactId) ?: error("프로필 없음")              // Profile / 프로필
            val msgs = loadMessages(contactId, range)                                         // Range / 구간
            val now = System.currentTimeMillis()                                              // Now / 현재
            val memories = db.analysisDao().activeMemories(contactId, now, PromptBuilder.MAX_MEMORIES).map {
                MemoryItem(it.id, it.eventDate ?: "-", it.category, it.summary, it.importance)
            }
            val style = db.analysisDao().style(contactId)?.toProfile() ?: recomputeStyle(contactId)
            val masker = masker()                                                             // Masker / 마스커
            val req = PromptBuilder(masker).build(
                mode,
                ContactContext(relationLabel(contact.relationType), contact.mbti, tags(contact.traitTagsJson), contact.memo),
                memories, style, msgs, userAsk
            )
            val counts = masker.maskAll(msgs.filter { it.type == MessageType.TEXT }.map { it.content }).second
            Preview(req, msgs.size, counts)
        }

    /**
     * 분석을 실행하고 결과를 저장한다. 형식 오류 시 1회 재시도.
     * Runs the analysis and saves it; retries once on a malformed response.
     *
     * @param contactId contact id / 상대 id
     * @param mode      mode / 모드
     * @param range     range / 구간
     * @param userAsk   extra question / 추가 질문
     * @return new session id / 세션 id
     * @throws LlmException on failure (Korean message) / 실패 시 (한글 메시지)
     */
    suspend fun analyze(contactId: Long, mode: AnalysisMode, range: RangeSpec, userAsk: String?): Long =
        withContext(Dispatchers.IO) {
            val p = preview(contactId, mode, range, userAsk)                                  // Same prompt as preview / 미리보기와 동일
            if (p.messageCount == 0) throw LlmException("분석할 메시지가 없어요. 대화를 먼저 가져와 주세요.")
            val msgs = loadMessages(contactId, range)                                         // For range bounds / 구간 경계용
            val s = settings.settings.value                                                   // Settings / 설정

            var result: AnalysisResult? = null                                                // Parsed result / 결과
            var raw = ""                                                                      // Raw text / 원문 응답
            for (attempt in 1..2) {
                raw = llm.complete(s.provider, s.activeKey, s.activeModel, p.request)         // Call / 호출
                result = runCatching { AnalysisResult.parse(raw) }.getOrNull()                // Parse / 파싱
                if (result != null && result.replies.size >= 3) break                         // Valid / 정상
            }
            val r = result ?: throw LlmException("AI 응답을 해석하지 못했어요. 다시 시도해 주세요.")

            val now = System.currentTimeMillis()                                              // Now / 현재
            val injected = db.analysisDao().activeMemories(contactId, now, PromptBuilder.MAX_MEMORIES).map { it.id }.toSet()
            val masker = masker()                                                             // Re-mask summaries / 요약 재마스킹
            val validCats = setOf("EVENT", "EMOTION", "PREFERENCE", "PROMISE", "SENSITIVE_TOPIC")
            val sid = db.analysisDao().saveAnalysis(
                session = AnalysisSessionEntity(
                    contactId = contactId, mode = mode.name,
                    rangeStartAt = msgs.first().sentAt.toMillis(), rangeEndAt = msgs.last().sentAt.toMillis(),
                    messageCount = msgs.size, maskedCount = p.maskCounts.values.sum(),
                    modelName = "${s.provider.label}/${s.activeModel}", promptVersion = PromptBuilder.PROMPT_VERSION,
                    moodTemperature = r.mood.temperature, moodScore = r.mood.score, confidence = r.confidence,
                    resultJson = raw, status = "SUCCESS", createdAt = now
                ),
                suggestions = r.replies.take(3).map {
                    ReplySuggestionEntity(sessionId = 0, tone = it.tone, text = it.text, intent = it.intent, risk = it.risk)
                },
                newMemories = r.memoryAdds.take(5).map {
                    val cat = if (it.category in validCats) it.category else "EVENT"          // Sanitize / 정규화
                    ContextMemoryEntity(
                        contactId = contactId, sourceSessionId = null, category = cat,
                        summary = masker.mask(it.summary).text, importance = it.importance,
                        eventDate = it.eventDate,
                        expiresAt = if (cat == "EMOTION") now + 14 * 86_400_000L else null,   // Emotions fade in 14d / 감정은 14일 후 만료
                        createdAt = now, updatedAt = now
                    )
                },
                deactivateIds = r.deactivateIds.filter { it in injected }                     // Only ids we sent / 주입한 id만
            )
            db.contactDao().get(contactId)?.let { db.contactDao().update(it.copy(lastAnalyzedAt = now)) }
            sid
        }

    // ── Helpers / 헬퍼 ───────────────────────────────────────────────────

    /**
     * 엔티티 → 코어 메시지 (1부터 순번).
     * Entities → core messages (1-based index).
     *
     * @param rows entities / 엔티티
     * @return messages / 메시지
     */
    private fun toChat(rows: List<ChatMessageEntity>): List<ChatMessage> = rows.mapIndexed { i, e ->
        ChatMessage(
            index = i + 1,
            sentAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.sentAt), zone),
            role = if (e.speakerRole == "ME") SpeakerRole.ME else SpeakerRole.OTHER,
            speakerName = e.speakerName, content = e.content,
            type = runCatching { MessageType.valueOf(e.msgType) }.getOrDefault(MessageType.TEXT)
        )
    }

    /**
     * LocalDateTime → epoch millis.
     *
     * @receiver time / 시각
     * @return millis / 밀리초
     */
    private fun LocalDateTime.toMillis(): Long = atZone(zone).toInstant().toEpochMilli()

    /**
     * SHA-256 hex.
     *
     * @param s input / 입력
     * @return hex digest / 해시
     */
    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * 태그 JSON 배열 → 목록.
 * Tag JSON array → list.
 *
 * @param json JSON array text / JSON 배열 문자열
 * @return tags / 태그
 */
fun tags(json: String): List<String> = runCatching {
    val a = JSONArray(json); (0 until a.length()).map { a.getString(it) }
}.getOrDefault(emptyList())

/**
 * 태그 목록 → JSON 배열.
 * Tag list → JSON array text.
 *
 * @param list tags / 태그
 * @return JSON text / JSON 문자열
 */
fun tagsJson(list: List<String>): String = JSONArray(list).toString()

/**
 * 말투 프로필 → 엔티티.
 * Style profile → entity.
 *
 * @receiver profile / 프로필
 * @param contactId contact id / 상대 id
 * @return entity / 엔티티
 */
fun StyleProfile.toEntity(contactId: Long) = UserStyleProfileEntity(
    contactId = contactId, politeRatio = politeRatio, laughRatio = laughRatio, avgLaughLength = avgLaughLength,
    hehRatio = hehRatio, emojiRatio = emojiRatio, tildeRatio = tildeRatio, periodEndRatio = periodEndRatio,
    avgLength = avgLength, splitRatio = splitRatio, topEndingsJson = tagsJson(topEndings),
    sampleCount = sampleCount, updatedAt = System.currentTimeMillis()
)

/**
 * 엔티티 → 말투 프로필.
 * Entity → style profile.
 *
 * @receiver entity / 엔티티
 * @return profile / 프로필
 */
fun UserStyleProfileEntity.toProfile() = StyleProfile(
    sampleCount, politeRatio, laughRatio, avgLaughLength, hehRatio, emojiRatio, tildeRatio,
    periodEndRatio, avgLength, splitRatio, tags(topEndingsJson)
)
