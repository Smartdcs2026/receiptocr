package com.receiptocr.app.ocr

import com.receiptocr.app.config.PosIdentityMapping
import com.receiptocr.app.config.PosIdentityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDrivenPosIdentityRound10418Test {
    private fun rule(lastWorkPos: Int = 0) = PosIdentityRule(
        enabled = true,
        allowedPrefixes = listOf("N", "B"),
        lastWorkPosPrefixes = listOf("B"),
        mappings = listOf(
            PosIdentityMapping("N01", workPos = 1),
            PosIdentityMapping("N02", workPos = 2)
        ),
        runtimeLastWorkPos = lastWorkPos
    )

    @Test fun profile_fallback_b01_uses_last_store_pos() {
        assertEquals(3, RuleDrivenOcrEngine.resolveSpatialPosCandidate("B01", rule(), listOf(1, 2, 3)))
    }

    @Test fun profile_fallback_801_recovers_b01_with_store_context() {
        assertEquals(3, RuleDrivenOcrEngine.resolveSpatialPosCandidate("801", rule(), listOf(1, 2, 3)))
        assertEquals(5, RuleDrivenOcrEngine.resolveSpatialPosCandidate("8O1", rule(), listOf(1, 2, 3, 4, 5)))
    }

    @Test fun profile_fallback_preserves_real_numeric_pos_8_and_801() {
        assertEquals(8, RuleDrivenOcrEngine.resolveSpatialPosCandidate("8", rule(), listOf(1, 2, 3, 8)))
        assertEquals(801, RuleDrivenOcrEngine.resolveSpatialPosCandidate("801", rule(), listOf(1, 2, 3, 801)))
    }

    @Test fun prefixed_candidate_extraction_keeps_letters_for_brand_mapping() {
        val values = RuleDrivenOcrEngine.spatialPosIdentityCandidates("BNO:S26080652 B01-004184 N02")
        assertTrue(values.contains("B01"))
        assertTrue(values.contains("N02"))
    }

    @Test fun standalone_ambiguous_numeric_candidate_reaches_contextual_resolver() {
        assertEquals(listOf("801"), RuleDrivenOcrEngine.spatialPosIdentityCandidates("801"))
        assertEquals(4, RuleDrivenOcrEngine.resolveSpatialPosCandidate("801", rule(), listOf(1, 2, 3, 4)))
    }
}
