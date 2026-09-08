package com.receiptocr.app.ocr

import com.receiptocr.app.config.PosIdentityMapping
import com.receiptocr.app.config.PosIdentityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CjTerminalFallbackRound10420Test {
    private fun cjRule() = PosIdentityRule(
        enabled = true,
        allowedPrefixes = listOf("N", "B"),
        lastWorkPosPrefixes = listOf("B"),
        mappings = listOf(
            PosIdentityMapping("N01", workPos = 1),
            PosIdentityMapping("N02", workPos = 2),
            PosIdentityMapping("N03", workPos = 3)
        ),
        fallbackUnknownToLastWorkPos = true
    )

    @Test
    fun knownNAndBIdentitiesKeepTheirConfiguredMeaning() {
        val available = setOf(1, 2, 3, 4)
        assertEquals(1, PosIdentityResolver.resolve("N01", cjRule(), available)?.workPos)
        assertEquals(2, PosIdentityResolver.resolve("N02", cjRule(), available)?.workPos)
        assertEquals(3, PosIdentityResolver.resolve("N03", cjRule(), available)?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("B01", cjRule(), available)?.workPos)
    }

    @Test
    fun b01OcrVariantsAndUnknownPrefixUseLastStorePos() {
        val available = setOf(1, 2, 3, 4)
        assertEquals(4, PosIdentityResolver.resolve("801", cjRule(), available)?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("8O1", cjRule(), available)?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("8", cjRule(), available)?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("X01", cjRule(), available)?.workPos)
        assertEquals(4, PosEvidenceFusion.resolveEvidencePosIdentity("8", cjRule(), available))
        assertEquals(4, TemplateSequenceFallback.resolveSequencePosIdentity("X01", cjRule(), available))
    }

    @Test
    fun realNumericPosAlwaysWinsBeforeUnknownFallback() {
        val available = setOf(1, 2, 3, 4, 8)
        assertEquals(8, PosIdentityResolver.resolve("8", cjRule(), available)?.workPos)
    }

    @Test
    fun missingMappingInsideAllowedNFamilyIsNotSilentlyMovedToLastPos() {
        assertNull(PosIdentityResolver.resolve("N04", cjRule(), setOf(1, 2, 3, 4)))
    }

    @Test
    fun brandsWithoutFallbackKeepPreviousBehavior() {
        val rule = cjRule().copy(fallbackUnknownToLastWorkPos = false)
        assertEquals(8, PosIdentityResolver.resolve("8", rule, setOf(1, 2, 3, 4))?.workPos)
        assertNull(PosIdentityResolver.resolve("X01", rule, setOf(1, 2, 3, 4)))
    }
}
