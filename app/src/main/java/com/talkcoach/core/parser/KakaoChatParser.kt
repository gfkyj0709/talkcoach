package com.talkcoach.core.parser

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 메시지 발화자 구분.
 * Speaker role of a parsed message.
 */
enum class SpeakerRole {
    /** The app user / 앱 사용자 본인 */
    ME,

    /** The counterpart(s) / 대화 상대방 */
    OTHER
}

/**
 * 메시지 유형. 미디어/삭제 메시지는 분석 시 텍스트 대신 placeholder 로 치환한다.
 * Message type. Media and deleted messages are replaced with placeholders during analysis.
 */
enum class MessageType {
    /** Plain text / 일반 텍스트 */
    TEXT,

    /** Photo, video, emoticon, file / 사진·동영상·이모티콘·파일 */
    MEDIA,

    /** Deleted message / 삭제된 메시지 */
    DELETED
}

/**
 * 카카오톡 내보내기 포맷 종류.
 * Detected KakaoTalk export format.
 */
enum class KakaoFormat {
    /** "2024년 1월 15일 오후 3:24, 이름 : 내용" */
    ANDROID,

    /** "2024. 1. 15. 오후 3:24, 이름 : 내용" */
    IOS,

    /** "[이름] [오후 3:24] 내용" (PC export / 모바일 텍스트 복사) */
    BRACKET,

    /** Nothing matched / 인식 실패 */
    UNKNOWN
}

/**
 * 파싱된 단일 메시지 (발화자 역할 확정 전 상태).
 * A single parsed message before the speaker role is resolved.
 *
 * @property sentAt      message timestamp / 메시지 시각
 * @property speakerName raw display name in the export / 내보내기 원문 상의 표시 이름
 * @property content     message body (multi-line merged) / 메시지 본문(멀티라인 병합됨)
 * @property type        message type / 메시지 유형
 */
data class RawMessage(
    val sentAt: LocalDateTime,
    val speakerName: String,
    val content: String,
    val type: MessageType
)

/**
 * 발화자 역할이 확정된 메시지.
 * A message whose speaker role has been resolved.
 *
 * @property index   sequential index used as evidence reference in the prompt / 프롬프트 근거 인용용 순번
 * @property sentAt  message timestamp / 메시지 시각
 * @property role    ME or OTHER / 나 또는 상대
 * @property speakerName raw display name / 원문 표시 이름
 * @property content message body / 메시지 본문
 * @property type    message type / 메시지 유형
 */
data class ChatMessage(
    val index: Int,
    val sentAt: LocalDateTime,
    val role: SpeakerRole,
    val speakerName: String,
    val content: String,
    val type: MessageType
)

/**
 * 파싱 결과.
 * Result of parsing an export text.
 *
 * @property format             detected format / 감지된 포맷
 * @property messages           parsed messages in chronological order / 시간순 메시지 목록
 * @property participants       speaker name → message count / 발화자 이름별 메시지 수
 * @property chatTitleName      counterpart name from the export title, if any / 내보내기 제목에서 추출한 상대 이름
 * @property skippedSystemLines number of filtered system lines / 필터링된 시스템 메시지 수
 */
data class ParseResult(
    val format: KakaoFormat,
    val messages: List<RawMessage>,
    val participants: Map<String, Int>,
    val chatTitleName: String?,
    val skippedSystemLines: Int
) {
    /**
     * 제목("OOO 님과 카카오톡 대화")과 참여자 목록으로 '나'의 이름을 추정한다. 1:1 대화에서만 신뢰 가능.
     * Guesses the user's own name from the title and participants. Reliable only for 1:1 chats.
     *
     * @return guessed self name, or null if it cannot be determined / 추정한 본인 이름, 판단 불가 시 null
     */
    fun guessSelfName(): String? {
        // Only meaningful for exactly two participants / 참여자가 정확히 2명일 때만 의미 있음
        if (participants.size != 2 || chatTitleName == null) return null
        // The participant who is NOT the title name is me / 제목 이름이 아닌 쪽이 '나'
        return participants.keys.firstOrNull { it != chatTitleName }
    }
}

/**
 * 카카오톡 대화 내보내기(.txt) 및 텍스트 복사본 파서.
 * Parser for KakaoTalk exported .txt files and copied chat text.
 *
 * 지원 포맷 / Supported formats:
 * - Android : `2024년 1월 15일 오후 3:24, 홍길동 : 안녕`
 * - iOS     : `2024. 1. 15. 오후 3:24, 홍길동 : 안녕`
 * - Bracket : `[홍길동] [오후 3:24] 안녕` + 날짜 구분선 `--------------- 2024년 1월 15일 월요일 ---------------`
 *
 * 영문 로케일 내보내기는 미지원 [확인필요].
 * English-locale exports are not supported [확인필요].
 */
object KakaoChatParser {

    // ── Regex patterns / 정규식 패턴 ─────────────────────────────────────────────

    /**
     * Android/iOS 공통 날짜+시각 접두부. 그룹: 1=년 2=월 3=일 4=오전/오후 5=시 6=분
     * Shared date-time prefix for Android/iOS. Groups: 1=year 2=month 3=day 4=AM/PM 5=hour 6=minute
     */
    private const val DATE_TIME_PREFIX =
        """^(\d{4})(?:년|\.)\s?(\d{1,2})(?:월|\.)\s?(\d{1,2})(?:일|\.)?\s(오전|오후)\s(\d{1,2}):(\d{2})"""

    /** Android/iOS user message: prefix + ", name : content" / 사용자 메시지 */
    private val MOBILE_MESSAGE = Regex("""$DATE_TIME_PREFIX,\s(.+?)\s:\s?(.*)$""")

    /** Android/iOS line with timestamp but no " : " → system message / 시각은 있으나 " : " 없음 → 시스템 메시지 */
    private val MOBILE_SYSTEM = Regex("""$DATE_TIME_PREFIX,\s(.*)$""")

    /** Bracket message: [name] [오후 3:24] content / 대괄호 포맷 메시지 */
    private val BRACKET_MESSAGE = Regex("""^\[(.+?)]\s\[(오전|오후)\s(\d{1,2}):(\d{2})]\s?(.*)$""")

    /** Date divider (bracket format): ----- 2024년 1월 15일 월요일 ----- / 날짜 구분선 */
    private val BRACKET_DATE_DIVIDER = Regex("""^-+\s*(\d{4})년\s(\d{1,2})월\s(\d{1,2})일\s\S+요일\s*-+$""")

    /** Standalone date line in mobile exports: 2024년 1월 15일 월요일 / 모바일 날짜 라인 */
    private val MOBILE_DATE_LINE = Regex("""^(\d{4})년\s(\d{1,2})월\s(\d{1,2})일\s\S+요일$""")

    /** Export title: "홍길동 님과 카카오톡 대화" / 내보내기 제목 */
    private val TITLE_LINE = Regex("""^(.+?)\s?님과 카카오톡 대화$""")

    /** Header line such as "저장한 날짜 : ..." / 헤더 라인 */
    private val HEADER_LINE = Regex("""^(저장한 날짜|카카오톡 대화)\s?.*""")

    /** Bracket-format system lines (no [name] prefix) / 대괄호 포맷 시스템 메시지 */
    private val BRACKET_SYSTEM = Regex("""^.+님[이을]\s?.*(들어왔습니다|나갔습니다|초대했습니다|내보냈습니다)\.?$""")

    /** Media placeholders produced by KakaoTalk / 카카오톡 미디어 대체 문구 */
    private val MEDIA_CONTENT = Regex("""^(사진(\s\d+장)?|동영상|이모티콘|음성메시지|파일:\s?.+|지도:\s?.+|연락처:\s?.+|송금.*|라이브톡.*)$""")

    /** Deleted message placeholder / 삭제된 메시지 문구 */
    private val DELETED_CONTENT = Regex("""^(삭제된 메시지입니다\.?|메시지가 삭제되었습니다\.?)$""")

    // ── Public API / 공개 API ─────────────────────────────────────────────────

    /**
     * 내보내기 텍스트 전체를 파싱한다.
     * Parses the whole exported text.
     *
     * @param text full text of the .txt file or copied chat / .txt 파일 또는 복사한 대화 전체 텍스트
     * @return parse result with messages and participant stats / 메시지와 참여자 통계를 담은 파싱 결과
     */
    fun parse(text: String): ParseResult {
        // Strip BOM and normalize line endings / BOM 제거 및 개행 정규화
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        // Detect format from the first matching lines / 앞부분에서 포맷 감지
        val format = detectFormat(lines)

        // Accumulated messages / 누적 메시지 목록
        val messages = mutableListOf<RawMessage>()
        // Builder for the message currently being read (multi-line) / 현재 읽는 메시지 버퍼(멀티라인)
        var current: MessageBuilder? = null
        // Current date for bracket format (from dividers) / 대괄호 포맷용 현재 날짜(구분선 기준)
        var currentDate: LocalDate? = null
        // Counterpart name from the title line / 제목 라인의 상대 이름
        var titleName: String? = null
        // Count of filtered system lines / 필터링된 시스템 라인 수
        var systemCount = 0

        for (rawLine in lines) {
            // Trim only trailing whitespace; keep leading spaces of continuation lines / 끝 공백만 제거
            val line = rawLine.trimEnd()

            // 1) Title / header lines (only before the first message) / 제목·헤더 (첫 메시지 이전에만)
            if (messages.isEmpty() && current == null) {
                val title = TITLE_LINE.matchEntire(line)                       // Try title / 제목 매칭
                if (title != null) { titleName = title.groupValues[1].trim(); continue }
                if (HEADER_LINE.matches(line)) continue                        // Skip header / 헤더 skip
            }

            // 2) Mobile user message / 모바일 사용자 메시지
            val mobile = MOBILE_MESSAGE.matchEntire(line)
            if (mobile != null) {
                current?.let { messages += it.build() }                        // Flush previous / 이전 메시지 확정
                val g = mobile.groupValues                                      // Group values / 그룹 값
                val dt = toDateTime(g[1], g[2], g[3], g[4], g[5], g[6])         // Build timestamp / 시각 생성
                current = MessageBuilder(dt, g[7].trim(), StringBuilder(g[8]))  // Start new message / 새 메시지 시작
                continue
            }

            // 3) Mobile system message (timestamp without " : ") / 모바일 시스템 메시지
            if (MOBILE_SYSTEM.matches(line)) {
                current?.let { messages += it.build() }                        // Flush previous / 이전 메시지 확정
                current = null                                                 // System line ends message / 시스템 라인은 메시지 종료
                systemCount++                                                  // Count it / 카운트
                continue
            }

            // 4) Date divider (bracket) or standalone date line (mobile) / 날짜 구분선·날짜 라인
            val dateLine = BRACKET_DATE_DIVIDER.matchEntire(line) ?: MOBILE_DATE_LINE.matchEntire(line)
            if (dateLine != null) {
                current?.let { messages += it.build() }                        // Flush previous / 이전 메시지 확정
                current = null                                                 // Reset buffer / 버퍼 초기화
                val g = dateLine.groupValues                                   // y, m, d / 년·월·일
                currentDate = LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt()) // Update date / 날짜 갱신
                continue
            }

            // 5) Bracket user message / 대괄호 포맷 사용자 메시지
            val bracket = BRACKET_MESSAGE.matchEntire(line)
            if (bracket != null) {
                current?.let { messages += it.build() }                        // Flush previous / 이전 메시지 확정
                val g = bracket.groupValues                                    // name, ampm, h, m, body / 그룹 값
                val date = currentDate ?: LocalDate.now()                      // Fallback when no divider / 구분선 없으면 오늘
                current = MessageBuilder(toDateTime(date, g[2], g[3], g[4]), g[1].trim(), StringBuilder(g[5]))
                continue
            }

            // 6) Bracket system line / 대괄호 포맷 시스템 메시지
            if (format == KakaoFormat.BRACKET && BRACKET_SYSTEM.matches(line)) {
                current?.let { messages += it.build() }                        // Flush previous / 이전 메시지 확정
                current = null                                                 // Reset buffer / 버퍼 초기화
                systemCount++                                                  // Count it / 카운트
                continue
            }

            // 7) Continuation of a multi-line message (ignored if none open) / 멀티라인 이어지는 줄
            current?.content?.append('\n')?.append(line)
        }
        // Flush the last message / 마지막 메시지 확정
        current?.let { messages += it.build() }

        // Count messages per speaker / 발화자별 메시지 수 집계
        val participants = messages.groupingBy { it.speakerName }.eachCount()
        return ParseResult(format, messages, participants, titleName, systemCount)
    }

    /**
     * 사용자가 선택한 '내 이름'을 기준으로 발화자 역할을 확정하고 순번을 부여한다.
     * Resolves speaker roles based on the user-selected self name and assigns indices.
     *
     * @param raw      parsed raw messages / 파싱된 원시 메시지
     * @param selfName the user's display name in the chat / 대화방에서의 내 표시 이름
     * @return messages with role and index / 역할·순번이 부여된 메시지
     */
    fun resolveRoles(raw: List<RawMessage>, selfName: String): List<ChatMessage> =
        raw.mapIndexed { i, m ->
            ChatMessage(
                index = i + 1,                                                         // 1-based index / 1부터 시작
                sentAt = m.sentAt,                                                     // Timestamp / 시각
                role = if (m.speakerName == selfName) SpeakerRole.ME else SpeakerRole.OTHER, // Role / 역할
                speakerName = m.speakerName,                                           // Raw name / 원문 이름
                content = m.content,                                                   // Body / 본문
                type = m.type                                                          // Type / 유형
            )
        }

    /**
     * 최근 N개 메시지를 선택한다.
     * Selects the most recent N messages.
     *
     * @param messages source list in chronological order / 시간순 원본 목록
     * @param n        number of messages to keep / 유지할 메시지 수
     * @return last n messages / 마지막 n개 메시지
     */
    fun <T> takeLast(messages: List<T>, n: Int): List<T> = messages.takeLast(n.coerceAtLeast(0))

    /**
     * 날짜 범위(양 끝 포함)로 메시지를 선택한다.
     * Selects messages within a date range (inclusive).
     *
     * @param messages source list / 원본 목록
     * @param from     start date inclusive / 시작일(포함)
     * @param to       end date inclusive / 종료일(포함)
     * @return messages within the range / 범위 내 메시지
     */
    fun filterByDate(messages: List<ChatMessage>, from: LocalDate, to: LocalDate): List<ChatMessage> =
        messages.filter { it.sentAt.toLocalDate() in from..to }

    // ── Internals / 내부 구현 ──────────────────────────────────────────────────

    /**
     * 처음 200줄 안에서 가장 먼저 매칭되는 패턴으로 포맷을 판단한다.
     * Detects the format by the first pattern matched within the first 200 lines.
     *
     * @param lines all lines / 전체 라인
     * @return detected format / 감지된 포맷
     */
    private fun detectFormat(lines: List<String>): KakaoFormat {
        for (line in lines.asSequence().take(200)) {
            // Android uses "년", iOS uses "." / 안드로이드는 "년", iOS는 "." 사용
            if (MOBILE_MESSAGE.matches(line) || MOBILE_SYSTEM.matches(line))
                return if (line.substring(0, 5).contains('년')) KakaoFormat.ANDROID else KakaoFormat.IOS
            // Bracket format / 대괄호 포맷
            if (BRACKET_MESSAGE.matches(line) || BRACKET_DATE_DIVIDER.matches(line)) return KakaoFormat.BRACKET
        }
        return KakaoFormat.UNKNOWN
    }

    /**
     * 문자열 그룹으로 LocalDateTime 을 만든다.
     * Builds a LocalDateTime from string groups.
     *
     * @param y year / 년
     * @param mo month / 월
     * @param d day / 일
     * @param ampm "오전" or "오후" / 오전·오후
     * @param h hour (1–12) / 시
     * @param mi minute / 분
     * @return timestamp / 시각
     */
    private fun toDateTime(y: String, mo: String, d: String, ampm: String, h: String, mi: String): LocalDateTime =
        toDateTime(LocalDate.of(y.toInt(), mo.toInt(), d.toInt()), ampm, h, mi)

    /**
     * 날짜 + 12시간제 시각을 24시간제 LocalDateTime 으로 변환한다.
     * Converts a date plus 12-hour time to a 24-hour LocalDateTime.
     *
     * @param date date / 날짜
     * @param ampm "오전" or "오후" / 오전·오후
     * @param h hour (1–12) / 시
     * @param mi minute / 분
     * @return timestamp / 시각
     */
    private fun toDateTime(date: LocalDate, ampm: String, h: String, mi: String): LocalDateTime {
        val hour12 = h.toInt() % 12                                // 12 → 0 / 12시는 0으로
        val hour24 = if (ampm == "오후") hour12 + 12 else hour12    // PM adds 12 / 오후는 +12
        return date.atTime(hour24, mi.toInt())                     // Combine / 결합
    }

    /**
     * 멀티라인 메시지를 모으는 버퍼.
     * Buffer that accumulates a multi-line message.
     *
     * @property sentAt  timestamp / 시각
     * @property speaker speaker name / 발화자 이름
     * @property content growing body / 누적 본문
     */
    private class MessageBuilder(val sentAt: LocalDateTime, val speaker: String, val content: StringBuilder) {
        /**
         * 버퍼를 RawMessage 로 확정하고 유형을 분류한다.
         * Finalizes the buffer into a RawMessage and classifies its type.
         *
         * @return finalized message / 확정된 메시지
         */
        fun build(): RawMessage {
            val body = content.toString().trim()                   // Remove trailing blank lines / 끝 빈 줄 제거
            val type = when {
                DELETED_CONTENT.matches(body) -> MessageType.DELETED // Deleted / 삭제됨
                MEDIA_CONTENT.matches(body) -> MessageType.MEDIA     // Media / 미디어
                else -> MessageType.TEXT                             // Text / 텍스트
            }
            return RawMessage(sentAt, speaker, body, type)
        }
    }
}
