package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateComposite
import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateSegment
import com.receiptocr.app.config.UniversalOcrTemplate
import com.receiptocr.app.model.PosRecord

/**
 * Round83: ตัวแยกข้อมูลแบบ field-first
 *
 * จุดประสงค์คือไม่บังคับให้ข้อความทั้งแถวต้องตรงกับ regex เดียวก่อนจึงจะเก็บข้อมูลได้
 * - POS เป็น anchor หลัก
 * - literal / separator และช่องประกอบที่ไม่ใช่ข้อมูลหลักเป็น soft structure
 * - CUSTOMER / DATE / TIME / STORE อ่านแยกเป็น capture ตามลำดับที่ Admin กำหนด
 * - รวมผลจากหลาย OCR pass ด้วย consensus ต่อ POS
 * - รองรับ 1-3 แถว และ COMPOSITE_CODE
 *
 * ตัวอ่านนี้ทำงานหลัง strict interpreter ล้มเหลวเท่านั้น จึงไม่เปลี่ยนผลของแม่แบบเดิมที่อ่านผ่านอยู่แล้ว
 */
object FieldFirstTemplateInterpreter {
    private const val DIGIT = "[0-9OoIl|SsZzBbGg]"
    private const val ALNUM = "[A-Za-z0-9OoIl|SsZzBbGg]"
    private const val SAME_ROW_GAP = "[\\s|,;:_#./\\-]{0,16}"
    private const val NEXT_ROW_GAP = ".{0,96}?"

    private data class CompiledTemplate(
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
        passes: List<OcrTextPass>,
        records: List<PosRecord>,
        imagePath: String,
        templates: List<UniversalOcrTemplate>
    ): UniversalTemplateResult {
        val textCandidates = buildTextCandidates(passes)
        if (textCandidates.isEmpty()) {
            return UniversalTemplateResult(
                records = records,
                message = "อ่านข้อความได้ แต่ยังแยกข้อมูลตามรูปแบบบิลนี้ไม่ได้",
                usedUniversalTemplate = true
            )
        }

        val parsed = templates.asSequence()
            .filter { it.active }
            .mapNotNull(::compileTemplate)
            .flatMap { compiled ->
                textCandidates.asSequence().flatMap { text ->
                    compiled.regex.findAll(text).mapNotNull { result ->
                        val fields = extract(compiled, result)
                        val pos = fields["POS_NUMBER"]?.let(OcrTextNormalizer::parsePosNumber)
                            ?: return@mapNotNull null
                        val coreCount = listOf("CUSTOMER_VALUE", "BILL_DATE", "BILL_TIME")
                            .count { !fields[it].isNullOrBlank() }
                        if (coreCount < 2) return@mapNotNull null
                        Candidate(
                            template = compiled.template,
                            fields = fields,
                            score = compiled.template.priority + coreCount * 35 + fields.values.count { it.isNotBlank() } * 8
                        )
                    }
                }
            }
            .toList()

        if (parsed.isEmpty()) {
            return UniversalTemplateResult(
                records = records,
                message = "อ่านข้อความได้ แต่ยังแยกข้อมูลตามรูปแบบบิลนี้ไม่ได้",
                usedUniversalTemplate = true
            )
        }

        val bestByPos = parsed
            .mapNotNull { candidate ->
                OcrTextNormalizer.parsePosNumber(candidate.fields["POS_NUMBER"].orEmpty())?.let { it to candidate }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, candidates) -> fuseCandidates(candidates) }
            .filterValues { it != null }
            .mapValues { it.value!! }
            .toSortedMap()

        if (bestByPos.isEmpty()) {
            return UniversalTemplateResult(
                records = records,
                message = "อ่านข้อความได้ แต่ยังแยกข้อมูลตามรูปแบบบิลนี้ไม่ได้",
                usedUniversalTemplate = true
            )
        }

        val updated = records.toMutableList()
        val assigned = linkedSetOf<Int>()
        val warnings = linkedMapOf<Int, List<String>>()
        val usedTemplateNames = linkedSetOf<String>()

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
            usedTemplateNames += candidate.template.templateName

            val current = updated[index]
            val customer = candidate.fields["CUSTOMER_VALUE"].orEmpty().filter(Char::isDigit)
            val date = candidate.fields["BILL_DATE"].orEmpty()
                .replace('.', '/')
                .replace('-', '/')
            val time = candidate.fields["BILL_TIME"].orEmpty().replace('.', ':')

            val missing = buildList {
                if (candidate.template.validation.requiredCore.customerValue && customer.isBlank()) add("ยอด/เลขลูกค้า")
                if (candidate.template.validation.requiredCore.date && date.isBlank()) add("วันที่")
                if (candidate.template.validation.requiredCore.time && time.isBlank()) add("เวลา")
            }
            val warningText = buildList {
                add("แยกข้อมูลจากลำดับช่องที่ Admin กำหนดได้ แม้ข้อความบางส่วนไม่ตรงทั้งแถว")
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
                ocrWarnings = warningText,
                ocrCounterCycle = candidate.template.duplicatePolicy.customerCounterCycle.uppercase()
            )
            warnings[pos] = listOf(warningText)
        }

        val detected = assigned.sorted()
        if (detected.isEmpty()) {
            return UniversalTemplateResult(
                records = records,
                message = "อ่านข้อความได้ แต่ยังแยกข้อมูลตามรูปแบบบิลนี้ไม่ได้",
                usedUniversalTemplate = true
            )
        }

        // ให้ extracted เรียงตำแหน่งตรงกับ detectedPos เสมอ รวมค่าว่างไว้ด้วย
        // เพื่อไม่ให้ STORE_ID ของ POS หนึ่งเลื่อนไปจับอีก POS เมื่อบางเครื่องอ่านร้านไม่ได้
        val extracted = linkedMapOf<String, List<String>>()
        val types = bestByPos.values.flatMap { it.fields.keys }.toSet()
        types.forEach { type ->
            extracted[type] = detected.map { pos -> bestByPos[pos]?.fields?.get(type).orEmpty() }
        }

        return UniversalTemplateResult(
            records = updated,
            message = "อ่านข้อความและแยกข้อมูลตามลำดับช่องได้ • พบ ${detected.size} เครื่อง • กรุณาตรวจเทียบกับภาพ",
            templateName = usedTemplateNames.joinToString(" / "),
            detectedPos = detected,
            extracted = extracted,
            validationWarnings = warnings,
            usedUniversalTemplate = true
        )
    }

    /** Pure parser สำหรับ unit test โดยไม่ต้องสร้าง ML Kit Text */
    internal fun parseText(text: String, template: UniversalOcrTemplate): List<Map<String, String>> {
        val compiled = compileTemplate(template) ?: return emptyList()
        return candidateVariants(text).flatMap { candidate ->
            compiled.regex.findAll(candidate).map { extract(compiled, it) }.toList()
        }.filter { fields ->
            fields["POS_NUMBER"]?.let(OcrTextNormalizer::parsePosNumber) != null &&
                listOf("CUSTOMER_VALUE", "BILL_DATE", "BILL_TIME").count { !fields[it].isNullOrBlank() } >= 2
        }.distinct()
    }

    private fun buildTextCandidates(passes: List<OcrTextPass>): List<String> = buildList {
        passes.filter { it.text.text.isNotBlank() }.forEach { pass ->
            val raw = pass.text.text
            addAll(candidateVariants(raw))

            val rebuiltLines = SpatialTextLayout.rebuild(pass.text).lines.map { it.text }.filter { it.isNotBlank() }
            addLineWindows(rebuiltLines, this)

            val mlLines = pass.text.textBlocks.flatMap { block -> block.lines.map { it.text } }.filter { it.isNotBlank() }
            addLineWindows(mlLines, this)

            val elements = pass.text.textBlocks.flatMap { block ->
                block.lines.flatMap { line ->
                    line.elements.mapNotNull { element ->
                        val box = element.boundingBox ?: return@mapNotNull null
                        Triple(element.text, box.exactCenterY() + pass.originY, box.exactCenterX() + pass.originX)
                    }
                }
            }.sortedWith(compareBy<Triple<String, Float, Float>> { it.second }.thenBy { it.third })
                .map { it.first }
            if (elements.isNotEmpty()) addAll(candidateVariants(elements.joinToString(" ")))
        }
    }.filter { it.isNotBlank() }.distinct()

    private fun addLineWindows(lines: List<String>, output: MutableList<String>) {
        if (lines.isEmpty()) return
        lines.forEach { output += candidateVariants(it) }
        val maxJoin = 8.coerceAtMost(lines.size)
        for (count in 2..maxJoin) {
            for (start in 0..lines.size - count) {
                output += candidateVariants(lines.subList(start, start + count).joinToString(" "))
            }
        }
        output += candidateVariants(lines.joinToString(" "))
    }

    private fun candidateVariants(raw: String): List<String> {
        val spaced = OcrTextNormalizer.normalizeLine(raw.replace('\n', ' '))
        if (spaced.isBlank()) return emptyList()
        val compact = spaced.replace(
            Regex("(?<=[A-Za-z0-9OoIl|SsZzBbGg])\\s+(?=[A-Za-z0-9OoIl|SsZzBbGg])"),
            ""
        )
        return listOf(spaced, compact).distinct()
    }

    private fun compileTemplate(template: UniversalOcrTemplate): CompiledTemplate? {
        val rows = template.recognition.rows.sortedBy { it.row }
        if (rows.isEmpty()) return null
        val captures = mutableListOf<String>()
        val parts = mutableListOf<String>()
        var hasPosAnchor = false

        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) parts += NEXT_ROW_GAP
            row.fields.sortedBy { it.order }.forEachIndexed { fieldIndex, field ->
                val fieldPattern = fieldPattern(field, captures) ?: return null
                val containsPos = field.type.equals("POS_NUMBER", true) ||
                    field.composite?.segments.orEmpty().any { it.type.equals("POS_NUMBER", true) }
                if (containsPos) hasPosAnchor = true

                if (fieldIndex > 0) parts += SAME_ROW_GAP
                val tokenGap = if (field.tokenGap > 0) {
                    "(?:\\s+\\S+){0,${field.tokenGap.coerceIn(0, 8)}}?\\s*"
                } else ""

                // POS เป็น anchor ที่ต้องมีจริง ส่วนช่องอื่นเก็บแบบ optional ก่อน แล้วค่อยตรวจครบภายหลัง
                parts += if (containsPos) {
                    tokenGap + fieldPattern
                } else {
                    "(?:$tokenGap$fieldPattern)?"
                }
            }
        }
        if (!hasPosAnchor) return null

        return runCatching {
            CompiledTemplate(
                template = template,
                regex = Regex(parts.joinToString(""), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                captureTypes = captures
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
            "POS_NUMBER" -> capture("POS_NUMBER", posPattern(sample, field.posPrefixes, field.posDigits, min, max))
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
            "SEPARATOR" -> softSeparator(field.separatorValue ?: sample.ifBlank { "-" })
            "IGNORE" -> ".{0,48}?"
            "COMPOSITE_CODE" -> compositePattern(field.composite, captures)
            else -> null
        }
    }

    private fun compositePattern(composite: OcrTemplateComposite?, captures: MutableList<String>): String? {
        if (composite == null || composite.segments.isEmpty()) return null
        val parts = mutableListOf<String>()
        composite.prefix?.takeIf { it.isNotBlank() }?.let { parts += "(?:${fuzzyLiteral(it)})?" }

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
            "SEPARATOR" -> softSeparator(sample.ifBlank { composite.separator ?: "-" })
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
            "${fixedDigits(lengths[1])}\\s*[./-]\\s*" + fixedDigits(lengths[2])
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

    private fun softSeparator(raw: String): String {
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

    private fun extract(compiled: CompiledTemplate, result: MatchResult): Map<String, String> {
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
                val prefix = compact.takeWhile { it.isLetter() && it.uppercaseChar() !in setOf('O', 'I', 'S', 'Z', 'B', 'G') }
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

    private fun fuseCandidates(candidates: List<Candidate>): Candidate? {
        if (candidates.isEmpty()) return null
        val byTemplate = candidates.groupBy { it.template.templateId }
        val winningGroup = byTemplate.values.maxWithOrNull(
            compareBy<List<Candidate>> { group -> group.size }
                .thenBy { group -> group.maxOfOrNull { it.score } ?: 0 }
        ) ?: return null
        val base = winningGroup.maxBy { it.score }
        val fieldTypes = winningGroup.flatMap { it.fields.keys }.toSet()
        val merged = linkedMapOf<String, String>()
        fieldTypes.forEach { type ->
            val values = winningGroup.mapNotNull { candidate -> candidate.fields[type]?.takeIf { it.isNotBlank() } }
            if (values.isNotEmpty()) {
                val consensus = values.groupingBy { it }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
                    .first().key
                merged[type] = consensus
            }
        }
        return base.copy(
            fields = merged,
            score = winningGroup.maxOf { it.score } + winningGroup.size.coerceAtMost(8) * 3
        )
    }
}
