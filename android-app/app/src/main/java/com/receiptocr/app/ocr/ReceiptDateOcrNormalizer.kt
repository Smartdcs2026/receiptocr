package com.receiptocr.app.ocr

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * แก้ค่าที่ OCR อ่านจากช่องวันที่โดยอาศัย "ตำแหน่งของข้อมูล" และรูปแบบที่ Admin กำหนด
 * ก่อนส่งค่าต่อไปยัง validation
 *
 * หลักสำคัญ:
 * - ไม่สลับ dd/MM กับ MM/dd เองเมื่อ Admin กำหนดรูปแบบไว้แล้ว
 * - ถ้าช่องเดือนอ่านได้เป็นเลขที่เป็นไปไม่ได้ เช่น 18 หรือ 28 แต่หลักหน่วยเป็น 1-9
 *   ให้ลองตีความเป็น 08/09/... เพราะเรารู้อยู่แล้วว่าตำแหน่งนี้คือเดือน
 * - การแก้อัตโนมัติต้องได้วันที่จริงในปฏิทิน และอยู่ใกล้วันงานพอสมควร
 * - ถ้ายังยืนยันไม่ได้ ให้คืนค่าดิบเพื่อให้ validation/ผู้ใช้ตรวจต่อ ไม่เดาสุ่ม
 */
object ReceiptDateOcrNormalizer {
    private val output = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    data class Result(
        val value: String?,
        val corrected: Boolean = false,
        val original: String = ""
    )

    fun normalize(
        raw: String,
        configuredFormat: String?,
        referenceDate: LocalDate,
        maxAutoCorrectionDistanceDays: Long = 45
    ): Result {
        val cleaned = OcrTextNormalizer.normalizeDigits(raw.trim())
            .replace('.', '/')
            .replace('-', '/')
        val parts = cleaned.split('/').map { it.trim() }
        if (parts.size != 3) return Result(null, original = cleaned)

        val order = resolveOrder(configuredFormat)
        val dayToken: String
        val monthToken: String
        val yearToken: String
        when (order) {
            DateOrder.MDY -> {
                monthToken = parts[0]
                dayToken = parts[1]
                yearToken = parts[2]
            }
            DateOrder.YMD -> {
                yearToken = parts[0]
                monthToken = parts[1]
                dayToken = parts[2]
            }
            DateOrder.DMY -> {
                dayToken = parts[0]
                monthToken = parts[1]
                yearToken = parts[2]
            }
        }

        val day = dayToken.toIntOrNull() ?: return Result(null, original = cleaned)
        val year = normalizeYear(yearToken, referenceDate) ?: return Result(null, original = cleaned)
        val month = monthToken.toIntOrNull() ?: return Result(null, original = cleaned)

        // แม้เป็นวันที่จริงในปฏิทิน ก็ยังต้องอยู่ใกล้วันงานพอที่จะเป็นวันที่จากบิลนี้
        // ป้องกัน OCR อ่านปี 69/61 แล้วกลายเป็น 2069/2061 และถูกนำไปใช้ต่อ
        buildDate(year, month, day)?.let { date ->
            val distance = abs(ChronoUnit.DAYS.between(referenceDate, date))
            if (distance > maxAutoCorrectionDistanceDays) {
                return Result(null, original = cleaned)
            }
            return Result(date.format(output), corrected = false, original = cleaned)
        }

        // ถ้า "เดือน" เป็นไปไม่ได้ ให้ใช้ข้อเท็จจริงว่าตำแหน่งนี้คือเดือนช่วยแก้ OCR
        // ตัวอย่าง 20/28/2026 -> 20/08/2026, 20/18/2026 -> 20/08/2026
        val correctedMonth = recoverMonth(monthToken) ?: return Result(null, original = cleaned)
        val correctedDate = buildDate(year, correctedMonth, day) ?: return Result(null, original = cleaned)
        val distance = abs(ChronoUnit.DAYS.between(referenceDate, correctedDate))
        if (distance > maxAutoCorrectionDistanceDays) return Result(null, original = cleaned)

        return Result(
            value = correctedDate.format(output),
            corrected = true,
            original = cleaned
        )
    }

    private fun recoverMonth(token: String): Int? {
        val digits = token.filter(Char::isDigit)
        if (digits.length != 2) return null
        val raw = digits.toIntOrNull() ?: return null
        if (raw in 1..12) return raw
        val last = digits.last().digitToIntOrNull() ?: return null
        return last.takeIf { it in 1..9 }
    }

    private fun normalizeYear(token: String, referenceDate: LocalDate): Int? {
        val raw = token.filter(Char::isDigit).toIntOrNull() ?: return null
        if (raw in 2400..2999) return (raw - 543).takeIf { it in 1900..2200 }
        if (raw >= 100) return raw.takeIf { it in 1900..2200 }

        // ใบเสร็จไทยพบทั้ง ค.ศ. 2 หลัก (26 = 2026) และ พ.ศ. 2 หลัก (69 = 2569 = 2026)
        // เลือกปีที่ใกล้วันงานที่สุด แทนการบังคับว่าเลข < 70 ต้องเป็น 20xx เสมอ
        val candidates = listOf(
            2000 + raw,
            1900 + raw,
            2500 + raw - 543
        ).filter { it in 1900..2200 }.distinct()
        return candidates.minByOrNull { abs(it - referenceDate.year) }
    }

    private fun buildDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private enum class DateOrder { DMY, MDY, YMD }

    private fun resolveOrder(format: String?): DateOrder {
        val value = format.orEmpty().uppercase()
            .replace("YYYY", "Y")
            .replace("YY", "Y")
            .replace("DD", "D")
            .replace("MM", "M")
            .replace(Regex("[^DMY]"), "")
        return when {
            value.startsWith("MDY") -> DateOrder.MDY
            value.startsWith("YMD") -> DateOrder.YMD
            else -> DateOrder.DMY // DATE/ANY/legacy = dd/MM/yyyy
        }
    }
}
