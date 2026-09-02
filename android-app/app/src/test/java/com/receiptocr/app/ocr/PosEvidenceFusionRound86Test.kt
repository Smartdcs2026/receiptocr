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

class PosEvidenceFusionRound86Test {

    private val mb02 = UniversalOcrTemplate(
        templateId = "mb02-r86",
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
    fun fusesPrefixFromOnePassWithDateAndTimeFromAnotherPassForSamePos() {
        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "BCM BCP VIBHAVADI 43 BANGKOK\nR202039030U400072 20/0876 1718\nVat 2.36",
                "BCM BCP VIBHAVADI 43 BANGKOK\nR202039030400072 20/08/69 17:18\nVat 2.36",
                "BCM BCP VIBHAVADI 43 BANGKOK\nR202039030U400072 20/08/69 1718\nVat 2.36",
                "R202039030U400072 20/08/69 17:18"
            ),
            template = mb02,
            allowedPos = setOf(1, 2, 3)
        )

        val pos2 = result.getValue(2)
        assertEquals("039030", pos2["CUSTOMER_VALUE"])
        assertEquals("20/08/69", pos2["BILL_DATE"])
        assertEquals("17:18", pos2["BILL_TIME"])
    }

    @Test
    fun keepsTwoPosEvidenceSeparated() {
        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R201051846U110030 20/0876 17:51\nR202039030U400072 20/08/69 17:18",
                "R201051846110030 20/08/69 17:51\nR202039030400072 20/08/69 17:18",
                "R201051846U110030 20/08/69 1751\nR202039030U400072 20/08/69 1718"
            ),
            template = mb02,
            allowedPos = setOf(1, 2, 3)
        )

        assertEquals("051846", result.getValue(1)["CUSTOMER_VALUE"])
        assertEquals("17:51", result.getValue(1)["BILL_TIME"])
        assertEquals("039030", result.getValue(2)["CUSTOMER_VALUE"])
        assertEquals("17:18", result.getValue(2)["BILL_TIME"])
    }

    @Test
    fun neverBorrowsDateOrTimeFromNextPosWhenCurrentPosHasNoValidDate() {
        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R201051846U110030 BAD BAD\nR202039030U400072 20/08/69 17:18",
                "R201051846U110030 BAD BAD\nR202039030U400072 20/08/69 17:18",
                "R201051846U110030 BAD BAD\nR202039030U400072 20/08/69 17:18"
            ),
            template = mb02,
            allowedPos = setOf(1, 2, 3)
        )

        assertFalse(result.containsKey(1))
        assertEquals("20/08/69", result.getValue(2)["BILL_DATE"])
        assertEquals("17:18", result.getValue(2)["BILL_TIME"])
    }

    @Test
    fun rejectsImpossibleTimeEvenWhenItAppearsNearAnchor() {
        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R202039030U400072 20/08/69 36:00",
                "R202039030400072 20/08/69 36:00",
                "R202039030U400072 20/08/69 36:00"
            ),
            template = mb02,
            allowedPos = setOf(1, 2, 3)
        )

        assertFalse(result.containsKey(2))
    }

    @Test
    fun doesNotAcceptPosOutsideWorkPlan() {
        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R209039030U400072 20/08/69 17:18",
                "R209039030U400072 20/08/69 17:18",
                "R209039030U400072 20/08/69 17:18"
            ),
            template = mb02,
            allowedPos = setOf(1, 2, 3)
        )

        assertTrue(result.isEmpty())
    }
}
