package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRequiredCore
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.OcrTemplateValidation
import com.receiptocr.app.config.PosIdentityMapping
import com.receiptocr.app.config.PosIdentityRule
import com.receiptocr.app.config.UniversalOcrTemplate
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CjFourReceiptBindingRound10421Test {
    private fun cjRule() = PosIdentityRule(
        enabled = true,
        allowedPrefixes = listOf("N", "B"),
        lastWorkPosPrefixes = listOf("B"),
        mappings = listOf(
            PosIdentityMapping("N01", workPos = 1),
            PosIdentityMapping("N02", workPos = 2),
            PosIdentityMapping("N03", workPos = 3)
        ),
        fallbackUnknownToLastWorkPos = true
    )

    private val template = UniversalOcrTemplate(
        templateId = "cj-four-receipts-r10421",
        brandId = "CJ",
        templateName = "CJ BNO one-line",
        recognition = OcrTemplateRecognition(
            rowCount = 1,
            rows = listOf(
                OcrTemplateRow(
                    row = 1,
                    fields = listOf(
                        OcrTemplateField(order = 1, type = "BILL_DATE", example = "05/09/2026", dateOrder = "DMY", dateCalendar = "GREGORIAN", dateYearDigits = 4),
                        OcrTemplateField(order = 2, type = "BILL_TIME", example = "17:37"),
                        OcrTemplateField(order = 3, type = "LITERAL", literal = "BNO:S"),
                        OcrTemplateField(order = 4, type = "YEAR_VALUE", example = "26", minLength = 2, maxLength = 2),
                        OcrTemplateField(order = 5, type = "MONTH_VALUE", example = "09", minLength = 2, maxLength = 2),
                        OcrTemplateField(order = 6, type = "STORE_ID", example = "0690", minLength = 4, maxLength = 4),
                        OcrTemplateField(order = 7, type = "POS_NUMBER", example = "N01", minLength = 3, maxLength = 3, posPrefixes = "N,B", posDigits = 2),
                        OcrTemplateField(order = 8, type = "SEPARATOR", separatorValue = "-"),
                        OcrTemplateField(order = 9, type = "CUSTOMER_VALUE", example = "000684", minLength = 6, maxLength = 6)
                    )
                )
            )
        ),
        validation = OcrTemplateValidation(
            requiredCore = OcrTemplateRequiredCore(date = true, time = true, customerValue = true)
        )
    )

    private fun work() = WorkItem(
        id = 1,
        brand = "CJ More",
        brandAbbr = "CJ",
        businessType = "",
        storeCode = "0690",
        storeName = "CJ field receipt test",
        posCount = 4,
        openClose = "",
        address = "",
        storeFormat = "",
        rank = "",
        latitude = "",
        longitude = "",
        receiptStoreId = "0690"
    )

    @Test
    fun actualFourReceiptTextBindsExactlyToPosOneThroughFour() {
        val result = TemplateSequenceFallback.apply(
            rawTexts = listOf(
                "05/09/2026 17:37 BNO:S26090690N01-000684",
                "05/09/2026 18:20 BNO:S26090690N02-001332",
                "05/09/2026 18:06 BNO:S26090690N03-000659",
                "05/09/2026 21:39 BNO:S26090690B01-000694"
            ),
            records = listOf(PosRecord(1), PosRecord(2), PosRecord(3), PosRecord(4)),
            work = work(),
            workDate = LocalDate.of(2026, 9, 8),
            imagePath = "cj-field-4-receipts.jpg",
            templates = listOf(template),
            posIdentityRule = cjRule()
        )

        assertEquals(listOf(1, 2, 3, 4), result.detectedPos)
        assertFalse(result.detectedPos.contains(8))

        val byPos = result.records.associateBy { it.posNumber }
        assertEquals("000684", byPos.getValue(1).customerNo)
        assertEquals("17:37", byPos.getValue(1).billTime)
        assertEquals("001332", byPos.getValue(2).customerNo)
        assertEquals("18:20", byPos.getValue(2).billTime)
        assertEquals("000659", byPos.getValue(3).customerNo)
        assertEquals("18:06", byPos.getValue(3).billTime)
        assertEquals("000694", byPos.getValue(4).customerNo)
        assertEquals("21:39", byPos.getValue(4).billTime)
        assertEquals(setOf(1, 2, 3, 4), byPos.keys)
    }

    @Test
    fun ocrEightCannotCreatePhantomPosWhenStoreHasOnlyOneToFour() {
        val available = setOf(1, 2, 3, 4)
        assertEquals(4, PosIdentityResolver.resolve("8", cjRule(), available)?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("801", cjRule(), available)?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("8O1", cjRule(), available)?.workPos)
    }
}
