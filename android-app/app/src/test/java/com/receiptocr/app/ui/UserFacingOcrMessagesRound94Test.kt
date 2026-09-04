package com.receiptocr.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFacingOcrMessagesRound94Test {
    @Test fun duplicate_warning_is_field_language_not_technical_phrase() {
        val message=UserFacingOcrMessages.warning("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS 2 • กรุณาตรวจว่ามีบิล POS ซ้ำหรือไม่")
        assertTrue(message.contains("POS 2 ซ้ำ"))
        assertFalse(message.contains("มากกว่าหนึ่งชุด"))
    }

    @Test fun store_integrity_is_explicit() {
        assertTrue(UserFacingOcrMessages.warning("บิลผิดร้าน • รหัสร้านที่อ่านได้ 999 ไม่ตรงกับรหัสร้านของงาน 123").contains("บิลผิดร้าน"))
        assertTrue(UserFacingOcrMessages.warning("พบบิลสลับร้าน • รหัสร้านที่อ่านได้ 999 ควรเป็น 123").contains("บิลสลับร้าน"))
    }
}
