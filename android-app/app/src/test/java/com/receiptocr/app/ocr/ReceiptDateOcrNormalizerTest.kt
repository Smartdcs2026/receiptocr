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
    fun thaiShortYear69IsResolvedTo2026NearWorkDate() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "22/08/69",
            configuredFormat = "DD/MM/YYYY",
            referenceDate = workDate
        )

        assertEquals("22/08/2026", result.value)
        assertTrue(result.corrected)
    }

    @Test
    fun gregorianShortYear26IsResolvedTo2026NearWorkDate() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "22/08/26",
            configuredFormat = "DD/MM/YYYY",
            referenceDate = workDate
        )

        assertEquals("22/08/2026", result.value)
        assertTrue(result.corrected)
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
}
