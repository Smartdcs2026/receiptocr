package com.receiptocr.app.ocr

/** แปลงเวลาที่ OCR อ่านได้ให้เก็บเป็น HH:mm เสมอ */
object ReceiptTimeOcrNormalizer {
    data class Result(
        val value: String?,
        val original: String = "",
        val warning: String? = null
    )

    fun normalize(raw: String): Result {
        val cleaned = OcrTextNormalizer.normalizeDigits(raw.trim())
            .replace('.', ':')
            .replace(Regex("\\s+"), "")
        val parts = cleaned.split(':')
        if (parts.size !in 2..3) {
            return Result(null, cleaned, "รูปแบบเวลาไม่ถูกต้อง")
        }
        val hour = parts[0].toIntOrNull()
            ?: return Result(null, cleaned, "ชั่วโมงอ่านเป็นตัวเลขไม่ได้")
        val minute = parts[1].toIntOrNull()
            ?: return Result(null, cleaned, "นาทีอ่านเป็นตัวเลขไม่ได้")
        val second = parts.getOrNull(2)?.toIntOrNull()
        if (hour !in 0..23 || minute !in 0..59 || (second != null && second !in 0..59)) {
            return Result(null, cleaned, "เวลาที่อ่านได้ไม่มีอยู่จริง")
        }
        return Result("%02d:%02d".format(hour, minute), original = cleaned)
    }
}
