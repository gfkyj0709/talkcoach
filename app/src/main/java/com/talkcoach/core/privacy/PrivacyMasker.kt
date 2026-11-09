package com.talkcoach.core.privacy

/**
 * 마스킹 대상 개인정보 유형. 선언 순서 = 적용 순서 (긴/구체적 패턴 먼저).
 * PII categories. Declaration order = application order (longer / more specific first).
 *
 * @property label Korean label used in LABELED style / LABELED 모드에서 쓰는 한글 라벨
 */
enum class PiiType(val label: String) {
    /** Resident / alien registration number / 주민·외국인등록번호 */
    RRN("주민번호"),

    /** Card number / 카드번호 */
    CARD("카드번호"),

    /** E-mail address / 이메일 */
    EMAIL("이메일"),

    /** Phone number (mobile, landline, 070/050x) / 전화번호 */
    PHONE("전화번호"),

    /** Bank account number / 계좌번호 */
    ACCOUNT("계좌번호"),

    /** Detailed address (road number, lot number, unit) / 상세주소 */
    ADDRESS("주소")
}

/**
 * 마스킹 표기 방식.
 * How masked values are rendered.
 */
enum class MaskStyle {
    /** Requirement default: "***" / 요구사항 기본값 */
    PLAIN,

    /** "[전화번호***]" — keeps context for the LLM / LLM이 맥락을 이해하도록 유형 표기 */
    LABELED
}

/**
 * 마스킹 결과.
 * Result of masking.
 *
 * @property text   masked text / 마스킹된 텍스트
 * @property counts number of hits per type (for UI notice) / 유형별 마스킹 건수 (UI 안내용)
 */
data class MaskResult(val text: String, val counts: Map<PiiType, Int>) {
    /** Total number of masked items / 전체 마스킹 건수 */
    val total: Int get() = counts.values.sum()
}

/**
 * 외부 LLM 전송 전 기기 내에서 개인정보를 정규식으로 마스킹한다.
 * Masks PII on-device with regex before sending text to an external LLM.
 *
 * 설계 원칙 / Design principles
 * - 오탐(과잉 마스킹)은 허용, 미탐(누출)은 최소화 / Prefer false positives over leaks.
 * - 단, 날짜·시각·금액처럼 대화 분석에 필요한 숫자는 보존 / Keep dates, times and amounts needed for analysis.
 * - 시/구 수준의 대략적 위치는 보존하고 도로명+번지, 동·호수만 가린다 / Keep city/district, mask street number and unit.
 *
 * 정규식만으로는 이름·자유형식 주소를 완벽히 잡을 수 없음 [확인필요: 필요 시 주소 사전/NER 보강].
 * Regex alone cannot catch every name or free-form address [확인필요].
 *
 * @property style masking style / 마스킹 표기 방식
 */
class PrivacyMasker(private val style: MaskStyle = MaskStyle.PLAIN) {

    /**
     * 유형별 규칙. validator 가 false 를 반환하면 해당 매칭은 마스킹하지 않는다.
     * Rule per type. When the validator returns false, the match is left untouched.
     *
     * @property type      PII type / 개인정보 유형
     * @property regex     detection regex / 탐지 정규식
     * @property validator extra check on the matched text / 매칭 문자열 추가 검증
     */
    private data class Rule(val type: PiiType, val regex: Regex, val validator: (String) -> Boolean = { true })

    /** Ordered rule list / 순서가 보장된 규칙 목록 */
    private val rules: List<Rule> = listOf(
        // RRN: YYMMDD-GXXXXXX, gender digit 1–8 (incl. foreigners), hyphen/space optional
        // 주민번호: 생년월일 6자리 + 성별 1~8(외국인 포함) + 6자리, 하이픈/공백 선택
        Rule(
            PiiType.RRN,
            Regex("""(?<!\d)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\s?-?\s?[1-8]\d{6}(?!\d)""")
        ),

        // Card: 4-4-4-4 (or 4-4-4-3 for some cards), separators optional. No Luhn check: a typo must not leak
        // 카드번호: 4-4-4-4(일부 4-4-4-3), 구분자 선택. 오타 번호도 가리기 위해 Luhn 검증은 하지 않음
        Rule(
            PiiType.CARD,
            Regex("""(?<!\d)\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{3,4}(?!\d)""")
        ),

        // E-mail / 이메일
        Rule(
            PiiType.EMAIL,
            Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        ),

        // Phone: mobile 01X, Seoul 02, regional 0[3-6][1-5], internet 070, safe-number 050X
        // 전화번호: 휴대폰 01X, 서울 02, 지역번호 0[3-6][1-5], 인터넷 070, 안심번호 050X
        Rule(
            PiiType.PHONE,
            Regex("""(?<!\d)(01[016789]|02|0[3-6][1-5]|070|050\d)[-.\s)]?\d{3,4}[-.\s]?\d{4}(?!\d)""")
        ),

        // Account (contextual): bank keyword followed by 10–16 digits with optional hyphens
        // 계좌번호(문맥형): 은행명/계좌 키워드 뒤 10~16자리 숫자(하이픈 허용)
        Rule(
            PiiType.ACCOUNT,
            Regex(
                """(?<=(?:은행|뱅크|계좌|국민|신한|우리|하나|농협|기업|카카오|토스|케이|새마을|우체국|수협|신협|SC|씨티|iM|부산|경남|광주|전북|제주)\s{0,3}(?:은행)?\s{0,3}(?:계좌)?\s{0,3}[:：]?\s{0,3})\d[\d-]{8,20}\d"""
            ),
            validator = { it.count(Char::isDigit) in 10..16 }
        ),

        // Account (shape-only): 3+ hyphenated groups with 10–16 digits in total (dates have 8 → excluded)
        // 계좌번호(형태형): 하이픈 3그룹 이상 & 숫자 10~16자리 (날짜는 8자리라 제외)
        Rule(
            PiiType.ACCOUNT,
            Regex("""(?<![\d-])\d{2,6}-\d{2,6}-\d{2,8}(?:-\d{1,3})?(?![\d-])"""),
            validator = { it.count(Char::isDigit) in 10..16 }
        ),

        // Apartment unit: "101동 1203호", "1203호" / 동·호수
        Rule(
            PiiType.ADDRESS,
            Regex("""(?<!\d)\d{1,4}\s?동\s?\d{1,5}\s?호|(?<![\d가-힣])\d{2,5}\s?호(?![가-힣])""")
        ),

        // Road address: "<name>로|길 [N번길] 123(-4)" — excludes "~으로" and numbers followed by units (시/분/명/개/원…)
        // 도로명주소: "OO로|길 [N번길] 123(-4)" — "~으로" 및 단위가 붙은 숫자(시/분/명/개/원…)는 제외
        Rule(
            PiiType.ADDRESS,
            Regex("""(?<![가-힣])[가-힣\d]{1,12}(?<!으)(로|길)(\s?\d{1,4}번?길)?\s?\d{1,4}(-\d{1,4})?(?![\d.:]|\s?[시분초명개원번살층차년월일주회배km])""")
        ),

        // Lot address: "<동|리|가> 123-4번지" (requires 번지 or hyphen to avoid false hits)
        // 지번주소: "OO동|리|가 123-4번지" (오탐 방지를 위해 '번지' 또는 하이픈 필수)
        Rule(
            PiiType.ADDRESS,
            Regex("""[가-힣\d]{1,10}(동|리|가)\s?\d{1,5}(-\d{1,5}\s?(번지)?|\s?번지)""")
        )
    )

    /**
     * 텍스트 하나를 마스킹한다.
     * Masks a single text.
     *
     * @param input original text / 원본 텍스트
     * @return masked text and hit counts / 마스킹된 텍스트와 유형별 건수
     */
    fun mask(input: String): MaskResult {
        var text = input                                    // Working copy / 작업 사본
        val counts = linkedMapOf<PiiType, Int>()            // Hit counter / 건수 집계

        for (rule in rules) {
            text = rule.regex.replace(text) { m ->
                if (!rule.validator(m.value)) return@replace m.value          // Not PII → keep / 개인정보 아님 → 유지
                counts[rule.type] = (counts[rule.type] ?: 0) + 1             // Count hit / 건수 증가
                token(rule.type)                                             // Replace / 치환
            }
        }
        return MaskResult(text, counts)
    }

    /**
     * 여러 메시지를 마스킹하고 전체 건수를 합산한다.
     * Masks many messages and sums the counts.
     *
     * @param inputs original texts / 원본 텍스트 목록
     * @return masked texts (same order) and total counts / 마스킹된 텍스트(동일 순서)와 합산 건수
     */
    fun maskAll(inputs: List<String>): Pair<List<String>, Map<PiiType, Int>> {
        val total = linkedMapOf<PiiType, Int>()                              // Total counter / 합산 집계
        val out = inputs.map { s ->
            val r = mask(s)                                                  // Mask one / 단건 마스킹
            r.counts.forEach { (k, v) -> total[k] = (total[k] ?: 0) + v }    // Merge counts / 건수 합산
            r.text                                                           // Masked text / 결과 텍스트
        }
        return out to total
    }

    /**
     * 유형별 치환 토큰을 만든다.
     * Builds the replacement token for a type.
     *
     * @param type PII type / 개인정보 유형
     * @return replacement string / 치환 문자열
     */
    private fun token(type: PiiType): String = when (style) {
        MaskStyle.PLAIN -> "***"                       // Requirement default / 요구사항 기본
        MaskStyle.LABELED -> "[${type.label}***]"      // Labeled for LLM context / LLM 맥락용 라벨
    }
}
