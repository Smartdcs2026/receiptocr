package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.UniversalOcrTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AdminRuleInterpreterTest {

    @Test
    fun sameTextThatPassesAdminTestIsSeparatedTheSameWayInApk() {
        val template = UniversalOcrTemplate(
            templateId = "mb-02-test",
            brandId = "MB",
            templateName = "Mb_02",
            recognition = OcrTemplateRecognition(
                rowCount = 1,
                rows = listOf(
                    OcrTemplateRow(
                        row = 1,
                        fields = listOf(
                            OcrTemplateField(
                                order = 1,
                                type = "LITERAL",
                                example = "R10",
                                literal = "R10",
                                minLength = 3,
                                maxLength = 3
                            ),
                            OcrTemplateField(
                                order = 2,
                                type = "POS_NUMBER",
                                example = "1",
                                minLength = 1,
                                maxLength = 1,
                                posDigits = 1
                            ),
                            OcrTemplateField(
                                order = 3,
                                type = "CUSTOMER_VALUE",
                                example = "219931",
                                minLength = 6,
                                maxLength = 6
                            ),
                            OcrTemplateField(
                                order = 4,
                                type = "IGNORE",
                                example = "U400040",
                                required = false,
                                minLength = 0,
                                maxLength = 40
                            ),
                            OcrTemplateField(
                                order = 5,
                                type = "BILL_DATE",
                                example = "22/08/69",
                                minLength = 8,
                                maxLength = 10,
                                format = "DD/MM/YYYY"
                            ),
                            OcrTemplateField(
                                order = 6,
                                type = "BILL_TIME",
                                example = "18:37",
                                minLength = 4,
                                maxLength = 5
                            )
                        )
                    )
                )
            )
        )

        val records = AdminRuleInterpreter.parseTextForTest(
            template = template,
            rawText = "R101219931U400040 22/08/69 18:37"
        )

        assertFalse(records.isEmpty())
        val first = records.first()
        assertEquals("1", first["POS_NUMBER"])
        assertEquals("219931", first["CUSTOMER_VALUE"])
        assertEquals("22/08/69", first["BILL_DATE"])
        assertEquals("18:37", first["BILL_TIME"])
    }

    @Test
    fun threeDigitReceiptPosIsReadAsIdentityNotAsWorkPlanRange() {
        val template = UniversalOcrTemplate(
            templateId = "pos-101-test",
            brandId = "TEST",
            templateName = "POS 101",
            recognition = OcrTemplateRecognition(
                rowCount = 1,
                rows = listOf(
                    OcrTemplateRow(
                        row = 1,
                        fields = listOf(
                            OcrTemplateField(order = 1, type = "LITERAL", example = "R", literal = "R"),
                            OcrTemplateField(
                                order = 2,
                                type = "POS_NUMBER",
                                example = "101",
                                minLength = 3,
                                maxLength = 3,
                                posDigits = 3
                            ),
                            OcrTemplateField(
                                order = 3,
                                type = "CUSTOMER_VALUE",
                                example = "219931",
                                minLength = 6,
                                maxLength = 6
                            )
                        )
                    )
                )
            )
        )

        val records = AdminRuleInterpreter.parseTextForTest(template, "R101219931")

        assertFalse(records.isEmpty())
        assertEquals("101", records.first()["POS_NUMBER"])
        assertEquals("219931", records.first()["CUSTOMER_VALUE"])
    }
}
