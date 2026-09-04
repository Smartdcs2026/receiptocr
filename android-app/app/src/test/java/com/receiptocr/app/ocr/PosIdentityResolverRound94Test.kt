package com.receiptocr.app.ocr

import com.receiptocr.app.config.PosIdentityMapping
import com.receiptocr.app.config.PosIdentityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosIdentityResolverRound94Test {
    @Test fun round93_numeric_behavior_remains_when_rule_disabled() {
        assertEquals(1, PosIdentityResolver.resolve("N01", PosIdentityRule())?.workPos)
        assertEquals(1, PosIdentityResolver.resolve("B01", PosIdentityRule())?.workPos)
        assertEquals(2, PosIdentityResolver.resolve("C02", PosIdentityRule())?.workPos)
    }

    @Test fun brand_mapping_keeps_same_number_prefixes_as_different_work_pos() {
        val rule = PosIdentityRule(
            enabled = true,
            allowedPrefixes = listOf("N", "B"),
            mappings = listOf(
                PosIdentityMapping("N01", 1),
                PosIdentityMapping("B01", 2)
            )
        )
        assertEquals(1, PosIdentityResolver.resolve("N01", rule)?.workPos)
        assertEquals(2, PosIdentityResolver.resolve("B01", rule)?.workPos)
        assertNull(PosIdentityResolver.resolve("B02", rule))
    }
}
