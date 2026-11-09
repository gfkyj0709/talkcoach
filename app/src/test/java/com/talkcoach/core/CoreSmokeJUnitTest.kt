package com.talkcoach.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CI 에서 스모크 테스트를 JUnit 으로 실행한다.
 * Runs the smoke test under JUnit in CI.
 */
class CoreSmokeJUnitTest {

    /**
     * 모든 파서·마스킹·프롬프트 검사가 통과해야 한다.
     * All parser, masking and prompt checks must pass.
     */
    @Test
    fun allChecksPass() {
        CoreSmokeTest.main(emptyArray())                 // Run checks / 검사 실행
        assertEquals(0, CoreSmokeTest.failed)            // No failures / 실패 0건
    }
}
