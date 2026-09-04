package com.receiptocr.app.validation

import com.receiptocr.app.config.ReceiptGroupDateRule
import com.receiptocr.app.model.PosRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReceiptValidationEngineTest {
    private val workDate = LocalDate.of(2026, 8, 23)
    private val rule = ReceiptGroupDateRule(
        enabled = true,
        maxBeforeDays = 2,
        afterDaysWhenOldestIsMaxBefore = 0,
        afterDaysWhenOldestIsOneDayBefore = 2,
        afterDaysWhenOldestIsWorkDay = 2
    )

    @Test
    fun impossibleMonthIsCalendarErrorNotFormatError() {
        val record = PosRecord(posNumber = 2, billDate = "20/28/2026")
        val issue = ReceiptValidationEngine.individualDateIssue(record, workDate, rule)

        assertTrue(issue.orEmpty().contains("เดือนที่อ่านได้ (28) ไม่มีอยู่จริง"))
    }

    @Test
    fun impossibleDayInMonthIsCalendarError() {
        val record = PosRecord(posNumber = 2, billDate = "31/02/2026")
        val issue = ReceiptValidationEngine.individualDateIssue(record, workDate, rule)

        assertTrue(issue.orEmpty().contains("ไม่มีอยู่จริง"))
        assertTrue(issue.orEmpty().contains("เดือน 02"))
    }

    @Test
    fun validLeapDayPassesCalendarParsing() {
        val leapWorkDate = LocalDate.of(2028, 2, 29)
        val record = PosRecord(posNumber = 1, billDate = "29/02/2028")
        val leapRule = rule.copy(maxBeforeDays = 0)

        assertEquals(null, ReceiptValidationEngine.individualDateIssue(record, leapWorkDate, leapRule))
    }

    @Test
    fun wrongShapeReportsAdminDrivenDateCondition() {
        val record = PosRecord(posNumber = 1, billDate = "2026-08-23")
        val issue = ReceiptValidationEngine.individualDateIssue(record, workDate, rule)

        assertTrue(issue.orEmpty().contains("รูปแบบวันที่ของร้าน"))
        assertTrue(issue.orEmpty().contains("ไม่สามารถนำไปใช้ได้"))
    }

    @Test
    fun threeDaysBeforeWorkStillReportsRangeRule() {
        val record = PosRecord(posNumber = 1, billDate = "20/08/2026")
        val issue = ReceiptValidationEngine.individualDateIssue(record, workDate, rule)

        assertEquals("ย้อนหลังเกิน 2 วัน", issue)
    }
}
