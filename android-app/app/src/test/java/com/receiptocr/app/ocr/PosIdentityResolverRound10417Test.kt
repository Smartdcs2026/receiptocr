package com.receiptocr.app.ocr

import com.receiptocr.app.config.PosIdentityMapping
import com.receiptocr.app.config.PosIdentityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosIdentityResolverRound10417Test {
    private fun rule(lastWorkPos: Int = 0) = PosIdentityRule(
        enabled = true,
        allowedPrefixes = listOf("N", "B"),
        mappings = listOf(
            PosIdentityMapping("N01", workPos = 1),
            PosIdentityMapping("N02", workPos = 2),
            PosIdentityMapping("N03", workPos = 3),
            PosIdentityMapping("B01", useLastWorkPos = true)
        ),
        runtimeLastWorkPos = lastWorkPos
    )

    @Test fun b01_is_last_pos_for_three_pos_store() {
        val r = rule()
        assertEquals(1, PosIdentityResolver.resolve("N01", r, listOf(1, 2, 3))?.workPos)
        assertEquals(2, PosIdentityResolver.resolve("N02", r, listOf(1, 2, 3))?.workPos)
        assertEquals(3, PosIdentityResolver.resolve("B01", r, listOf(1, 2, 3))?.workPos)
    }

    @Test fun b01_is_last_pos_for_four_pos_store() {
        val r = rule()
        assertEquals(4, PosIdentityResolver.resolve("B01", r, listOf(1, 2, 3, 4))?.workPos)
        assertEquals(1, PosIdentityResolver.resolve("N01", r, listOf(1, 2, 3, 4))?.workPos)
    }

    @Test fun runtime_last_pos_supports_interpreter_without_explicit_plan_list() {
        val r = rule(lastWorkPos = 5)
        assertEquals(5, PosIdentityResolver.resolve("B01", r)?.workPos)
    }

    @Test fun last_mapping_does_not_guess_when_store_plan_is_unknown() {
        assertNull(PosIdentityResolver.resolve("B01", rule()))
    }

    @Test fun fixed_mapping_remains_backward_compatible() {
        val fixed = PosIdentityRule(
            enabled = true,
            allowedPrefixes = listOf("N", "B"),
            mappings = listOf(
                PosIdentityMapping("N01", 1),
                PosIdentityMapping("B01", 2)
            )
        )
        assertEquals(1, PosIdentityResolver.resolve("N01", fixed, listOf(1, 2, 3))?.workPos)
        assertEquals(2, PosIdentityResolver.resolve("B01", fixed, listOf(1, 2, 3))?.workPos)
    }
}
