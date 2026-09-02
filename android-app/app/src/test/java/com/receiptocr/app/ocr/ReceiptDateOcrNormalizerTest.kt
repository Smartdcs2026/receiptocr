package com.receiptocr.app.ocr

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptDateOcrNormalizerTest {
    private val workDate = LocalDate.of(2026, 8, 23)

    @Test
    fun impossibleMonth28IsRecoveredToAugustFromMonthPosition() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/28/2026",
            configuredFormat = "DD/MM/YYYY",
            referenceDate = workDate
        )

        assertEquals("20/08/2026", result.value)
        assertTrue(result.corrected)
    }

    @Test
    fun impossibleMonth18IsRecoveredToAugustFromMonthPosition() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/18/2026",
            configuredFormat = "DD/MM/YYYY",
            referenceDate = workDate
        )

        assertEquals("20/08/2026", result.value)
        assertTrue(result.corrected)
    }

    @Test
    fun ambiguousMonth87IsNotGuessed() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/87/69",
            configuredFormat = "DATE",
            referenceDate = workDate,
            dateOrder = "DMY",
            dateCalendar = "BUDDHIST",
            dateYearDigits = 2
        )

        assertNull(result.value)
        assertEquals("20/87/69", result.original)
    }

    @Test
    fun validMonthIsNotChanged() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "22/08/2026",
            configuredFormat = "DD/MM/YYYY",
            referenceDate = workDate
        )

        assertEquals("22/08/2026", result.value)
        assertFalse(result.corrected)
    }

    @Test
    fun invalidCalendarDayIsNotInvented() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "31/02/2026",
            configuredFormat = "DD/MM/YYYY",
            referenceDate = workDate
        )

        assertNull(result.value)
    }

    @Test
    fun adminMdyFormatIsRespected() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "08/20/2026",
            configuredFormat = "MM/DD/YYYY",
            referenceDate = workDate
        )

        assertEquals("20/08/2026", result.value)
        assertFalse(result.corrected)
    }

    @Test
    fun adminYmdFormatIsRespected() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "2026-08-20",
            configuredFormat = "YYYY-MM-DD",
            referenceDate = workDate
        )

        assertEquals("20/08/2026", result.value)
        assertFalse(result.corrected)
    }

    @Test
    fun thaiTwoDigitBuddhistYear69MapsTo2026NearWorkDate() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08/69",
            configuredFormat = "DD/MM/YY",
            referenceDate = LocalDate.of(2026, 9, 2)
        )

        assertEquals("20/08/2026", result.value)
        assertFalse(result.corrected)
    }

    @Test
    fun distantTwoDigitYearMisreadIsRejectedInsteadOfBecoming2061() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/06/61",
            configuredFormat = "DD/MM/YY",
            referenceDate = LocalDate.of(2026, 9, 2)
        )

        assertNull(result.value)
    }

    @Test
    fun buddhistTwoDigitRuleMaps69To2026() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08/69",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "BUDDHIST",
            dateYearDigits = 2
        )
        assertEquals("20/08/2026", result.value)
    }

    @Test
    fun buddhistFourDigitRuleMaps2569To2026() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08/2569",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "BUDDHIST",
            dateYearDigits = 4
        )
        assertEquals("20/08/2026", result.value)
    }

    @Test
    fun gregorianTwoDigitRuleMaps26To2026() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08/26",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "GREGORIAN",
            dateYearDigits = 2
        )
        assertEquals("20/08/2026", result.value)
    }

    @Test
    fun mdyGregorianFourDigitRuleNormalizesToDmyStorage() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "08/20/2026",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "MDY",
            dateCalendar = "GREGORIAN",
            dateYearDigits = 4
        )
        assertEquals("20/08/2026", result.value)
    }

    @Test
    fun wrongCalendarRuleIsRejectedButRawRemainsAvailable() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08/69",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "GREGORIAN",
            dateYearDigits = 2
        )
        assertNull(result.value)
        assertEquals("20/08/69", result.original)
        assertTrue(result.warning.orEmpty().isNotBlank())
    }

    @Test
    fun partiallySeparatedThaiDatePreservesAdminDayMonthBoundary() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08769",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "BUDDHIST",
            dateYearDigits = 2,
            dateExample = "22/08/69"
        )
        assertEquals("20/08/2026", result.value)
        assertTrue(result.corrected)
        assertEquals("20/08769", result.original)
    }

    @Test
    fun missingSecondSeparatorUsesAdminLengths() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/0869",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "BUDDHIST",
            dateYearDigits = 2
        )
        assertEquals("20/08/2026", result.value)
        assertTrue(result.corrected)
    }

    @Test
    fun canonicalValueCanPassSecondStageWithoutBreakingSourceTwoDigitRule() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08/2026",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "BUDDHIST",
            dateYearDigits = 2,
            dateExample = "22/08/69",
            allowCanonicalInput = true
        )
        assertEquals("20/08/2026", result.value)
    }

    // Round90 completion: ชุดตัวอย่างที่ Admin ต้องรองรับตามรูปแบบบิลแต่ละแบรนด์
    @Test
    fun dmyGregorianFourDigit_31_08_2026() {
        assertAdminDate("31/08/2026", "DMY", "GREGORIAN", 4)
    }

    @Test
    fun dmyGregorianTwoDigit_31_08_26() {
        assertAdminDate("31/08/26", "DMY", "GREGORIAN", 2)
    }

    @Test
    fun dmyBuddhistFourDigit_31_08_2569() {
        assertAdminDate("31/08/2569", "DMY", "BUDDHIST", 4)
    }

    @Test
    fun dmyBuddhistTwoDigit_31_08_69() {
        assertAdminDate("31/08/69", "DMY", "BUDDHIST", 2)
    }

    @Test
    fun mdyGregorianFourDigit_08_31_2026() {
        assertAdminDate("08/31/2026", "MDY", "GREGORIAN", 4)
    }

    @Test
    fun mdyGregorianTwoDigit_08_31_26() {
        assertAdminDate("08/31/26", "MDY", "GREGORIAN", 2)
    }

    @Test
    fun mdyBuddhistFourDigit_08_31_2569() {
        assertAdminDate("08/31/2569", "MDY", "BUDDHIST", 4)
    }

    @Test
    fun mdyBuddhistTwoDigit_08_31_69() {
        assertAdminDate("08/31/69", "MDY", "BUDDHIST", 2)
    }

    @Test
    fun adminYearDigitRuleRejectsFourDigitYearWhenTwoDigitsRequired() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "31/08/2026",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "GREGORIAN",
            dateYearDigits = 2
        )
        assertNull(result.value)
        assertTrue(result.warning.orEmpty().contains("2 หลัก"))
    }

    @Test
    fun adminOrderRuleRejectsMdyTextWhenDmyIsRequired() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "08/31/2026",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "GREGORIAN",
            dateYearDigits = 4
        )
        assertNull(result.value)
    }

    private fun assertAdminDate(
        raw: String,
        order: String,
        calendar: String,
        yearDigits: Int
    ) {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = raw,
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = order,
            dateCalendar = calendar,
            dateYearDigits = yearDigits
        )
        assertEquals("31/08/2026", result.value)
        assertFalse(result.corrected)
    }
}
