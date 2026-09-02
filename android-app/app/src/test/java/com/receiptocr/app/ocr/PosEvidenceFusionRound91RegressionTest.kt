package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRequiredCore
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.OcrTemplateValidation
import com.receiptocr.app.config.UniversalOcrTemplate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosEvidenceFusionRound91RegressionTest {

    private fun template(
        id: String,
        name: String,
        fields: List<OcrTemplateField>
    ) = UniversalOcrTemplate(
        templateId = id,
        brandId = "round91-regression",
        templateName = name,
        recognition = OcrTemplateRecognition(
            rowCount = 1,
            rows = listOf(OcrTemplateRow(row = 1, fields = fields))
        ),
        validation = OcrTemplateValidation(
            requiredCore = OcrTemplateRequiredCore(date = true, time = true, customerValue = true)
        )
    )

    @Test
    fun mdyGregorianTemplateStillReadsAfterMb02RecoveryChanges() {
        val mb01Like = template(
            id = "mb01-like-r91",
            name = "Mb_01-like",
            fields = listOf(
                OcrTemplateField(
                    order = 1,
                    type = "BILL_DATE",
                    example = "08-21-2026",
                    dateOrder = "MDY",
                    dateCalendar = "GREGORIAN",
                    dateYearDigits = 4
                ),
                OcrTemplateField(order = 2, type = "BILL_TIME", example = "12:33"),
                OcrTemplateField(order = 3, type = "LITERAL", example = "Rcpt", literal = "Rcpt"),
                OcrTemplateField(order = 4, type = "POS_NUMBER", example = "1", minLength = 1, maxLength = 1, posDigits = 1),
                OcrTemplateField(order = 5, type = "CUSTOMER_VALUE", example = "003648", minLength = 6, maxLength = 6)
            )
        )

        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "08-21-2026 12:33 Rcpt1003648",
                "08-21-2026 12:33 Rcpt1003648",
                "08-21-2026 12:33 Rcpt1003648"
            ),
            template = mb01Like,
            allowedPos = setOf(1, 2, 3),
            referenceDate = LocalDate.of(2026, 8, 21)
        )

        assertTrue(result.containsKey(1))
        assertEquals("003648", result.getValue(1)["CUSTOMER_VALUE"])
        assertEquals("21/08/2026", result.getValue(1)["BILL_DATE"])
        assertEquals("12:33", result.getValue(1)["BILL_TIME"])
    }

    @Test
    fun buddhistFourDigitTemplateUsesItsOwnAdminDateRules() {
        val otherBrand = template(
            id = "other-dmy-be4-r91",
            name = "Other DMY BE4",
            fields = listOf(
                OcrTemplateField(order = 1, type = "LITERAL", example = "X", literal = "X"),
                OcrTemplateField(order = 2, type = "POS_NUMBER", example = "2", minLength = 1, maxLength = 1, posDigits = 1),
                OcrTemplateField(order = 3, type = "CUSTOMER_VALUE", example = "654321", minLength = 6, maxLength = 6),
                OcrTemplateField(
                    order = 4,
                    type = "BILL_DATE",
                    example = "31/08/2569",
                    dateOrder = "DMY",
                    dateCalendar = "BUDDHIST",
                    dateYearDigits = 4
                ),
                OcrTemplateField(order = 5, type = "BILL_TIME", example = "09:45")
            )
        )

        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "X2654321 31/08/2569 09:45",
                "X2654321 31/08/2569 09:45",
                "X2654321 31/08/2569 09:45"
            ),
            template = otherBrand,
            allowedPos = setOf(1, 2, 3),
            referenceDate = LocalDate.of(2026, 8, 31)
        )

        assertTrue(result.containsKey(2))
        assertEquals("654321", result.getValue(2)["CUSTOMER_VALUE"])
        assertEquals("31/08/2026", result.getValue(2)["BILL_DATE"])
        assertEquals("09:45", result.getValue(2)["BILL_TIME"])
    }

    @Test
    fun customerLengthFromAdminRemainsStrict() {
        val strict = template(
            id = "strict-length-r91",
            name = "Strict length",
            fields = listOf(
                OcrTemplateField(order = 1, type = "LITERAL", example = "R", literal = "R"),
                OcrTemplateField(order = 2, type = "NUMBER_TEXT", example = "20", minLength = 2, maxLength = 2),
                OcrTemplateField(order = 3, type = "POS_NUMBER", example = "1", minLength = 1, maxLength = 1, posDigits = 1),
                OcrTemplateField(order = 4, type = "CUSTOMER_VALUE", example = "123456", minLength = 6, maxLength = 6),
                OcrTemplateField(order = 5, type = "LITERAL", example = "U", literal = "U"),
                OcrTemplateField(order = 6, type = "NUMBER_TEXT", example = "400072", minLength = 6, maxLength = 6),
                OcrTemplateField(
                    order = 7,
                    type = "BILL_DATE",
                    example = "20/08/69",
                    dateOrder = "DMY",
                    dateCalendar = "BUDDHIST",
                    dateYearDigits = 2
                ),
                OcrTemplateField(order = 8, type = "BILL_TIME", example = "17:18")
            )
        )

        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R2012345U400072 20/08/69 17:18",
                "R2012345U400072 20/08/69 17:18",
                "R2012345U400072 20/08/69 17:18"
            ),
            template = strict,
            allowedPos = setOf(1, 2, 3),
            referenceDate = LocalDate.of(2026, 8, 20)
        )

        assertFalse("5-digit customer must not be shifted into a 6-digit Admin slot", result.containsKey(1))
    }

    @Test
    fun uOcrConfusionDoesNotRelaxUnrelatedFixedLiteral() {
        val unrelatedLiteral = template(
            id = "literal-k-r91",
            name = "Literal K",
            fields = listOf(
                OcrTemplateField(order = 1, type = "LITERAL", example = "R", literal = "R"),
                OcrTemplateField(order = 2, type = "POS_NUMBER", example = "2", minLength = 1, maxLength = 1, posDigits = 1),
                OcrTemplateField(order = 3, type = "CUSTOMER_VALUE", example = "039030", minLength = 6, maxLength = 6),
                OcrTemplateField(order = 4, type = "LITERAL", example = "K", literal = "K"),
                OcrTemplateField(order = 5, type = "NUMBER_TEXT", example = "400072", minLength = 6, maxLength = 6),
                OcrTemplateField(
                    order = 6,
                    type = "BILL_DATE",
                    example = "20/08/69",
                    dateOrder = "DMY",
                    dateCalendar = "BUDDHIST",
                    dateYearDigits = 2
                ),
                OcrTemplateField(order = 7, type = "BILL_TIME", example = "17:18")
            )
        )

        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R20390300400072 20/08/69 17:18",
                "R20390300400072 20/08/69 17:18",
                "R20390300400072 20/08/69 17:18"
            ),
            template = unrelatedLiteral,
            allowedPos = setOf(1, 2, 3),
            referenceDate = LocalDate.of(2026, 8, 20)
        )

        assertFalse("O/0 recovery for literal U must not make unrelated literal K optional", result.containsKey(2))
    }

    @Test
    fun onePosCannotBorrowMissingDateFromAnotherPos() {
        val twoPosTemplate = template(
            id = "two-pos-isolation-r91",
            name = "Two POS isolation",
            fields = listOf(
                OcrTemplateField(order = 1, type = "LITERAL", example = "R", literal = "R"),
                OcrTemplateField(order = 2, type = "POS_NUMBER", example = "1", minLength = 1, maxLength = 1, posDigits = 1),
                OcrTemplateField(order = 3, type = "CUSTOMER_VALUE", example = "111111", minLength = 6, maxLength = 6),
                OcrTemplateField(
                    order = 4,
                    type = "BILL_DATE",
                    example = "20/08/69",
                    dateOrder = "DMY",
                    dateCalendar = "BUDDHIST",
                    dateYearDigits = 2
                ),
                OcrTemplateField(order = 5, type = "BILL_TIME", example = "17:18")
            )
        )

        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R1111111 20/08/69 17:18 R2222222 17:19",
                "R1111111 20/08/69 17:18 R2222222 17:19",
                "R1111111 20/08/69 17:18 R2222222 17:19"
            ),
            template = twoPosTemplate,
            allowedPos = setOf(1, 2),
            referenceDate = LocalDate.of(2026, 8, 20)
        )

        assertTrue(result.containsKey(1))
        assertFalse("POS2 without its own date must not borrow POS1 date", result.containsKey(2))
    }
}
