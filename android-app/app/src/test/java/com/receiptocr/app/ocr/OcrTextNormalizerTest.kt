package com.receiptocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrTextNormalizerTest {
    @Test
    fun readsCommonPosLabelsAndOcrConfusions() {
        val cases = mapOf(
            "POS:NO4" to 4,
            "POS NO.4" to 4,
            "POS: 4" to 4,
            "N04" to 4,
            "B0I" to 1,
            "TERMINAL # O2" to 2
        )

        cases.forEach { (input, expected) ->
            assertEquals(input, expected, OcrTextNormalizer.parsePosNumber(input))
        }
    }

    @Test
    fun readsEveryPosMentionOnOneLine() {
        assertEquals(listOf(1, 2, 4), OcrTextNormalizer.findPosNumbers("POS:1 N02 POS:NO4"))
    }

    @Test
    fun readsFivePosMachinesWithoutAssumingFour() {
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            OcrTextNormalizer.findPosNumbers("N01 N02 N03 N04 N05")
        )
    }

    @Test
    fun standaloneParserDoesNotTreatDatesAsPos() {
        assertNull(OcrTextNormalizer.parseStandalonePosNumber("24/08/2026"))
        assertEquals(4, OcrTextNormalizer.parseStandalonePosNumber("N04"))
    }
}
