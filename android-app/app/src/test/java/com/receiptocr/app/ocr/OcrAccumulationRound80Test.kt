package com.receiptocr.app.ocr

import com.receiptocr.app.model.PosRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrAccumulationRound80Test {
    @Test
    fun actualPosNumberCanFillUnusedSlot() {
        val originals = listOf(PosRecord(1), PosRecord(2))
        val detected = listOf(
            PosRecord(101, customerNo = "219931", billDate = "22/08/2026", billTime = "18:37", source = "OCR-TEMPLATE", ocrSourceImagePath = "a.jpg")
        )
        val result = OcrAccumulationPolicy.merge(
            originals = originals,
            templateRecords = detected,
            profileRecords = detected,
            currentDetectedPos = setOf(101)
        )
        assertTrue(result.records.any { it.posNumber == 101 })
        assertEquals("219931", result.records.first { it.posNumber == 101 }.customerNo)
    }
}
