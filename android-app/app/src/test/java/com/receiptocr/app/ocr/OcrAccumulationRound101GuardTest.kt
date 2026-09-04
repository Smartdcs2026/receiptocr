package com.receiptocr.app.ocr

import com.receiptocr.app.model.PosRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrAccumulationRound101GuardTest {
    @Test
    fun retry_cannot_replace_good_fields_when_only_store_needs_review() {
        val original = listOf(
            PosRecord(
                posNumber = 2,
                customerNo = "0101809",
                billDate = "13/08/2026",
                billTime = "19:00",
                source = "OCR-TEMPLATE",
                ocrWarnings = "บิลผิดร้าน • รหัสร้านบนบิลไม่ตรงกับงาน",
                ocrStoreId = "7600"
            )
        )
        val noisyRetry = listOf(
            original.single().copy(
                customerNo = "0181809",
                billDate = "18/08/2026",
                billTime = "19:08",
                ocrStoreId = "1600",
                ocrSourceImagePath = "same-bill.jpg"
            )
        )

        val result = OcrAccumulationPolicy.merge(original, noisyRetry, emptyList(), setOf(2))
        val kept = result.records.single()

        assertEquals("0101809", kept.customerNo)
        assertEquals("13/08/2026", kept.billDate)
        assertEquals("19:00", kept.billTime)
    }

    @Test
    fun retry_can_fill_only_a_missing_field_without_touching_existing_good_fields() {
        val original = listOf(
            PosRecord(
                posNumber = 3,
                customerNo = "003333",
                billDate = "22/08/2026",
                billTime = "",
                source = "OCR-TEMPLATE"
            )
        )
        val retry = listOf(
            original.single().copy(
                customerNo = "009999",
                billDate = "21/08/2026",
                billTime = "17:50",
                ocrSourceImagePath = "same-bill.jpg"
            )
        )

        val result = OcrAccumulationPolicy.merge(original, retry, emptyList(), setOf(3))
        val merged = result.records.single()

        assertEquals("003333", merged.customerNo)
        assertEquals("22/08/2026", merged.billDate)
        assertEquals("17:50", merged.billTime)
    }
}
