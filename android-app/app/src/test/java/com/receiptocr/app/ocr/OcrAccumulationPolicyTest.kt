package com.receiptocr.app.ocr

import com.receiptocr.app.model.PosRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrAccumulationPolicyTest {
    private fun blank(pos: Int) = PosRecord(posNumber = pos)

    @Test
    fun secondImageFillsOnlyMissingPos() {
        val original = listOf(
            PosRecord(1, "001111", "22/08/2026", "10:00", source = "OCR-TEMPLATE"),
            PosRecord(2, "002222", "22/08/2026", "11:00", source = "OCR-TEMPLATE"),
            PosRecord(3, "003333", "22/08/2026", "12:00", source = "OCR-TEMPLATE"),
            blank(4)
        )
        val candidate = original.map {
            if (it.posNumber == 4) it.copy(
                customerNo = "004444", billDate = "22/08/2026", billTime = "13:00",
                source = "OCR-TEMPLATE", ocrSourceImagePath = "bill-2.jpg"
            ) else it
        }

        val result = OcrAccumulationPolicy.merge(original, candidate, emptyList(), setOf(4))

        assertEquals("001111", result.records[0].customerNo)
        assertEquals("004444", result.records[3].customerNo)
        assertEquals(setOf(4), result.improvedPos)
        assertTrue(result.conflictsByPos.isEmpty())
    }

    @Test
    fun laterImageCanRepairWarnedOcrField() {
        val original = listOf(
            PosRecord(
                2, "002766", "20/28/2026", "15:33",
                source = "OCR-TEMPLATE",
                ocrWarnings = "วันที่อ่านได้ไม่ถูกต้อง"
            )
        )
        val candidate = listOf(
            original[0].copy(billDate = "20/08/2026", source = "OCR-TEMPLATE", ocrSourceImagePath = "bill-3.jpg")
        )

        val result = OcrAccumulationPolicy.merge(original, candidate, emptyList(), setOf(2))

        assertEquals("20/08/2026", result.records.single().billDate)
        assertEquals("", result.records.single().ocrWarnings)
        assertEquals(setOf(2), result.improvedPos)
    }

    @Test
    fun laterImageDoesNotSilentlyOverwriteGoodOcr() {
        val original = listOf(
            PosRecord(1, "001111", "22/08/2026", "10:00", source = "OCR-TEMPLATE")
        )
        val candidate = listOf(
            original[0].copy(customerNo = "009999", billTime = "19:30", ocrSourceImagePath = "bill-2.jpg")
        )

        val result = OcrAccumulationPolicy.merge(original, candidate, emptyList(), setOf(1))

        assertEquals("001111", result.records.single().customerNo)
        assertEquals("10:00", result.records.single().billTime)
        assertTrue(result.conflictsByPos.containsKey(1))
    }

    @Test
    fun manualValueIsNeverOverwrittenByOcr() {
        val original = listOf(PosRecord(1, "001111", "22/08/2026", "10:00", source = "MANUAL"))
        val candidate = listOf(original[0].copy(customerNo = "009999", source = "OCR-TEMPLATE"))

        val result = OcrAccumulationPolicy.merge(original, candidate, emptyList(), setOf(1))

        assertEquals("001111", result.records.single().customerNo)
    }
}
