package com.receiptocr.app.ocr

import com.receiptocr.app.config.PosIdentityMapping
import com.receiptocr.app.config.PosIdentityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosIdentityResolverRound10417Test {
    private fun prefixRule(lastWorkPos: Int = 0) = PosIdentityRule(
        enabled = true,
        allowedPrefixes = listOf("N", "B"),
        lastWorkPosPrefixes = listOf("B"),
        mappings = listOf(
            PosIdentityMapping("N01", workPos = 1),
            PosIdentityMapping("N02", workPos = 2),
            PosIdentityMapping("N03", workPos = 3)
        ),
        runtimeLastWorkPos = lastWorkPos
    )

    @Test fun every_b_code_uses_last_pos_for_three_pos_store() {
        val r = prefixRule()
        assertEquals(1, PosIdentityResolver.resolve("N01", r, listOf(1, 2, 3))?.workPos)
        assertEquals(3, PosIdentityResolver.resolve("B01", r, listOf(1, 2, 3))?.workPos)
        assertEquals(3, PosIdentityResolver.resolve("B02", r, listOf(1, 2, 3))?.workPos)
        assertEquals(3, PosIdentityResolver.resolve("B99", r, listOf(1, 2, 3))?.workPos)
    }

    @Test fun every_b_code_uses_last_pos_for_five_pos_store() {
        val r = prefixRule()
        listOf("B01", "B02", "B09", "B99").forEach { code ->
            assertEquals(5, PosIdentityResolver.resolve(code, r, listOf(1, 2, 3, 4, 5))?.workPos)
        }
    }

    @Test fun terminal_prefix_does_not_use_digits_after_b() {
        val r = prefixRule()
        assertEquals(4, PosIdentityResolver.resolve("B01", r, listOf(1, 2, 3, 4))?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("B02", r, listOf(1, 2, 3, 4))?.workPos)
    }

    @Test fun runtime_last_pos_supports_interpreter_without_explicit_plan_list() {
        val r = prefixRule(lastWorkPos = 5)
        assertEquals(5, PosIdentityResolver.resolve("B88", r)?.workPos)
    }

    @Test fun terminal_prefix_does_not_guess_when_store_plan_is_unknown() {
        assertNull(PosIdentityResolver.resolve("B01", prefixRule()))
    }

    @Test fun fixed_mapping_for_other_prefix_remains_supported() {
        val fixed = PosIdentityRule(
            enabled = true,
            allowedPrefixes = listOf("N", "B"),
            lastWorkPosPrefixes = listOf("B"),
            mappings = listOf(PosIdentityMapping("N01", 1), PosIdentityMapping("N02", 2))
        )
        assertEquals(1, PosIdentityResolver.resolve("N01", fixed, listOf(1, 2, 3))?.workPos)
        assertEquals(2, PosIdentityResolver.resolve("N02", fixed, listOf(1, 2, 3))?.workPos)
        assertEquals(3, PosIdentityResolver.resolve("B01", fixed, listOf(1, 2, 3))?.workPos)
    }

    @Test fun legacy_exact_last_mapping_still_works() {
        val legacy = PosIdentityRule(
            enabled = true,
            mappings = listOf(PosIdentityMapping("B01", useLastWorkPos = true))
        )
        assertEquals(3, PosIdentityResolver.resolve("B01", legacy, listOf(1, 2, 3))?.workPos)
    }
}
