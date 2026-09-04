package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.PosIdentityMapping
import com.receiptocr.app.config.PosIdentityRule
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
                        OcrTemplateField(3, "POS_NUMBER", example = "1", minLength = 1, maxLength = 3, posDigits = 1),
                        OcrTemplateField(4, "CUSTOMER_VALUE", example = "657846", minLength = 6, maxLength = 7),
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
    fun onePhysicalReceiptWithDifferentPassInterpretationsIsNotDuplicate() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf(
                "R2020101809U110030 13/08/69 19:00",
                "R2020101809U110030 13/08/69 19:08"
            ),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(2))
    }

    @Test
    fun twoDifferentReceiptsForSamePosOnSeparateLinesInOnePassWarn() {
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
    fun differentSignaturesSeenOnlyInDifferentPassesDoNotProveDuplicate() {
        val first = "R201657846U110030 20/08/69 17:51"
        val second = "R201111222U110030 21/08/69 09:05"
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf(first, second, first, second),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(1))
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

    @Test
    fun differentTemplatesDoNotCreateFalseDuplicateForSamePos() {
        val second = mb.copy(
            templateId = "mb-alt-test",
            templateName = "Mb_alt",
            recognition = OcrTemplateRecognition(
                rows = listOf(OcrTemplateRow(row = 1, fields = listOf(
                    OcrTemplateField(1, "LITERAL", example = "X", literal = "X"),
                    OcrTemplateField(2, "NUMBER_TEXT", example = "20", minLength = 2, maxLength = 2),
                    OcrTemplateField(3, "POS_NUMBER", example = "1", minLength = 1, maxLength = 1, posDigits = 1),
                    OcrTemplateField(4, "CUSTOMER_VALUE", example = "111222", minLength = 6, maxLength = 6),
                    OcrTemplateField(5, "EMPLOYEE_CODE", example = "U110030", minLength = 7, maxLength = 7),
                    OcrTemplateField(6, "BILL_DATE", example = "20-08-69", minLength = 8, maxLength = 8, dateOrder = "DMY", dateCalendar = "BUDDHIST", dateYearDigits = 2),
                    OcrTemplateField(7, "BILL_TIME", example = "09:05", minLength = 5, maxLength = 5)
                )))
            )
        )
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf("R201657846U110030 20/08/69 17:51\nX201111222U110030 20-08-69 09:05"),
            templates = listOf(mb, second),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(1))
    }

    @Test
    fun separatorVariationAcrossPassesIsSameReceipt() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf("R201657846U110030 20/08/69 17:51", "R201657846U110030 20-08-69 17.51"),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(1))
    }

    @Test
    fun mappedN01AndB01AreDifferentWorkPosAndNotDuplicate() {
        val prefixed = UniversalOcrTemplate(
            templateId = "prefix-test",
            brandId = "P",
            templateName = "Prefix",
            recognition = OcrTemplateRecognition(
                rows = listOf(OcrTemplateRow(row = 1, fields = listOf(
                    OcrTemplateField(1, "POS_NUMBER", example = "N01", minLength = 3, maxLength = 3),
                    OcrTemplateField(2, "CUSTOMER_VALUE", example = "123456", minLength = 6, maxLength = 6),
                    OcrTemplateField(3, "BILL_DATE", example = "03/09/26", minLength = 8, maxLength = 8, dateOrder = "DMY", dateCalendar = "GREGORIAN", dateYearDigits = 2),
                    OcrTemplateField(4, "BILL_TIME", example = "10:00", minLength = 5, maxLength = 5)
                )))
            )
        )
        val rule = PosIdentityRule(
            enabled = true,
            allowedPrefixes = listOf("N", "B"),
            mappings = listOf(PosIdentityMapping("N01", 1), PosIdentityMapping("B01", 2))
        )
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf("N01 123456 03/09/26 10:00\nB01 654321 03/09/26 10:05"),
            templates = listOf(prefixed),
            allowedPos = setOf(1, 2),
            posIdentityRule = rule
        )
        assertFalse(warnings.containsKey(1))
        assertFalse(warnings.containsKey(2))
    }
}
