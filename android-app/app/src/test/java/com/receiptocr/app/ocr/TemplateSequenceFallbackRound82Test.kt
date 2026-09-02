package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.UniversalOcrTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateSequenceFallbackRound82Test {

    private val mb02 = UniversalOcrTemplate(
        templateId = "mb02-test",
        brandId = "brand-test",
        templateName = "Mb_02",
        recognition = OcrTemplateRecognition(
            rowCount = 1,
            rows = listOf(
                OcrTemplateRow(
                    row = 1,
                    fields = listOf(
                        OcrTemplateField(order = 1, type = "LITERAL", example = "R", literal = "R"),
                        OcrTemplateField(order = 2, type = "NUMBER_TEXT", example = "10", minLength = 2, maxLength = 2),
                        OcrTemplateField(order = 3, type = "POS_NUMBER", example = "1", minLength = 1, maxLength = 1),
                        OcrTemplateField(order = 4, type = "CUSTOMER_VALUE", example = "219931", minLength = 6, maxLength = 6),
                        OcrTemplateField(order = 5, type = "LITERAL", example = "U", literal = "U"),
                        OcrTemplateField(order = 6, type = "NUMBER_TEXT", example = "400040", minLength = 6, maxLength = 6),
                        OcrTemplateField(order = 7, type = "BILL_DATE", example = "22/08/69"),
                        OcrTemplateField(order = 8, type = "BILL_TIME", example = "18:37")
                    )
                )
            )
        )
    )

    @Test
    fun separatesFirstRealReceipt() {
        val matches = TemplateSequenceFallback.parseText(
            "R201051846U110030 20/08/69 17:51",
            mb02
        )
        assertTrue(matches.isNotEmpty())
        assertEquals("1", matches.first()["POS_NUMBER"])
        assertEquals("051846", matches.first()["CUSTOMER_VALUE"])
        assertEquals("20/08/69", matches.first()["BILL_DATE"])
        assertEquals("17:51", matches.first()["BILL_TIME"])
    }

    @Test
    fun separatesSecondRealReceipt() {
        val matches = TemplateSequenceFallback.parseText(
            "R202039030U400072 20/08/69 17:18",
            mb02
        )
        assertTrue(matches.isNotEmpty())
        assertEquals("2", matches.first()["POS_NUMBER"])
        assertEquals("039030", matches.first()["CUSTOMER_VALUE"])
        assertEquals("20/08/69", matches.first()["BILL_DATE"])
        assertEquals("17:18", matches.first()["BILL_TIME"])
    }

    @Test
    fun stillSeparatesWhenSeparatorLetterIsReadDifferently() {
        val matches = TemplateSequenceFallback.parseText(
            "R201051846V110030 20/08/69 17:51",
            mb02
        )
        assertTrue(matches.isNotEmpty())
        assertEquals("1", matches.first()["POS_NUMBER"])
        assertEquals("051846", matches.first()["CUSTOMER_VALUE"])
    }
}
