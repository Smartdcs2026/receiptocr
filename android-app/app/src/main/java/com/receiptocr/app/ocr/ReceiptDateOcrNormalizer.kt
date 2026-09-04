package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * แหล่งเดียวสำหรับตีความวันที่บิลใน APK
 *
 * กติกามาจาก Admin เท่านั้น:
 * - ลำดับ DMY / MDY / YMD
 * - ระบบปี พ.ศ. / ค.ศ. / รับทั้งสองแบบ
 * - ปี 2 หลัก / 4 หลัก / รับได้ทั้งสองแบบ
 *
 * ผลที่ยอมรับแล้วเก็บเป็น dd/MM/yyyy เท่านั้น
 * ถ้าข้อความ OCR มีตัวเกิน 1-2 หลัก จะลองแก้เฉพาะเมื่อกติกา + วันงาน
 * ชี้ไปยังวันที่จริงได้อย่างชัดเจน และจะคืน corrected=true เพื่อให้ UI เตือนเสมอ
 */
object ReceiptDateOcrNormalizer {
    private val output = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val canonicalShape = Regex("^(\\d{2})/(\\d{2})/(\\d{4})$")

    data class Result(
        val value: String?,
        val corrected: Boolean = false,
        val original: String = "",
        val warning: String? = null
    )

    fun normalizeForField(
        raw: String,
        field: OcrTemplateField?,
        referenceDate: LocalDate,
        maxAutoCorrectionDistanceDays: Long = 45,
        allowCanonicalInput: Boolean = false,
        actualDate: LocalDate = LocalDate.now(ZoneId.of("Asia/Bangkok"))
    ): Result = normalize(
        raw = raw,
        configuredFormat = field?.format,
        referenceDate = referenceDate,
        maxAutoCorrectionDistanceDays = maxAutoCorrectionDistanceDays,
        dateOrder = field?.dateOrder,
        dateCalendar = field?.dateCalendar,
        dateYearDigits = field?.dateYearDigits ?: 0,
        dateExample = field?.example,
        allowCanonicalInput = allowCanonicalInput,
        actualDate = actualDate
    )

    fun normalize(
        raw: String,
        configuredFormat: String?,
        referenceDate: LocalDate,
        maxAutoCorrectionDistanceDays: Long = 45,
        dateOrder: String? = null,
        dateCalendar: String? = null,
        dateYearDigits: Int = 0,
        dateExample: String? = null,
        allowCanonicalInput: Boolean = false,
        actualDate: LocalDate = LocalDate.now(ZoneId.of("Asia/Bangkok"))
    ): Result {
        val cleaned = OcrTextNormalizer.normalizeDigits(raw.trim())
            .replace('.', '/')
            .replace('-', '/')
            .replace(Regex("\\s+"), "")

        if (cleaned.isBlank()) {
            return Result(null, original = cleaned, warning = "ยังอ่านวันที่จากบิลไม่ได้")
        }

        if (allowCanonicalInput && isCanonical(cleaned)) {
            val date = parseCanonical(cleaned)
            if (date != null) {
                return verifyDistance(
                    date = date,
                    original = cleaned,
                    referenceDate = referenceDate,
                    maxDistanceDays = maxAutoCorrectionDistanceDays
                )
            }
        }

        val order = resolveOrder(dateOrder, configuredFormat)
        val calendar = resolveCalendar(dateCalendar)

        // Round95: ถ้า Template คาด 2 หลัก แต่เห็นวัน/เดือนเพียง 1 หลัก
        // ห้ามปล่อย parseStructured ตีเป็นเลขหลักเดียวทันที เพราะ 2 อาจเป็น 02/12/22
        // วันที่จริงใช้ตัดเฉพาะค่าที่ยังไม่เกิดขึ้น; วันงาน/ช่วง Admin ห้ามใช้เลือกเลขที่หาย
        proveShortStructuredDate(
            cleaned = cleaned,
            order = order,
            calendar = calendar,
            configuredYearDigits = dateYearDigits,
            dateExample = dateExample,
            referenceDate = referenceDate,
            actualDate = actualDate,
            maxDistanceDays = maxAutoCorrectionDistanceDays
        )?.let { return it }

        val exact = parseStructured(
            cleaned = cleaned,
            order = order,
            calendar = calendar,
            configuredYearDigits = dateYearDigits,
            referenceDate = referenceDate,
            maxDistanceDays = maxAutoCorrectionDistanceDays
        )
        if (exact.value != null) return exact

        // ถ้าข้อความมีตัวคั่นครบ 3 ส่วนแล้ว จำนวนหลักของปีคือหลักฐานที่ชัดเจน
        // ห้าม noisy recovery ลบเลขจากปี 4 หลักให้กลายเป็น 2 หลัก (หรือกลับกัน)
        // เพื่อบังคับใช้กติกาที่ Admin เลือกอย่างเคร่งครัด
        if (hasExplicitStructuredYearDigitMismatch(cleaned, order, dateYearDigits)) {
            return exact
        }

        // OCR ของบิลความร้อนอาจทำตัวคั่นหายหนึ่งตำแหน่ง เช่น 20/0869 หรือ 20/08769
        // รักษาขอบเขตที่ยังอ่านได้ แล้วแก้เฉพาะ token ที่รวมกันตามจำนวนหลักจาก Admin
        recoverPartiallySeparatedDate(
            cleaned = cleaned,
            order = order,
            calendar = calendar,
            configuredYearDigits = dateYearDigits,
            referenceDate = referenceDate,
            maxDistanceDays = maxAutoCorrectionDistanceDays
        )?.let { return it }

        // กรณีเดือนถูก OCR แทรก/สลับเป็นเลขที่ไม่มีจริง เช่น 20/28/2026
        // จะยอมแก้ก็ต่อเมื่อการตัดตัวเลขหนึ่งหลัก + กฎ Admin + วันงาน
        // เหลือวันที่จริงเพียงคำตอบเดียวเท่านั้น
        recoverStructuredMonth(
            cleaned = cleaned,
            order = order,
            calendar = calendar,
            referenceDate = referenceDate,
            maxDistanceDays = maxAutoCorrectionDistanceDays
        )?.let { return it }

        val recovered = recoverNoisyDate(
            cleaned = cleaned,
            order = order,
            calendar = calendar,
            configuredYearDigits = dateYearDigits,
            dateExample = dateExample,
            referenceDate = referenceDate,
            maxDistanceDays = maxAutoCorrectionDistanceDays
        )
        if (recovered != null) return recovered

        return exact
    }

    fun isCanonical(value: String): Boolean = parseCanonical(value.trim()) != null

    private data class OrderedTokens(
        val day: String,
        val month: String,
        val year: String
    )

    private data class ExpectedTokenLengths(
        val day: Int,
        val month: Int,
        val year: Int
    )

    private fun proveShortStructuredDate(
        cleaned: String,
        order: DateOrder,
        calendar: DateCalendar,
        configuredYearDigits: Int,
        dateExample: String?,
        referenceDate: LocalDate,
        actualDate: LocalDate,
        maxDistanceDays: Long
    ): Result? {
        val parts = cleaned.split('/').map { it.trim() }
        if (parts.size != 3 || parts.any { it.isBlank() || !it.all(Char::isDigit) }) return null
        val tokens = tokensByOrder(parts, order)
        val expected = expectedTokenLengths(order, configuredYearDigits, dateExample)

        // ถ้า Template ตั้งใจยอมรับ 1 หลักอยู่แล้ว ไม่ใช่กรณีเลขหาย
        val shortDay = expected.day == 2 && tokens.day.length == 1
        val shortMonth = expected.month == 2 && tokens.month.length == 1
        if (!shortDay && !shortMonth) return null

        // รูปร่างส่วนอื่นต้องยังตรง Template จึงค่อยพิสูจน์เลขนำหน้าที่หาย
        if ((!shortDay && tokens.day.length != expected.day) ||
            (!shortMonth && tokens.month.length != expected.month) ||
            tokens.year.length != expected.year) {
            return Result(null, original = cleaned, warning = "วันที่บนบิลอ่านไม่ครบ กรุณาตรวจภาพบิล")
        }

        val year = normalizeYear(tokens.year, calendar, referenceDate)
            ?: return Result(null, original = cleaned, warning = calendarWarning(calendar))

        fun dayCandidates(): List<Int> = if (shortDay) {
            (0..3).mapNotNull { tens -> ("$tens${tokens.day}").toIntOrNull() }.distinct()
        } else listOfNotNull(tokens.day.toIntOrNull())

        fun monthCandidates(): List<Int> = if (shortMonth) {
            (0..1).mapNotNull { tens -> ("$tens${tokens.month}").toIntOrNull() }.distinct()
        } else listOfNotNull(tokens.month.toIntOrNull())

        val candidates = buildList {
            dayCandidates().forEach { day ->
                monthCandidates().forEach { month ->
                    val date = buildDate(year, month, day) ?: return@forEach
                    // ข้อเท็จจริงเพียงอย่างเดียวที่ใช้ตัด candidate คือวันที่นั้นเกิดขึ้นแล้วหรือยัง
                    if (!date.isAfter(actualDate)) add(date)
                }
            }
        }.distinct()

        if (candidates.size != 1) {
            return Result(null, original = cleaned, warning = "วันที่บนบิลอ่านไม่ครบ กรุณาตรวจภาพบิล")
        }

        val verified = verifyDistance(
            date = candidates.single(),
            original = cleaned,
            referenceDate = referenceDate,
            maxDistanceDays = maxDistanceDays
        )
        return if (verified.value != null) verified.copy(corrected = true) else verified
    }

    private fun expectedTokenLengths(
        order: DateOrder,
        configuredYearDigits: Int,
        dateExample: String?
    ): ExpectedTokenLengths {
        val groups = Regex("\\d+").findAll(dateExample.orEmpty()).map { it.value }.toList()
        if (groups.size == 3) {
            val tokens = tokensByOrder(groups, order)
            return ExpectedTokenLengths(
                day = tokens.day.length,
                month = tokens.month.length,
                year = if (configuredYearDigits in setOf(2, 4)) configuredYearDigits else tokens.year.length
            )
        }
        return ExpectedTokenLengths(
            day = 2,
            month = 2,
            year = if (configuredYearDigits in setOf(2, 4)) configuredYearDigits else 4
        )
    }

    private fun hasExplicitStructuredYearDigitMismatch(
        cleaned: String,
        order: DateOrder,
        configuredYearDigits: Int
    ): Boolean {
        if (configuredYearDigits !in setOf(2, 4)) return false
        val parts = cleaned.split('/').map { it.trim() }
        if (parts.size != 3) return false
        val yearDigits = tokensByOrder(parts, order).year.count(Char::isDigit)
        return yearDigits > 0 && yearDigits != configuredYearDigits
    }

    private fun parseStructured(
        cleaned: String,
        order: DateOrder,
        calendar: DateCalendar,
        configuredYearDigits: Int,
        referenceDate: LocalDate,
        maxDistanceDays: Long
    ): Result {
        val parts = cleaned.split('/').map { it.trim() }
        if (parts.size != 3) {
            return Result(null, original = cleaned, warning = "รูปแบบวันที่ไม่ตรงเงื่อนไขที่ Admin กำหนด")
        }
        val tokens = tokensByOrder(parts, order)
        val yearDigitsActual = tokens.year.count(Char::isDigit)
        if (configuredYearDigits in setOf(2, 4) && yearDigitsActual != configuredYearDigits) {
            return Result(
                null,
                original = cleaned,
                warning = "ปีบนบิลมี $yearDigitsActual หลัก แต่ Admin กำหนดให้ใช้ $configuredYearDigits หลัก"
            )
        }

        val day = tokens.day.toIntOrNull()
            ?: return Result(null, original = cleaned, warning = "วันในบิลอ่านเป็นตัวเลขไม่ได้")
        val month = tokens.month.toIntOrNull()
            ?: return Result(null, original = cleaned, warning = "เดือนในบิลอ่านเป็นตัวเลขไม่ได้")
        val year = normalizeYear(tokens.year, calendar, referenceDate)
            ?: return Result(
                null,
                original = cleaned,
                warning = calendarWarning(calendar)
            )

        val date = buildDate(year, month, day)
            ?: return Result(null, original = cleaned, warning = invalidCalendarWarning(day, month, cleaned))
        return verifyDistance(date, cleaned, referenceDate, maxDistanceDays)
    }

    private fun recoverPartiallySeparatedDate(
        cleaned: String,
        order: DateOrder,
        calendar: DateCalendar,
        configuredYearDigits: Int,
        referenceDate: LocalDate,
        maxDistanceDays: Long
    ): Result? {
        val visibleParts = cleaned.split('/').map { it.trim() }
        if (visibleParts.size != 2 || visibleParts.any { it.isBlank() || !it.all(Char::isDigit) }) return null

        val yearLengths = when (configuredYearDigits) {
            2 -> listOf(2)
            4 -> listOf(4)
            else -> listOf(2, 4)
        }
        val accepted = linkedSetOf<LocalDate>()

        fun accept(parts: List<String>) {
            if (parts.size != 3) return
            val tokens = tokensByOrder(parts, order)
            val day = tokens.day.toIntOrNull() ?: return
            val month = tokens.month.toIntOrNull() ?: return
            val year = normalizeYear(tokens.year, calendar, referenceDate) ?: return
            val date = buildDate(year, month, day) ?: return
            val distance = abs(ChronoUnit.DAYS.between(referenceDate, date))
            if (distance <= maxDistanceDays) accepted += date
        }

        yearLengths.forEach { yearLength ->
            val lengths = layoutFor(order, yearLength)
            val left = visibleParts[0]
            val right = visibleParts[1]

            if (left.length == lengths[0] &&
                right.length >= lengths[1] + lengths[2] &&
                right.length <= lengths[1] + lengths[2] + 2) {
                val second = right.take(lengths[1])
                val thirdRaw = right.drop(lengths[1])
                shrinkToLength(thirdRaw, lengths[2]).forEach { third ->
                    accept(listOf(left, second, third))
                }
            }

            if (right.length == lengths[2] &&
                left.length >= lengths[0] + lengths[1] &&
                left.length <= lengths[0] + lengths[1] + 2) {
                val first = left.take(lengths[0])
                val secondRaw = left.drop(lengths[0])
                shrinkToLength(secondRaw, lengths[1]).forEach { second ->
                    accept(listOf(first, second, right))
                }
            }
        }

        if (accepted.size != 1) return null
        return Result(
            value = accepted.single().format(output),
            corrected = true,
            original = cleaned,
            warning = "เติม/ปรับตัวคั่นวันที่ตามลำดับและจำนวนหลักที่ Admin กำหนด"
        )
    }

    private fun recoverNoisyDate(
        cleaned: String,
        order: DateOrder,
        calendar: DateCalendar,
        configuredYearDigits: Int,
        dateExample: String?,
        referenceDate: LocalDate,
        maxDistanceDays: Long
    ): Result? {
        val digits = cleaned.filter(Char::isDigit)
        if (digits.length !in 5..10) return null

        val layouts = expectedLayouts(order, configuredYearDigits, dateExample)
        data class Candidate(
            val date: LocalDate,
            val removed: Int,
            val distance: Long
        )

        val candidates = mutableListOf<Candidate>()
        layouts.forEach { lengths ->
            val expected = lengths.sum()
            if (digits.length < expected || digits.length > expected + 2) return@forEach
            val removed = digits.length - expected
            shrinkToLength(digits, expected).forEach { compact ->
                val rawParts = splitByLengths(compact, lengths)
                if (rawParts.size != 3) return@forEach
                val tokens = tokensByOrder(rawParts, order)
                val day = tokens.day.toIntOrNull() ?: return@forEach
                val month = tokens.month.toIntOrNull() ?: return@forEach
                val year = normalizeYear(tokens.year, calendar, referenceDate) ?: return@forEach
                val date = buildDate(year, month, day) ?: return@forEach
                val distance = abs(ChronoUnit.DAYS.between(referenceDate, date))
                if (distance <= maxDistanceDays) {
                    candidates += Candidate(date, removed, distance)
                }
            }
        }

        if (candidates.isEmpty()) return null
        val bestRemoved = candidates.minOf { it.removed }
        val bestDates = candidates
            .filter { it.removed == bestRemoved }
            .map { it.date }
            .distinct()
        // ถ้ามีมากกว่าหนึ่งวันที่เป็นไปได้ ห้ามเลือกวันที่ใกล้วันงานที่สุด
        if (bestDates.size != 1) return null

        return Result(
            value = bestDates.single().format(output),
            corrected = bestRemoved > 0 || !isCanonical(cleaned),
            original = cleaned,
            warning = if (bestRemoved > 0) "ปรับวันที่ตามลำดับและระบบปีที่ Admin กำหนด" else null
        )
    }

    private fun expectedLayouts(
        order: DateOrder,
        configuredYearDigits: Int,
        dateExample: String?
    ): List<List<Int>> {
        // เมื่อ Admin ระบุจำนวนหลักของปีแล้ว ให้ค่านี้มีสิทธิ์เหนือ example เสมอ
        if (configuredYearDigits in setOf(2, 4)) {
            return listOf(layoutFor(order, configuredYearDigits))
        }

        val exampleGroups = Regex("\\d+").findAll(dateExample.orEmpty())
            .map { it.value.length }
            .toList()
        if (exampleGroups.size == 3) return listOf(exampleGroups)

        return listOf(
            layoutFor(order, 2),
            layoutFor(order, 4)
        )
    }

    private fun layoutFor(order: DateOrder, yearLength: Int): List<Int> = when (order) {
        DateOrder.YMD -> listOf(yearLength, 2, 2)
        DateOrder.DMY, DateOrder.MDY -> listOf(2, 2, yearLength)
    }

    private fun splitByLengths(value: String, lengths: List<Int>): List<String> {
        if (lengths.sum() != value.length) return emptyList()
        val result = mutableListOf<String>()
        var cursor = 0
        lengths.forEach { length ->
            result += value.substring(cursor, cursor + length)
            cursor += length
        }
        return result
    }

    private fun shrinkToLength(value: String, target: Int): Set<String> {
        if (target <= 0 || value.length < target || value.length - target > 2) return emptySet()
        var current = setOf(value)
        repeat(value.length - target) {
            current = buildSet {
                current.forEach { item ->
                    item.indices.forEach { index -> add(item.removeRange(index, index + 1)) }
                }
            }
        }
        return current.filterTo(linkedSetOf()) { it.length == target }
    }

    private fun tokensByOrder(parts: List<String>, order: DateOrder): OrderedTokens = when (order) {
        DateOrder.MDY -> OrderedTokens(day = parts[1], month = parts[0], year = parts[2])
        DateOrder.YMD -> OrderedTokens(day = parts[2], month = parts[1], year = parts[0])
        DateOrder.DMY -> OrderedTokens(day = parts[0], month = parts[1], year = parts[2])
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

    private fun recoverStructuredMonth(
        cleaned: String,
        order: DateOrder,
        calendar: DateCalendar,
        referenceDate: LocalDate,
        maxDistanceDays: Long
    ): Result? {
        val parts = cleaned.split('/').map { it.trim() }
        if (parts.size != 3) return null
        val tokens = tokensByOrder(parts, order)
        val day = tokens.day.toIntOrNull() ?: return null
        val year = normalizeYear(tokens.year, calendar, referenceDate) ?: return null
        val monthDigits = tokens.month.filter(Char::isDigit)
        val rawMonth = monthDigits.toIntOrNull() ?: return null
        if (rawMonth in 1..12 || monthDigits.length != 2) return null

        val candidates = monthDigits.indices
            .mapNotNull { index -> monthDigits.removeRange(index, index + 1).toIntOrNull() }
            .filter { it in 1..12 }
            .distinct()
            .mapNotNull { month -> buildDate(year, month, day) }
            .filter { date -> abs(ChronoUnit.DAYS.between(referenceDate, date)) <= maxDistanceDays }
            .distinct()

        if (candidates.size != 1) return null
        return Result(
            value = candidates.single().format(output),
            corrected = true,
            original = cleaned,
            warning = "ปรับเดือนตามเงื่อนไขที่ Admin กำหนดและวันงาน • กรุณาตรวจเทียบกับภาพ"
        )
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

    private fun parseCanonical(value: String): LocalDate? {
        val match = canonicalShape.matchEntire(value) ?: return null
        return runCatching {
            LocalDate.of(
                match.groupValues[3].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[1].toInt()
            )
        }.getOrNull()
    }

    private fun buildDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private fun invalidCalendarWarning(day: Int, month: Int, original: String): String = when {
        month !in 1..12 -> "เดือนที่อ่านได้ ($month) ไม่มีอยู่จริงตามปฏิทิน"
        day < 1 -> "วันที่ที่อ่านได้ ($original) ไม่มีอยู่จริง"
        else -> "วันที่ที่อ่านได้ ($original) ไม่มีอยู่จริงตามปฏิทิน"
    }

    private fun calendarWarning(calendar: DateCalendar): String = when (calendar) {
        DateCalendar.BUDDHIST -> "ปีบนบิลไม่ตรงเงื่อนไข พ.ศ. ที่ Admin กำหนด"
        DateCalendar.GREGORIAN -> "ปีบนบิลไม่ตรงเงื่อนไข ค.ศ. ที่ Admin กำหนด"
        DateCalendar.AUTO -> "ปีบนบิลไม่อยู่ในรูปแบบ พ.ศ. หรือ ค.ศ. ที่รองรับ"
    }

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
