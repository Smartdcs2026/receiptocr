package com.receiptocr.app.ui

/**
 * Converts internal OCR diagnostics into short field-work language.
 * Technical evidence stays in the record for validation/debugging but is not shown to users.
 */
object UserFacingOcrMessages {
    fun warning(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""

        val messages = linkedSetOf<String>()

        Regex("ถูกปรับเป็น\\s+(\\d{2}/\\d{2}/\\d{4})").find(text)?.groupValues?.getOrNull(1)?.let { date ->
            messages += "วันที่บิล $date ควรตรวจเทียบกับภาพ"
        }

        if (
            text.contains("หลักฐานยืนยัน OCR", ignoreCase = true) ||
            text.contains("ช่องที่ยืนยันเพียง") ||
            Regex("(?:ลูกค้า|วันที่|เวลา)\\s+\\d+\\s+รอบ").containsMatchIn(text)
        ) {
            messages += "ข้อมูลบางช่องควรตรวจเทียบกับภาพบิล"
        }

        if (text.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS")) {
            val pos = Regex("POS\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)
            messages += if (pos != null) {
                "พบข้อมูลบิลมากกว่าหนึ่งชุดสำหรับ POS $pos กรุณาตรวจภาพบิล"
            } else {
                "พบข้อมูลบิลมากกว่าหนึ่งชุด กรุณาตรวจภาพบิล"
            }
        }

        if (text.contains("ภาพใหม่อ่านข้อมูล POS") && text.contains("ต่างจากข้อมูลเดิม")) {
            val pos = Regex("POS\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)
            messages += if (pos != null) {
                "ข้อมูลจากภาพล่าสุดของ POS $pos ต่างจากข้อมูลที่บันทึกไว้ กรุณาตรวจสอบก่อนเปลี่ยน"
            } else {
                "ข้อมูลจากภาพล่าสุดต่างจากข้อมูลที่บันทึกไว้ กรุณาตรวจสอบก่อนเปลี่ยน"
            }
        }

        if (text.contains("รหัสร้าน") && text.contains("ไม่ตรง")) {
            messages += "รหัสร้านบนบิลไม่ตรงกับร้านในแผนงาน"
        } else if (text.contains("ยืนยันร้านไม่ได้") || text.contains("ไม่พบรหัสร้าน")) {
            messages += "ยังตรวจสอบรหัสร้านจากบิลไม่ได้ กรุณาตรวจภาพบิล"
        }

        if (text.contains("ค่านี้จะไม่ถูกใช้เป็นวันที่") || text.contains("วันที่ที่อ่านจากภาพ") && text.contains("ไม่ตรง")) {
            messages += "วันที่บิลยังไม่ถูกต้อง กรุณาตรวจจากภาพบิล"
        }
        if (text.contains("ค่านี้จะไม่ถูกใช้เป็นเวล") || text.contains("เวลาที่อ่านจากภาพไม่ถูกต้อง")) {
            messages += "เวลาในบิลยังไม่ถูกต้อง กรุณาตรวจจากภาพบิล"
        }
        if (text.contains("ไม่พบวันที่")) messages += "ยังไม่พบวันที่บิล"
        if (text.contains("ไม่พบเวลา")) messages += "ยังไม่พบเวลาในบิล"
        if (text.contains("ไม่พบยอด/เลขลูกค้า") || text.contains("ยังไม่มีเลข/ยอดลูกค้า")) {
            messages += "ยังไม่พบเลข/ยอดลูกค้า"
        }

        if (messages.isEmpty()) {
            messages += "กรุณาตรวจข้อมูลกับภาพบิลก่อนใช้งาน"
        }
        return messages.joinToString(" • ")
    }

    fun summary(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""
        return when {
            text.contains("ไม่พบข้อความในภาพ") -> "ยังอ่านข้อมูลจากภาพไม่ได้ กรุณาถ่ายใหม่ให้บิลชัดเจน"
            text.contains("ยังแยกข้อมูล") || text.contains("ยังอ่านหมายเลขเครื่องไม่ได้") ->
                "ยังอ่านข้อมูลที่จำเป็นจากภาพไม่ครบ กรุณาตรวจภาพแล้วลองอีกครั้ง"
            text.contains("สำเร็จ") || text.contains("พบ") -> "อ่านข้อมูลจากบิลแล้ว กรุณาตรวจความถูกต้องก่อนใช้"
            else -> "กรุณาตรวจข้อมูลจากภาพบิลก่อนใช้งาน"
        }
    }
}
