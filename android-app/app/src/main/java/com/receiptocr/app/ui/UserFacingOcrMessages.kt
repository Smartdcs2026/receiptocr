package com.receiptocr.app.ui

object UserFacingOcrMessages {
    private val correctionPattern = Regex(
        """วันที่ที่อ่านจากภาพ\s+\S+\s+ถูกปรับเป็น\s+\d{2}/\d{2}/\d{4}\s+ตามเงื่อนไข\s+Admin(?:\s*•\s*กรุณาตรวจเทียบกับภาพ)?""",
        RegexOption.IGNORE_CASE
    )

    fun warning(raw: String): String {
        val original = raw.trim()
        if (original.isBlank()) return ""
        val text = correctionPattern.replace(original, "")
            .split("•").map(String::trim).filter(String::isNotBlank).joinToString(" • ")
        if (text.isBlank()) return ""

        val messages = linkedSetOf<String>()
        if (
            text.contains("หลักฐานยืนยัน OCR", ignoreCase = true) || text.contains("ช่องที่ยืนยันเพียง") ||
            Regex("(?:ลูกค้า|วันที่|เวลา)\\s+\\d+\\s+รอบ").containsMatchIn(text)
        ) messages += "ข้อมูลบางช่องควรตรวจเทียบกับภาพบิล"

        if (
            text.contains("พบบิลซ้ำในร้านเดียวกัน") || text.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS") ||
            (text.contains("POS") && text.contains("ซ้ำ"))
        ) {
            val pos = Regex("POS\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)
            messages += if (pos != null) "พบบิลซ้ำในร้านเดียวกัน • POS $pos ซ้ำ" else "พบบิลซ้ำในร้านเดียวกัน • ตรวจบิล"
        } else if (text.contains("มากกว่า 1 ใบในภาพ") && text.contains("POS")) {
            val pos = Regex("POS\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)
            messages += if (pos != null) "พบ POS $pos มากกว่า 1 ใบ • ตรวจบิล" else "พบ POS มากกว่า 1 ใบ • ตรวจบิล"
        }

        if (text.contains("ภาพใหม่อ่านข้อมูล POS") && text.contains("ต่างจากข้อมูลเดิม")) {
            val pos = Regex("POS\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)
            messages += if (pos != null) "ข้อมูล POS $pos จากภาพล่าสุดต่างจากข้อมูลที่บันทึกไว้" else "ข้อมูลจากภาพล่าสุดต่างจากข้อมูลที่บันทึกไว้"
        }

        when {
            text.contains("พบบิลสลับร้าน") -> messages += "บิลสลับร้าน • ตรวจบิล"
            text.contains("บิลผิดร้าน") -> messages += "บิลผิดร้าน • รหัสร้านบนบิลไม่ตรงกับงาน"
            text.contains("รหัสร้าน") && text.contains("ไม่ตรง") -> messages += "รหัสร้านบนบิลไม่ตรงกับงาน"
            text.contains("ยืนยันร้านไม่ได้") || text.contains("ไม่พบรหัสร้าน") -> messages += "ยังอ่านรหัสร้านไม่ได้ • ตรวจภาพบิล"
        }

        if (text.contains("ค่านี้จะไม่ถูกใช้เป็นวันที่") || text.contains("วันที่ที่อ่านจากภาพ") && text.contains("ไม่ตรง")) messages += "วันที่บิลไม่ถูกต้อง • ตรวจจากภาพ"
        if (text.contains("ค่านี้จะไม่ถูกใช้เป็นเวล") || text.contains("เวลาที่อ่านจากภาพไม่ถูกต้อง")) messages += "เวลาในบิลไม่ถูกต้อง • ตรวจจากภาพ"
        if (text.contains("ไม่พบวันที่")) messages += "ยังไม่พบวันที่บิล"
        if (text.contains("ไม่พบเวลา")) messages += "ยังไม่พบเวลาในบิล"
        if (text.contains("ไม่พบยอด/เลขลูกค้า") || text.contains("ยังไม่มีเลข/ยอดลูกค้า")) messages += "ยังไม่พบเลข/ยอดลูกค้า"
        if (text.contains("ยังอ่านไม่ครบ") && text.contains("ขาด POS")) {
            Regex("ขาด POS\\s+([^•]+)").find(text)?.groupValues?.getOrNull(1)?.trim()?.let { messages += "ยังขาด POS $it" }
        }
        if (text.contains("ยังไม่ได้กำหนดว่าจะลง POS ใด")) {
            val identity = Regex("""หมายเลขเครื่อง\s+([A-Za-z0-9]+)""").find(text)?.groupValues?.getOrNull(1)
            messages += if (identity != null) "พบเครื่อง $identity แต่ยังไม่มีช่อง POS" else "พบเครื่องที่ยังไม่มีช่อง POS"
        }
        if (text.contains("ถูกระบุว่าไม่ได้บิล")) {
            val pos = Regex("""POS\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)
            messages += if (pos != null) "พบข้อมูล POS $pos แต่เลือกไว้ว่าไม่ได้บิล" else "พบข้อมูลในช่องที่เลือกไว้ว่าไม่ได้บิล"
        }
        if (messages.isEmpty()) messages += "ตรวจข้อมูลกับภาพบิลก่อนใช้"
        return messages.joinToString(" • ")
    }

    fun hasVisibleWarning(raw: String): Boolean = warning(raw).isNotBlank()

    fun isCritical(raw: String): Boolean {
        val text = raw.trim()
        return text.contains("บิลผิดร้าน") || text.contains("บิลสลับร้าน") || text.contains("ร้านอื่นปะปน") ||
            text.contains("พบบิลซ้ำ") || text.contains("มากกว่า 1 ใบในภาพ") ||
            (text.contains("POS") && text.contains("ซ้ำ")) || text.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS")
    }

    fun dateInfo(rawDate: String, canonicalDate: String): String {
        val raw = rawDate.trim()
        val canonical = canonicalDate.trim()
        if (raw.isBlank() || canonical.isBlank()) return ""
        val normalizedPunctuation = raw.replace('.', '/').replace('-', '/').replace(Regex("\\s+"), "")
        if (normalizedPunctuation == canonical) return ""
        val structured = Regex("""^\d{1,4}[./-]\d{1,2}[./-]\d{2,4}$""").matches(raw)
        return if (structured) "วันที่บนบิล $raw → $canonical" else "วันที่บิลใช้เป็น $canonical"
    }

    fun summary(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""
        return when {
            text.contains("ไม่พบข้อความในภาพ") -> "ยังอ่านบิลไม่ได้ • ถ่ายใหม่ให้ชัด"
            text.contains("ยังอ่านไม่ครบ") && text.contains("ขาด") -> text
            text.contains("ยังแยกข้อมูล") || text.contains("ยังอ่านหมายเลขเครื่องไม่ได้") -> "อ่านบิลยังไม่ครบ • ตรวจภาพแล้วลองอีกครั้ง"
            text.contains("สำเร็จ") || text.contains("พบ") -> "อ่านบิลแล้ว • ตรวจข้อมูลก่อนใช้"
            else -> "ตรวจข้อมูลกับภาพบิลก่อนใช้"
        }
    }
}
