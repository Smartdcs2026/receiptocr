package com.receiptocr.app.ocr

import com.google.mlkit.vision.text.Text
import com.receiptocr.app.config.OcrTemplateComposite
import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.OcrTemplateSegment
import com.receiptocr.app.config.UniversalOcrTemplate
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * ตัวอ่านรูปแบบบิลของ Round80
 *
 * จุดประสงค์หลักคือให้กฎที่หน้า "ทดสอบรูปแบบบิล" ใช้ ตีความเหมือนกับ APK:
 * - จำนวนหลักจากตัวอย่างใช้หลักเดียวกัน
 * - ข้อความคงที่/ตัวคั่น/ข้อมูลที่ข้ามได้ใช้หลักเดียวกัน
 * - จำนวน POS ของร้านใช้ตรวจจำนวนชุดข้อมูล ไม่ใช้บังคับเลขเครื่องให้อยู่ 1..N
 * - หมายเลขเครื่องที่พบบนบิลเก็บแยกจากลำดับช่องของงาน เพื่อให้ภาพถัดไปเติมเครื่องเดิมได้ถูกช่อง
 */
object AdminRuleInterpreter {
    private const val OCR_DIGIT = "[0-9OoIl|]"

    private data class PatternPart(
        val pattern: String,
        val captureKeys: List<String> = emptyList()
    )

    private data class CompiledRow(
        val regex: Regex,
        val captureKeys: List<String>
    )

    private data class ParsedMatch(
        val template: UniversalOcrTemplate,
        val fields: Map<String, String>,
        val matchedText: String,
        val sourceLines: List<String>,
        val score: Int
    )

    fun apply(
        mlTexts: List<Text>,
        records: List<PosRecord>,
        work: WorkItem,
        workDate: LocalDate,
        imagePath: String,
        templates: List<UniversalOcrTemplate>
    ): UniversalTemplateResult {
        if (templates.isEmpty()) {
            return UniversalTemplateResult(
                records = records,
                message = "ยังไม่มีรูปแบบการอ่านบิลสำหรับแบรนด์นี้ กรุณาแจ้งผู้ดูแล",
                usedUniversalTemplate = false
            )
        }
        if (mlTexts.all { it.text.isBlank() }) {
            return UniversalTemplateResult(
                records = records,
                message = "ไม่พบข้อความในภาพ กรุณาตรวจภาพแล้วลองใหม่",
                usedUniversalTemplate = true
            )
        }

        val textCandidates = buildTextCandidates(mlTexts)
        val allMatches = templates
            .filter { it.active }
            .sortedByDescending { it.priority }
            .flatMap { template ->
                textCandidates.flatMap { rawText ->
                    parseTemplateText(template, rawText).map { parsed ->
                        ParsedMatch(
                            template = template,
                            fields = parsed.fields,
                            matchedText = parsed.matchedText,
                            sourceLines = parsed.sourceLines,
                            score = score(template, parsed.fields, work, workDate)
                        )
                    }
                }
            }
            .filter { it.score > 0 }
            .distinctBy { match ->
                match.template.templateId + "|" + signature(match.fields) + "|" + match.matchedText
            }
            .sortedByDescending { it.score }

        if (allMatches.isEmpty()) {
            val names = templates.filter { it.active }.map { it.templateName }.distinct()
            val suffix = names.takeIf { it.size == 1 }?.firstOrNull()?.let { " (${it})" }.orEmpty()
            return UniversalTemplateResult(
                records = records,
                message = "อ่านข้อความจากภาพได้ แต่ยังแยกข้อมูลตามรูปแบบบิล$suffixไม่ได้",
                usedUniversalTemplate = true
            )
        }

        val byActualPos = allMatches
            .mapNotNull { match ->
                val rawPos = match.fields["POS_NUMBER"] ?: return@mapNotNull null
                val actual = OcrTextNormalizer.parsePosNumber(rawPos) ?: return@mapNotNull null
                actual to match
            }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (actual, matches) -> fuseMatches(matches)?.let { actual to it } }
            .sortedBy { it.first }

        if (byActualPos.isEmpty()) {
            return UniversalTemplateResult(
                records = records,
                message = "พบข้อมูลในภาพ แต่ยังไม่พบหมายเลขเครื่องตามรูปแบบบิลนี้",
                templateName = allMatches.firstOrNull()?.template?.templateName,
                usedUniversalTemplate = true
            )
        }

        val updated = records.toMutableList()
        val warningsBySlot = linkedMapOf<Int, MutableList<String>>()
        val acceptedBySlot = linkedMapOf<Int, ParsedMatch>()
        val actualDisplayBySlot = linkedMapOf<Int, String>()
        val existingSlotByActual = linkedMapOf<Int, Int>()
        records.forEach { record ->
            OcrTextNormalizer.parsePosNumber(record.receiptPosNumber)?.let { actual ->
                existingSlotByActual.putIfAbsent(actual, record.posNumber)
            }
        }
        val usedSlots = existingSlotByActual.values.toMutableSet()
        val overflowActual = mutableListOf<String>()
        var firstStore: String? = null

        byActualPos.forEach { (actualPos, match) ->
            val slot = existingSlotByActual[actualPos] ?: run {
                val free = records
                    .filter { it.posNumber !in usedSlots }
                    .sortedWith(
                        compareBy<PosRecord> {
                            val blank = it.customerNo.isBlank() && it.billDate.isBlank() && it.billTime.isBlank() && !it.noReceipt
                            if (blank) 0 else 1
                        }.thenBy { it.posNumber }
                    )
                    .firstOrNull()
                    ?.posNumber
                if (free != null) {
                    existingSlotByActual[actualPos] = free
                    usedSlots += free
                }
                free
            }

            val rawPos = match.fields["POS_NUMBER"].orEmpty()
            val displayPos = normalizeReceiptPos(rawPos, actualPos)
            if (slot == null) {
                overflowActual += displayPos
                return@forEach
            }

            val index = updated.indexOfFirst { it.posNumber == slot }
            if (index < 0) return@forEach
            val current = updated[index]
            val warnings = warningsBySlot.getOrPut(slot) { mutableListOf() }

            val dateRaw = match.fields["BILL_DATE"].orEmpty()
            val dateFormat = dateFormat(match.template)
            val dateResult = if (dateRaw.isNotBlank()) {
                ReceiptDateOcrNormalizer.normalize(
                    raw = dateRaw,
                    configuredFormat = dateFormat,
                    referenceDate = workDate
                )
            } else null
            val normalizedDate = dateResult?.value ?: dateRaw
            val time = match.fields["BILL_TIME"]?.replace('.', ':').orEmpty()
            val customer = match.fields["CUSTOMER_VALUE"]?.let(OcrTextNormalizer::normalizeDigits)
                ?.filter(Char::isDigit)
                .orEmpty()
            val store = match.fields["STORE_ID"].orEmpty()
            val core = match.template.validation.requiredCore

            if (core.date && dateRaw.isBlank()) warnings += "ไม่พบวันที่ตามรูปแบบบิล"
            if (core.time && time.isBlank()) warnings += "ไม่พบเวลาตามรูปแบบบิล"
            if (core.customerValue && customer.isBlank()) warnings += "ไม่พบยอด/เลขลูกค้าตามรูปแบบบิล"
            expectedLength(match.template, "CUSTOMER_VALUE")?.let { expected ->
                if (customer.isNotBlank() && customer.length !in expected) {
                    warnings += "ยอด/เลขลูกค้ามี ${customer.length} หลัก แต่รูปแบบนี้กำหนด ${expected.first}-${expected.last} หลัก"
                }
            }
            if (dateRaw.isNotBlank() && dateResult?.value == null) {
                warnings += "วันที่ที่อ่านได้ ($dateRaw) ยังตรวจยืนยันไม่ได้"
            }
            warnings += comparisonWarnings(match.template, match.fields, dateResult?.value, workDate)

            if (match.template.validation.store.mustMatchWorkPlan && store.isNotBlank() && work.expectedReceiptStoreId.isNotBlank()) {
                if (!sameStore(store, work.expectedReceiptStoreId)) {
                    warnings += "รหัสร้านที่อ่านได้ ($store) ไม่ตรงกับรหัสร้านของงาน (${work.expectedReceiptStoreId})"
                }
            }
            if (match.template.validation.store.sameStoreAcrossAllMatches && store.isNotBlank()) {
                if (firstStore == null) firstStore = store
                else if (!sameStore(firstStore.orEmpty(), store)) warnings += "รหัสร้านในบิลแต่ละชุดไม่ตรงกัน"
            }

            val hasAnyCore = dateRaw.isNotBlank() || time.isNotBlank() || customer.isNotBlank()
            if (!hasAnyCore) return@forEach

            updated[index] = current.copy(
                receiptPosNumber = displayPos,
                customerNo = customer.ifBlank { current.customerNo },
                billDate = normalizedDate.ifBlank { current.billDate },
                billTime = time.ifBlank { current.billTime },
                noReceipt = false,
                noReceiptReason = "",
                source = "OCR-RULE",
                ocrSourceImagePath = imagePath,
                ocrWarnings = warnings.distinct().joinToString(" • "),
                ocrStoreId = store.ifBlank { current.ocrStoreId },
                ocrStoreIdExpected = templateHasStoreId(match.template),
                ocrCounterCycle = match.template.duplicatePolicy.customerCounterCycle.uppercase()
            )
            acceptedBySlot[slot] = match
            actualDisplayBySlot[slot] = displayPos
        }

        if (acceptedBySlot.isEmpty()) {
            return UniversalTemplateResult(
                records = records,
                message = if (overflowActual.isNotEmpty()) {
                    "พบข้อมูลมากกว่าจำนวน POS ของร้าน กรุณาตรวจภาพและแผนงาน"
                } else {
                    "อ่านข้อความได้ แต่ยังจับข้อมูลลงแต่ละ POS ไม่ได้"
                },
                templateName = allMatches.firstOrNull()?.template?.templateName,
                usedUniversalTemplate = true
            )
        }

        val detectedSlots = acceptedBySlot.keys.sorted()
        val extracted = linkedMapOf<String, MutableList<String>>()
        detectedSlots.forEach { slot ->
            acceptedBySlot[slot]?.fields?.forEach { (key, value) ->
                extracted.getOrPut(baseKey(key)) { mutableListOf() }.add(value)
            }
        }
        val names = detectedSlots.mapNotNull { acceptedBySlot[it]?.template?.templateName }.distinct()
        val warningCount = warningsBySlot.values.sumOf { it.distinct().size }
        val actualList = detectedSlots.mapNotNull { actualDisplayBySlot[it] }.distinct()
        val message = buildString {
            append("อ่านข้อมูลจากภาพสำเร็จ • พบ ${detectedSlots.size} เครื่อง")
            if (actualList.isNotEmpty()) append(" • POS ${actualList.joinToString(", ")}")
            if (overflowActual.isNotEmpty()) append(" • พบข้อมูลเกินจำนวนเครื่องของร้าน")
            if (warningCount > 0) append(" • มี $warningCount จุดที่ควรตรวจ")
            append(" • กรุณาตรวจเทียบกับภาพก่อนยืนยัน")
        }

        return UniversalTemplateResult(
            records = updated,
            message = message,
            templateName = names.joinToString(" / "),
            detectedPos = detectedSlots,
            extracted = extracted,
            validationWarnings = warningsBySlot.mapValues { it.value.distinct() },
            usedUniversalTemplate = true
        )
    }

    /** ใช้ใน Unit Test เพื่อยืนยันว่า APK แยกข้อความตามกฎเดียวกับหน้า Admin */
    internal fun parseTextForTest(
        template: UniversalOcrTemplate,
        rawText: String
    ): List<Map<String, String>> = parseTemplateText(template, rawText).map { it.fields }

    private data class RawParsed(
        val fields: Map<String, String>,
        val matchedText: String,
        val sourceLines: List<String>
    )

    private fun parseTemplateText(template: UniversalOcrTemplate, rawText: String): List<RawParsed> {
        val lines = normalizeLines(rawText)
        val rows = template.recognition.rows.sortedBy { it.row }
        if (lines.isEmpty() || rows.isEmpty()) return emptyList()
        val compiled = rows.map { compileRow(it) }
        if (compiled.any { it == null }) return emptyList()
        val safe = compiled.filterNotNull()
        return if (safe.size == 1) {
            findSingleRowRecords(safe.first(), lines, maxJoin = 6)
        } else {
            findMultiRowRecords(safe, lines, template.recognition.lineTolerance.coerceIn(0, 3))
        }
    }

    private fun buildTextCandidates(mlTexts: List<Text>): List<String> = buildList {
        mlTexts.forEach { text ->
            if (text.text.isNotBlank()) add(text.text)
            val rebuilt = SpatialTextLayout.rebuild(text, deskew = true).lines
                .joinToString("\n") { it.text }
            if (rebuilt.isNotBlank()) add(rebuilt)
        }
    }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun normalizeLines(value: String): List<String> = StringBuilder(value)
        .toString()
        .replace("\r", "")
        .split("\n")
        .map(::normalizeText)
        .filter { it.isNotBlank() }

    private fun normalizeText(value: String): String = OcrTextNormalizer.normalizeLine(value)

    private fun compileRow(row: OcrTemplateRow): CompiledRow? {
        val counts = mutableMapOf<String, Int>()
        val captures = mutableListOf<String>()
        val parts = row.fields.sortedBy { it.order }.mapNotNull { field ->
            val part = fieldPattern(field, counts) ?: return@mapNotNull null
            captures += part.captureKeys
            if (field.required) part.pattern else "(?:${part.pattern})?"
        }
        if (parts.isEmpty()) return null
        return runCatching {
            CompiledRow(
                regex = Regex(parts.joinToString("[\\s|,;:_-]*"), setOf(RegexOption.IGNORE_CASE)),
                captureKeys = captures
            )
        }.getOrNull()
    }

    private fun fieldPattern(field: OcrTemplateField, counts: MutableMap<String, Int>): PatternPart? {
        val (min, max) = exactOrRange(field)
        fun capture(type: String, inner: String): PatternPart {
            val key = nextKey(type, counts)
            return PatternPart("($inner)", listOf(key))
        }

        return when (field.type) {
            "BILL_DATE" -> capture("BILL_DATE", "$OCR_DIGIT{1,2}[./-]$OCR_DIGIT{1,2}[./-]$OCR_DIGIT{2,4}")
            "BILL_TIME" -> capture("BILL_TIME", "$OCR_DIGIT{1,2}[:.]$OCR_DIGIT{2}(?::$OCR_DIGIT{2})?")
            "STORE_ID" -> capture("STORE_ID", "$OCR_DIGIT{${min.coerceAtLeast(1)},${max.coerceAtLeast(1)}}")
            "CUSTOMER_VALUE" -> capture("CUSTOMER_VALUE", "$OCR_DIGIT{1,18}(?!$OCR_DIGIT)")
            "YEAR_VALUE" -> capture("YEAR_VALUE", "$OCR_DIGIT{${min.coerceAtLeast(1)},${max.coerceAtLeast(1)}}")
            "MONTH_VALUE" -> capture("MONTH_VALUE", "$OCR_DIGIT{${min.coerceAtLeast(1)},${max.coerceAtLeast(1)}}")
            "DAY_VALUE" -> capture("DAY_VALUE", "$OCR_DIGIT{${min.coerceAtLeast(1)},${max.coerceAtLeast(1)}}")
            "EMPLOYEE_CODE" -> capture("EMPLOYEE_CODE", "[A-Za-z0-9]{${min.coerceAtLeast(1)},${max.coerceAtLeast(1)}}")
            "NUMBER_TEXT" -> PatternPart("$OCR_DIGIT{${min.coerceAtLeast(1)},${max.coerceAtLeast(1)}}")
            "ALNUM_TEXT" -> PatternPart("[A-Za-z0-9]{${min.coerceAtLeast(1)},${max.coerceAtLeast(1)}}")
            "LITERAL" -> literalPattern(field.literal ?: field.example.orEmpty())?.let(::PatternPart)
            "SEPARATOR" -> PatternPart(Regex.escape(field.separatorValue ?: field.example ?: "-"))
            "IGNORE" -> PatternPart(".{0,40}?")
            "POS_NUMBER" -> {
                val key = nextKey("POS_NUMBER", counts)
                val prefixes = field.posPrefixes.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
                val digits = posDigitCount(field)
                val pattern = if (prefixes.isNotEmpty()) {
                    val prefix = prefixes.joinToString("|", "(?:", ")") { Regex.escape(it) }
                    "$prefix$OCR_DIGIT{$digits}"
                } else {
                    val example = field.example.orEmpty().trim()
                    val prefix = example.takeWhile(Char::isLetter)
                    val exampleDigits = example.takeLastWhile(Char::isDigit)
                    if (prefix.isNotBlank() && exampleDigits.isNotBlank()) {
                        Regex.escape(prefix) + "$OCR_DIGIT{${exampleDigits.length}}"
                    } else {
                        "[A-Za-z]?$OCR_DIGIT{$digits}"
                    }
                }
                PatternPart("($pattern)", listOf(key))
            }
            "COMPOSITE_CODE" -> compositePattern(field, counts)
            else -> null
        }
    }

    private fun compositePattern(field: OcrTemplateField, counts: MutableMap<String, Int>): PatternPart {
        val composite = field.composite
        if (composite == null || composite.segments.isEmpty()) {
            val (min, max) = exactOrRange(field)
            val key = nextKey("COMPOSITE_CODE", counts)
            return PatternPart("([A-Za-z0-9:_-]{${min.coerceAtLeast(1)},${max.coerceAtLeast(1)}})", listOf(key))
        }

        val captureKeys = mutableListOf<String>()
        val pattern = buildString {
            composite.prefix?.takeIf { it.isNotBlank() }?.let { prefix -> append(literalPattern(prefix) ?: Regex.escape(prefix)) }
            val segments = composite.segments.sortedBy { it.order }
            segments.forEachIndexed { index, segment ->
                if (index > 0 && segment.type == "CUSTOMER_VALUE" && !composite.separator.isNullOrBlank() && segments[index - 1].type != "SEPARATOR") {
                    append(Regex.escape(composite.separator.orEmpty()))
                }
                val part = segmentPattern(segment, composite, counts)
                append(part.pattern)
                captureKeys += part.captureKeys
            }
        }
        return PatternPart(pattern, captureKeys)
    }

    private fun segmentPattern(
        segment: OcrTemplateSegment,
        composite: OcrTemplateComposite,
        counts: MutableMap<String, Int>
    ): PatternPart {
        val length = when {
            segment.length > 0 -> segment.length
            !segment.example.isNullOrBlank() -> segment.example!!.length
            segment.type in setOf("YEAR_VALUE", "MONTH_VALUE", "DAY_VALUE") -> 2
            else -> 1
        }.coerceAtLeast(1)

        fun capture(type: String, inner: String): PatternPart {
            val key = nextKey(type, counts)
            return PatternPart("($inner)", listOf(key))
        }

        return when (segment.type) {
            "YEAR_VALUE", "MONTH_VALUE", "DAY_VALUE", "STORE_ID" -> capture(segment.type, "$OCR_DIGIT{$length}")
            "CUSTOMER_VALUE" -> capture("CUSTOMER_VALUE", "$OCR_DIGIT{1,18}(?!$OCR_DIGIT)")
            "EMPLOYEE_CODE" -> capture("EMPLOYEE_CODE", "[A-Za-z0-9]{$length}")
            "POS_NUMBER" -> {
                val example = segment.example.orEmpty().trim()
                val prefix = example.takeWhile(Char::isLetter)
                val digits = example.takeLastWhile(Char::isDigit).length.takeIf { it > 0 }
                    ?: (length - prefix.length).coerceAtLeast(1)
                val inner = (if (prefix.isNotBlank()) Regex.escape(prefix) else "[A-Za-z]?") + "$OCR_DIGIT{$digits}"
                capture("POS_NUMBER", inner)
            }
            "LITERAL" -> PatternPart(literalPattern(segment.example.orEmpty()) ?: Regex.escape(segment.example.orEmpty()))
            "SEPARATOR" -> PatternPart(Regex.escape(segment.example ?: composite.separator ?: "-"))
            "NUMBER_TEXT" -> PatternPart("$OCR_DIGIT{$length}")
            "ALNUM_TEXT" -> PatternPart("[A-Za-z0-9]{$length}")
            "IGNORE" -> PatternPart(".{$length}")
            else -> PatternPart(".{$length}")
        }
    }

    private fun exactOrRange(field: OcrTemplateField): Pair<Int, Int> {
        val example = field.example.orEmpty().trim()
        val doesNotFixLength = field.type in setOf("BILL_DATE", "BILL_TIME", "LITERAL", "SEPARATOR", "CUSTOMER_VALUE")
        if (example.isNotBlank() && !doesNotFixLength) {
            val length = example.length
            return length to length
        }
        val min = field.minLength.coerceAtLeast(0)
        val max = field.maxLength.coerceAtLeast(maxOf(1, min))
        return min to max
    }

    private fun posDigitCount(field: OcrTemplateField): Int {
        val example = field.example.orEmpty().trim()
        val digits = example.takeLastWhile(Char::isDigit)
        if (digits.isNotBlank()) return digits.length
        val prefixes = field.posPrefixes.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (prefixes.isEmpty() && field.minLength == field.maxLength && field.minLength > 0) return field.minLength
        return (field.posDigits ?: 2).coerceIn(1, 6)
    }

    private fun literalPattern(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return when {
            value.matches(Regex("BNO\\s*:\\s*S", RegexOption.IGNORE_CASE)) -> "[B8]N[O0]\\s*[:;]\\s*[S$5]"
            value.matches(Regex("BNO\\s*:", RegexOption.IGNORE_CASE)) -> "[B8]N[O0]\\s*[:;]"
            else -> Regex.escape(value).replace("\\ ", "\\s+")
        }
    }

    private fun nextKey(type: String, counts: MutableMap<String, Int>): String {
        val count = (counts[type] ?: 0) + 1
        counts[type] = count
        return if (count == 1) type else "${type}_$count"
    }

    private fun findSingleRowRecords(regex: CompiledRow, lines: List<String>, maxJoin: Int): List<RawParsed> {
        val found = mutableListOf<RawParsed>()
        val seen = linkedSetOf<String>()
        candidateWindows(lines, maxJoin).forEach { candidate ->
            regex.regex.findAll(candidate.text).forEach { match ->
                val fields = extractFields(regex, match)
                val key = signature(fields)
                if (key.isBlank() || !seen.add(key)) return@forEach
                found += RawParsed(fields, match.value, lines.subList(candidate.start, candidate.end + 1))
            }
        }
        return found
    }

    private fun findMultiRowRecords(rows: List<CompiledRow>, lines: List<String>, lineTolerance: Int): List<RawParsed> {
        val found = mutableListOf<RawParsed>()
        val seen = linkedSetOf<String>()
        for (start in lines.indices) {
            var cursor = start
            val fields = linkedMapOf<String, String>()
            val source = mutableListOf<String>()
            var ok = true
            rows.forEach { row ->
                var rowMatch: Pair<Int, MatchResult>? = null
                val last = minOf(lines.lastIndex, cursor + lineTolerance)
                for (index in cursor..last) {
                    val match = row.regex.find(lines[index])
                    if (match != null) {
                        rowMatch = index to match
                        break
                    }
                }
                if (rowMatch == null) {
                    ok = false
                    return@forEach
                }
                val (index, match) = rowMatch!!
                extractFields(row, match).forEach { (key, value) -> if (!fields.containsKey(key)) fields[key] = value }
                source += lines[index]
                cursor = index + 1
            }
            val key = signature(fields)
            if (ok && key.isNotBlank() && seen.add(key)) {
                found += RawParsed(fields, source.joinToString(" "), source)
            }
        }
        return found
    }

    private data class CandidateWindow(val start: Int, val end: Int, val text: String)

    private fun candidateWindows(lines: List<String>, maxJoin: Int): List<CandidateWindow> {
        val candidates = mutableListOf<CandidateWindow>()
        for (start in lines.indices) {
            for (count in 1..maxJoin) {
                if (start + count > lines.size) break
                candidates += CandidateWindow(
                    start,
                    start + count - 1,
                    normalizeText(lines.subList(start, start + count).joinToString(" "))
                )
            }
        }
        if (lines.size > maxJoin) {
            candidates += CandidateWindow(0, lines.lastIndex, normalizeText(lines.joinToString(" ")))
        }
        return candidates
    }

    private fun extractFields(compiled: CompiledRow, match: MatchResult): Map<String, String> {
        val result = linkedMapOf<String, String>()
        compiled.captureKeys.forEachIndexed { index, key ->
            val raw = match.groupValues.getOrNull(index + 1).orEmpty()
            if (raw.isNotBlank()) result[key] = normalizeCaptured(key, raw)
        }
        return result
    }

    private fun normalizeCaptured(key: String, raw: String): String {
        val base = baseKey(key)
        return when (base) {
            "BILL_DATE", "BILL_TIME", "YEAR_VALUE", "MONTH_VALUE", "DAY_VALUE", "STORE_ID", "CUSTOMER_VALUE" ->
                OcrTextNormalizer.normalizeDigits(raw)
            "POS_NUMBER" -> {
                val prefix = raw.takeWhile { it.isLetter() && it !in listOf('O', 'o', 'I', 'i', 'l') }
                prefix + OcrTextNormalizer.normalizeDigits(raw.drop(prefix.length))
            }
            else -> raw
        }
    }

    private fun baseKey(key: String): String = key.replace(Regex("_\\d+$"), "")

    private fun signature(fields: Map<String, String>): String = fields.entries
        .sortedBy { it.key }
        .joinToString("|") { "${it.key}=${it.value}" }

    private fun fuseMatches(candidates: List<ParsedMatch>): ParsedMatch? {
        val ranked = candidates.sortedWith(
            compareByDescending<ParsedMatch> { candidate ->
                listOf("POS_NUMBER", "STORE_ID", "BILL_DATE", "BILL_TIME", "CUSTOMER_VALUE")
                    .count { !candidate.fields[it].isNullOrBlank() }
            }.thenByDescending { it.score }
        )
        val base = ranked.firstOrNull() ?: return null
        val merged = linkedMapOf<String, String>().apply { putAll(base.fields) }
        ranked.drop(1).forEach { candidate ->
            val currentStore = merged["STORE_ID"]
            val otherStore = candidate.fields["STORE_ID"]
            if (!currentStore.isNullOrBlank() && !otherStore.isNullOrBlank() && !sameStore(currentStore, otherStore)) return@forEach
            candidate.fields.forEach { (key, value) -> if (merged[key].isNullOrBlank()) merged[key] = value }
        }
        return base.copy(fields = merged, score = ranked.maxOf { it.score } + (merged.size - base.fields.size).coerceAtLeast(0) * 5)
    }

    private fun score(
        template: UniversalOcrTemplate,
        fields: Map<String, String>,
        work: WorkItem,
        workDate: LocalDate
    ): Int {
        var result = template.priority
        if (fields["BILL_DATE"] != null) result += 25
        if (fields["BILL_TIME"] != null) result += 15
        if (fields["STORE_ID"] != null) result += 30
        if (fields["POS_NUMBER"] != null) result += 30
        if (fields["CUSTOMER_VALUE"] != null) result += 25
        if (fields["YEAR_VALUE"] != null) result += 10
        if (fields["MONTH_VALUE"] != null) result += 10

        val store = fields["STORE_ID"]
        if (!store.isNullOrBlank() && work.expectedReceiptStoreId.isNotBlank()) {
            result += if (sameStore(store, work.expectedReceiptStoreId)) 50 else -40
        }
        // จำนวนเครื่องคือจำนวนชุดข้อมูล ไม่ใช่ช่วงเลข POS; พบ 101 ในร้าน 1 POS จึงไม่ควรถูกหักคะแนน
        if (fields["POS_NUMBER"]?.let(OcrTextNormalizer::parsePosNumber) != null) result += 20

        fields["BILL_DATE"]?.let { raw ->
            val normalized = ReceiptDateOcrNormalizer.normalize(raw, dateFormat(template), workDate).value
            val date = normalized?.let { runCatching { LocalDate.parse(it, java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrNull() }
            if (date != null) {
                val distance = abs(ChronoUnit.DAYS.between(workDate, date))
                result += when {
                    distance == 0L -> 20
                    distance <= 2L -> 15
                    distance <= 31L -> 5
                    else -> 0
                }
            }
        }
        val customer = fields["CUSTOMER_VALUE"]?.filter(Char::isDigit)
        expectedLength(template, "CUSTOMER_VALUE")?.let { expected ->
            if (!customer.isNullOrBlank() && customer.length in expected) result += 10
        }
        return result
    }

    private fun dateFormat(template: UniversalOcrTemplate): String? = template.recognition.rows
        .asSequence()
        .flatMap { it.fields.asSequence() }
        .firstOrNull { it.type == "BILL_DATE" }
        ?.format

    private fun expectedLength(template: UniversalOcrTemplate, type: String): IntRange? {
        template.recognition.rows.asSequence()
            .flatMap { it.fields.asSequence() }
            .firstOrNull { it.type == type }
            ?.let { field ->
                val min = field.minLength.coerceAtLeast(1)
                return min..field.maxLength.coerceAtLeast(min)
            }
        template.recognition.rows.asSequence()
            .flatMap { it.fields.asSequence() }
            .mapNotNull { it.composite }
            .flatMap { it.segments.asSequence() }
            .firstOrNull { it.type == type }
            ?.length
            ?.takeIf { it > 0 }
            ?.let { return it..it }
        return null
    }

    private fun comparisonWarnings(
        template: UniversalOcrTemplate,
        fields: Map<String, String>,
        normalizedBillDate: String?,
        workDate: LocalDate
    ): List<String> {
        val billDate = normalizedBillDate?.let {
            runCatching { LocalDate.parse(it, java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrNull()
        }
        return template.recognition.rows.asSequence()
            .flatMap { it.fields.asSequence() }
            .filter { field ->
                field.compareTo.uppercase() in setOf("BILL_DATE", "WORK_DATE") &&
                    field.type in setOf("YEAR_VALUE", "MONTH_VALUE", "DAY_VALUE")
            }
            .mapNotNull { field ->
                val raw = fields[field.type]?.filter(Char::isDigit).orEmpty()
                if (raw.isBlank()) return@mapNotNull null
                val target = if (field.compareTo.equals("WORK_DATE", true)) workDate else billDate ?: return@mapNotNull null
                val matched = when (field.type) {
                    "YEAR_VALUE" -> {
                        val gregorian = target.year.toString().takeLast(raw.length)
                        val buddhist = (target.year + 543).toString().takeLast(raw.length)
                        raw == gregorian || raw == buddhist
                    }
                    "MONTH_VALUE" -> raw.toIntOrNull() == target.monthValue
                    else -> raw.toIntOrNull() == target.dayOfMonth
                }
                if (matched) null else {
                    val label = when (field.type) {
                        "YEAR_VALUE" -> "ปี"
                        "MONTH_VALUE" -> "เดือน"
                        else -> "วัน"
                    }
                    "$labelที่อ่านได้ ($raw) ไม่ตรงกับวันที่ที่กำหนด"
                }
            }
            .distinct()
            .toList()
    }

    private fun normalizeReceiptPos(raw: String, parsed: Int): String {
        val normalized = OcrTextNormalizer.normalizeDigits(raw.trim())
        return normalized.ifBlank { parsed.toString() }
    }

    private fun sameStore(a: String, b: String): Boolean {
        val aa = a.filter(Char::isDigit).trimStart('0').ifBlank { "0" }
        val bb = b.filter(Char::isDigit).trimStart('0').ifBlank { "0" }
        return aa == bb
    }

    private fun templateHasStoreId(template: UniversalOcrTemplate): Boolean =
        template.recognition.rows.any { row ->
            row.fields.any { field ->
                field.type == "STORE_ID" || field.composite?.segments.orEmpty().any { it.type == "STORE_ID" }
            }
        }
}
