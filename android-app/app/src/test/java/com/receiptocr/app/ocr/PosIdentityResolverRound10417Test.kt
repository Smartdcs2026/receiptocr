package com.receiptocr.app.ocr

import com.receiptocr.app.config.PosIdentityMapping
import com.receiptocr.app.config.PosIdentityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun ocr_801_is_recovered_as_b01_when_store_has_no_pos_801() {
        val result = PosIdentityResolver.resolve("801", prefixRule(), listOf(1, 2, 3))
        assertEquals("B01", result?.display)
        assertEquals("B1", result?.key)
        assertEquals(3, result?.workPos)
        assertTrue(result?.mappedByBrandRule == true)
    }

    @Test fun ocr_8o1_is_recovered_as_b01_with_digit_normalization() {
        val result = PosIdentityResolver.resolve("8O1", prefixRule(), listOf(1, 2, 3, 4, 5))
        assertEquals("B01", result?.display)
        assertEquals(5, result?.workPos)
    }

    @Test fun genuine_numeric_801_is_preserved_when_store_has_pos_801() {
        val result = PosIdentityResolver.resolve("801", prefixRule(), listOf(1, 2, 3, 801))
        assertEquals("801", result?.display)
        assertEquals(801, result?.workPos)
        assertFalse(result?.mappedByBrandRule == true)
    }

    @Test fun single_digit_8_is_never_changed_to_b() {
        val result = PosIdentityResolver.resolve("8", prefixRule(), listOf(1, 2, 3, 8))
        assertEquals("8", result?.display)
        assertEquals(8, result?.workPos)
        assertFalse(result?.mappedByBrandRule == true)
    }

    @Test fun numeric_value_is_not_recovered_without_store_context() {
        val result = PosIdentityResolver.resolve("801", prefixRule())
        assertEquals("801", result?.display)
        assertEquals(801, result?.workPos)
        assertFalse(result?.mappedByBrandRule == true)
    }

    @Test fun runtime_last_pos_supports_interpreter_without_explicit_plan_list() {
        val r = prefixRule(lastWorkPos = 5)
        assertEquals(5, PosIdentityResolver.resolve("B88", r)?.workPos)
    }

    @Test fun ambiguous_8_prefix_can_use_runtime_store_context() {
        val r = prefixRule(lastWorkPos = 5)
        val result = PosIdentityResolver.resolve("801", r)
        assertEquals("B01", result?.display)
        assertEquals(5, result?.workPos)
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
