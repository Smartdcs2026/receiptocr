package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateComposite
import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateSegment
import com.receiptocr.app.config.UniversalOcrTemplate
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem

/**
 * Round84 anchored-sequence parser
 *
 * หลักสำคัญ: เดินตามลำดับช่องที่ Admin กำหนดจริง ๆ และห้ามข้ามช่องที่จำเป็น
 * เพื่อไม่ให้ข้อมูลเลื่อนตำแหน่ง เช่น R | 20 | POS 2 | ลูกค้า 039030
 * กลายเป็น POS 2 | ลูกค้า 020390 แบบ Round83
 *
 * ใช้เมื่อ strict interpreter จับไม่ได้เท่านั้น
 */
object TemplateSequenceFallback {
    private const val DIGIT = "[0-9OoIl|SsZzBbGg]"
    private const val ALNUM = "[A-Za-z0-9OoIl|SsZzBbGg]"
    private const val FIELD_GAP = "\\s*"
    private const val ROW_GAP = "(?:\\s+\\S+){0,6}?\\s*"

    private data class Compiled(
        val template: UniversalOcrTemplate,
        val regex: Regex,
        val captureTypes: List<String>,
        val anchorCount: Int
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
        val allowedPos = records.map { it.posNumber }.toSet()
        val textCandidates = buildTextCandidates(rawTexts)
        if (textCandidates.isEmpty()) return failed(records)

        val matches = templates.asSequence()
            .filter { it.active }
            .mapNotNull(::compileTemplate)
            .flatMap { compiled ->
                textCandidates.asSequence().flatMap { text ->
                    compiled.regex.findAll(text).mapNotNull { result ->
                        val fields = extract(compiled, result)
                        val pos = fields["POS_NUMBER"]?.let(OcrTextNormalizer::parsePosNumber)
                            ?: return@mapNotNull null
                        if (compiled.template.validation.pos.mustExistInStorePlan && pos !in allowedPos) {
                            return@mapNotNull null
                        }
                        if (!coreFieldsArePlausible(fields, compiled.template)) return@mapNotNull null

                        Candidate(
                            template = compiled.template,
                            fields = fields,
                            score = compiled.template.priority +
                                compiled.anchorCount * 25 +
                                fields.values.count { it.isNotBlank() } * 10
                        )
                    }
                }
            }
            .toList()

        if (matches.isEmpty()) return failed(records)

        // เลือกชุดข้อมูลที่ซ้ำกันจากหลายรอบอ่านภาพมากที่สุด
        // ไม่ผสมค่าคนละ candidate เข้าด้วยกัน เพื่อป้องกัน POS/ลูกค้า/เวลาไขว้กัน
        val bestByPos = matches
            .mapNotNull { candidate ->
                OcrTextNormalizer.parsePosNumber(candidate.fields["POS_NUMBER"].orEmpty())?.let { it to candidate }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, candidates) -> chooseWholeRecordConsensus(candidates) }
            .filterValues { it != null }
            .mapValues { it.value!! }
            .toSortedMap()

        if (bestByPos.isEmpty()) return failed(records)

        val updated = records.toMutableList()
        val usedNames = linkedSetOf<String>()
        val detected = mutableListOf<Int>()

        bestByPos.forEach { (pos, candidate) ->
            val index = updated.indexOfFirst { it.posNumber == pos }
            if (index < 0) return@forEach

            val current = updated[index]
            val customer = candidate.fields["CUSTOMER_VALUE"].orEmpty().filter(Char::isDigit)
            val date = candidate.fields["BILL_DATE"].orEmpty()
                .replace('.', '/')
                .replace('-', '/')
            val time = candidate.fields["BILL_TIME"].orEmpty().replace('.', ':')

            updated[index] = current.copy(
                customerNo = customer.ifBlank { current.customerNo },
                billDate = date.ifBlank { current.billDate },
                billTime = time.ifBlank { current.billTime },
                noReceipt = false,
                noReceiptReason = "",
                source = "OCR-SEQUENCE",
                ocrSourceImagePath = imagePath,
                ocrWarnings = "",
                ocrCounterCycle = candidate.template.duplicatePolicy.customerCounterCycle.uppercase()
            )
            usedNames += candidate.template.templateName
            detected += pos
        }

        if (detected.isEmpty()) return failed(records)

        val orderedPos = detected.distinct().sorted()
        val extracted = linkedMapOf<String, List<String>>()
        val fieldTypes = bestByPos.values.flatMap { it.fields.keys }.toSet()
        fieldTypes.forEach { type ->
            extracted[type] = orderedPos.map { pos -> bestByPos[pos]?.fields?.get(type).orEmpty() }
        }

        return UniversalTemplateResult(
            records = updated,
            message = "อ่านข้อความและแยกข้อมูลตามลำดับที่กำหนดได้ • พบ ${orderedPos.size} เครื่อง",
            templateName = usedNames.joinToString(" / "),
            detectedPos = orderedPos,
            extracted = extracted,
            validationWarnings = emptyMap(),
            usedUniversalTemplate = true
        )
    }

    /** ใช้ unit test โดยไม่ต้องสร้าง ML Kit Text */
    internal fun parseText(text: String, template: UniversalOcrTemplate): List<Map<String, String>> {
        val compiled = compileTemplate(template) ?: return emptyList()
        return buildTextCandidates(listOf(text)).flatMap { candidate ->
            compiled.regex.findAll(candidate).map { extract(compiled, it) }.toList()
        }.filter { fields -> coreFieldsArePlausible(fields, template) }
            .distinct()
    }

    private fun failed(records: List<PosRecord>) = UniversalTemplateResult(
        records = records,
        message = "อ่านข้อความได้ แต่ยังแยกข้อมูลตามรูปแบบบิลนี้ไม่ได้",
        usedUniversalTemplate = true
    )

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
        val normalized = OcrTextNormalizer.normalizeLine(raw)
        if (normalized.isBlank()) return emptyList()
        val compact = normalized.replace(
            Regex("(?<=[A-Za-z0-9OoIl|SsZzBbGg])\\s+(?=[A-Za-z0-9OoIl|SsZzBbGg])"),
            ""
        )
        return listOf(normalized, compact).distinct()
    }

    private fun compileTemplate(template: UniversalOcrTemplate): Compiled? {
        val rows = template.recognition.rows.sortedBy { it.row }
        if (rows.isEmpty()) return null

        val captures = mutableListOf<String>()
        val parts = mutableListOf<String>()
        var hasPos = false
        var anchors = 0

        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) parts += ROW_GAP
            val fields = row.fields.sortedBy { it.order }
            fields.forEachIndexed { index, field ->
                if (index > 0) parts += FIELD_GAP
                val built = fieldPattern(field, captures) ?: return null
                val containsPos = field.type.equals("POS_NUMBER", true) ||
                    field.composite?.segments.orEmpty().any { it.type.equals("POS_NUMBER", true) }
                if (containsPos) hasPos = true
                if (field.type in setOf("LITERAL", "SEPARATOR", "COMPOSITE_CODE")) anchors++

                val tokenGap = if (field.tokenGap > 0) {
                    "(?:\\s+\\S+){0,${field.tokenGap.coerceIn(0, 8)}}?\\s*"
                } else ""

                parts += when {
                    field.type == "IGNORE" -> built
                    field.required -> tokenGap + built
                    else -> "(?:$tokenGap$built)?"
                }
            }
        }

        if (!hasPos) return null

        return runCatching {
            Compiled(
                template = template,
                regex = Regex(
                    parts.joinToString(""),
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                ),
                captureTypes = captures,
                anchorCount = anchors
            )
        }.getOrNull()
    }

    private fun fieldPattern(field: OcrTemplateField, captures: MutableList<String>): String? {
        val type = field.type.uppercase()
        val sample = field.example?.trim().orEmpty()
        val sampleDigits = sample.count(Char::isDigit)
        val min = field.minLength.coerceAtLeast(1)
        val max = field.maxLength.coerceAtLeast(min)

        fun capture(name: String, pattern: String): String {
            captures += name
            return "($pattern)"
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
            "LITERAL" -> fuzzyLiteral(field.literal ?: sample)
            "SEPARATOR" -> fuzzyLiteral(field.separatorValue ?: sample.ifBlank { "-" })
            "IGNORE" -> ".{0,${field.maxLength.coerceIn(0, 40)}}?"
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
        composite.prefix?.takeIf { it.isNotBlank() }?.let { parts += fuzzyLiteral(it) }

        composite.segments.sortedBy { it.order }.forEachIndexed { index, segment ->
            if (index > 0 || parts.isNotEmpty()) parts += FIELD_GAP
            parts += segmentPattern(segment, composite, captures) ?: return null
        }
        return parts.joinToString("")
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

        fun capture(name: String, pattern: String): String {
            captures += name
            return "($pattern)"
        }

        return when (type) {
            "POS_NUMBER" -> capture("POS_NUMBER", posPattern(sample, null, null, 1, length.coerceAtLeast(1)))
            "CUSTOMER_VALUE" -> capture("CUSTOMER_VALUE", fixedDigits(digits.takeIf { it > 0 } ?: length))
            "STORE_ID" -> capture("STORE_ID", fixedDigits(digits.takeIf { it > 0 } ?: length))
            "YEAR_VALUE", "YEAR" -> capture("YEAR_VALUE", fixedDigits(digits.takeIf { it > 0 } ?: length))
            "MONTH_VALUE", "MONTH" -> capture("MONTH_VALUE", fixedDigits(digits.takeIf { it > 0 } ?: length))
            "DAY_VALUE", "DAY" -> capture("DAY_VALUE", fixedDigits(digits.takeIf { it > 0 } ?: length))
            "EMPLOYEE_CODE" -> capture("EMPLOYEE_CODE", fixedAlnum(length))
            "LITERAL" -> fuzzyLiteral(sample)
            "SEPARATOR" -> fuzzyLiteral(sample.ifBlank { composite.separator ?: "-" })
            "NUMBER_TEXT" -> fixedDigits(digits.takeIf { it > 0 } ?: length)
            "ALNUM_TEXT" -> fixedAlnum(length)
            "IGNORE" -> ".{0,$length}?"
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
            else -> ""
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

    private fun fuzzyLiteral(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        return value.map { character ->
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
    }

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

    private fun coreFieldsArePlausible(
        fields: Map<String, String>,
        template: UniversalOcrTemplate
    ): Boolean {
        val pos = fields["POS_NUMBER"]?.let(OcrTextNormalizer::parsePosNumber) ?: return false
        if (pos <= 0) return false

        val core = template.validation.requiredCore
        if (core.customerValue && fields["CUSTOMER_VALUE"].isNullOrBlank()) return false
        if (core.date && fields["BILL_DATE"].isNullOrBlank()) return false
        if (core.time && fields["BILL_TIME"].isNullOrBlank()) return false

        val time = fields["BILL_TIME"]
        if (!time.isNullOrBlank() && !isValidClockTime(time)) return false
        return true
    }

    private fun isValidClockTime(raw: String): Boolean {
        val normalized = normalizeCaptured("BILL_TIME", raw)
        val parts = normalized.split(':')
        if (parts.size !in 2..3) return false
        val hour = parts[0].toIntOrNull() ?: return false
        val minute = parts[1].toIntOrNull() ?: return false
        val second = parts.getOrNull(2)?.toIntOrNull()
        if (hour !in 0..23 || minute !in 0..59) return false
        if (second != null && second !in 0..59) return false
        return true
    }

    private fun chooseWholeRecordConsensus(candidates: List<Candidate>): Candidate? {
        if (candidates.isEmpty()) return null
        val byTemplate = candidates.groupBy { it.template.templateId }
        val winningTemplate = byTemplate.values.maxWithOrNull(
            compareBy<List<Candidate>> { it.size }
                .thenBy { group -> group.maxOfOrNull { it.score } ?: 0 }
        ) ?: return null

        return winningTemplate
            .groupBy { candidate ->
                listOf(
                    candidate.fields["POS_NUMBER"].orEmpty(),
                    candidate.fields["CUSTOMER_VALUE"].orEmpty(),
                    candidate.fields["BILL_DATE"].orEmpty(),
                    candidate.fields["BILL_TIME"].orEmpty(),
                    candidate.fields["STORE_ID"].orEmpty()
                ).joinToString("|")
            }
            .values
            .maxWithOrNull(
                compareBy<List<Candidate>> { it.size }
                    .thenBy { group -> group.maxOfOrNull { it.score } ?: 0 }
            )
            ?.maxByOrNull { it.score }
    }
}
