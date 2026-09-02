package com.receiptocr.app.ocr

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * แปลงวันที่จากข้อความ OCR ตามเงื่อนไขที่ Admin กำหนด แล้วคืนค่าเก็บมาตรฐาน dd/MM/yyyy
 *
 * รองรับ:
 * - DMY / MDY / YMD
 * - พ.ศ. / ค.ศ. / รับทั้งสองระบบ
 * - ปี 2 หลัก / 4 หลัก / รับได้ทั้ง 2 และ 4 หลัก
 *
 * ถ้าข้อความไม่ตรงเงื่อนไข จะไม่เดาเป็นค่าที่ใช้ส่งงาน แต่คืน original + warning
 * เพื่อให้หน้าตรวจทานยังแสดงสิ่งที่ OCR อ่านได้จริง
 */
object ReceiptDateOcrNormalizer {
    private val output = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    data class Result(
        val value: String?,
        val corrected: Boolean = false,
        val original: String = "",
        val warning: String? = null
    )

    fun normalize(
        raw: String,
        configuredFormat: String?,
        referenceDate: LocalDate,
        maxAutoCorrectionDistanceDays: Long = 45,
        dateOrder: String? = null,
        dateCalendar: String? = null,
        dateYearDigits: Int = 0
    ): Result {
        val cleaned = OcrTextNormalizer.normalizeDigits(raw.trim())
            .replace('.', '/')
            .replace('-', '/')
        val parts = cleaned.split('/').map { it.trim() }
        if (parts.size != 3) {
            return Result(null, original = cleaned, warning = "รูปแบบวันที่ไม่ตรงเงื่อนไขที่กำหนด")
        }

        val order = resolveOrder(dateOrder, configuredFormat)
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

        val day = dayToken.toIntOrNull()
            ?: return Result(null, original = cleaned, warning = "วันในบิลอ่านเป็นตัวเลขไม่ได้")
        val monthRaw = monthToken.toIntOrNull()
            ?: return Result(null, original = cleaned, warning = "เดือนในบิลอ่านเป็นตัวเลขไม่ได้")
        val yearDigitsActual = yearToken.filter(Char::isDigit).length
        if (dateYearDigits in setOf(2, 4) && yearDigitsActual != dateYearDigits) {
            return Result(
                null,
                original = cleaned,
                warning = "ปีบนบิลมี $yearDigitsActual หลัก แต่กำหนดให้ใช้ $dateYearDigits หลัก"
            )
        }

        val calendar = resolveCalendar(dateCalendar)
        val year = normalizeYear(yearToken, calendar, referenceDate)
            ?: return Result(
                null,
                original = cleaned,
                warning = when (calendar) {
                    DateCalendar.BUDDHIST -> "ปีบนบิลไม่ตรงเงื่อนไข พ.ศ."
                    DateCalendar.GREGORIAN -> "ปีบนบิลไม่ตรงเงื่อนไข ค.ศ."
                    DateCalendar.AUTO -> "ปีบนบิลไม่อยู่ในรูปแบบ พ.ศ. หรือ ค.ศ. ที่รองรับ"
                }
            )

        // ใช้ค่าที่ถูกต้องก่อน
        buildDate(year, monthRaw, day)?.let { date ->
            return verifyDistance(date, cleaned, referenceDate, maxAutoCorrectionDistanceDays)
        }

        // OCR มักแทรกหลักเกินในตำแหน่งเดือน เช่น 28/18 ทั้งที่ตำแหน่งนี้รู้แน่ว่าเป็นเดือน
        val correctedMonth = recoverMonth(monthToken)
            ?: return Result(null, original = cleaned, warning = "วันที่ที่อ่านได้ไม่มีอยู่จริงตามปฏิทิน")
        val correctedDate = buildDate(year, correctedMonth, day)
            ?: return Result(null, original = cleaned, warning = "วันที่ที่อ่านได้ไม่มีอยู่จริงตามปฏิทิน")
        val checked = verifyDistance(correctedDate, cleaned, referenceDate, maxAutoCorrectionDistanceDays)
        return if (checked.value != null) checked.copy(corrected = true) else checked
    }

    private fun verifyDistance(
        date: LocalDate,
        original: String,
        referenceDate: LocalDate,
        maxDistanceDays: Long
    ): Result {
        val distance = abs(ChronoUnit.DAYS.between(referenceDate, date))
        if (distance > maxDistanceDays) {
            return Result(
                null,
                original = original,
                warning = "วันที่ที่อ่านจากภาพ ($original) ห่างจากวันงานมากผิดปกติ"
            )
        }
        return Result(date.format(output), corrected = false, original = original)
    }

    private fun recoverMonth(token: String): Int? {
        val digits = token.filter(Char::isDigit)
        if (digits.length != 2) return null
        val raw = digits.toIntOrNull() ?: return null
        if (raw in 1..12) return raw
        val last = digits.last().digitToIntOrNull() ?: return null
        return last.takeIf { it in 1..9 }
    }

    private fun normalizeYear(
        token: String,
        calendar: DateCalendar,
        referenceDate: LocalDate
    ): Int? {
        val digits = token.filter(Char::isDigit)
        val raw = digits.toIntOrNull() ?: return null

        val candidates = when (calendar) {
            DateCalendar.BUDDHIST -> when (digits.length) {
                4 -> listOfNotNull((raw - 543).takeIf { raw in 2400..2999 && it in 1900..2200 })
                2 -> listOf(2500 + raw - 543).filter { it in 1900..2200 }
                else -> emptyList()
            }
            DateCalendar.GREGORIAN -> when (digits.length) {
                4 -> listOf(raw).filter { it in 1900..2200 }
                2 -> listOf(2000 + raw, 1900 + raw).filter { it in 1900..2200 }
                else -> emptyList()
            }
            DateCalendar.AUTO -> when (digits.length) {
                4 -> buildList {
                    if (raw in 1900..2200) add(raw)
                    if (raw in 2400..2999) add(raw - 543)
                }
                2 -> listOf(2000 + raw, 1900 + raw, 2500 + raw - 543)
                    .filter { it in 1900..2200 }
                else -> emptyList()
            }
        }.distinct()

        return candidates.minByOrNull { abs(it - referenceDate.year) }
    }

    private fun buildDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private enum class DateOrder { DMY, MDY, YMD }
    private enum class DateCalendar { AUTO, GREGORIAN, BUDDHIST }

    private fun resolveOrder(explicit: String?, format: String?): DateOrder {
        when (explicit.orEmpty().trim().uppercase()) {
            "MDY" -> return DateOrder.MDY
            "YMD" -> return DateOrder.YMD
            "DMY" -> return DateOrder.DMY
        }
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

    private fun resolveCalendar(value: String?): DateCalendar = when (value.orEmpty().trim().uppercase()) {
        "BUDDHIST", "BE", "THAI" -> DateCalendar.BUDDHIST
        "GREGORIAN", "CE", "AD" -> DateCalendar.GREGORIAN
        else -> DateCalendar.AUTO
    }
}
