package com.talkcoach.data.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * 분위기.
 * Conversation mood.
 *
 * @property summary     2–3 sentence summary / 요약
 * @property temperature WARM, NEUTRAL, COOL, TENSE / 온도
 * @property score       0–100 / 점수
 */
data class Mood(val summary: String, val temperature: String, val score: Int)

/**
 * 숨은 의도.
 * Hidden intent.
 *
 * @property summary  summary / 요약
 * @property evidence evidence message indices / 근거 메시지 번호
 */
data class HiddenIntent(val summary: String, val evidence: List<Int>)

/**
 * 주의 포인트.
 * Caution point.
 *
 * @property point        what to watch / 주의할 점
 * @property reason       why / 이유
 * @property avoidExample sentence to avoid / 피할 문장
 */
data class Caution(val point: String, val reason: String, val avoidExample: String)

/**
 * 추천 답장.
 * Reply option.
 *
 * @property tone   EMPATHETIC, LIGHT_HUMOR, FIRM_POLITE / 톤
 * @property text   reply text / 답장
 * @property intent intended effect / 의도
 * @property risk   risk / 주의점
 */
data class ReplyOption(val tone: String, val text: String, val intent: String, val risk: String)

/**
 * 새 맥락.
 * New memory proposed by the model.
 *
 * @property category   category / 분류
 * @property summary    summary / 요약
 * @property importance 1–5 / 중요도
 * @property eventDate  yyyy-MM-dd or null / 날짜
 */
data class MemoryAdd(val category: String, val summary: String, val importance: Int, val eventDate: String?)

/**
 * 파싱된 AI 분석 결과.
 * Parsed AI analysis result.
 *
 * @property mood            mood / 분위기
 * @property hiddenIntent    hidden intent / 숨은 의도
 * @property cautions        caution points / 주의점
 * @property replies         reply options / 추천 답장
 * @property modeSpecific    mode-specific fields as display lines / 모드별 필드 (표시용 라인)
 * @property memoryAdds      memories to add / 추가할 맥락
 * @property deactivateIds   memory ids to deactivate / 비활성화할 맥락 id
 * @property confidence      0–1 / 신뢰도
 * @property dataLimitations limitations / 한계
 * @property safetyFlag      safety concern / 안전 경고
 */
data class AnalysisResult(
    val mood: Mood,
    val hiddenIntent: HiddenIntent,
    val cautions: List<Caution>,
    val replies: List<ReplyOption>,
    val modeSpecific: List<Pair<String, String>>,
    val memoryAdds: List<MemoryAdd>,
    val deactivateIds: List<Long>,
    val confidence: Double,
    val dataLimitations: String,
    val safetyFlag: Boolean
) {
    companion object {

        /** Korean labels for known mode_specific keys / 알려진 mode_specific 키의 한글명 */
        private val KEY_LABELS = mapOf(
            "interest_score" to "호감 신호 점수", "interest_signals" to "관심 신호", "distance_signals" to "거리두기 신호",
            "decline_target" to "거절 대상", "leave_door_open" to "여지 남기기 권장",
            "trigger_message_index" to "갈등 발단 메시지", "other_need" to "상대가 원하는 것", "apology_needed" to "사과 필요",
            "topic_hooks" to "이어갈 소재", "action_items" to "할 일", "ambiguities" to "확인 필요한 점"
        )

        /**
         * 모델 텍스트에서 JSON 객체를 추출해 파싱한다 (코드펜스·앞뒤 설명 허용).
         * Extracts and parses the JSON object from model text (tolerates code fences and stray text).
         *
         * @param raw model text / 모델 응답
         * @return parsed result / 파싱 결과
         * @throws IllegalArgumentException when no valid JSON object / JSON 객체가 없을 때
         */
        fun parse(raw: String): AnalysisResult {
            val start = raw.indexOf('{')                                              // First brace / 첫 중괄호
            val end = raw.lastIndexOf('}')                                            // Last brace / 마지막 중괄호
            require(start >= 0 && end > start) { "JSON 없음" }                         // Must exist / 존재해야 함
            val o = JSONObject(raw.substring(start, end + 1))                         // Parse / 파싱

            val mood = o.optJSONObject("mood") ?: JSONObject()                        // Mood / 분위기
            val intent = o.optJSONObject("hidden_intent") ?: JSONObject()             // Intent / 의도
            val mem = o.optJSONObject("memory_updates") ?: JSONObject()               // Memory / 맥락

            return AnalysisResult(
                mood = Mood(mood.optString("summary"), mood.optString("temperature", "NEUTRAL"), mood.optInt("score", 50)),
                hiddenIntent = HiddenIntent(intent.optString("summary"), ints(intent.optJSONArray("evidence"))),
                cautions = objects(o.optJSONArray("caution_points")).map {
                    Caution(it.optString("point"), it.optString("reason"), it.optString("avoid_example"))
                },
                replies = objects(o.optJSONArray("reply_options")).map {
                    ReplyOption(it.optString("tone"), it.optString("text"), it.optString("intent"), it.optString("risk"))
                }.filter { it.text.isNotBlank() },
                modeSpecific = o.optJSONObject("mode_specific")?.let { flatten(it) } ?: emptyList(),
                memoryAdds = objects(mem.optJSONArray("add")).map {
                    MemoryAdd(
                        it.optString("category", "EVENT"), it.optString("summary"),
                        it.optInt("importance", 3).coerceIn(1, 5),
                        it.optString("event_date").takeIf { d -> d.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }
                    )
                }.filter { it.summary.isNotBlank() },
                deactivateIds = ints(mem.optJSONArray("deactivate_ids")).map { it.toLong() },
                confidence = o.optDouble("confidence", 0.5).let { if (it.isNaN()) 0.5 else it },
                dataLimitations = o.optString("data_limitations"),
                safetyFlag = o.optBoolean("safety_flag", false)
            )
        }

        /**
         * JSON 배열 → 정수 목록 (숫자/문자 혼재 허용).
         * JSON array → ints (accepts numbers or numeric strings).
         *
         * @param a array / 배열
         * @return ints / 정수 목록
         */
        private fun ints(a: JSONArray?): List<Int> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it).toString().filter(Char::isDigit).toIntOrNull() }

        /**
         * JSON 배열 → 객체 목록.
         * JSON array → objects.
         *
         * @param a array / 배열
         * @return objects / 객체 목록
         */
        private fun objects(a: JSONArray?): List<JSONObject> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

        /**
         * mode_specific 을 "라벨 → 값" 표시 라인으로 평탄화한다.
         * Flattens mode_specific into "label → value" display lines.
         *
         * @param o object / 객체
         * @return display pairs / 표시용 쌍
         */
        private fun flatten(o: JSONObject): List<Pair<String, String>> =
            o.keys().asSequence().map { k ->
                val label = KEY_LABELS[k] ?: k                                         // Korean label / 한글 라벨
                val v = o.opt(k)                                                       // Value / 값
                val text = when (v) {
                    is JSONArray -> (0 until v.length()).joinToString("\n") { i -> "• " + render(v.opt(i)) } // List / 목록
                    is Boolean -> if (v) "예" else "아니오"                              // Bool / 불리언
                    else -> render(v)                                                  // Scalar / 스칼라
                }
                label to text
            }.toList()

        /**
         * 중첩 값을 읽기 쉬운 문자열로 바꾼다.
         * Renders nested values as readable text.
         *
         * @param v value / 값
         * @return text / 문자열
         */
        private fun render(v: Any?): String = when (v) {
            is JSONObject -> v.keys().asSequence().joinToString(" · ") { k -> "${KEY_LABELS[k] ?: k}: ${render(v.opt(k))}" }
            is JSONArray -> (0 until v.length()).joinToString(", ") { render(v.opt(it)) }
            null, JSONObject.NULL -> "-"
            else -> v.toString()
        }
    }
}
