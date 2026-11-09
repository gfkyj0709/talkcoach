package com.talkcoach.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * ERD (모든 하위 테이블은 contact_profile 삭제 시 CASCADE 삭제)
 * ERD (every child table is CASCADE-deleted with contact_profile)
 *
 *  contact_profile 1 ─┬─ N chat_import 1 ── N chat_message
 *                     ├─ 1 user_style_profile
 *                     ├─ N analysis_session 1 ─┬─ N reply_suggestion
 *                     │                        └─ N context_memory (source_session_id, SET NULL)
 *                     └─ N context_memory
 *
 * 시각 컬럼은 모두 epoch millis(Long) / All time columns are epoch millis (Long).
 */

/**
 * 대화 상대 프로필.
 * Contact profile.
 *
 * @property id              PK / 기본키
 * @property displayName     name shown in the app / 앱 표시 이름
 * @property exportName      counterpart name as it appears in exports / 내보내기 파일상 상대 이름
 * @property myExportName    user's own name in exports (for ME/OTHER split) / 내보내기 파일상 내 이름
 * @property relationType    SOME, LOVER, BOSS, COWORKER, CLIENT, FRIEND, FAMILY, ETC / 관계 유형
 * @property mbti            MBTI (nullable) / MBTI
 * @property traitTagsJson   JSON array of trait tags, e.g. ["직설적","감성적"] / 성향 태그 JSON 배열
 * @property memo            free memo / 자유 메모
 * @property isGroup         group chat flag / 단체방 여부
 * @property createdAt       created time / 생성 시각
 * @property updatedAt       updated time / 수정 시각
 * @property lastAnalyzedAt  last analysis time / 마지막 분석 시각
 */
@Entity(tableName = "contact_profile", indices = [Index("export_name")])
data class ContactProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "export_name") val exportName: String?,
    @ColumnInfo(name = "my_export_name") val myExportName: String?,
    @ColumnInfo(name = "relation_type") val relationType: String,
    @ColumnInfo(name = "mbti") val mbti: String?,
    @ColumnInfo(name = "trait_tags_json") val traitTagsJson: String = "[]",
    @ColumnInfo(name = "memo") val memo: String?,
    @ColumnInfo(name = "is_group") val isGroup: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_analyzed_at") val lastAnalyzedAt: Long? = null
)

/**
 * 가져오기(공유) 이력. 같은 파일 재공유 방지에 file_hash 사용.
 * Import (share) history. file_hash prevents re-importing the same file.
 *
 * @property id           PK / 기본키
 * @property contactId    FK → contact_profile / 상대 FK
 * @property sourceType   EXPORT_TXT, CLIPBOARD / 유입 경로
 * @property format       ANDROID, IOS, BRACKET / 감지 포맷
 * @property fileHash     SHA-256 of the raw text / 원문 SHA-256
 * @property messageCount number of messages imported / 가져온 메시지 수
 * @property firstMsgAt   first message time / 첫 메시지 시각
 * @property lastMsgAt    last message time / 마지막 메시지 시각
 * @property importedAt   import time / 가져온 시각
 */
@Entity(
    tableName = "chat_import",
    foreignKeys = [ForeignKey(
        entity = ContactProfileEntity::class, parentColumns = ["id"], childColumns = ["contact_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("contact_id"), Index(value = ["contact_id", "file_hash"], unique = true)]
)
data class ChatImportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "contact_id") val contactId: Long,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "format") val format: String,
    @ColumnInfo(name = "file_hash") val fileHash: String,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "first_msg_at") val firstMsgAt: Long?,
    @ColumnInfo(name = "last_msg_at") val lastMsgAt: Long?,
    @ColumnInfo(name = "imported_at") val importedAt: Long
)

/**
 * 원본 대화 메시지 (기기 내 암호화 DB에만 저장, 서버 전송 시에는 마스킹본만 사용).
 * Original chat message (stored only in the on-device encrypted DB; only masked copies leave the device).
 *
 * dedup_key = sha256(sent_at|speaker_name|content) — 기간이 겹치는 내보내기를 다시 공유해도 중복 저장 안 됨.
 * dedup_key = sha256(sent_at|speaker_name|content) — overlapping re-exports are not stored twice.
 *
 * @property id          PK / 기본키
 * @property contactId   FK → contact_profile / 상대 FK
 * @property importId    FK → chat_import / 가져오기 FK
 * @property sentAt      message time / 메시지 시각
 * @property speakerRole ME, OTHER / 발화자 역할
 * @property speakerName raw display name / 원문 표시 이름
 * @property content     original body / 원문 본문
 * @property msgType     TEXT, MEDIA, DELETED / 메시지 유형
 * @property dedupKey    duplicate-detection hash / 중복 판별 해시
 */
@Entity(
    tableName = "chat_message",
    foreignKeys = [
        ForeignKey(
            entity = ContactProfileEntity::class, parentColumns = ["id"], childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ChatImportEntity::class, parentColumns = ["id"], childColumns = ["import_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["contact_id", "sent_at"]),
        Index("import_id"),
        Index(value = ["contact_id", "dedup_key"], unique = true)
    ]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "contact_id") val contactId: Long,
    @ColumnInfo(name = "import_id") val importId: Long,
    @ColumnInfo(name = "sent_at") val sentAt: Long,
    @ColumnInfo(name = "speaker_role") val speakerRole: String,
    @ColumnInfo(name = "speaker_name") val speakerName: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "msg_type") val msgType: String,
    @ColumnInfo(name = "dedup_key") val dedupKey: String
)

/**
 * 분석 실행 이력. 원본 대화는 저장하지 않고 범위와 결과만 저장한다.
 * Analysis run history. Stores range and result only, not the conversation.
 *
 * @property id              PK / 기본키
 * @property contactId       FK → contact_profile / 상대 FK
 * @property mode            INTEREST, DECLINE, CONFLICT, DAILY, BUSINESS / 상황 모드
 * @property rangeStartAt    first analyzed message time / 분석 구간 시작
 * @property rangeEndAt      last analyzed message time / 분석 구간 끝
 * @property messageCount    analyzed message count / 분석 메시지 수
 * @property maskedCount     total masked PII items / 마스킹된 개인정보 건수
 * @property modelName       LLM model id / 모델명
 * @property promptVersion   prompt version / 프롬프트 버전
 * @property moodTemperature WARM, NEUTRAL, COOL, TENSE (for trend chart) / 분위기(추이 차트용)
 * @property moodScore       0–100 / 분위기 점수
 * @property confidence      0–1 / 신뢰도
 * @property resultJson      full validated response JSON / 검증된 응답 JSON 전체
 * @property status          SUCCESS, PARSE_ERROR, API_ERROR / 상태
 * @property createdAt       created time / 생성 시각
 */
@Entity(
    tableName = "analysis_session",
    foreignKeys = [ForeignKey(
        entity = ContactProfileEntity::class, parentColumns = ["id"], childColumns = ["contact_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["contact_id", "created_at"])]
)
data class AnalysisSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "contact_id") val contactId: Long,
    @ColumnInfo(name = "mode") val mode: String,
    @ColumnInfo(name = "range_start_at") val rangeStartAt: Long,
    @ColumnInfo(name = "range_end_at") val rangeEndAt: Long,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "masked_count") val maskedCount: Int,
    @ColumnInfo(name = "model_name") val modelName: String,
    @ColumnInfo(name = "prompt_version") val promptVersion: String,
    @ColumnInfo(name = "mood_temperature") val moodTemperature: String?,
    @ColumnInfo(name = "mood_score") val moodScore: Int?,
    @ColumnInfo(name = "confidence") val confidence: Double?,
    @ColumnInfo(name = "result_json") val resultJson: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * 누적 맥락(대화 요약 히스토리). 다음 분석 프롬프트의 [이전 맥락]으로 주입된다.
 * Accumulated context (summary history). Injected as [이전 맥락] into the next prompt.
 *
 * @property id              PK / 기본키
 * @property contactId       FK → contact_profile / 상대 FK
 * @property sourceSessionId session that produced it (null if user-written) / 생성한 분석 세션(사용자 직접 입력 시 null)
 * @property category        EVENT, EMOTION, PREFERENCE, PROMISE, SENSITIVE_TOPIC / 분류
 * @property summary         one-line summary (no PII) / 한 줄 요약(개인정보 없음)
 * @property importance      1–5 / 중요도
 * @property eventDate       yyyy-MM-dd / 사건 날짜
 * @property isActive        false when resolved / 해결되면 false
 * @property isPinned        pinned by user (never auto-expired) / 사용자 고정(자동 만료 제외)
 * @property expiresAt       auto-expiry time (EMOTION: +14d, others null) / 자동 만료 시각
 * @property createdAt       created time / 생성 시각
 * @property updatedAt       updated time / 수정 시각
 */
@Entity(
    tableName = "context_memory",
    foreignKeys = [
        ForeignKey(
            entity = ContactProfileEntity::class, parentColumns = ["id"], childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AnalysisSessionEntity::class, parentColumns = ["id"], childColumns = ["source_session_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["contact_id", "is_active", "importance"]), Index("source_session_id")]
)
data class ContextMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "contact_id") val contactId: Long,
    @ColumnInfo(name = "source_session_id") val sourceSessionId: Long?,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "importance") val importance: Int,
    @ColumnInfo(name = "event_date") val eventDate: String?,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "expires_at") val expiresAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * 추천 답장. 사용자가 고른 답장/피드백은 이후 말투 보정 품질 개선에 활용한다.
 * Suggested reply. Selection and feedback feed later tone-calibration improvements.
 *
 * @property id        PK / 기본키
 * @property sessionId FK → analysis_session / 분석 세션 FK
 * @property tone      EMPATHETIC, LIGHT_HUMOR, FIRM_POLITE / 톤
 * @property text      reply text / 답장 문구
 * @property intent    intended effect / 의도
 * @property risk      risk note / 주의점
 * @property isCopied  copied by the user / 복사 여부
 * @property feedback  GOOD, BAD, null / 사용자 피드백
 */
@Entity(
    tableName = "reply_suggestion",
    foreignKeys = [ForeignKey(
        entity = AnalysisSessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("session_id")]
)
data class ReplySuggestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "tone") val tone: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "intent") val intent: String,
    @ColumnInfo(name = "risk") val risk: String,
    @ColumnInfo(name = "is_copied") val isCopied: Boolean = false,
    @ColumnInfo(name = "feedback") val feedback: String? = null
)

/**
 * 상대별 사용자 말투 프로필 (상사에겐 존댓말, 친구에겐 반말처럼 관계마다 다르므로 1:1).
 * Per-contact user style profile (1:1, since tone differs by relationship).
 *
 * @property contactId      PK & FK → contact_profile / 기본키 겸 상대 FK
 * @property politeRatio    honorific ratio / 존댓말 비율
 * @property laughRatio     ㅋ usage ratio / ㅋ 사용 비율
 * @property avgLaughLength average ㅋ run length / ㅋ 평균 길이
 * @property hehRatio       ㅎㅎ ratio / ㅎㅎ 비율
 * @property emojiRatio     emoji ratio / 이모지 비율
 * @property tildeRatio     tilde ratio / 물결 비율
 * @property periodEndRatio period-ending ratio / 마침표 종결 비율
 * @property avgLength      average length / 평균 길이
 * @property splitRatio     split-sending ratio / 끊어 보내기 비율
 * @property topEndingsJson JSON array of frequent endings / 자주 쓰는 어미 JSON
 * @property sampleCount    sample size / 표본 수
 * @property updatedAt      updated time / 갱신 시각
 */
@Entity(
    tableName = "user_style_profile",
    foreignKeys = [ForeignKey(
        entity = ContactProfileEntity::class, parentColumns = ["id"], childColumns = ["contact_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class UserStyleProfileEntity(
    @PrimaryKey @ColumnInfo(name = "contact_id") val contactId: Long,
    @ColumnInfo(name = "polite_ratio") val politeRatio: Double,
    @ColumnInfo(name = "laugh_ratio") val laughRatio: Double,
    @ColumnInfo(name = "avg_laugh_length") val avgLaughLength: Double,
    @ColumnInfo(name = "heh_ratio") val hehRatio: Double,
    @ColumnInfo(name = "emoji_ratio") val emojiRatio: Double,
    @ColumnInfo(name = "tilde_ratio") val tildeRatio: Double,
    @ColumnInfo(name = "period_end_ratio") val periodEndRatio: Double,
    @ColumnInfo(name = "avg_length") val avgLength: Int,
    @ColumnInfo(name = "split_ratio") val splitRatio: Double,
    @ColumnInfo(name = "top_endings_json") val topEndingsJson: String,
    @ColumnInfo(name = "sample_count") val sampleCount: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
