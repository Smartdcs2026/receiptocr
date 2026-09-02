package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateComposite
import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateSegment
import com.receiptocr.app.config.UniversalOcrTemplate
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem

/**
 * Round83 field-first fallback
 *
 * ใช้เมื่อ strict template จับทั้งแถวไม่ได้ โดยเปลี่ยนหลักคิดจาก
 * "ทั้งแถวต้องตรงก่อน" เป็น "POS เป็น anchor แล้วเก็บแต่ละช่องตามลำดับ Admin"
 *
 * - รองรับ 1-3 แถว
 * - รองรับ COMPOSITE_CODE
 * - LITERAL / SEPARATOR เป็น soft structure ไม่ทำให้ทั้ง POS หายเมื่อ OCR อ่านคลาด 1 ตัว
 * - CUSTOMER / DATE / TIME / STORE จับแยกและรวมผลจากหลาย OCR pass ด้วย consensus
 * - ไม่ hard-code แบรนด์ และไม่สร้างค่าที่ไม่มีในข้อความ
 */
object TemplateSequenceFallback {
    private const val DIGIT = "[0-9OoIl|SsZzBbGg]"
    private const val ALNUM = "[A-Za-z0-9OoIl|SsZzBbGg]"
    private const val SAME_ROW_GAP = "[\\s|,;:_#./\\-]{0,16}"
    private const val NEXT_ROW_GAP = ".{0,96}?"

    private data class Compiled(
        val template: UniversalOcrTemplate,
        val regex: Regex,
        val captureTypes: List<String>
    )

    private data class Candidate(
        val template: UniversalOcrTemplate,
        val fields: Map<String, String>,
        val score: Int
    )

    fun apply(
        rawTexts: List<String>,
        records: List<PosRecord>,
        work: WorkItem,
        imagePath: String,
        templates: List<UniversalOcrTemplate>
    ): UniversalTemplateResult {
        val textCandidates = buildTextCandidates(rawTexts)
        if (textCandidates.isEmpty()) return failed(records)

        val parsed = templates.asSequence()
            .filter { it.active }
            .mapNotNull(::compileTemplate)
            .flatMap { compiled ->
                textCandidates.asSequence().flatMap { text ->
                    compiled.regex.findAll(text).mapNotNull { result ->
                        val fields = extract(compiled, result)
                        val pos = fields["POS_NUMBER"]?.let(OcrTextNormalizer::parsePosNumber)
                            ?: return@mapNotNull null
                        if (pos <= 0) return@mapNotNull null

                        // ยอมรับ partial record เพื่อให้หลาย pass ช่วยกันเติมค่า
                        // แต่ต้องมีอย่างน้อย 2 ช่องหลัก ลดโอกาสหยิบเลขทั่วไปมาเป็น POS
                        val coreCount = listOf("CUSTOMER_VALUE", "BILL_DATE", "BILL_TIME")
                            .count { !fields[it].isNullOrBlank() }
                        if (coreCount < 2) return@mapNotNull null

                        Candidate(
                            template = compiled.template,
                            fields = fields,
                            score = compiled.template.priority +
                                coreCount * 35 +
                                fields.values.count { it.isNotBlank() } * 8
                        )
                    }
                }
            }
            .toList()

        if (parsed.isEmpty()) return failed(records)

        val bestByPos = parsed
            .mapNotNull { candidate ->
                OcrTextNormalizer.parsePosNumber(candidate.fields["POS_NUMBER"].orEmpty())?.let { it to candidate }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, candidates) -> fuseCandidates(candidates) }
            .filterValues { it != null }
            .mapValues { it.value!! }
            .toSortedMap()

        if (bestByPos.isEmpty()) return failed(records)

        val updated = records.toMutableList()
        val assigned = linkedSetOf<Int>()
        val warningsByPos = linkedMapOf<Int, List<String>>()
        val usedTemplates = linkedSetOf<String>()

        bestByPos.forEach { (pos, candidate) ->
            var index = updated.indexOfFirst { it.posNumber == pos }
            if (index < 0) {
                index = updated.indexOfFirst { record ->
                    record.posNumber !in assigned &&
                        record.customerNo.isBlank() &&
                        record.billDate.isBlank() &&
                        record.billTime.isBlank() &&
                        !record.noReceipt
                }
                if (index < 0) return@forEach
                updated[index] = updated[index].copy(posNumber = pos)
            }

            assigned += pos
            usedTemplates += candidate.template.templateName
            val current = updated[index]
            val customer = candidate.fields["CUSTOMER_VALUE"].orEmpty().filter(Char::isDigit)
            val date = candidate.fields["BILL_DATE"].orEmpty()
                .replace('.', '/')
                .replace('-', '/')
            val time = candidate.fields["BILL_TIME"].orEmpty().replace('.', ':')

            val missing = buildList {
                val core = candidate.template.validation.requiredCore
                if (core.customerValue && customer.isBlank()) add("ยอด/เลขลูกค้า")
                if (core.date && date.isBlank()) add("วันที่")
                if (core.time && time.isBlank()) add("เวลา")
            }
            val warning = buildList {
                add("อ่านข้อความบางส่วนไม่ตรงทั้งแถว แต่แยกช่องตามลำดับที่ Admin กำหนดได้")
                if (missing.isNotEmpty()) add("ยังอ่าน ${missing.joinToString(", ")} ไม่ครบ")
                add("กรุณาตรวจเทียบกับภาพก่อนส่ง")
            }.joinToString(" • ")

            updated[index] = current.copy(
                customerNo = customer.ifBlank { current.customerNo },
                billDate = date.ifBlank { current.billDate },
                billTime = time.ifBlank { current.billTime },
                noReceipt = false,
                noReceiptReason = "",
                source = "OCR-FIELD-FIRST",
                ocrSourceImagePath = imagePath,
                ocrWarnings = warning,
                ocrCounterCycle = candidate.template.duplicatePolicy.customerCounterCycle.uppercase()
            )
            warningsByPos[pos] = listOf(warning)
        }

        val detected = assigned.sorted()
        if (detected.isEmpty()) return failed(records)

        // รักษาลำดับ extracted ให้ตรงกับ detectedPos แม้บาง POS จะไม่มี STORE_ID
        val extracted = linkedMapOf<String, List<String>>()
        val types = bestByPos.values.flatMap { it.fields.keys }.toSet()
        types.forEach { type ->
            extracted[type] = detected.map { pos -> bestByPos[pos]?.fields?.get(type).orEmpty() }
        }

        return UniversalTemplateResult(
            records = updated,
            message = "อ่านข้อความและแยกข้อมูลตามลำดับช่องได้ • พบ ${detected.size} เครื่อง • กรุณาตรวจเทียบกับภาพ",
            templateName = usedTemplates.joinToString(" / "),
            detectedPos = detected,
            extracted = extracted,
            validationWarnings = warningsByPos,
            usedUniversalTemplate = true
        )
    }

    /** ใช้ทดสอบ parser โดยตรงโดยไม่ต้องสร้างภาพ */
    internal fun parseText(text: String, template: UniversalOcrTemplate): List<Map<String, String>> {
        val compiled = compileTemplate(template) ?: return emptyList()
        return buildTextCandidates(listOf(text)).flatMap { candidate ->
            compiled.regex.findAll(candidate).map { extract(compiled, it) }.toList()
        }.filter { fields ->
            fields["POS_NUMBER"]?.let(OcrTextNormalizer::parsePosNumber) != null &&
                listOf("CUSTOMER_VALUE", "BILL_DATE", "BILL_TIME")
                    .count { !fields[it].isNullOrBlank() } >= 2
        }.distinct()
    }

    private fun failed(records: List<PosRecord>) = UniversalTemplateResult(
        records = records,
        message = "อ่านข้อความได้ แต่ยังแยกข้อมูลตามรูปแบบบิลนี้ไม่ได้",
        usedUniversalTemplate = true
    )

    /**
     * สร้างหลายมุมมองจากข้อความจริงที่ ML Kit คืนมา
     * ทั้งบรรทัดเดี่ยว, หน้าต่างหลายบรรทัด, ทั้งข้อความ และแบบ compact
     * เพื่อไม่ผูกกับการตัดบรรทัดของ ML Kit
     */
    private fun buildTextCandidates(rawTexts: List<String>): List<String> = buildList {
        rawTexts.filter { it.isNotBlank() }.forEach { raw ->
            val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
            lines.forEach { addAll(candidateVariants(it)) }

            val maxJoin = 8.coerceAtMost(lines.size)
            if (maxJoin >= 2) {
                for (count in 2..maxJoin) {
                    for (start in 0..lines.size - count) {
                        addAll(candidateVariants(lines.subList(start, start + count).joinToString(" ")))
                    }
                }
            }

            addAll(candidateVariants(lines.joinToString(" ")))
            addAll(candidateVariants(raw.replace('\n', ' ')))
        }
    }.filter { it.isNotBlank() }.distinct()

    private fun candidateVariants(raw: String): List<String> {
        val spaced = OcrTextNormalizer.normalizeLine(raw)
        if (spaced.isBlank()) return emptyList()
        val compact = spaced.replace(
            Regex("(?<=[A-Za-z0-9OoIl|SsZzBbGg])\\s+(?=[A-Za-z0-9OoIl|SsZzBbGg])"),
            ""
        )
        return listOf(spaced, compact).distinct()
    }

    private fun compileTemplate(template: UniversalOcrTemplate): Compiled? {
        val rows = template.recognition.rows.sortedBy { it.row }
        if (rows.isEmpty()) return null

        val captureTypes = mutableListOf<String>()
        val parts = mutableListOf<String>()
        var hasPosAnchor = false

        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) parts += NEXT_ROW_GAP

            row.fields.sortedBy { it.order }.forEachIndexed { fieldIndex, field ->
                val pattern = fieldPattern(field, captureTypes) ?: return null
                val containsPos = field.type.equals("POS_NUMBER", true) ||
                    field.composite?.segments.orEmpty().any { it.type.equals("POS_NUMBER", true) }
                if (containsPos) hasPosAnchor = true

                if (fieldIndex > 0) parts += SAME_ROW_GAP
                val tokenGap = if (field.tokenGap > 0) {
                    "(?:\\s+\\S+){0,${field.tokenGap.coerceIn(0, 8)}}?\\s*"
                } else ""

                // POS ต้องมีจริง ส่วนช่องอื่นยอมให้หายจาก OCR pass หนึ่งก่อน
                // แล้วใช้ผลจาก pass อื่นมารวมภายหลัง
                parts += if (containsPos) {
                    tokenGap + pattern
                } else {
                    "(?:$tokenGap$pattern)?"
                }
            }
        }

        if (!hasPosAnchor) return null

        return runCatching {
            Compiled(
                template = template,
                regex = Regex(
                    parts.joinToString(""),
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                ),
                captureTypes = captureTypes
            )
        }.getOrNull()
    }

    private fun fieldPattern(field: OcrTemplateField, captures: MutableList<String>): String? {
        val type = field.type.uppercase()
        val sample = field.example?.trim().orEmpty()
        val sampleDigits = sample.count(Char::isDigit)
        val min = field.minLength.coerceAtLeast(1)
        val max = field.maxLength.coerceAtLeast(min)

        fun capture(captureType: String, inner: String): String {
            captures += captureType
            return "($inner)"
        }

        return when (type) {
            "POS_NUMBER" -> capture(
                "POS_NUMBER",
                posPattern(sample, field.posPrefixes, field.posDigits, min, max)
            )
            "CUSTOMER_VALUE" -> {
                val length = sampleDigits.takeIf { it > 0 } ?: min.takeIf { min == max }
                capture("CUSTOMER_VALUE", length?.let(::fixedDigits) ?: rangedDigits(min, max))
            }
            "BILL_DATE" -> capture("BILL_DATE", datePattern(sample))
            "BILL_TIME" -> capture("BILL_TIME", timePattern(sample))
            "STORE_ID" -> {
                val length = sampleDigits.takeIf { it > 0 } ?: min.takeIf { min == max } ?: min
                capture("STORE_ID", fixedDigits(length))
            }
            "YEAR_VALUE", "YEAR" -> capture("YEAR_VALUE", fixedDigits(sampleDigits.takeIf { it > 0 } ?: 2))
            "MONTH_VALUE", "MONTH" -> capture("MONTH_VALUE", fixedDigits(sampleDigits.takeIf { it > 0 } ?: 2))
            "DAY_VALUE", "DAY" -> capture("DAY_VALUE", fixedDigits(sampleDigits.takeIf { it > 0 } ?: 2))
            "EMPLOYEE_CODE" -> {
                val length = sample.length.takeIf { sample.isNotBlank() } ?: min.takeIf { min == max }
                capture("EMPLOYEE_CODE", length?.let(::fixedAlnum) ?: "$ALNUM{$min,$max}")
            }
            "NUMBER_TEXT" -> {
                val length = sampleDigits.takeIf { it > 0 } ?: min.takeIf { min == max } ?: min
                fixedDigits(length)
            }
            "ALNUM_TEXT" -> {
                val length = sample.length.takeIf { sample.isNotBlank() } ?: min.takeIf { min == max }
                length?.let(::fixedAlnum) ?: "$ALNUM{$min,$max}"
            }
            "LITERAL" -> softLiteral(field.literal ?: sample)
            "SEPARATOR" -> softLiteral(field.separatorValue ?: sample.ifBlank { "-" })
            "IGNORE" -> ".{0,48}?"
            "COMPOSITE_CODE" -> compositePattern(field.composite, captures)
            else -> null
        }
    }

    private fun compositePattern(
        composite: OcrTemplateComposite?,
        captures: MutableList<String>
    ): String? {
        if (composite == null || composite.segments.isEmpty()) return null
        val parts = mutableListOf<String>()

        composite.prefix?.takeIf { it.isNotBlank() }?.let {
            parts += "(?:${fuzzyLiteral(it)})?"
        }

        composite.segments.sortedBy { it.order }.forEach { segment ->
            val pattern = segmentPattern(segment, composite, captures) ?: return null
            val isPos = segment.type.equals("POS_NUMBER", true)
            parts += if (isPos) pattern else "(?:$pattern)?"
        }
        return parts.joinToString("\\s*")
    }

    private fun segmentPattern(
        segment: OcrTemplateSegment,
        composite: OcrTemplateComposite,
        captures: MutableList<String>
    ): String? {
        val type = segment.type.uppercase()
        val sample = segment.example?.trim().orEmpty()
        val digits = sample.count(Char::isDigit)
        val length = when {
            segment.length > 0 -> segment.length
            sample.isNotBlank() -> sample.length
            type in setOf("YEAR_VALUE", "YEAR", "MONTH_VALUE", "MONTH", "DAY_VALUE", "DAY") -> 2
            else -> 1
        }

        fun capture(captureType: String, inner: String): String {
            captures += captureType
            return "($inner)"
        }

        return when (type) {
            "POS_NUMBER" -> capture("POS_NUMBER", posPattern(sample, null, null, 1, length.coerceAtLeast(1)))
            "CUSTOMER_VALUE" -> capture("CUSTOMER_VALUE", fixedDigits(digits.takeIf { it > 0 } ?: length))
            "STORE_ID" -> capture("STORE_ID", fixedDigits(digits.takeIf { it > 0 } ?: length))
            "YEAR_VALUE", "YEAR" -> capture("YEAR_VALUE", fixedDigits(digits.takeIf { it > 0 } ?: length))
            "MONTH_VALUE", "MONTH" -> capture("MONTH_VALUE", fixedDigits(digits.takeIf { it > 0 } ?: length))
            "DAY_VALUE", "DAY" -> capture("DAY_VALUE", fixedDigits(digits.takeIf { it > 0 } ?: length))
            "EMPLOYEE_CODE" -> capture("EMPLOYEE_CODE", fixedAlnum(length))
            "LITERAL" -> softLiteral(sample)
            "SEPARATOR" -> softLiteral(sample.ifBlank { composite.separator ?: "-" })
            "NUMBER_TEXT" -> fixedDigits(digits.takeIf { it > 0 } ?: length)
            "ALNUM_TEXT" -> fixedAlnum(length)
            "IGNORE" -> ".{0,32}?"
            else -> null
        }
    }

    private fun posPattern(
        sample: String,
        prefixesRaw: String?,
        configuredDigits: Int?,
        min: Int,
        max: Int
    ): String {
        val examplePrefix = sample.takeWhile(Char::isLetter)
        val exampleDigits = sample.takeLastWhile(Char::isDigit).length.takeIf { it > 0 }
        val digits = exampleDigits
            ?: configuredDigits
            ?: min.takeIf { min == max }
            ?: max.coerceAtMost(3).coerceAtLeast(1)
        val prefixes = prefixesRaw.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
        val prefix = when {
            prefixes.isNotEmpty() -> prefixes.joinToString("|", "(?:", ")") { fuzzyLiteral(it) }
            examplePrefix.isNotBlank() -> fuzzyLiteral(examplePrefix)
            else -> "[A-Za-z]?"
        }
        return "$prefix${fixedDigits(digits)}"
    }

    private fun datePattern(sample: String): String {
        val groups = Regex("\\d+").findAll(sample).map { it.value.length }.toList()
        val lengths = if (groups.size == 3) groups else listOf(2, 2, 4)
        return "${fixedDigits(lengths[0])}\\s*[./-]\\s*" +
            "${fixedDigits(lengths[1])}\\s*[./-]\\s*" +
            fixedDigits(lengths[2])
    }

    private fun timePattern(sample: String): String {
        val groups = Regex("\\d+").findAll(sample).map { it.value.length }.toList()
        val hour = groups.getOrNull(0)?.coerceIn(1, 2) ?: 2
        val minute = groups.getOrNull(1)?.coerceIn(2, 2) ?: 2
        val second = groups.getOrNull(2)?.coerceIn(2, 2)
        return buildString {
            append(fixedDigits(hour))
            append("\\s*[:.]\\s*")
            append(fixedDigits(minute))
            if (second != null) {
                append("\\s*[:.]\\s*")
                append(fixedDigits(second))
            }
        }
    }

    private fun fixedDigits(length: Int): String {
        val size = length.coerceAtLeast(1)
        return if (size == 1) DIGIT else "$DIGIT(?:\\s*$DIGIT){${size - 1}}"
    }

    private fun rangedDigits(min: Int, max: Int): String {
        val safeMin = min.coerceAtLeast(1)
        val safeMax = max.coerceAtLeast(safeMin)
        return "$DIGIT(?:\\s*$DIGIT){${safeMin - 1},${safeMax - 1}}"
    }

    private fun fixedAlnum(length: Int): String {
        val size = length.coerceAtLeast(1)
        return if (size == 1) ALNUM else "$ALNUM(?:\\s*$ALNUM){${size - 1}}"
    }

    private fun softLiteral(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        return "(?:${fuzzyLiteral(value)})?"
    }

    private fun fuzzyLiteral(raw: String): String = raw.trim().map { character ->
        when (character) {
            '0', 'O', 'o' -> "[0Oo]"
            '1', 'I', 'i', 'l', '|' -> "[1Iil|]"
            '2', 'Z', 'z' -> "[2Zz]"
            '5', 'S', 's' -> "[5Ss]"
            '8', 'B', 'b' -> "[8Bb]"
            'U', 'u', 'V', 'v' -> "[UuVv]"
            else -> Regex.escape(character.toString())
        }
    }.joinToString("\\s*")

    private fun extract(compiled: Compiled, result: MatchResult): Map<String, String> {
        val values = linkedMapOf<String, String>()
        compiled.captureTypes.forEachIndexed { index, type ->
            val raw = result.groupValues.getOrNull(index + 1).orEmpty().trim()
            if (raw.isBlank() || values.containsKey(type)) return@forEachIndexed
            values[type] = normalizeCaptured(type, raw)
        }
        return values
    }

    private fun normalizeCaptured(type: String, raw: String): String {
        val compact = raw.replace(Regex("\\s+"), "")
        return when (type) {
            "POS_NUMBER" -> {
                val prefix = compact.takeWhile {
                    it.isLetter() && it.uppercaseChar() !in setOf('O', 'I', 'S', 'Z', 'B', 'G')
                }
                prefix + normalizeDigits(compact.drop(prefix.length))
            }
            "CUSTOMER_VALUE", "STORE_ID", "YEAR_VALUE", "MONTH_VALUE", "DAY_VALUE" -> normalizeDigits(compact)
            "BILL_DATE" -> normalizeDigits(compact).replace('.', '/').replace('-', '/')
            "BILL_TIME" -> normalizeDigits(compact).replace('.', ':')
            else -> compact
        }
    }

    private fun normalizeDigits(value: String): String = value.map { character ->
        when (character) {
            'O', 'o' -> '0'
            'I', 'i', 'l', '|' -> '1'
            'S', 's' -> '5'
            'Z', 'z' -> '2'
            'B', 'b' -> '8'
            'G' -> '6'
            'g' -> '9'
            else -> character
        }
    }.joinToString("")

    /**
     * เลือกแม่แบบที่มีหลักฐานซ้ำจากหลาย pass มากที่สุดก่อน
     * แล้วเลือกค่าของแต่ละ field ด้วย majority vote เพื่อลดผลจาก pass ที่อ่านตัวเดียวผิด
     */
    private fun fuseCandidates(candidates: List<Candidate>): Candidate? {
        if (candidates.isEmpty()) return null
        val groups = candidates.groupBy { it.template.templateId }
        val winningGroup = groups.values.maxWithOrNull(
            compareBy<List<Candidate>> { it.size }
                .thenBy { group -> group.maxOfOrNull { it.score } ?: 0 }
        ) ?: return null

        val base = winningGroup.maxBy { it.score }
        val fieldTypes = winningGroup.flatMap { it.fields.keys }.toSet()
        val merged = linkedMapOf<String, String>()

        fieldTypes.forEach { type ->
            val values = winningGroup.mapNotNull { candidate ->
                candidate.fields[type]?.takeIf { it.isNotBlank() }
            }
            if (values.isNotEmpty()) {
                val selected = values.groupingBy { it }.eachCount().entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, Int>> { it.value }
                            .thenByDescending { it.key.length }
                    )
                    .first().key
                merged[type] = selected
            }
        }

        return base.copy(
            fields = merged,
            score = winningGroup.maxOf { it.score } + winningGroup.size.coerceAtMost(8) * 3
        )
    }
}
