package com.receiptocr.app.ocr

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * ทำความสะอาดวันที่ที่อ่านจากบิลก่อนนำไปตรวจเงื่อนไข
 *
 * หลักสำคัญ:
 * - ใช้ลำดับ วัน/เดือน/ปี ตามรูปแบบบิลที่กำหนด
 * - ถ้าเดือนอ่านเพี้ยนเป็น 18/28 แต่ตำแหน่งนี้เป็นเดือน ให้ลองกู้เป็น 08
 * - ปี 2 หลักรองรับทั้งปีสากลและปีไทย โดยเลือกปีที่สมเหตุสมผลกับวันทำงาน
 * - แก้อัตโนมัติเฉพาะเมื่อได้วันที่จริงและอยู่ใกล้วันทำงาน ไม่เดาค่าที่ไม่มีหลักฐาน
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

        // ค่าที่เป็นวันที่จริงอยู่แล้ว ใช้ได้ทันที
        buildDate(year, month, day)?.let {
            return Result(it.format(output), corrected = yearToken.filter(Char::isDigit).length <= 2, original = cleaned)
        }

        // ตำแหน่งนี้ถูกกำหนดว่าเป็น "เดือน" จึงกู้เฉพาะความคลาดเคลื่อนที่อธิบายได้
        // เช่น 20/28/2026 -> 20/08/2026 และ 20/18/2026 -> 20/08/2026
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

    /**
     * ปี 2 หลักบนบิลไทยอาจหมายถึง 69 = พ.ศ. 2569 = ค.ศ. 2026
     * หรืออาจเป็นปีสากล 26 = ค.ศ. 2026 จึงสร้างตัวเลือกที่เป็นไปได้
     * แล้วเลือกปีที่ใกล้วันทำงานที่สุด แทนการบังคับ 20xx อย่างเดียว
     */
    private fun normalizeYear(token: String, referenceDate: LocalDate): Int? {
        val digits = token.filter(Char::isDigit)
        val raw = digits.toIntOrNull() ?: return null

        if (digits.length <= 2) {
            val candidates = buildSet {
                add(2000 + raw)
                add(1900 + raw)
                add(2500 + raw - 543) // ปีไทยแบบ 2 หลัก เช่น 69 -> 2569 -> 2026
            }.filter { it in 1900..2200 }
            return candidates.minByOrNull { year -> abs(year - referenceDate.year) }
        }

        var year = raw
        if (year in 2400..2999) year -= 543
        return year.takeIf { it in 1900..2200 }
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
            else -> DateOrder.DMY
        }
    }
}
