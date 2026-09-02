package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.UniversalOcrTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TemplateAwareDateResolverRound92Test {
    private val workDate = LocalDate.of(2026, 8, 22)

    @Test
    fun mb01MdyGregorianFourDigitBecomesCanonicalDmy() {
        val result = TemplateAwareDateResolver.resolve(
            raw = "08-21-2026",
            templateName = "Mb_01",
            templates = listOf(mb01(), mb02()),
            referenceDate = workDate,
            allowCanonicalInput = false
        )
        assertEquals("21/08/2026", result.value)
    }

    @Test
    fun mb01SecondReceiptMdyGregorianFourDigitBecomesCanonicalDmy() {
        val result = TemplateAwareDateResolver.resolve(
            raw = "08-22-2026",
            templateName = "Mb_01",
            templates = listOf(mb01(), mb02()),
            referenceDate = workDate,
            allowCanonicalInput = false
        )
        assertEquals("22/08/2026", result.value)
    }

    @Test
    fun mb02DmyBuddhistTwoDigitStillWorks() {
        val result = TemplateAwareDateResolver.resolve(
            raw = "20/08/69",
            templateName = "Mb_02",
            templates = listOf(mb01(), mb02()),
            referenceDate = workDate,
            allowCanonicalInput = false
        )
        assertEquals("20/08/2026", result.value)
    }

    @Test
    fun combinedTemplateMetadataChoosesOnlyPlausibleDateMeaning() {
        val result = TemplateAwareDateResolver.resolve(
            raw = "08/21/2026",
            templateName = "Mb_01 / Mb_02",
            templates = listOf(mb01(), mb02()),
            referenceDate = workDate,
            allowCanonicalInput = false
        )
        assertEquals("21/08/2026", result.value)
    }

    @Test
    fun canonicalInternalDateIsNeverReinterpretedAsMdy() {
        val result = TemplateAwareDateResolver.resolve(
            raw = "21/08/2026",
            templateName = "Mb_01",
            templates = listOf(mb01()),
            referenceDate = workDate,
            allowCanonicalInput = true
        )
        assertEquals("21/08/2026", result.value)
    }

    @Test
    fun ambiguousSourceAcrossTwoValidOrdersIsNotGuessed() {
        val mdy = template("A", "MDY", "GREGORIAN", 4)
        val dmy = template("B", "DMY", "GREGORIAN", 4)
        val result = TemplateAwareDateResolver.resolve(
            raw = "08/09/2026",
            templateName = "A / B",
            templates = listOf(mdy, dmy),
            referenceDate = LocalDate.of(2026, 9, 8),
            allowCanonicalInput = false
        )
        assertNull(result.value)
    }

    private fun mb01() = template("Mb_01", "MDY", "GREGORIAN", 4)
    private fun mb02() = template("Mb_02", "DMY", "BUDDHIST", 2)

    private fun template(name: String, order: String, calendar: String, yearDigits: Int) = UniversalOcrTemplate(
        templateId = name.lowercase(),
        brandId = "MB",
        templateName = name,
        recognition = OcrTemplateRecognition(
            rows = listOf(
                OcrTemplateRow(
                    row = 1,
                    fields = listOf(
                        OcrTemplateField(
                            order = 1,
                            type = "BILL_DATE",
                            example = if (order == "MDY") "08-21-2026" else "20/08/69",
                            format = "DATE",
                            dateOrder = order,
                            dateCalendar = calendar,
                            dateYearDigits = yearDigits
                        )
                    )
                )
            )
        )
    )
}
