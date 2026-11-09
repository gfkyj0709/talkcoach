package com.talkcoach.core

import com.talkcoach.core.parser.KakaoChatParser
import com.talkcoach.core.parser.KakaoFormat
import com.talkcoach.core.parser.MessageType
import com.talkcoach.core.prompt.AnalysisMode
import com.talkcoach.core.prompt.ContactContext
import com.talkcoach.core.prompt.MemoryItem
import com.talkcoach.core.prompt.PromptBuilder
import com.talkcoach.core.privacy.MaskStyle
import com.talkcoach.core.privacy.PrivacyMasker
import com.talkcoach.core.style.SpeechStyleAnalyzer

/**
 * JUnit 없이 실행 가능한 스모크 테스트 (CI 에서는 JUnit 로 이관).
 * Smoke test runnable without JUnit (migrate to JUnit in CI).
 */
object CoreSmokeTest {

    /** Failure counter / 실패 카운터 */
    var failed = 0

    /**
     * 조건을 검사하고 결과를 출력한다.
     * Checks a condition and prints the result.
     *
     * @param name test name / 테스트명
     * @param ok   condition / 조건
     * @param info extra info on failure / 실패 시 추가 정보
     */
    private fun check(name: String, ok: Boolean, info: Any? = null) {
        println((if (ok) "PASS " else "FAIL ") + name + if (!ok) " → $info" else "")
        if (!ok) failed++
    }

    /**
     * 진입점.
     * Entry point.
     *
     * @param args unused / 미사용
     */
    @JvmStatic
    fun main(args: Array<String>) {
        // ── Android export ──
        val android = """
            김지수 님과 카카오톡 대화
            저장한 날짜 : 2026-11-08 22:10:03

            2026년 11월 7일 토요일
            2026년 11월 7일 오후 9:58, 김지수 : 오늘 너무 피곤하다 ㅠㅠ
            2026년 11월 7일 오후 10:01, 권영진 : 고생했어요 ㅋㅋㅋ
            푹 쉬어요~
            2026년 11월 7일 오후 10:02, 김지수 : 사진
            2026년 11월 7일 오후 10:03, 김지수님이 나갔습니다.
            2026년 11월 8일 오전 12:15, 김지수 : 내일 시간 돼요? 010-1234-5678 로 연락줘요
            2026년 11월 8일 오후 12:30, 권영진 : 삭제된 메시지입니다.
        """.trimIndent()
        val a = KakaoChatParser.parse(android)
        check("android format", a.format == KakaoFormat.ANDROID, a.format)
        check("android count", a.messages.size == 5, a.messages)
        check("android multiline", a.messages[1].content == "고생했어요 ㅋㅋㅋ\n푹 쉬어요~", a.messages[1].content)
        check("android system filtered", a.skippedSystemLines == 1, a.skippedSystemLines)
        check("android media", a.messages[2].type == MessageType.MEDIA)
        check("android deleted", a.messages[4].type == MessageType.DELETED)
        check("android 12AM → 00h", a.messages[3].sentAt.hour == 0, a.messages[3].sentAt)
        check("android 12PM → 12h", a.messages[4].sentAt.hour == 12, a.messages[4].sentAt)
        check("android self guess", a.guessSelfName() == "권영진", a.guessSelfName())

        // ── iOS export ──
        val ios = """
            2026. 11. 7. 오후 9:58, 김지수 : 안녕하세요
            2026. 11. 7. 오후 10:01, 권영진 : 네 반가워요 : ) 시간 괜찮아요
        """.trimIndent()
        val i = KakaoChatParser.parse(ios)
        check("ios format", i.format == KakaoFormat.IOS, i.format)
        check("ios colon in body", i.messages[1].content == "네 반가워요 : ) 시간 괜찮아요", i.messages[1].content)

        // ── Bracket (PC / copied) ──
        val pc = """
            --------------- 2026년 11월 7일 토요일 ---------------
            [김지수] [오후 9:58] 오늘 회식 어땠어?
            [권영진] [오후 10:01] 그냥 그랬어 ㅋㅋ
            진짜 피곤
            박민수님이 들어왔습니다.
            --------------- 2026년 11월 8일 일요일 ---------------
            [김지수] [오전 8:00] 잘 잤어?
        """.trimIndent()
        val p = KakaoChatParser.parse(pc)
        check("bracket format", p.format == KakaoFormat.BRACKET, p.format)
        check("bracket count", p.messages.size == 3, p.messages)
        check("bracket date", p.messages[2].sentAt.toString() == "2026-11-08T08:00", p.messages[2].sentAt)
        check("bracket multiline", p.messages[1].content == "그냥 그랬어 ㅋㅋ\n진짜 피곤", p.messages[1].content)
        check("bracket system", p.skippedSystemLines == 1, p.skippedSystemLines)

        // ── Masking: must mask ──
        val m = PrivacyMasker(MaskStyle.LABELED)
        val mustMask = mapOf(
            "번호 010-1234-5678 이야" to "전화번호",
            "01012345678로 줘" to "전화번호",
            "사무실 02-123-4567" to "전화번호",
            "주민번호 900101-1234567" to "주민번호",
            "9001011234567" to "주민번호",
            "카드 1234-5678-9012-3456" to "카드번호",
            "국민은행 123456-01-123456 으로 보내" to "계좌번호",
            "신한 110123456789" to "계좌번호",
            "계좌: 3333-01-1234567" to "계좌번호",
            "a.b@gmail.com 으로" to "이메일",
            "테헤란로 152 로 와" to "주소",
            "강남대로 123-4" to "주소",
            "101동 1203호" to "주소",
            "역삼동 123-45번지" to "주소"
        )
        mustMask.forEach { (src, label) ->
            val r = m.mask(src).text
            check("mask[$label] $src", r.contains("[$label***]"), r)
        }

        // ── Masking: must NOT mask (analysis-relevant numbers) ──
        val keep = listOf(
            "2026-11-08 에 보자", "오후 3:30 어때", "집으로 3시까지 갈게", "학교로 5명 와",
            "택시로 10분", "3만원 보냈어", "20,000원", "1층 로비", "2시로 바꾸자", "3번 출구"
        )
        keep.forEach { src -> check("keep $src", m.mask(src).total == 0, m.mask(src).text) }

        // ── Style ──
        val style = SpeechStyleAnalyzer.analyze(
            List(10) { "고생했어요 ㅋㅋㅋ" } + List(10) { "내일 봐요~" } + List(5) { "알겠어" }
        )
        check("style polite ratio", style.politeRatio in 0.79..0.81, style.politeRatio)
        check("style laugh length", style.avgLaughLength == 3.0, style.avgLaughLength)
        println(style.toPromptGuide())

        // ── Prompt ──
        val msgs = KakaoChatParser.resolveRoles(a.messages, "권영진")
        val req = PromptBuilder(PrivacyMasker()).build(
            AnalysisMode.INTEREST,
            ContactContext("썸", "ENFP", listOf("감성적"), null),
            listOf(MemoryItem(3, "2026-11-01", "EMOTION", "약속 변경으로 서운해함", 4)),
            style, msgs
        )
        check("prompt no phone", !req.userPrompt.contains("5678"), req.userPrompt)
        check("prompt pseudonym", !req.userPrompt.contains("김지수") && req.userPrompt.contains("상대:"), req.userPrompt)
        check("prompt placeholders filled", !req.systemPrompt.contains("{{"))
        println("---- user prompt ----\n" + req.userPrompt)

        println(if (failed == 0) "\nALL PASSED" else "\nFAILED: $failed")
    }
}
