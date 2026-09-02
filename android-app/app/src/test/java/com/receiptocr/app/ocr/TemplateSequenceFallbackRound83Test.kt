package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateComposite
import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.OcrTemplateSegment
import com.receiptocr.app.config.UniversalOcrTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateSequenceFallbackRound83Test {

    private val mb02 = UniversalOcrTemplate(
        templateId = "mb02-round83",
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
    fun readsDenseCharactersAsOneToken() {
        val match = TemplateSequenceFallback.parseText(
            "R201051846U11003020/08/6917:51",
            mb02
        ).first()

        assertEquals("1", match["POS_NUMBER"])
        assertEquals("051846", match["CUSTOMER_VALUE"])
        assertEquals("20/08/69", match["BILL_DATE"])
        assertEquals("17:51", match["BILL_TIME"])
    }

    @Test
    fun readsWhenMlKitBreaksOneReceiptIntoSeveralLines() {
        val match = TemplateSequenceFallback.parseText(
            "R201051846U110030\n20/08/69\n17:51",
            mb02
        ).first()

        assertEquals("1", match["POS_NUMBER"])
        assertEquals("051846", match["CUSTOMER_VALUE"])
        assertEquals("20/08/69", match["BILL_DATE"])
        assertEquals("17:51", match["BILL_TIME"])
    }

    @Test
    fun keepsCoreFieldsWhenUIsMissingFromOcrText() {
        val match = TemplateSequenceFallback.parseText(
            "R201051846110030 20/08/69 17:51",
            mb02
        ).first()

        assertEquals("1", match["POS_NUMBER"])
        assertEquals("051846", match["CUSTOMER_VALUE"])
        assertEquals("20/08/69", match["BILL_DATE"])
        assertEquals("17:51", match["BILL_TIME"])
    }

    @Test
    fun findsMoreThanOnePosFromSameOcrPass() {
        val matches = TemplateSequenceFallback.parseText(
            "R201051846U110030 20/08/69 17:51\nR202039030U400072 20/08/69 17:18",
            mb02
        )

        val pos = matches.mapNotNull { it["POS_NUMBER"] }.toSet()
        assertTrue("1" in pos)
        assertTrue("2" in pos)
    }

    @Test
    fun supportsCompositeCodeInsteadOfRejectingWholeTemplate() {
        val compositeTemplate = UniversalOcrTemplate(
            templateId = "composite-round83",
            brandId = "brand-test",
            templateName = "Composite",
            recognition = OcrTemplateRecognition(
                rowCount = 1,
                rows = listOf(
                    OcrTemplateRow(
                        row = 1,
                        fields = listOf(
                            OcrTemplateField(
                                order = 1,
                                type = "COMPOSITE_CODE",
                                composite = OcrTemplateComposite(
                                    prefix = "R20",
                                    segments = listOf(
                                        OcrTemplateSegment(order = 1, type = "POS_NUMBER", length = 1, example = "1"),
                                        OcrTemplateSegment(order = 2, type = "CUSTOMER_VALUE", length = 6, example = "051846"),
                                        OcrTemplateSegment(order = 3, type = "LITERAL", length = 1, example = "U"),
                                        OcrTemplateSegment(order = 4, type = "NUMBER_TEXT", length = 6, example = "110030")
                                    )
                                )
                            ),
                            OcrTemplateField(order = 2, type = "BILL_DATE", example = "20/08/69"),
                            OcrTemplateField(order = 3, type = "BILL_TIME", example = "17:51")
                        )
                    )
                )
            )
        )

        val match = TemplateSequenceFallback.parseText(
            "R201051846U110030 20/08/69 17:51",
            compositeTemplate
        ).first()

        assertEquals("1", match["POS_NUMBER"])
        assertEquals("051846", match["CUSTOMER_VALUE"])
        assertEquals("20/08/69", match["BILL_DATE"])
        assertEquals("17:51", match["BILL_TIME"])
    }
}
