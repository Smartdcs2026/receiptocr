package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.UniversalOcrTemplate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateSequenceDiagnosticsRound85Test {

    private val mb02 = UniversalOcrTemplate(
        templateId = "mb02-r85",
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
    fun reportsCompleteSequenceForRealMb02Text() {
        val detail = TemplateSequenceFallback.diagnose(
            rawTexts = listOf("R202039030U400072 20/08/69 17:18"),
            templates = listOf(mb02)
        ).joinToString(" ")

        assertTrue(detail.contains("อ่านลำดับครบ"))
    }

    @Test
    fun reportsWhereSequenceStopsWhenLiteralIsDifferent() {
        val detail = TemplateSequenceFallback.diagnose(
            rawTexts = listOf("R202039030X400072 20/08/69 17:18"),
            templates = listOf(mb02)
        ).joinToString(" ")

        assertTrue(detail.contains("หยุดก่อนช่อง 5"))
        assertTrue(detail.contains("ข้อความคงที่"))
    }

    @Test
    fun reportsImpossibleClockTimeInsteadOfAcceptingIt() {
        val detail = TemplateSequenceFallback.diagnose(
            rawTexts = listOf("R202039030U400072 20/08/69 36:00"),
            templates = listOf(mb02)
        ).joinToString(" ")

        assertTrue(detail.contains("36:00"))
        assertTrue(detail.contains("ใช้ไม่ได้"))
    }
}
