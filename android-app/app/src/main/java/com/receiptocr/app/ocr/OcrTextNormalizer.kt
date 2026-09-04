package com.receiptocr.app.ocr

/**
 * แปลงอักขระที่ระบบอ่านภาพมักอ่านสลับกัน และค้นหาเลข POS โดยไม่ผูกกับแบรนด์
 * ใช้ร่วมกันทั้งแม่แบบและกฎตำแหน่ง เพื่อให้ผลจากสองทางตีความเหมือนกัน
 */
object OcrTextNormalizer {
    private const val OCR_DIGITS = "0-9OoIl|"
    private const val DENSE_DIGITS = "0-9OoIl|SsZzBb"
    private val labeledPos = Regex(
        "(?i)(?:\\bP\\s*\\.?\\s*O\\s*\\.?\\s*S\\.?|\\bTERMINAL\\b|เครื่อง)" +
            "\\s*[:#=\\-]?\\s*(?:(?:N\\s*[O0]|NO|NUMBER)\\s*\\.?\\s*)?" +
            "([$OCR_DIGITS]{1,3})"
    )
    private val prefixedPos = Regex("(?i)\\b([NB])\\s*([$OCR_DIGITS]{1,3})\\b")
    private val standalonePos = Regex("(?i)^\\s*(?:[A-Z]{1,4}\\s*)?([$OCR_DIGITS]{1,3})\\s*$")
    private val denseVBetweenNumbers = Regex("([$DENSE_DIGITS]{4,})[Vv]([$DENSE_DIGITS]{4,})")

    fun normalizeDigits(value: String): String = value.map { character ->
        when (character) {
            'O', 'o' -> '0'
            'I', 'i', 'l', '|' -> '1'
            else -> character
        }
    }.joinToString("")


    /** แสดงรหัส POS ตามที่เห็นบนบิลโดยยังเก็บอักษรนำหน้าไว้ */
    fun displayPosIdentity(value: String): String? {
        var text = normalizeDigits(value).trim().uppercase().replace(Regex("""\s+"""), "")
        text = text.removePrefix("POS").trimStart(':', '#', '=', '-')
        val match = Regex("^([A-Z]{1,4})?([0-9]{1,3})$").matchEntire(text) ?: return null
        return match.groupValues[1] + match.groupValues[2]
    }

    /** key สำหรับเทียบ mapping: N01/N1 -> N1, B01 -> B1, 01 -> 1 */
    fun normalizePosIdentity(value: String): String? {
        val display = displayPosIdentity(value) ?: return null
        val match = Regex("^([A-Z]{1,4})?([0-9]{1,3})$").matchEntire(display) ?: return null
        val number = match.groupValues[2].toIntOrNull() ?: return null
        if (number <= 0) return null
        return match.groupValues[1] + number.toString()
    }

    fun normalizeLine(value: String): String {
        var normalized = value.trim().replace(Regex("\\s+"), " ")
        normalized = labeledPos.replace(normalized) { match ->
            val digits = normalizeDigits(match.groupValues[1]).filter(Char::isDigit)
            if (digits.isBlank()) match.value else "POS N${digits.padStart(2, '0')}"
        }
        normalized = normalized.replace(Regex("\\s*([:/\\.#-])\\s*"), "${'$'}1")
        return normalizeDenseReceiptCode(normalized)
    }

    /**
     * บิลพิมพ์แบบจุดบางรุ่นทำให้ตัวอักษรในชุดเลขติดกันถูกอ่านคลาดได้
     * แก้เฉพาะตัวที่อยู่คั่นกลางชุดตัวเลขยาว เพื่อไม่กระทบข้อความทั่วไป
     */
    internal fun normalizeDenseReceiptCode(value: String): String {
        var normalized = value

        // U ที่คั่นกลางเลขมักถูกอ่านคล้าย V ในตัวพิมพ์แบบจุด
        normalized = denseVBetweenNumbers.replace(normalized) { match ->
            "${match.groupValues[1]}U${match.groupValues[2]}"
        }

        // แก้ตัวที่มีรูปร่างคล้ายเลข เฉพาะเมื่อถูกประกบด้วยตัวเลขเท่านั้น
        normalized = replaceDigitLikeBetweenDigits(normalized, 'S', '5')
        normalized = replaceDigitLikeBetweenDigits(normalized, 's', '5')
        normalized = replaceDigitLikeBetweenDigits(normalized, 'Z', '2')
        normalized = replaceDigitLikeBetweenDigits(normalized, 'z', '2')
        normalized = replaceDigitLikeBetweenDigits(normalized, 'B', '8')
        normalized = replaceDigitLikeBetweenDigits(normalized, 'b', '8')

        // เลข 9 ท้ายส่วนวันที่อาจถูกอ่านเป็น g แต่ต้องอยู่ติดกับเลขและตามด้วยขอบเขตข้อมูล
        normalized = normalized.replace(
            Regex("(?<=[0-9OoIl|])[g](?=\\b|[\\s:/.-])"),
            "9"
        )
        return normalized
    }

    private fun replaceDigitLikeBetweenDigits(value: String, from: Char, to: Char): String =
        value.replace(
            Regex("(?<=[0-9OoIl|])${Regex.escape(from.toString())}(?=[0-9OoIl|])"),
            to.toString()
        )

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
