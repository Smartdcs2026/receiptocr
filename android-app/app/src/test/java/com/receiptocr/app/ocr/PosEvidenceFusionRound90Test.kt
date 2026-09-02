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

class PosEvidenceFusionRound90Test {
    private val mb02 = UniversalOcrTemplate(
        templateId = "mb02-r90",
        brandId = "brand-test",
        templateName = "Mb_02",
        recognition = OcrTemplateRecognition(
            rowCount = 1,
            rows = listOf(
                OcrTemplateRow(
                    row = 1,
                    fields = listOf(
                        OcrTemplateField(order = 1, type = "LITERAL", example = "R", literal = "R"),
                        OcrTemplateField(order = 2, type = "NUMBER_TEXT", example = "20", minLength = 2, maxLength = 2),
                        OcrTemplateField(order = 3, type = "POS_NUMBER", example = "1", minLength = 1, maxLength = 1, posDigits = 1),
                        OcrTemplateField(order = 4, type = "CUSTOMER_VALUE", example = "051846", minLength = 6, maxLength = 6),
                        OcrTemplateField(order = 5, type = "LITERAL", example = "U", literal = "U"),
                        OcrTemplateField(order = 6, type = "NUMBER_TEXT", example = "110030", minLength = 6, maxLength = 6),
                        OcrTemplateField(
                            order = 7,
                            type = "BILL_DATE",
                            example = "20/08/69",
                            dateOrder = "DMY",
                            dateCalendar = "BUDDHIST",
                            dateYearDigits = 2
                        ),
                        OcrTemplateField(order = 8, type = "BILL_TIME", example = "17:51")
                    )
                )
            )
        ),
        validation = OcrTemplateValidation(
            requiredCore = OcrTemplateRequiredCore(date = true, time = true, customerValue = true)
        )
    )

    @Test
    fun recoversNoisyThaiDateNearPos2WithoutInventingCustomer() {
        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R201657846U110030 20/06/61 36:00\nR2020390300400072 20/08769 17:18",
                "R201657846U110030 20/06/61 36:00\nR202039030U400072 20/08/69 17:18",
                "R2020390300400072 20/08769 17:18"
            ),
            template = mb02,
            allowedPos = setOf(1, 2, 3),
            referenceDate = LocalDate.of(2026, 9, 2)
        )

        assertTrue(result.containsKey(2))
        assertEquals("039030", result.getValue(2)["CUSTOMER_VALUE"])
        assertEquals("20/08/2026", result.getValue(2)["BILL_DATE"])
        assertEquals("17:18", result.getValue(2)["BILL_TIME"])
        assertFalse("POS1 bad evidence must not block or alter POS2", result.containsKey(1))
    }
    @Test
    fun resolvesMb02WhenAllPassesHaveLiteralOrDateOcrNoise() {
        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R2020390300400072 20/08769 17:18",
                "R202039030O400072 20/08769 17:18",
                "R202039030V400072 20/0869 17:18"
            ),
            template = mb02,
            allowedPos = setOf(1, 2, 3),
            referenceDate = LocalDate.of(2026, 9, 2)
        )

        assertTrue(result.containsKey(2))
        assertEquals("039030", result.getValue(2)["CUSTOMER_VALUE"])
        assertEquals("20/08/2026", result.getValue(2)["BILL_DATE"])
        assertEquals("17:18", result.getValue(2)["BILL_TIME"])
    }

}
