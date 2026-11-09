package com.talkcoach.core.style

import kotlin.math.roundToInt

/**
 * 사용자의 말투 통계. 상대방(관계)마다 말투가 다르므로 프로필별로 계산·저장한다.
 * The user's speech-style statistics. Computed and stored per contact, since tone differs by relationship.
 *
 * @property sampleCount       number of analyzed messages / 분석한 메시지 수
 * @property politeRatio       share of sentences ending in honorific form (0–1) / 존댓말 종결 비율
 * @property laughRatio        share of messages containing ㅋ / ㅋ 포함 메시지 비율
 * @property avgLaughLength    average length of a ㅋ run (e.g. ㅋㅋㅋ = 3) / ㅋ 연속 평균 길이
 * @property hehRatio          share of messages containing ㅎㅎ / ㅎㅎ 포함 메시지 비율
 * @property emojiRatio        share of messages containing emoji or emoticon chars / 이모지 포함 비율
 * @property tildeRatio        share of messages containing "~" / 물결(~) 사용 비율
 * @property periodEndRatio    share of messages ending with "." / 마침표로 끝나는 비율
 * @property avgLength         average characters per message / 메시지 평균 글자 수
 * @property splitRatio        share of consecutive short messages (split sending) / 끊어 보내기 비율
 * @property topEndings        most frequent sentence endings / 자주 쓰는 어미 Top N
 */
data class StyleProfile(
    val sampleCount: Int,
    val politeRatio: Double,
    val laughRatio: Double,
    val avgLaughLength: Double,
    val hehRatio: Double,
    val emojiRatio: Double,
    val tildeRatio: Double,
    val periodEndRatio: Double,
    val avgLength: Int,
    val splitRatio: Double,
    val topEndings: List<String>
) {
    /**
     * 프롬프트 주입용 자연어 가이드를 만든다.
     * Builds a natural-language guide for prompt injection.
     *
     * @return Korean style guide text / 한국어 말투 가이드 문자열
     */
    fun toPromptGuide(): String {
        // Not enough data → generic instruction / 데이터 부족 시 일반 지침
        if (sampleCount < 20) return "- 표본 부족(${sampleCount}건): 대화 내 사용자 발화의 말투를 그대로 따라갈 것"
        val speech = when {
            politeRatio >= 0.8 -> "존댓말 위주"           // Mostly polite / 존댓말 위주
            politeRatio <= 0.2 -> "반말 위주"             // Mostly casual / 반말 위주
            else -> "존댓말·반말 혼용"                     // Mixed / 혼용
        }
        val laugh = if (laughRatio < 0.05) "거의 사용 안 함" else
            "메시지의 ${pct(laughRatio)}에서 사용, 평균 'ㅋ' ${avgLaughLength.roundToInt()}개"
        return buildString {
            appendLine("- 존댓말 비율 ${pct(politeRatio)} → $speech")
            appendLine("- 'ㅋ' 웃음: $laugh / 'ㅎㅎ' 사용 ${pct(hehRatio)}")
            appendLine("- 이모지 ${pct(emojiRatio)}, 물결(~) ${pct(tildeRatio)}, 마침표 종결 ${pct(periodEndRatio)}")
            appendLine("- 메시지 평균 ${avgLength}자, 끊어 보내기 ${pct(splitRatio)}")
            if (topEndings.isNotEmpty()) appendLine("- 자주 쓰는 어미: ${topEndings.joinToString(", ")}")
        }.trimEnd()
    }

    /**
     * 비율을 퍼센트 문자열로 변환한다.
     * Formats a ratio as a percent string.
     *
     * @param r ratio 0–1 / 비율
     * @return e.g. "35%" / 예: "35%"
     */
    private fun pct(r: Double): String = "${(r * 100).roundToInt()}%"
}

/**
 * '나'의 발화만으로 말투 통계를 계산한다. 순수 함수이며 기기 내에서만 실행된다.
 * Computes style statistics from the user's own messages only. Pure function, runs on-device.
 */
object SpeechStyleAnalyzer {

    /** Honorific sentence endings / 존댓말 종결 어미 */
    private val POLITE_END = Regex("""(요|니다|니까|세요|셔요|죠|십시오|습니다|ㅂ니다|여)$""")

    /** Laugh run ㅋ+ / ㅋ 연속 */
    private val LAUGH = Regex("""ㅋ+""")

    /** Heh ㅎㅎ+ / ㅎㅎ 연속 */
    private val HEH = Regex("""ㅎ{2,}""")

    /** Emoji (surrogate pairs, misc symbols) and text emoticons / 이모지 및 텍스트 이모티콘 */
    private val EMOJI = Regex("""[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]|\^\^|ㅠㅠ|ㅜㅜ|:\)|;\)""")

    /** Trailing decorations to strip before checking the ending / 어미 판단 전 제거할 꼬리 장식 */
    private val TRAILING_DECOR = Regex("""[\sㅋㅎㅠㅜ~!?.^;:)(\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]+$""")

    /** Sentence splitter / 문장 분리 */
    private val SENTENCE_SPLIT = Regex("""(?<=[.!?~])\s+|\n+""")

    /**
     * 말투 프로필을 계산한다.
     * Computes the style profile.
     *
     * @param myMessages the user's text messages (media excluded) / 사용자의 텍스트 메시지(미디어 제외)
     * @param timestampsMillis optional epoch millis aligned with messages, for split-sending detection / 끊어 보내기 판단용 시각(선택)
     * @return computed profile / 계산된 프로필
     */
    fun analyze(myMessages: List<String>, timestampsMillis: List<Long>? = null): StyleProfile {
        val msgs = myMessages.map(String::trim).filter(String::isNotEmpty)   // Drop blanks / 빈 메시지 제거
        if (msgs.isEmpty()) return StyleProfile(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, emptyList())

        val n = msgs.size.toDouble()                                        // Sample size / 표본 수
        var politeSentences = 0                                             // Polite sentence count / 존댓말 문장 수
        var hangulSentences = 0                                             // Judgeable sentence count / 판단 가능한 문장 수
        val endings = mutableMapOf<String, Int>()                           // Ending frequency / 어미 빈도

        for (m in msgs) {
            for (sentence in m.split(SENTENCE_SPLIT)) {
                val core = sentence.replace(TRAILING_DECOR, "")              // Strip decorations / 꼬리 장식 제거
                if (core.isEmpty() || !core.last().isHangulSyllable()) continue // Need a Hangul ending / 한글 종결만 판단
                hangulSentences++                                           // Judgeable / 판단 대상
                if (POLITE_END.containsMatchIn(core)) politeSentences++      // Polite / 존댓말
                val ending = core.takeLast(2).filter { it.isHangulSyllable() } // Last 2 syllables / 마지막 2음절
                if (ending.isNotEmpty()) endings[ending] = (endings[ending] ?: 0) + 1
            }
        }

        val laughRuns = msgs.flatMap { LAUGH.findAll(it).map { r -> r.value.length }.toList() } // ㅋ run lengths / ㅋ 길이
        val split = timestampsMillis?.takeIf { it.size == msgs.size }?.let { ts ->
            // Consecutive own messages within 10s count as split sending / 10초 내 연속 발화 = 끊어 보내기
            ts.zipWithNext().count { (a, b) -> b - a in 0..10_000 } / n
        } ?: 0.0

        return StyleProfile(
            sampleCount = msgs.size,
            politeRatio = if (hangulSentences == 0) 0.0 else politeSentences.toDouble() / hangulSentences,
            laughRatio = msgs.count { LAUGH.containsMatchIn(it) } / n,
            avgLaughLength = if (laughRuns.isEmpty()) 0.0 else laughRuns.average(),
            hehRatio = msgs.count { HEH.containsMatchIn(it) } / n,
            emojiRatio = msgs.count { EMOJI.containsMatchIn(it) } / n,
            tildeRatio = msgs.count { '~' in it } / n,
            periodEndRatio = msgs.count { it.endsWith('.') && !it.endsWith("..") } / n,
            avgLength = msgs.map(String::length).average().roundToInt(),
            splitRatio = split,
            topEndings = endings.entries.sortedByDescending { it.value }.take(5).map { "~${it.key}" }
        )
    }

    /**
     * 한글 완성형 음절 여부.
     * Whether the char is a complete Hangul syllable.
     *
     * @receiver char to test / 검사할 문자
     * @return true for 가–힣 / 가~힣 이면 true
     */
    private fun Char.isHangulSyllable(): Boolean = this in '가'..'힣'
}
