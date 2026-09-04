package com.receiptocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFacingOcrMessagesRound96Test {
    @Test fun wrongStoreIsShortAndCritical() {
        val raw = "บิลผิดร้าน • รหัสร้านที่อ่านได้ (7600) ไม่ตรงกับรหัสร้านของงาน (1600)"
        assertEquals("บิลผิดร้าน • รหัสร้านบนบิลไม่ตรงกับงาน", UserFacingOcrMessages.warning(raw))
        assertTrue(UserFacingOcrMessages.isCritical(raw))
    }

    @Test fun duplicateIsShortAndCritical() {
        val raw = "พบบิลซ้ำในร้านเดียวกัน • POS 2 มีมากกว่า 1 ใบ"
        assertEquals("พบบิลซ้ำในร้านเดียวกัน • POS 2 ซ้ำ", UserFacingOcrMessages.warning(raw))
        assertTrue(UserFacingOcrMessages.isCritical(raw))
    }

    @Test fun missingTimeIsCautionNotCritical() {
        val raw = "ไม่พบเวลา ตามเงื่อนไขที่กำหนด"
        assertEquals("ยังไม่พบเวลาในบิล", UserFacingOcrMessages.warning(raw))
        assertFalse(UserFacingOcrMessages.isCritical(raw))
    }
}
