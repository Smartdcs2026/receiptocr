package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRequiredCore
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.OcrTemplateValidation
import com.receiptocr.app.config.UniversalOcrTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosEvidenceFusionRound87Test {

    private val mb02 = UniversalOcrTemplate(
        templateId = "mb02-r87",
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
        ),
        validation = OcrTemplateValidation(
            requiredCore = OcrTemplateRequiredCore(date = true, time = true, customerValue = true)
        )
    )

    @Test
    fun acceptsPos2EvenWhenPos1NeverBecomesComplete() {
        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R201651846U110030 20/68/64 36:00\nR202039030U400072 20/08/69 17:18",
                "R201657846U110030 20/87/69 1751\nR202039030400072 20/08/69 17:18",
                "R201651846U110030 20/06/61 36:00\nR202039030U400072 20/08/69 1718",
                "R202039030U400072 20/08/69 17:18"
            ),
            template = mb02,
            allowedPos = setOf(1, 2, 3)
        )

        assertTrue(result.containsKey(2))
        assertEquals("039030", result.getValue(2)["CUSTOMER_VALUE"])
        assertEquals("20/08/69", result.getValue(2)["BILL_DATE"])
        assertEquals("17:18", result.getValue(2)["BILL_TIME"])
        assertFalse("POS 1 must not block POS 2 or be fabricated", result.containsKey(1))
    }

    @Test
    fun oneCoherentCompletePassCanRescueSplitConsensusForSamePos() {
        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R202039030U400072 20/0876 1718",
                "R202039030U400072 20/68/69 36:00",
                "R202039030U400072 20/08/69 17:18",
                "R202039030U400072 20/0876 17:18"
            ),
            template = mb02,
            allowedPos = setOf(1, 2, 3)
        )

        assertTrue(result.containsKey(2))
        assertEquals("039030", result.getValue(2)["CUSTOMER_VALUE"])
        assertEquals("20/08/69", result.getValue(2)["BILL_DATE"])
        assertEquals("17:18", result.getValue(2)["BILL_TIME"])
    }

    @Test
    fun impossibleTimeStillCannotCreateAResolvedPos() {
        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R202039030U400072 20/08/69 36:00",
                "R202039030U400072 20/08/69 36:00",
                "R202039030U400072 20/08/69 3600"
            ),
            template = mb02,
            allowedPos = setOf(1, 2, 3)
        )

        assertFalse(result.containsKey(2))
    }
}
