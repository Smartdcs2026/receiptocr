package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ReceiptDateDeterministicProofRound95Test {
    private fun field(example: String, order: String = "DMY") = OcrTemplateField(
        order = 1, type = "BILL_DATE", example = example, required = true,
        minLength = 1, maxLength = 10, format = "DATE",
        dateOrder = order, dateCalendar = "GREGORIAN", dateYearDigits = 4
    )

    @Test fun dayLeadingZeroMayRecoverOnlyWhenActualDateLeavesOneCandidate() {
        val result = ReceiptDateOcrNormalizer.normalizeForField(
            raw = "2/08/2026", field = field("02/08/2026"),
            referenceDate = LocalDate.of(2026, 8, 3),
            actualDate = LocalDate.of(2026, 8, 3)
        )
        assertEquals("02/08/2026", result.value)
    }

    @Test fun dayLeadingDigitIsRejectedWhenMoreThanOnePastCandidateExists() {
        val result = ReceiptDateOcrNormalizer.normalizeForField(
            raw = "2/08/2026", field = field("02/08/2026"),
            referenceDate = LocalDate.of(2026, 8, 12),
            actualDate = LocalDate.of(2026, 8, 12)
        )
        assertNull(result.value)
        assertEquals("วันที่บนบิลอ่านไม่ครบ กรุณาตรวจภาพบิล", result.warning)
    }

    @Test fun adminWorkWindowMustNotSelect24FromAmbiguous4() {
        val result = ReceiptDateOcrNormalizer.normalizeForField(
            raw = "4/08/2026", field = field("04/08/2026"),
            referenceDate = LocalDate.of(2026, 8, 25),
            actualDate = LocalDate.of(2026, 8, 25)
        )
        assertNull(result.value)
    }

    @Test fun monthLeadingZeroMayRecoverWhenOnlyRealHistoricalMonthExists() {
        val result = ReceiptDateOcrNormalizer.normalizeForField(
            raw = "02/8/2026", field = field("02/08/2026"),
            referenceDate = LocalDate.of(2026, 8, 3),
            actualDate = LocalDate.of(2026, 8, 3)
        )
        assertEquals("02/08/2026", result.value)
    }

    @Test fun templateThatIntentionallyUsesOneDigitDayDoesNotTriggerRepair() {
        val result = ReceiptDateOcrNormalizer.normalizeForField(
            raw = "4/08/2026", field = field("4/08/2026"),
            referenceDate = LocalDate.of(2026, 8, 4),
            actualDate = LocalDate.of(2026, 8, 4)
        )
        assertEquals("04/08/2026", result.value)
    }

    @Test fun mdyUsesTemplateOrderBeforeProof() {
        val result = ReceiptDateOcrNormalizer.normalizeForField(
            raw = "8/02/2026", field = field("08/02/2026", order = "MDY"),
            referenceDate = LocalDate.of(2026, 8, 3),
            actualDate = LocalDate.of(2026, 8, 3)
        )
        assertEquals("02/08/2026", result.value)
    }
}
