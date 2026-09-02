package com.receiptocr.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFacingOcrMessagesRound92Test {
    @Test
    fun confidenceDetailsAreHiddenFromUser() {
        val message = UserFacingOcrMessages.warning(
            "หลักฐานยืนยัน OCR: ลูกค้า 6 รอบ • วันที่ 1 รอบ • เวลา 6 รอบ • รอบที่อ่านข้อมูลหลักครบ 1 รอบ • ช่องที่ยืนยันเพียง 1 รอบยังต้องตรวจเทียบกับภาพก่อนส่ง"
        )
        assertTrue(message.contains("ตรวจเทียบกับภาพบิล"))
        assertFalse(message.contains("OCR", ignoreCase = true))
        assertFalse(message.contains("รอบ"))
        assertFalse(message.contains("หลักฐานยืนยัน"))
    }

    @Test
    fun correctedDateShowsOnlyAcceptedDate() {
        val message = UserFacingOcrMessages.warning(
            "วันที่ที่อ่านจากภาพ 20/08769 ถูกปรับเป็น 20/08/2026 ตามเงื่อนไข Admin • กรุณาตรวจเทียบกับภาพ"
        )
        assertTrue(message.contains("20/08/2026"))
        assertFalse(message.contains("20/08769"))
        assertFalse(message.contains("Admin", ignoreCase = true))
    }

    @Test
    fun conflictUsesFieldWorkLanguage() {
        val message = UserFacingOcrMessages.warning(
            "ภาพใหม่อ่านข้อมูล POS 2 ต่างจากข้อมูลเดิม • ระบบยังไม่ทับข้อมูลที่ยืนยันไว้"
        )
        assertTrue(message.contains("POS 2"))
        assertTrue(message.contains("ต่างจากข้อมูลที่บันทึกไว้"))
        assertFalse(message.contains("OCR", ignoreCase = true))
    }
}
