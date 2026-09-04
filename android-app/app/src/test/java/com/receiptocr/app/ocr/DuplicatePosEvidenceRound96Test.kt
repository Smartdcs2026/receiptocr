package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.UniversalOcrTemplate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatePosEvidenceRound96Test {
    private val template = UniversalOcrTemplate(
        templateId = "store-pos-round96", brandId = "T", templateName = "StorePos",
        recognition = OcrTemplateRecognition(rows = listOf(OcrTemplateRow(row = 1, fields = listOf(
            OcrTemplateField(1, "STORE_ID", example = "1600", minLength = 4, maxLength = 4),
            OcrTemplateField(2, "POS_NUMBER", example = "1", minLength = 1, maxLength = 1, posDigits = 1),
            OcrTemplateField(3, "CUSTOMER_VALUE", example = "123456", minLength = 6, maxLength = 6),
            OcrTemplateField(4, "BILL_DATE", example = "05/09/26", minLength = 8, maxLength = 8, dateOrder = "DMY", dateCalendar = "GREGORIAN", dateYearDigits = 2),
            OcrTemplateField(5, "BILL_TIME", example = "10:00", minLength = 5, maxLength = 5)
        ))))
    )

    @Test fun sameStoreSamePosTwoPhysicalLinesWarn() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            listOf("1600 1 123456 05/09/26 10:00\n1600 1 654321 05/09/26 10:05"), listOf(template), setOf(1)
        )
        assertTrue(warnings[1]?.contains("พบบิลซ้ำในร้านเดียวกัน") == true)
    }

    @Test fun identicalValuesOnTwoPhysicalLinesStillWarn() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            listOf("1600 1 123456 05/09/26 10:00\n1600 1 123456 05/09/26 10:00"), listOf(template), setOf(1)
        )
        assertTrue(warnings.containsKey(1))
    }

    @Test fun samePosDifferentKnownStoreIsNotDuplicateWarning() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            listOf("1600 1 123456 05/09/26 10:00\n7600 1 654321 05/09/26 10:05"), listOf(template), setOf(1)
        )
        assertFalse(warnings.containsKey(1))
    }

    @Test fun repeatedPassesAreNotPhysicalDuplicates() {
        val raw = "1600 1 123456 05/09/26 10:00"
        assertFalse(DuplicatePosEvidenceDetector.detect(listOf(raw, raw, raw), listOf(template), setOf(1)).containsKey(1))
    }
}
