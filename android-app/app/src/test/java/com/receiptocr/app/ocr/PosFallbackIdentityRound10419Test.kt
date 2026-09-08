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
import org.junit.Assert.assertTrue
import org.junit.Test

class PosFallbackIdentityRound10419Test {
    private fun identityRule() = PosIdentityRule(
        enabled = true,
        allowedPrefixes = listOf("N", "B"),
        lastWorkPosPrefixes = listOf("B"),
        mappings = listOf(
            PosIdentityMapping("N01", workPos = 1),
            PosIdentityMapping("N02", workPos = 2)
        )
    )

    private val terminalTemplate = UniversalOcrTemplate(
        templateId = "terminal-r10419",
        brandId = "test-brand",
        templateName = "Terminal fallback",
        recognition = OcrTemplateRecognition(
            rowCount = 1,
            rows = listOf(
                OcrTemplateRow(
                    row = 1,
                    fields = listOf(
                        OcrTemplateField(
                            order = 1,
                            type = "POS_NUMBER",
                            example = "B01",
                            minLength = 3,
                            maxLength = 3,
                            posPrefixes = "B",
                            posDigits = 2
                        ),
                        OcrTemplateField(order = 2, type = "CUSTOMER_VALUE", example = "123456", minLength = 6, maxLength = 6),
                        OcrTemplateField(
                            order = 3,
                            type = "BILL_DATE",
                            example = "20/08/69",
                            dateOrder = "DMY",
                            dateCalendar = "BUDDHIST",
                            dateYearDigits = 2
                        ),
                        OcrTemplateField(order = 4, type = "BILL_TIME", example = "17:18")
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
        brand = "TEST",
        brandAbbr = "T",
        businessType = "",
        storeCode = "1001",
        storeName = "Test store",
        posCount = 3,
        openClose = "",
        address = "",
        storeFormat = "",
        rank = "",
        latitude = "",
        longitude = ""
    )

    @Test
    fun evidenceFusionResolvesB01And801ToLastStorePos() {
        val b01 = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf("B01 123456 20/08/69 17:18"),
            template = terminalTemplate,
            allowedPos = setOf(1, 2, 3),
            referenceDate = LocalDate.of(2026, 9, 2),
            posIdentityRule = identityRule()
        )
        val misread801 = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf("801 123456 20/08/69 17:18"),
            template = terminalTemplate,
            allowedPos = setOf(1, 2, 3),
            referenceDate = LocalDate.of(2026, 9, 2),
            posIdentityRule = identityRule()
        )

        assertTrue(b01.containsKey(3))
        assertTrue(misread801.containsKey(3))
        assertEquals("123456", misread801.getValue(3)["CUSTOMER_VALUE"])
    }

    @Test
    fun sequenceFallbackUsesResolvedWorkPosInsteadOfReparsingRawIdentity() {
        val result = TemplateSequenceFallback.apply(
            rawTexts = listOf("801 123456 20/08/69 17:18"),
            records = listOf(PosRecord(1), PosRecord(2), PosRecord(3)),
            work = work(),
            workDate = LocalDate.of(2026, 9, 2),
            imagePath = "round10419-test.jpg",
            templates = listOf(terminalTemplate),
            posIdentityRule = identityRule()
        )

        assertEquals(listOf(3), result.detectedPos)
        assertEquals("123456", result.records.first { it.posNumber == 3 }.customerNo)
        assertEquals("20/08/2026", result.records.first { it.posNumber == 3 }.billDate)
        assertEquals("17:18", result.records.first { it.posNumber == 3 }.billTime)
    }

    @Test
    fun realNumericEightAnd801StayNumericWhenTheyExistInStorePlan() {
        assertEquals(
            8,
            PosEvidenceFusion.resolveEvidencePosIdentity("8", identityRule(), setOf(1, 2, 3, 8))
        )
        assertEquals(
            801,
            PosEvidenceFusion.resolveEvidencePosIdentity("801", identityRule(), setOf(1, 2, 3, 801))
        )
        assertEquals(
            801,
            TemplateSequenceFallback.resolveSequencePosIdentity("801", identityRule(), setOf(1, 2, 3, 801))
        )
    }
}
