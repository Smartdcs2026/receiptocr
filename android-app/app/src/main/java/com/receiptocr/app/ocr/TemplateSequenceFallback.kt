package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.UniversalOcrTemplate
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem

/**
 * ทางสำรองเมื่ออ่านข้อความได้ แต่การเทียบทั้งแถวแบบปกติไม่ผ่าน
 * ยังคงใช้ลำดับช่องและจำนวนตัวจากรูปแบบที่ผู้ดูแลกำหนดเท่านั้น
 * ไม่ผูกกับชื่อแบรนด์และไม่สร้างค่าขึ้นเอง
 */
object TemplateSequenceFallback {
    private const val DIGIT = "[0-9OoIl|SsZzBbGg]"
    private const val BETWEEN_FIELDS = "[\\s|,;:_#.\\-]*"

    private data class Compiled(
        val regex: Regex,
        val captureTypes: List<String>
    )

    private data class Match(
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
        val candidates = rawTexts
            .filter { it.isNotBlank() }
            .flatMap { raw ->
                val lines = raw.lineSequence()
                    .map(OcrTextNormalizer::normalizeLine)
                    .filter { it.isNotBlank() }
                    .toList()
                buildList {
                    if (lines.isNotEmpty()) add(lines.joinToString(" "))
                    add(OcrTextNormalizer.normalizeLine(raw.replace('\n', ' ')))
                }
            }
            .distinct()

        if (candidates.isEmpty()) {
            return UniversalTemplateResult(records, "อ่านข้อความได้ แต่ยังแยกข้อมูลตามรูปแบบบิลนี้ไม่ได้", usedUniversalTemplate = true)
        }

        val matches = templates.asSequence()
            .filter { it.active && it.recognition.rows.size == 1 }
            .flatMap { template ->
                val compiled = compile(template.recognition.rows.first().fields) ?: return@flatMap emptySequence()
                candidates.asSequence().flatMap { text ->
                    compiled.regex.findAll(text).mapNotNull { result ->
                        val fields = extract(compiled, result)
                        val pos = fields["POS_NUMBER"]?.let(OcrTextNormalizer::parsePosNumber)
                        val customer = fields["CUSTOMER_VALUE"].orEmpty()
                        val date = fields["BILL_DATE"].orEmpty()
                        val time = fields["BILL_TIME"].orEmpty()
                        if (pos == null || customer.isBlank() || date.isBlank() || time.isBlank()) null
                        else Match(
                            template = template,
                            fields = fields,
                            score = template.priority + fields.size * 10
                        )
                    }
                }
            }
            .toList()

        if (matches.isEmpty()) {
            return UniversalTemplateResult(records, "อ่านข้อความได้ แต่ยังแยกข้อมูลตามรูปแบบบิลนี้ไม่ได้", usedUniversalTemplate = true)
        }

        val bestByPos = matches
            .mapNotNull { match ->
                OcrTextNormalizer.parsePosNumber(match.fields["POS_NUMBER"].orEmpty())?.let { it to match }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.maxBy { it.score } }
            .toSortedMap()

        val updated = records.toMutableList()
        val extracted = mutableMapOf<String, MutableList<String>>()
        val warnings = mutableMapOf<Int, List<String>>()
        val usedTemplates = linkedSetOf<String>()
        val assigned = linkedSetOf<Int>()

        bestByPos.forEach { (pos, match) ->
            var index = updated.indexOfFirst { it.posNumber == pos }
            if (index < 0) {
                index = updated.indexOfFirst { record ->
                    record.posNumber !in assigned &&
                        record.customerNo.isBlank() && record.billDate.isBlank() && record.billTime.isBlank() &&
                        !record.noReceipt
                }
                if (index < 0) return@forEach
                updated[index] = updated[index].copy(posNumber = pos)
            }
            assigned += pos

            val current = updated[index]
            val customer = match.fields["CUSTOMER_VALUE"].orEmpty().filter(Char::isDigit)
            val date = match.fields["BILL_DATE"].orEmpty().replace('.', '/').replace('-', '/')
            val time = match.fields["BILL_TIME"].orEmpty().replace('.', ':')

            updated[index] = current.copy(
                customerNo = customer.ifBlank { current.customerNo },
                billDate = date.ifBlank { current.billDate },
                billTime = time.ifBlank { current.billTime },
                noReceipt = false,
                noReceiptReason = "",
                source = "OCR-TEMPLATE",
                ocrSourceImagePath = imagePath,
                ocrWarnings = "พบตัวอักษรบางจุดไม่ตรงกับตัวอย่าง แต่แยกข้อมูลสำคัญได้ • กรุณาตรวจเทียบกับภาพ",
                ocrCounterCycle = match.template.duplicatePolicy.customerCounterCycle.uppercase()
            )

            match.fields.forEach { (type, value) ->
                extracted.getOrPut(type) { mutableListOf() }.add(value)
            }
            warnings[pos] = listOf("พบตัวอักษรบางจุดไม่ตรงกับตัวอย่าง • กรุณาตรวจเทียบกับภาพ")
            usedTemplates += match.template.templateName
        }

        val detected = assigned.sorted()
        if (detected.isEmpty()) {
            return UniversalTemplateResult(records, "อ่านข้อความได้ แต่ยังแยกข้อมูลตามรูปแบบบิลนี้ไม่ได้", usedUniversalTemplate = true)
        }

        return UniversalTemplateResult(
            records = updated,
            message = "อ่านข้อมูลจากภาพได้ • พบ ${detected.size} เครื่อง • กรุณาตรวจเทียบกับภาพ",
            templateName = usedTemplates.joinToString(" / "),
            detectedPos = detected,
            extracted = extracted,
            validationWarnings = warnings,
            usedUniversalTemplate = true
        )
    }

    /** ใช้ทดสอบลำดับช่องจากข้อความตัวอย่างโดยไม่ต้องสร้างภาพ */
    internal fun parseText(text: String, template: UniversalOcrTemplate): List<Map<String, String>> {
        val row = template.recognition.rows.singleOrNull() ?: return emptyList()
        val compiled = compile(row.fields) ?: return emptyList()
        val normalized = OcrTextNormalizer.normalizeLine(text.replace('\n', ' '))
        return compiled.regex.findAll(normalized).map { extract(compiled, it) }.toList()
    }

    private fun compile(fields: List<OcrTemplateField>): Compiled? {
        if (fields.isEmpty()) return null
        val captureTypes = mutableListOf<String>()
        val parts = mutableListOf<String>()

        fields.sortedBy { it.order }.forEach { field ->
            val pattern = fieldPattern(field, captureTypes) ?: return null
            val withGap = if (field.tokenGap > 0) {
                "(?:\\s+\\S+){0,${field.tokenGap.coerceIn(0, 8)}}?\\s*$pattern"
            } else pattern
            parts += if (field.required) withGap else "(?:$withGap)?"
        }

        return runCatching {
            Compiled(
                regex = Regex(parts.joinToString(BETWEEN_FIELDS), RegexOption.IGNORE_CASE),
                captureTypes = captureTypes
            )
        }.getOrNull()
    }

    private fun fieldPattern(field: OcrTemplateField, captures: MutableList<String>): String? {
        fun capture(type: String, inner: String): String {
            captures += type
            return "($inner)"
        }

        val sample = field.example?.trim().orEmpty()
        val sampleDigits = sample.count(Char::isDigit)
        val exactSampleLength = sample.length.takeIf { sample.isNotBlank() }
        val min = field.minLength.coerceAtLeast(1)
        val max = field.maxLength.coerceAtLeast(min)

        return when (field.type.uppercase()) {
            "BILL_DATE" -> capture("BILL_DATE", datePattern(sample))
            "BILL_TIME" -> capture("BILL_TIME", timePattern())
            "CUSTOMER_VALUE" -> {
                val length = sampleDigits.takeIf { it > 0 }
                    ?: min.takeIf { min == max }
                val inner = length?.let(::fixedDigits) ?: "$DIGIT{$min,$max}"
                capture("CUSTOMER_VALUE", inner)
            }
            "POS_NUMBER" -> {
                val prefix = sample.takeWhile(Char::isLetter)
                val digits = sample.takeLastWhile(Char::isDigit).length.takeIf { it > 0 }
                    ?: field.posDigits
                    ?: min.takeIf { min == max }
                    ?: 1
                val prefixPattern = when {
                    field.posPrefixes.orEmpty().isNotBlank() -> field.posPrefixes.orEmpty()
                        .split(',').map { it.trim() }.filter { it.isNotBlank() }
                        .joinToString("|", "(?:", ")") { Regex.escape(it) }
                    prefix.isNotBlank() -> fuzzyLiteral(prefix)
                    else -> "[A-Za-z]?"
                }
                capture("POS_NUMBER", "$prefixPattern${fixedDigits(digits)}")
            }
            "STORE_ID" -> {
                val length = sampleDigits.takeIf { it > 0 }
                    ?: exactSampleLength
                    ?: min.takeIf { min == max }
                    ?: min
                capture("STORE_ID", fixedDigits(length.coerceAtLeast(1)))
            }
            "YEAR_VALUE", "YEAR" -> capture("YEAR_VALUE", fixedDigits(sampleDigits.takeIf { it > 0 } ?: 2))
            "MONTH_VALUE", "MONTH" -> capture("MONTH_VALUE", fixedDigits(sampleDigits.takeIf { it > 0 } ?: 2))
            "DAY_VALUE", "DAY" -> capture("DAY_VALUE", fixedDigits(sampleDigits.takeIf { it > 0 } ?: 2))
            "EMPLOYEE_CODE" -> {
                val length = exactSampleLength ?: min.takeIf { min == max }
                if (length != null) "[A-Za-z0-9](?:\\s*[A-Za-z0-9]){${(length - 1).coerceAtLeast(0)}}"
                else "[A-Za-z0-9]{$min,$max}"
            }
            "NUMBER_TEXT" -> {
                val length = sampleDigits.takeIf { it > 0 }
                    ?: exactSampleLength
                    ?: min.takeIf { min == max }
                    ?: min
                fixedDigits(length.coerceAtLeast(1))
            }
            "ALNUM_TEXT" -> {
                val length = exactSampleLength ?: min.takeIf { min == max }
                if (length != null) "[A-Za-z0-9](?:\\s*[A-Za-z0-9]){${(length - 1).coerceAtLeast(0)}}"
                else "[A-Za-z0-9]{$min,$max}"
            }
            "LITERAL" -> softLiteral(field.literal ?: sample)
            "SEPARATOR" -> fuzzyLiteral(field.separatorValue ?: sample.ifBlank { "-" })
            "IGNORE" -> ".{0,40}?"
            // รูปแบบประกอบมีตัวอ่านหลักรองรับอยู่แล้ว จึงไม่ใช้ทางสำรองนี้เพื่อป้องกันการตีความผิด
            "COMPOSITE_CODE" -> null
            else -> null
        }
    }

    private fun fixedDigits(length: Int): String {
        val size = length.coerceAtLeast(1)
        return if (size == 1) DIGIT else "$DIGIT(?:\\s*$DIGIT){${size - 1}}"
    }

    private fun datePattern(sample: String): String {
        val sampleGroups = Regex("\\d+").findAll(sample).map { it.value.length }.toList()
        if (sampleGroups.size == 3) {
            return "${fixedDigits(sampleGroups[0])}\\s*[./-]\\s*" +
                "${fixedDigits(sampleGroups[1])}\\s*[./-]\\s*" +
                fixedDigits(sampleGroups[2])
        }
        return "$DIGIT(?:\\s*$DIGIT)?\\s*[./-]\\s*$DIGIT(?:\\s*$DIGIT)?\\s*[./-]\\s*$DIGIT(?:\\s*$DIGIT){1,3}?"
    }

    private fun timePattern(): String =
        "$DIGIT(?:\\s*$DIGIT)?\\s*[:.]\\s*$DIGIT\\s*$DIGIT(?:\\s*:\\s*$DIGIT\\s*$DIGIT)?"

    /**
     * ข้อความคงที่สั้นหนึ่งตัวใช้เป็นเพียงตัวแบ่งช่องในทางสำรอง
     * จึงยอมให้ตัวนั้นคลาดหรือหายได้ แต่ค่าหลักรอบข้างยังต้องตรงจำนวนตัวตาม Admin
     */
    private fun softLiteral(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        if (value.length == 1) return "(?:${fuzzyLiteral(value)}|[A-Za-z0-9])?"
        return fuzzyLiteral(value)
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
                val prefix = compact.takeWhile { it.isLetter() && it.uppercaseChar() !in setOf('O', 'I', 'S', 'Z', 'B', 'G') }
                prefix + normalizeFallbackDigits(compact.drop(prefix.length))
            }
            "CUSTOMER_VALUE", "STORE_ID", "YEAR_VALUE", "MONTH_VALUE", "DAY_VALUE" -> normalizeFallbackDigits(compact)
            "BILL_DATE" -> normalizeFallbackDigits(compact).replace('.', '/').replace('-', '/')
            "BILL_TIME" -> normalizeFallbackDigits(compact).replace('.', ':')
            else -> compact
        }
    }

    private fun normalizeFallbackDigits(value: String): String = value.map { character ->
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
}
