package com.receiptocr.app.ocr

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import com.receiptocr.app.config.AdminOcrProfile
import com.receiptocr.app.config.BrandReceiptRule
import com.receiptocr.app.config.NormalizedRect
import com.receiptocr.app.config.OcrFieldType
import com.receiptocr.app.config.OcrMatchMode
import com.receiptocr.app.config.OcrRegionRule
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class SpatialOcrItem(
    val text: String,
    val box: NormalizedRect,
    val lineIndex: Int
) {
    val centerX: Float get() = (box.left + box.right) / 2f
    val centerY: Float get() = (box.top + box.bottom) / 2f
}

data class OcrFieldCandidate(
    val fieldType: OcrFieldType,
    val value: String,
    val score: Int,
    val sourceText: String,
    val centerY: Float
)

data class RuleDrivenOcrResult(
    val records: List<PosRecord>,
    val message: String,
    val detectedPos: List<Int>,
    val rawFieldSummary: Map<OcrFieldType, List<String>>
)

/**
 * RuleDrivenOcrEngine
 *
 * หลักการ:
 * 1. ML Kit อ่านข้อความพร้อม bounding box
 * 2. แปลง box เป็น normalized coordinate
 * 3. ใช้ AdminOcrProfile กรอง ROI / label / regex
 * 4. หา POS ทุกตัวในภาพก่อน
 * 5. แบ่งภาพเป็นช่วง POS แนวตั้งหรือแนวนอนตามตำแหน่งจริง
 * 6. อ่าน date/time/customer ภายใน band ของแต่ละ POS
 * 7. ให้คะแนน candidate โดย ROI + label proximity + regex + ความใกล้วันงาน
 * 8. ไม่พบ POS ชัดเจน = ไม่เดาว่าค่าควรลง POS ใด
 *
 * เมื่อ Web Admin/API พร้อม APK เปลี่ยนเฉพาะ profile ที่โหลดมา
 * ตัว engine นี้ไม่ต้อง hard-code รูปแบบบิลรายแบรนด์
 */
object RuleDrivenOcrEngine {
    private val outDate = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun apply(
        mlText: Text,
        imageWidth: Int,
        imageHeight: Int,
        originX: Int = 0,
        originY: Int = 0,
        records: List<PosRecord>,
        work: WorkItem,
        workDate: LocalDate,
        imagePath: String,
        profile: AdminOcrProfile,
        receiptRule: BrandReceiptRule
    ): RuleDrivenOcrResult {
        if (mlText.text.isBlank()) {
            return RuleDrivenOcrResult(
                records = records,
                message = "ไม่พบข้อความในภาพ กรุณาตรวจความชัดของภาพหรือถ่ายใหม่",
                detectedPos = emptyList(),
                rawFieldSummary = emptyMap()
            )
        }

        val items = buildSpatialItems(mlText, imageWidth, imageHeight, originX, originY)
        if (items.isEmpty()) {
            return RuleDrivenOcrResult(
                records = records,
                message = "พบข้อความในภาพ แต่ยังแยกข้อมูลไม่ได้ กรุณาถ่ายภาพใหม่ให้ชัดเจน",
                detectedPos = emptyList(),
                rawFieldSummary = emptyMap()
            )
        }

        val rules = profile.regions.sortedBy { it.priority }
        val posRules = rules.filter { it.fieldType == OcrFieldType.POS_NUMBER }
        val posCandidates = findPosCandidates(items, posRules)
            .filter { candidate -> records.any { it.posNumber == candidate.posNumber } }
            .groupBy { it.posNumber }
            .mapNotNull { (_, anchors) -> anchors.minByOrNull { it.sourceOrder } }

        if (posCandidates.isEmpty()) {
            return RuleDrivenOcrResult(
                records = records,
                message = "อ่านภาพแล้ว แต่ยังไม่พบหมายเลขเครื่องที่ตรงกับแผนงาน",
                detectedPos = emptyList(),
                rawFieldSummary = emptyMap()
            )
        }

        val bands = buildPosBands(posCandidates)
        val updated = records.toMutableList()
        val summary = mutableMapOf<OcrFieldType, MutableList<String>>()
        val updatedPos = mutableListOf<Int>()

        bands.forEach { band ->
            val recordIndex = updated.indexOfFirst { it.posNumber == band.posNumber }
            if (recordIndex < 0) return@forEach

            val bandItems = items.filter {
                it.centerY in band.top..band.bottom && it.centerX in band.left..band.right
            }
            val current = updated[recordIndex]

            val dateCandidate = bestCandidate(
                field = OcrFieldType.BILL_DATE,
                items = bandItems,
                rules = rules,
                workDate = workDate,
                receiptRule = receiptRule
            )
            val timeCandidate = bestCandidate(
                field = OcrFieldType.BILL_TIME,
                items = bandItems,
                rules = rules,
                workDate = workDate,
                receiptRule = receiptRule
            )
            val customerCandidate = bestCandidate(
                field = OcrFieldType.CUSTOMER_VALUE,
                items = bandItems,
                rules = rules,
                workDate = workDate,
                receiptRule = receiptRule
            )

            val normalizedDate = dateCandidate?.value?.let {
                normalizeDateCandidate(it, workDate, receiptRule)
            }
            val displayedDate = normalizedDate ?: dateCandidate?.value?.trim()
            val normalizedTime = timeCandidate?.value?.replace('.', ':')
            val customer = customerCandidate?.value?.filter(Char::isDigit)?.takeIf { it.isNotBlank() }

            listOfNotNull(
                dateCandidate?.let { OcrFieldType.BILL_DATE to (normalizedDate ?: it.value) },
                timeCandidate?.let { OcrFieldType.BILL_TIME to (normalizedTime ?: it.value) },
                customerCandidate?.let { OcrFieldType.CUSTOMER_VALUE to (customer ?: it.value) }
            ).forEach { (type, value) ->
                summary.getOrPut(type) { mutableListOf() }.add("POS${band.posNumber}:$value")
            }

            // ต้องมีอย่างน้อยหนึ่งค่าจริงจาก OCR จึงถือว่า POS นี้ได้ผล
            if (displayedDate != null || normalizedTime != null || customer != null) {
                updated[recordIndex] = current.copy(
                    customerNo = customer ?: current.customerNo,
                    billDate = displayedDate ?: current.billDate,
                    billTime = normalizedTime ?: current.billTime,
                    noReceipt = false,
                    noReceiptReason = "",
                    source = "OCR",
                    ocrSourceImagePath = imagePath
                )
                updatedPos += band.posNumber
            }
        }

        val storeRules = rules.filter { it.fieldType == OcrFieldType.STORE_ID }
        val storeCandidates = collectCandidates(
            field = OcrFieldType.STORE_ID,
            items = items,
            rules = storeRules,
            workDate = workDate,
            receiptRule = receiptRule
        )
        if (storeCandidates.isNotEmpty()) {
            summary.getOrPut(OcrFieldType.STORE_ID) { mutableListOf() }
                .addAll(storeCandidates.take(5).map { it.value })
        }

        val message = buildString {
            append("อ่านข้อมูลจากภาพแล้ว • พบเครื่อง ")
            append(posCandidates.map { it.posNumber }.sorted().joinToString(", "))
            if (updatedPos.isNotEmpty()) {
                append(" • เติมข้อมูลเครื่อง ")
                append(updatedPos.distinct().sorted().joinToString(", "))
            } else {
                append(" • ยังอ่านข้อมูลสำคัญได้ไม่ครบ")
            }
            append(" • กรุณาตรวจเทียบภาพก่อนส่ง")
        }

        return RuleDrivenOcrResult(
            records = updated,
            message = message,
            detectedPos = posCandidates.map { it.posNumber }.distinct().sorted(),
            rawFieldSummary = summary
        )
    }

    private data class PosBand(
        val posNumber: Int,
        val top: Float,
        val bottom: Float,
        val left: Float,
        val right: Float
    )

    private data class PosAnchor(
        val posNumber: Int,
        val centerX: Float,
        val centerY: Float,
        val sourceOrder: Int
    )

    private fun buildSpatialItems(
        mlText: Text,
        imageWidth: Int,
        imageHeight: Int,
        originX: Int,
        originY: Int
    ): List<SpatialOcrItem> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList()
        val result = mutableListOf<SpatialOcrItem>()
        var lineIndex = 0

        mlText.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val rect = line.boundingBox
                if (rect != null && line.text.isNotBlank()) {
                    result += SpatialOcrItem(
                        text = line.text.trim(),
                        box = rect.toNormalized(imageWidth, imageHeight, originX, originY),
                        lineIndex = lineIndex++
                    )
                }
            }
        }
        return result
    }

    private fun Rect.toNormalized(width: Int, height: Int, originX: Int, originY: Int): NormalizedRect =
        NormalizedRect(
            left = (left + originX).toFloat() / width,
            top = (top + originY).toFloat() / height,
            right = (right + originX).toFloat() / width,
            bottom = (bottom + originY).toFloat() / height
        ).normalized()

    private fun findPosCandidates(
        items: List<SpatialOcrItem>,
        rules: List<OcrRegionRule>
    ): List<PosAnchor> {
        val result = mutableListOf<PosAnchor>()
        val activeRules = if (rules.isEmpty()) emptyList() else rules

        activeRules.forEach { rule ->
            val regionItems = items.filter { inside(it.box, rule.region) }
            val regex = rule.regexPattern?.let { safeRegex(it) }

            regionItems.forEach { item ->
                regex?.findAll(item.text)?.forEach { match ->
                    val captured = match.groupValues.getOrNull(1).orEmpty().ifBlank { match.value }
                    OcrTextNormalizer.parsePosNumber(captured)?.let { number ->
                        result += PosAnchor(number, item.centerX, item.centerY, item.lineIndex)
                    }
                }

                OcrTextNormalizer.findPosNumbers(item.text).forEach { number ->
                    result += PosAnchor(number, item.centerX, item.centerY, item.lineIndex)
                }

                val isLabel = rule.labelHints.any { item.text.contains(it, ignoreCase = true) } ||
                    Regex("(?i)P\\s*\\.?\\s*O\\s*\\.?\\s*S|TERMINAL|เครื่อง").containsMatchIn(item.text)
                if (isLabel && OcrTextNormalizer.findPosNumbers(item.text).isEmpty()) {
                    regionItems.asSequence()
                        .filter { candidate -> candidate !== item }
                        .filter { candidate ->
                            abs(candidate.centerY - item.centerY) <= rule.searchRadiusY.coerceAtLeast(0.035f)
                        }
                        .sortedBy { candidate ->
                            abs(candidate.centerY - item.centerY) + abs(candidate.centerX - item.centerX) * 0.35f
                        }
                        .mapNotNull { candidate ->
                            OcrTextNormalizer.parseStandalonePosNumber(candidate.text)?.let { it to candidate }
                        }
                        .firstOrNull()
                        ?.let { (number, candidate) ->
                            result += PosAnchor(number, candidate.centerX, candidate.centerY, candidate.lineIndex)
                        }
                }
            }
        }
        return result.distinctBy { "${it.posNumber}|${it.centerX}|${it.centerY}" }
    }

    private fun buildPosBands(pos: List<PosAnchor>): List<PosBand> {
        if (pos.isEmpty()) return emptyList()
        val xSpread = pos.maxOf { it.centerX } - pos.minOf { it.centerX }
        val ySpread = pos.maxOf { it.centerY } - pos.minOf { it.centerY }
        val horizontal = pos.size > 1 && xSpread > ySpread * 1.5f && ySpread < 0.16f
        val ordered = if (horizontal) pos.sortedBy { it.centerX } else pos.sortedBy { it.centerY }

        return ordered.mapIndexed { index, current ->
            val previous = ordered.getOrNull(index - 1)
            val next = ordered.getOrNull(index + 1)
            if (horizontal) {
                val left = if (previous == null) 0f else (previous.centerX + current.centerX) / 2f
                val right = if (next == null) 1f else (current.centerX + next.centerX) / 2f
                PosBand(current.posNumber, 0f, 1f, left.coerceIn(0f, 1f), right.coerceIn(0f, 1f))
            } else {
                val top = if (previous == null) 0f else (previous.centerY + current.centerY) / 2f
                val bottom = if (next == null) 1f else (current.centerY + next.centerY) / 2f
                PosBand(current.posNumber, top.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f), 0f, 1f)
            }
        }
    }

    private fun bestCandidate(
        field: OcrFieldType,
        items: List<SpatialOcrItem>,
        rules: List<OcrRegionRule>,
        workDate: LocalDate,
        receiptRule: BrandReceiptRule
    ): OcrFieldCandidate? =
        collectCandidates(field, items, rules.filter { it.fieldType == field }, workDate, receiptRule)
            .maxByOrNull { it.score }

    private fun collectCandidates(
        field: OcrFieldType,
        items: List<SpatialOcrItem>,
        rules: List<OcrRegionRule>,
        workDate: LocalDate,
        receiptRule: BrandReceiptRule
    ): List<OcrFieldCandidate> {
        val result = mutableListOf<OcrFieldCandidate>()

        rules.forEach { rule ->
            val regionItems = items.filter { inside(it.box, rule.region) }
            val regex = rule.regexPattern?.let { safeRegex(it) }

            regionItems.forEach { item ->
                val matches = if (regex != null) {
                    regex.findAll(item.text).map { it.value }.toList()
                } else {
                    listOf(item.text)
                }

                matches.forEach { raw ->
                    var score = 20
                    if (regex != null) score += 30

                    val labelDistance = nearestLabelDistance(item, regionItems, rule.labelHints)
                    if (labelDistance != null) {
                        score += when {
                            labelDistance <= 0.02f -> 40
                            labelDistance <= rule.searchRadiusY -> 25
                            else -> 5
                        }
                    } else if (rule.matchMode == OcrMatchMode.NEAR_LABEL && rule.labelHints.isNotEmpty()) {
                        score -= 15
                    }

                    val normalized = when (field) {
                        OcrFieldType.BILL_DATE ->
                            normalizeDateCandidate(raw, workDate, receiptRule) ?: raw.trim().takeIf { it.isNotBlank() }
                        OcrFieldType.BILL_TIME ->
                            normalizeTimeCandidate(raw) ?: raw.trim().takeIf { it.isNotBlank() }
                        OcrFieldType.CUSTOMER_VALUE ->
                            normalizeIntegerCandidate(raw, item.text)
                        else -> raw.trim().takeIf { it.isNotBlank() }
                    }

                    if (normalized != null) {
                        if (field == OcrFieldType.BILL_DATE) {
                            val date = parseOutputDate(normalized)
                            if (date != null) {
                                val distance = abs(ChronoUnit.DAYS.between(workDate, date)).toInt()
                                score += max(0, 25 - (distance * 4))
                            }
                        }

                        // ตัวเลขสั้น 1-2 หลักมักเป็น POS/วันที่/เวลา ไม่ใช่ยอดลูกค้า
                        if (field == OcrFieldType.CUSTOMER_VALUE) {
                            score += when (normalized.length) {
                                in 3..7 -> 15
                                1, 2 -> -30
                                else -> 0
                            }
                        }

                        result += OcrFieldCandidate(
                            fieldType = field,
                            value = normalized,
                            score = score - rule.priority.coerceAtMost(100) / 10,
                            sourceText = item.text,
                            centerY = item.centerY
                        )
                    }
                }
            }
        }

        return result.distinctBy { "${it.fieldType}|${it.value}|${it.centerY}" }
    }

    private fun nearestLabelDistance(
        valueItem: SpatialOcrItem,
        allItems: List<SpatialOcrItem>,
        hints: List<String>
    ): Float? {
        if (hints.isEmpty()) return null
        return allItems.asSequence()
            .filter { candidate ->
                hints.any { hint -> candidate.text.contains(hint, ignoreCase = true) }
            }
            .map { abs(it.centerY - valueItem.centerY) }
            .minOrNull()
    }

    private fun normalizeIntegerCandidate(raw: String, sourceLine: String): String? {
        val digits = Regex("\\b\\d{1,9}\\b")
            .findAll(raw)
            .map { it.value }
            .toList()
        if (digits.isEmpty()) return null

        // ถ้า source line มี date/time ชัดเจน ลดโอกาสหยิบส่วนของ date/time เป็นยอดลูกค้า
        val dateLike = Regex("\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}").containsMatchIn(sourceLine)
        val timeLike = Regex("(?:[01]?\\d|2[0-3])[:.]\\d{2}").containsMatchIn(sourceLine)
        if (dateLike || timeLike) {
            return digits.firstOrNull { it.length >= 3 && !sourceLine.contains("/$it") }
        }
        return digits.maxByOrNull { it.length }
    }

    private fun normalizeTimeCandidate(raw: String): String? {
        val match = Regex("\\b(?:[01]?\\d|2[0-3])[:.]([0-5]\\d)\\b").find(raw) ?: return null
        val value = match.value.replace('.', ':')
        val parts = value.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return "%02d:%02d".format(hour, minute)
    }

    /**
     * รองรับทั้ง dd/MM/yyyy และ MM/dd/yyyy
     * - ถ้าด้านใด > 12 จะตัดสินรูปแบบได้ทันที
     * - ถ้ากำกวม จะเลือก candidate ที่ใกล้ workDate มากกว่า
     * - ไม่บังคับว่าต้องอยู่ใน date window เพราะ date window อาจเป็น WARNING
     */
    private fun normalizeDateCandidate(
        raw: String,
        workDate: LocalDate,
        receiptRule: BrandReceiptRule
    ): String? {
        val match = Regex("\\b(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})\\b").find(raw) ?: return null
        val a = match.groupValues[1].toIntOrNull() ?: return null
        val b = match.groupValues[2].toIntOrNull() ?: return null
        var year = match.groupValues[3].toIntOrNull() ?: return null
        if (year in 2400..2999) year -= 543
        if (year < 100) year += if (year >= 70) 1900 else 2000

        val candidates = mutableListOf<LocalDate>()
        fun add(day: Int, month: Int) {
            try {
                candidates += LocalDate.of(year, month, day)
            } catch (_: Exception) {
            }
        }

        when {
            a > 12 && b in 1..12 -> add(a, b)       // dd/MM
            b > 12 && a in 1..12 -> add(b, a)       // MM/dd
            else -> {
                add(a, b)
                if (a != b) add(b, a)
            }
        }

        if (candidates.isEmpty()) return null

        val dateRule = receiptRule.dateWindowRule
        val minDate = workDate.minusDays(dateRule.beforeDays.coerceAtLeast(0).toLong())
        val maxDate = workDate.plusDays(dateRule.afterDays.coerceAtLeast(0).toLong())

        val chosen = candidates.distinct().minWithOrNull(
            compareBy<LocalDate>(
                { if (dateRule.enabled && !it.isBefore(minDate) && !it.isAfter(maxDate)) 0 else 1 },
                { abs(ChronoUnit.DAYS.between(workDate, it)) }
            )
        ) ?: return null

        return chosen.format(outDate)
    }

    private fun parseOutputDate(value: String): LocalDate? =
        try {
            LocalDate.parse(value, outDate)
        } catch (_: Exception) {
            null
        }

    private fun inside(item: NormalizedRect, rule: NormalizedRect): Boolean {
        val cx = (item.left + item.right) / 2f
        val cy = (item.top + item.bottom) / 2f
        return cx in min(rule.left, rule.right)..max(rule.left, rule.right) &&
            cy in min(rule.top, rule.bottom)..max(rule.top, rule.bottom)
    }

    private fun safeRegex(pattern: String): Regex? =
        try {
            Regex(pattern)
        } catch (_: Exception) {
            null
        }
}
