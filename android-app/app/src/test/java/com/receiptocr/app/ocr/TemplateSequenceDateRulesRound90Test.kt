package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.UniversalOcrTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateSequenceDateRulesRound90Test {

    private fun template(
        name: String,
        dateExample: String,
        dateOrder: String,
        dateCalendar: String,
        dateYearDigits: Int
    ) = UniversalOcrTemplate(
        templateId = name.lowercase(),
        brandId = "brand-test",
        templateName = name,
        recognition = OcrTemplateRecognition(
            rowCount = 1,
            rows = listOf(
                OcrTemplateRow(
                    row = 1,
                    fields = listOf(
                        OcrTemplateField(order = 1, type = "LITERAL", example = "R", literal = "R"),
                        OcrTemplateField(order = 2, type = "POS_NUMBER", example = "1", minLength = 1, maxLength = 1, posDigits = 1),
                        OcrTemplateField(order = 3, type = "CUSTOMER_VALUE", example = "123456", minLength = 6, maxLength = 6),
                        OcrTemplateField(
                            order = 4,
                            type = "BILL_DATE",
                            example = dateExample,
                            dateOrder = dateOrder,
                            dateCalendar = dateCalendar,
                            dateYearDigits = dateYearDigits
                        ),
                        OcrTemplateField(order = 5, type = "BILL_TIME", example = "17:18", minLength = 5, maxLength = 5)
                    )
                )
            )
        )
    )

    @Test
    fun acceptsDmyTwoDigitYearWhenAdminRequiresTwoDigits() {
        val parsed = TemplateSequenceFallback.parseText(
            "R1123456 31/08/69 17:18",
            template("DMY-BE-2", "31/08/69", "DMY", "BUDDHIST", 2)
        )
        assertEquals(1, parsed.size)
        assertEquals("31/08/69", parsed.single()["BILL_DATE"])
    }

    @Test
    fun acceptsMdyFourDigitYearWhenAdminRequiresFourDigits() {
        val parsed = TemplateSequenceFallback.parseText(
            "R1123456 08/31/2026 17:18",
            template("MDY-CE-4", "08/31/2026", "MDY", "GREGORIAN", 4)
        )
        assertEquals(1, parsed.size)
        assertEquals("08/31/2026", parsed.single()["BILL_DATE"])
    }

    @Test
    fun acceptsYmdFourDigitYearFromAdminOrder() {
        val parsed = TemplateSequenceFallback.parseText(
            "R1123456 2026/08/31 17:18",
            template("YMD-CE-4", "31/08/69", "YMD", "GREGORIAN", 4)
        )
        assertEquals(1, parsed.size)
        assertEquals("2026/08/31", parsed.single()["BILL_DATE"])
    }

    @Test
    fun rejectsFourDigitSourceWhenAdminRequiresTwoDigitYear() {
        val parsed = TemplateSequenceFallback.parseText(
            "R1123456 31/08/2026 17:18",
            template("DMY-BE-2", "31/08/69", "DMY", "BUDDHIST", 2)
        )
        assertTrue(parsed.isEmpty())
    }
}
