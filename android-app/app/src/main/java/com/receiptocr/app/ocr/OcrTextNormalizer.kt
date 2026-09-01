package com.receiptocr.app.ocr

/**
 * แปลงอักขระที่ OCR มักอ่านสลับกัน และค้นหาเลข POS โดยไม่ผูกกับแบรนด์
 * ใช้ร่วมกันทั้งแม่แบบและกฎตำแหน่ง เพื่อให้ผลจากสองทางตีความเหมือนกัน
 */
object OcrTextNormalizer {
    private const val OCR_DIGITS = "0-9OoIl|"
    private val labeledPos = Regex(
        "(?i)(?:\\bP\\s*\\.?\\s*O\\s*\\.?\\s*S\\.?|\\bTERMINAL\\b|เครื่อง)" +
            "\\s*[:#=\\-]?\\s*(?:(?:N\\s*[O0]|NO|NUMBER)\\s*\\.?\\s*)?" +
            "([$OCR_DIGITS]{1,3})"
    )
    private val prefixedPos = Regex("(?i)\\b([NB])\\s*([$OCR_DIGITS]{1,3})\\b")
    private val standalonePos = Regex("(?i)^\\s*(?:[NB]\\s*)?([$OCR_DIGITS]{1,3})\\s*$")

    fun normalizeDigits(value: String): String = value.map { character ->
        when (character) {
            'O', 'o' -> '0'
            'I', 'i', 'l', '|' -> '1'
            else -> character
        }
    }.joinToString("")

    fun normalizeLine(value: String): String {
        var normalized = value.trim().replace(Regex("\\s+"), " ")
        normalized = labeledPos.replace(normalized) { match ->
            val digits = normalizeDigits(match.groupValues[1]).filter(Char::isDigit)
            if (digits.isBlank()) match.value else "POS N${digits.padStart(2, '0')}"
        }
        return normalized.replace(Regex("\\s*([:/.-])\\s*"), "${'$'}1")
    }

    /** คืนทุกเลข POS ที่มีคำกำกับหรือคำนำหน้า N/B ในข้อความเดียว */
    fun findPosNumbers(value: String): List<Int> {
        val found = mutableListOf<Int>()
        labeledPos.findAll(value).forEach { match ->
            normalizedPositiveInt(match.groupValues[1])?.let(found::add)
        }
        prefixedPos.findAll(value).forEach { match ->
            normalizedPositiveInt(match.groupValues[2])?.let(found::add)
        }
        return found.distinct().sorted()
    }

    /** ใช้เมื่อกฎของ Admin จับข้อความเฉพาะส่วน เช่น N04 หรือ 004 */
    fun parsePosNumber(value: String): Int? {
        findPosNumbers(value).firstOrNull()?.let { return it }
        return standalonePos.matchEntire(value)?.groupValues?.getOrNull(1)?.let(::normalizedPositiveInt)
            ?: normalizedPositiveInt(value.filter { it.isDigit() || it in "OoIl|" })
    }

    /** ใช้กับบรรทัดข้างเคียงของคำว่า POS เพื่อไม่หยิบเลขวันที่หรือยอดขายมาเป็น POS */
    fun parseStandalonePosNumber(value: String): Int? =
        standalonePos.matchEntire(value)?.groupValues?.getOrNull(1)?.let(::normalizedPositiveInt)

    private fun normalizedPositiveInt(value: String): Int? = normalizeDigits(value)
        .filter(Char::isDigit)
        .toIntOrNull()
        ?.takeIf { it > 0 }
}
