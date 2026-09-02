package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.UniversalOcrTemplate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatePosEvidenceDetectorTest {
    private val mb = UniversalOcrTemplate(
        templateId = "mb-02-test",
        brandId = "MB",
        templateName = "Mb_02",
        recognition = OcrTemplateRecognition(
            rows = listOf(
                OcrTemplateRow(
                    row = 1,
                    fields = listOf(
                        OcrTemplateField(1, "LITERAL", example = "R", literal = "R"),
                        OcrTemplateField(2, "NUMBER_TEXT", example = "20", minLength = 2, maxLength = 2),
                        OcrTemplateField(3, "POS_NUMBER", example = "1", minLength = 1, maxLength = 1, posDigits = 1),
                        OcrTemplateField(4, "CUSTOMER_VALUE", example = "657846", minLength = 6, maxLength = 6),
                        OcrTemplateField(5, "EMPLOYEE_CODE", example = "U110030", minLength = 7, maxLength = 7),
                        OcrTemplateField(
                            6, "BILL_DATE", example = "20/08/69", minLength = 8, maxLength = 8,
                            dateOrder = "DMY", dateCalendar = "BUDDHIST", dateYearDigits = 2
                        ),
                        OcrTemplateField(7, "BILL_TIME", example = "17:51", minLength = 5, maxLength = 5)
                    )
                )
            )
        )
    )

    @Test
    fun sameReceiptRepeatedAcrossOcrPassesIsNotDuplicatePos() {
        val raw = "R201657846U110030 20/08/69 17:51"
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf(raw, raw, raw),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(1))
    }

    @Test
    fun twoDifferentReceiptsForSamePosInOnePassAlwaysWarn() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf(
                "R201657846U110030 20/08/69 17:51\n" +
                    "R201111222U110030 21/08/69 09:05"
            ),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertTrue(warnings.containsKey(1))
    }

    @Test
    fun twoDifferentReceiptsRepeatedAcrossPassesWarn() {
        val first = "R201657846U110030 20/08/69 17:51"
        val second = "R201111222U110030 21/08/69 09:05"
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf(first, second, first, second),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertTrue(warnings.containsKey(1))
    }

    @Test
    fun differentPosAreNotTreatedAsDuplicate() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf(
                "R201657846U110030 20/08/69 17:51\n" +
                    "R202039030U400072 20/08/69 17:18"
            ),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(1))
        assertFalse(warnings.containsKey(2))
    }
}
