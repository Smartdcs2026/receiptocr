package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.UniversalOcrTemplate
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Round87 POS evidence fusion + independent POS resolution
 *
 * ปัญหาจากภาพจริง: OCR หลาย pass อ่านส่วนต่าง ๆ ของบิลได้ดีไม่เหมือนกัน
 * การบังคับให้ pass เดียวอ่านครบทั้ง record ทำให้ Mb_02 ถูกทิ้งทั้งชุด
 *
 * ตัวนี้จึง:
 * - ใช้ลำดับต้นบิลถึง POS/ลูกค้าเป็น anchor ของ record
 * - เก็บหลักฐานแยกตาม POS + template + pass
 * - ค้นวันที่/เวลาเฉพาะในหน้าต่างข้อความใกล้ anchor เดิม ไม่ค้นทั้งภาพ
 * - โหวตค่าทีละ field จาก pass อิสระ แต่ห้ามข้าม POS
 * - รับเฉพาะวันที่/เวลาที่เป็นค่าจริง
 * - ถ้าหลักฐานไม่พอ จะไม่สร้างค่าขึ้นมา
 */
object PosEvidenceFusion {
    private const val DIGIT = "[0-9OoIl|SsZzBbGg]"
    private const val ALNUM = "[A-Za-z0-9OoIl|SsZzBbGg]"
    private const val FIELD_GAP = "\\s*"
    private const val ROW_GAP = "(?:\\s+\\S+){0,6}?\\s*"
    private const val LOCAL_AFTER_ANCHOR = 96

    private data class OrderedField(
        val rowIndex: Int,
        val field: OcrTemplateField
    )

    private data class CompiledPrefix(
        val regex: Regex,
        val captureTypes: List<String>,
        val depth: Int
    )

    private data class LocalCandidate(
        val passIndex: Int,
        val text: String
    )

    private data class Evidence(
        val passIndex: Int,
        val template: UniversalOcrTemplate,
        val pos: Int,
        val fields: Map<String, String>,
        val depth: Int,
        val score: Int
    )

    private data class ResolvedValue(
        val value: String,
        val support: Int,
        val score: Int
    )

    private data class ResolvedPosCandidate(
        val template: UniversalOcrTemplate,
        val values: Map<String, ResolvedValue>,
        val completePassSupport: Int,
        val weakestCoreSupport: Int,
        val score: Int
    )

    fun apply(
        rawTexts: List<String>,
        records: List<PosRecord>,
        work: WorkItem,
        imagePath: String,
        templates: List<UniversalOcrTemplate>
    ): UniversalTemplateResult {
        if (rawTexts.none { it.isNotBlank() } || templates.none { it.active }) {
            return failed(records)
        }

        val allowedPos = records.map { it.posNumber }.toSet()
        val candidates = buildLocalCandidates(rawTexts)
        if (candidates.isEmpty()) return failed(records)

        val evidence = templates
            .filter { it.active }
            .flatMap { template -> collectTemplateEvidence(template, candidates, allowedPos) }

        if (evidence.isEmpty()) return failed(records)

        val byPos = evidence.groupBy { it.pos }.toSortedMap()
        val updated = records.toMutableList()
        val detected = mutableListOf<Int>()
        val usedNames = linkedSetOf<String>()
        val extractedByPos = linkedMapOf<Int, Map<String, String>>()
        val warnings = linkedMapOf<Int, List<String>>()

        byPos.forEach { (pos, posEvidence) ->
            // Round87: ตัดสินทีละ POS อย่างอิสระ และลองทุก template ที่มีหลักฐาน
            // ห้ามเลือก template ที่มี noise เยอะกว่าแล้วทำให้ template ที่ครบจริงถูกทิ้ง
            val resolved = resolvePosCandidate(
                evidence = posEvidence,
                passCount = rawTexts.count { it.isNotBlank() }
            ) ?: return@forEach

            val template = resolved.template
            val customer = resolved.values["CUSTOMER_VALUE"]
            val date = resolved.values["BILL_DATE"]
            val time = resolved.values["BILL_TIME"]?.let { resolvedTime ->
                ReceiptTimeOcrNormalizer.normalize(resolvedTime.value).value?.let {
                    resolvedTime.copy(value = it)
                }
            }
            val store = resolved.values["STORE_ID"]

            val index = updated.indexOfFirst { it.posNumber == pos }
            if (index < 0) return@forEach
            val current = updated[index]

            val fields = linkedMapOf("POS_NUMBER" to pos.toString())
            customer?.let { fields["CUSTOMER_VALUE"] = it.value }
            date?.let { fields["BILL_DATE"] = it.value }
            time?.let { fields["BILL_TIME"] = it.value }
            store?.let { fields["STORE_ID"] = it.value }

            val warning = if (resolved.weakestCoreSupport <= 1 && rawTexts.count { it.isNotBlank() } >= 3) {
                "POS นี้มีข้อมูลครบจากอย่างน้อยหนึ่งรอบ แต่บางช่องมีหลักฐานยืนยันซ้ำไม่ถึง 2 รอบ กรุณาตรวจเทียบกับภาพก่อนส่ง"
            } else ""

            updated[index] = current.copy(
                customerNo = customer?.value ?: current.customerNo,
                billDate = date?.value ?: current.billDate,
                billTime = time?.value ?: current.billTime,
                noReceipt = false,
                noReceiptReason = "",
                source = "OCR-EVIDENCE",
                ocrSourceImagePath = imagePath,
                ocrTemplateName = template.templateName,
                ocrWarnings = warning,
                ocrStoreId = store?.value ?: current.ocrStoreId,
                ocrStoreIdExpected = templateHasStoreId(template),
                ocrCounterCycle = template.duplicatePolicy.customerCounterCycle.uppercase()
            )

            detected += pos
            usedNames += template.templateName
            extractedByPos[pos] = fields
            if (warning.isNotBlank()) warnings[pos] = listOf(warning)
        }

        if (detected.isEmpty()) return failed(records)

        val orderedPos = detected.distinct().sorted()
        val allTypes = extractedByPos.values.flatMap { it.keys }.toSet()
        val extracted = linkedMapOf<String, List<String>>()
        allTypes.forEach { type ->
            extracted[type] = orderedPos.map { pos -> extractedByPos[pos]?.get(type).orEmpty() }
        }

        return UniversalTemplateResult(
            records = updated,
            message = "อ่านข้อความจากหลายรอบและรวมข้อมูลตามเครื่องได้ • พบ ${orderedPos.size} เครื่อง",
            templateName = usedNames.joinToString(" / "),
            detectedPos = orderedPos,
            extracted = extracted,
            validationWarnings = warnings,
            usedUniversalTemplate = true
        )
    }

    /** Pure helper for unit tests. */
    internal fun fuseTextPasses(
        rawTexts: List<String>,
        template: UniversalOcrTemplate,
        allowedPos: Set<Int>
    ): Map<Int, Map<String, String>> {
        val candidates = buildLocalCandidates(rawTexts)
        val evidence = collectTemplateEvidence(template, candidates, allowedPos)
        val passCount = rawTexts.count { it.isNotBlank() }
        return evidence.groupBy { it.pos }.mapNotNull { (pos, all) ->
            val resolved = resolvePosCandidate(all, passCount) ?: return@mapNotNull null
            pos to buildMap<String, String> {
                put("POS_NUMBER", pos.toString())
                resolved.values["CUSTOMER_VALUE"]?.let { put("CUSTOMER_VALUE", it.value) }
                resolved.values["BILL_DATE"]?.let { put("BILL_DATE", it.value) }
                resolved.values["BILL_TIME"]?.let { put("BILL_TIME", it.value) }
                resolved.values["STORE_ID"]?.let { put("STORE_ID", it.value) }
            }
        }.toMap()
    }

    private fun collectTemplateEvidence(
        template: UniversalOcrTemplate,
        candidates: List<LocalCandidate>,
        allowedPos: Set<Int>
    ): List<Evidence> {
        val ordered = orderedFields(template)
        if (ordered.isEmpty()) return emptyList()
        val posIndex = ordered.indexOfFirst { containsPos(it.field) }
        if (posIndex < 0) return emptyList()

        val customerIndex = ordered.indexOfFirst { it.field.type.equals("CUSTOMER_VALUE", true) }
        val minimumDepth = maxOf(posIndex + 1, if (customerIndex in 0..posIndex + 2) customerIndex + 1 else posIndex + 1)
        val compiled = (minimumDepth..ordered.size).mapNotNull { depth ->
            compilePrefix(ordered, depth)
        }
        if (compiled.isEmpty()) return emptyList()

        // ใช้ prefix ถึง POS เป็นขอบเขตของ record ถัดไป เพื่อห้ามวันที่/เวลาไหลข้าม POS
        val recordBoundary = compilePrefix(ordered, posIndex + 1)

        val results = mutableListOf<Evidence>()
        candidates.forEach { candidate ->
            compiled.forEach { prefix ->
                prefix.regex.findAll(candidate.text).forEach { match ->
                    val fields = extract(prefix.captureTypes, match)
                    val pos = fields["POS_NUMBER"]?.let(OcrTextNormalizer::parsePosNumber)
                        ?: return@forEach
                    if (pos <= 0 || pos !in allowedPos) return@forEach

                    // ถ้า CUSTOMER อยู่ใน prefix ที่เราพยายามอ่านแล้ว แต่จับไม่ได้ ไม่รับ anchor นี้
                    if (customerIndex >= 0 && prefix.depth > customerIndex && fields["CUSTOMER_VALUE"].isNullOrBlank()) {
                        return@forEach
                    }

                    val enriched = fields.toMutableMap()
                    val anchorStart = match.range.first.coerceAtLeast(0)
                    val anchorEndExclusive = (match.range.last + 1).coerceAtMost(candidate.text.length)
                    val distanceEnd = (anchorEndExclusive + LOCAL_AFTER_ANCHOR).coerceAtMost(candidate.text.length)
                    val nextRecordStart = recordBoundary?.regex
                        ?.find(candidate.text, anchorEndExclusive)
                        ?.range
                        ?.first
                        ?.takeIf { it > anchorStart }
                    val localEnd = nextRecordStart?.coerceAtMost(distanceEnd) ?: distanceEnd
                    val localText = candidate.text.substring(anchorStart, localEnd)
                    enrichDateAndTime(template, ordered, enriched, localText)

                    val score = prefix.depth * 20 +
                        enriched.keys.count { it in setOf("CUSTOMER_VALUE", "BILL_DATE", "BILL_TIME", "STORE_ID") } * 30
                    results += Evidence(
                        passIndex = candidate.passIndex,
                        template = template,
                        pos = pos,
                        fields = enriched,
                        depth = prefix.depth,
                        score = score
                    )
                }
            }
        }

        // หน้าต่างซ้อนกันใน pass เดียวอาจสร้างหลักฐานซ้ำจำนวนมาก
        // เหลือเฉพาะชุดที่ลึก/ครบที่สุดต่อ pass + POS + ค่า core เดียวกัน
        return results.groupBy { evidence ->
            listOf(
                evidence.passIndex.toString(),
                evidence.template.templateId,
                evidence.pos.toString(),
                evidence.fields["CUSTOMER_VALUE"].orEmpty(),
                evidence.fields["BILL_DATE"].orEmpty(),
                evidence.fields["BILL_TIME"].orEmpty(),
                evidence.fields["STORE_ID"].orEmpty()
            ).joinToString("|")
        }.values.mapNotNull { group -> group.maxByOrNull { it.score } }
    }

    private fun resolvePosCandidate(
        evidence: List<Evidence>,
        passCount: Int
    ): ResolvedPosCandidate? {
        if (evidence.isEmpty()) return null
        return evidence.groupBy { it.template.templateId }
            .values
            .mapNotNull { group -> resolveTemplateCandidate(group, passCount) }
            .maxWithOrNull(
                compareBy<ResolvedPosCandidate> { it.completePassSupport }
                    .thenBy { it.weakestCoreSupport }
                    .thenBy { it.score }
            )
    }

    private fun resolveTemplateCandidate(
        group: List<Evidence>,
        passCount: Int
    ): ResolvedPosCandidate? {
        if (group.isEmpty()) return null
        val template = group.first().template
        val minimumSupport = if (passCount >= 3) 2 else 1

        val completeEvidence = group
            .filter { isEvidenceCoreComplete(it, template) }
            .maxByOrNull { it.score }

        fun resolveWithCompleteFallback(
            type: String,
            validator: (String) -> Boolean
        ): ResolvedValue? {
            val consensus = resolveField(group, type, minimumSupport, validator)
            if (consensus != null) return consensus
            val fallbackValue = completeEvidence?.fields?.get(type)
                ?.takeIf { it.isNotBlank() && validator(it) }
                ?: return null
            return ResolvedValue(
                value = fallbackValue,
                support = 1,
                score = completeEvidence.score
            )
        }

        val values = linkedMapOf<String, ResolvedValue>()
        resolveWithCompleteFallback("CUSTOMER_VALUE") { it.isNotBlank() && it.all(Char::isDigit) }
            ?.let { values["CUSTOMER_VALUE"] = it }
        resolveWithCompleteFallback("BILL_DATE", ::isValidDate)
            ?.let { values["BILL_DATE"] = it }
        resolveWithCompleteFallback("BILL_TIME", ::isValidTime)
            ?.let { values["BILL_TIME"] = it }

        // STORE_ID ไม่ใช่ core field แต่ถ้ามีให้เลือกจาก consensus ก่อน แล้วค่อยใช้ pass ที่ครบ
        val store = resolveField(group, "STORE_ID", minimumSupport) { it.isNotBlank() }
            ?: completeEvidence?.fields?.get("STORE_ID")
                ?.takeIf { it.isNotBlank() }
                ?.let { ResolvedValue(it, 1, completeEvidence.score) }
        store?.let { values["STORE_ID"] = it }

        val core = template.validation.requiredCore
        if (core.customerValue && values["CUSTOMER_VALUE"] == null) return null
        if (core.date && values["BILL_DATE"] == null) return null
        if (core.time && values["BILL_TIME"] == null) return null

        val completePassSupport = group
            .filter { isEvidenceCoreComplete(it, template) }
            .map { it.passIndex }
            .distinct()
            .size

        val coreSupports = buildList {
            if (core.customerValue) values["CUSTOMER_VALUE"]?.support?.let(::add)
            if (core.date) values["BILL_DATE"]?.support?.let(::add)
            if (core.time) values["BILL_TIME"]?.support?.let(::add)
        }
        val weakest = coreSupports.minOrNull() ?: 1
        val score = completePassSupport * 10000 +
            weakest * 1000 +
            values.values.sumOf { it.support * 100 + it.score.coerceAtMost(999) } +
            (group.maxOfOrNull { it.depth } ?: 0) * 10

        return ResolvedPosCandidate(
            template = template,
            values = values,
            completePassSupport = completePassSupport,
            weakestCoreSupport = weakest,
            score = score
        )
    }

    private fun isEvidenceCoreComplete(
        evidence: Evidence,
        template: UniversalOcrTemplate
    ): Boolean {
        val core = template.validation.requiredCore
        val customerOk = !core.customerValue || evidence.fields["CUSTOMER_VALUE"]
            ?.let { it.isNotBlank() && it.all(Char::isDigit) } == true
        val dateOk = !core.date || evidence.fields["BILL_DATE"]?.let(::isValidDate) == true
        val timeOk = !core.time || evidence.fields["BILL_TIME"]?.let(::isValidTime) == true
        return customerOk && dateOk && timeOk
    }

    private fun resolveField(
        evidence: List<Evidence>,
        type: String,
        minimumSupport: Int,
        validator: (String) -> Boolean
    ): ResolvedValue? {
        val perPassValue = evidence.mapNotNull { item ->
            val value = item.fields[type]?.takeIf { it.isNotBlank() && validator(it) } ?: return@mapNotNull null
            Triple(item.passIndex, value, item.score)
        }.groupBy { it.first to it.second }
            .mapValues { (_, values) -> values.maxOf { it.third } }

        if (perPassValue.isEmpty()) return null

        return perPassValue.entries
            .groupBy { it.key.second }
            .map { (value, rows) ->
                ResolvedValue(
                    value = value,
                    support = rows.map { it.key.first }.distinct().size,
                    score = rows.sumOf { it.value }
                )
            }
            .filter { it.support >= minimumSupport }
            .maxWithOrNull(
                compareBy<ResolvedValue> { it.support }
                    .thenBy { it.score }
            )
    }

    private fun enrichDateAndTime(
        template: UniversalOcrTemplate,
        ordered: List<OrderedField>,
        fields: MutableMap<String, String>,
        localText: String
    ) {
        val dateField = ordered.firstOrNull { it.field.type.equals("BILL_DATE", true) }?.field
        val timeField = ordered.firstOrNull { it.field.type.equals("BILL_TIME", true) }?.field
        val dateIndex = ordered.indexOfFirst { it.field.type.equals("BILL_DATE", true) }
        val timeIndex = ordered.indexOfFirst { it.field.type.equals("BILL_TIME", true) }

        var dateRange: IntRange? = null
        if (fields["BILL_DATE"].isNullOrBlank() && dateField != null) {
            findDate(localText, dateField)?.let { found ->
                fields["BILL_DATE"] = found.first
                dateRange = found.second
            }
        } else if (!fields["BILL_DATE"].isNullOrBlank()) {
            val value = fields["BILL_DATE"].orEmpty()
            val index = localText.indexOf(value)
            if (index >= 0) dateRange = index until (index + value.length)
        }

        if (fields["BILL_TIME"].isNullOrBlank() && timeField != null) {
            val searchText = if (dateIndex >= 0 && timeIndex > dateIndex && dateRange != null) {
                localText.substring((dateRange!!.last + 1).coerceAtMost(localText.length))
            } else localText
            findTime(searchText, timeField)?.let { fields["BILL_TIME"] = it }
        }
    }

    private fun findDate(text: String, field: OcrTemplateField): Pair<String, IntRange>? {
        val lengths = Regex("\\d+").findAll(field.example.orEmpty()).map { it.value.length }.toList()
            .takeIf { it.size == 3 } ?: listOf(2, 2, 4)
        val separated = Regex(
            "${fixedDigits(lengths[0])}\\s*[./-]\\s*${fixedDigits(lengths[1])}\\s*[./-]\\s*${fixedDigits(lengths[2])}",
            RegexOption.IGNORE_CASE
        )
        separated.findAll(text).forEach { match ->
            val value = normalizeDate(match.value)
            if (isValidDate(value)) return value to match.range
        }

        val total = lengths.sum()
        val compact = Regex("(?<![0-9OoIl|SsZzBbGg])${fixedDigits(total)}(?![0-9OoIl|SsZzBbGg])")
        compact.findAll(text).forEach { match ->
            val digits = normalizeDigits(match.value).filter(Char::isDigit)
            if (digits.length == total) {
                val value = listOf(
                    digits.substring(0, lengths[0]),
                    digits.substring(lengths[0], lengths[0] + lengths[1]),
                    digits.substring(lengths[0] + lengths[1])
                ).joinToString("/")
                if (isValidDate(value)) return value to match.range
            }
        }
        return null
    }

    private fun findTime(text: String, field: OcrTemplateField): String? {
        val groups = Regex("\\d+").findAll(field.example.orEmpty()).map { it.value.length }.toList()
        val hasSeconds = groups.size >= 3
        val separated = if (hasSeconds) {
            Regex("${fixedDigits(2)}\\s*[:.]\\s*${fixedDigits(2)}\\s*[:.]\\s*${fixedDigits(2)}")
        } else {
            Regex("${fixedDigits(2)}\\s*[:.]\\s*${fixedDigits(2)}")
        }
        separated.findAll(text).forEach { match ->
            val value = normalizeTime(match.value)
            if (isValidTime(value)) return value
        }

        // รองรับ ML Kit ทำเครื่องหมาย ':' หาย เช่น 1718 แต่ค้นเฉพาะหน้าต่างใกล้ record anchor
        val compactLength = if (hasSeconds) 6 else 4
        val compact = Regex("(?<![0-9OoIl|SsZzBbGg])${fixedDigits(compactLength)}(?![0-9OoIl|SsZzBbGg])")
        compact.findAll(text).forEach { match ->
            val digits = normalizeDigits(match.value).filter(Char::isDigit)
            val value = if (hasSeconds && digits.length == 6) {
                "${digits.substring(0, 2)}:${digits.substring(2, 4)}:${digits.substring(4, 6)}"
            } else if (!hasSeconds && digits.length == 4) {
                "${digits.substring(0, 2)}:${digits.substring(2, 4)}"
            } else return@forEach
            if (isValidTime(value)) return value
        }
        return null
    }

    private fun orderedFields(template: UniversalOcrTemplate): List<OrderedField> = template.recognition.rows
        .sortedBy { it.row }
        .flatMapIndexed { rowIndex, row ->
            row.fields.sortedBy { it.order }.map { OrderedField(rowIndex, it) }
        }

    private fun compilePrefix(ordered: List<OrderedField>, depth: Int): CompiledPrefix? {
        val selected = ordered.take(depth)
        if (selected.isEmpty()) return null
        val captures = mutableListOf<String>()
        val parts = mutableListOf<String>()
        var previousRow = -1

        selected.forEachIndexed { index, item ->
            if (index > 0) parts += if (item.rowIndex == previousRow) FIELD_GAP else ROW_GAP
            val pattern = fieldPattern(item.field, captures) ?: return null
            val tokenGap = if (item.field.tokenGap > 0) {
                "(?:\\s+\\S+){0,${item.field.tokenGap.coerceIn(0, 8)}}?\\s*"
            } else ""
            parts += when {
                item.field.type.equals("IGNORE", true) -> pattern
                item.field.required -> tokenGap + pattern
                else -> "(?:$tokenGap$pattern)?"
            }
            previousRow = item.rowIndex
        }

        return runCatching {
            CompiledPrefix(
                regex = Regex(parts.joinToString(""), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                captureTypes = captures,
                depth = depth
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
            "LITERAL" -> fuzzyLiteral(field.literal ?: sample)
            "SEPARATOR" -> fuzzyLiteral(field.separatorValue ?: sample.ifBlank { "-" })
            "IGNORE" -> ".{0,${field.maxLength.coerceIn(0, 40)}}?"
            // ถ้าเป็น composite ให้ parser Round84 เดิมรับช่วงต่อ ไม่ฝืนรวมหลักฐานแบบไม่รู้ขอบเขต
            "COMPOSITE_CODE" -> null
            else -> null
        }
    }

    private fun containsPos(field: OcrTemplateField): Boolean =
        field.type.equals("POS_NUMBER", true) ||
            field.composite?.segments.orEmpty().any { it.type.equals("POS_NUMBER", true) }

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
        return "${fixedDigits(lengths[0])}\\s*[./-]\\s*${fixedDigits(lengths[1])}\\s*[./-]\\s*${fixedDigits(lengths[2])}"
    }

    private fun timePattern(sample: String): String {
        val groups = Regex("\\d+").findAll(sample).map { it.value.length }.toList()
        val second = groups.getOrNull(2)
        return buildString {
            append(fixedDigits(groups.getOrNull(0)?.coerceIn(1, 2) ?: 2))
            append("\\s*[:.]\\s*")
            append(fixedDigits(2))
            if (second != null) {
                append("\\s*[:.]\\s*")
                append(fixedDigits(2))
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

    private fun extract(captureTypes: List<String>, result: MatchResult): Map<String, String> {
        val values = linkedMapOf<String, String>()
        captureTypes.forEachIndexed { index, type ->
            val raw = result.groupValues.getOrNull(index + 1).orEmpty().trim()
            if (raw.isBlank() || values.containsKey(type)) return@forEachIndexed
            values[type] = normalizeCaptured(type, raw)
        }
        return values
    }

    private fun normalizeCaptured(type: String, raw: String): String {
        val compact = raw.replace(Regex("\\s+"), "")
        return when (type) {
            "POS_NUMBER" -> OcrTextNormalizer.normalizeDigits(compact).filter(Char::isDigit)
            "CUSTOMER_VALUE", "STORE_ID", "YEAR_VALUE", "MONTH_VALUE", "DAY_VALUE" ->
                normalizeDigits(compact).filter(Char::isDigit)
            "BILL_DATE" -> normalizeDate(compact)
            "BILL_TIME" -> normalizeTime(compact)
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

    private fun normalizeDate(value: String): String = normalizeDigits(value)
        .replace('.', '/')
        .replace('-', '/')
        .replace(Regex("\\s+"), "")

    private fun normalizeTime(value: String): String = normalizeDigits(value)
        .replace('.', ':')
        .replace(Regex("\\s+"), "")

    private fun isValidDate(value: String): Boolean {
        val parts = value.split('/')
        if (parts.size != 3) return false
        val day = parts[0].toIntOrNull() ?: return false
        val month = parts[1].toIntOrNull() ?: return false
        val rawYear = parts[2].toIntOrNull() ?: return false
        val year = if (parts[2].length <= 2) 2000 + rawYear else rawYear
        return try {
            LocalDate.of(year, month, day)
            true
        } catch (_: DateTimeException) {
            false
        }
    }

    private fun isValidTime(value: String): Boolean {
        val parts = value.split(':')
        if (parts.size !in 2..3) return false
        val hour = parts[0].toIntOrNull() ?: return false
        val minute = parts[1].toIntOrNull() ?: return false
        val second = parts.getOrNull(2)?.toIntOrNull()
        if (hour !in 0..23 || minute !in 0..59) return false
        if (second != null && second !in 0..59) return false
        return true
    }

    private fun templateHasStoreId(template: UniversalOcrTemplate): Boolean = template.recognition.rows.any { row ->
        row.fields.any { field ->
            field.type.equals("STORE_ID", true) ||
                field.composite?.segments.orEmpty().any { it.type.equals("STORE_ID", true) }
        }
    }

    private fun buildLocalCandidates(rawTexts: List<String>): List<LocalCandidate> = buildList {
        rawTexts.forEachIndexed { passIndex, raw ->
            val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
            lines.forEachIndexed { index, line ->
                candidateVariants(line).forEach { add(LocalCandidate(passIndex, it)) }
                if (index + 1 < lines.size) {
                    candidateVariants(lines.subList(index, index + 2).joinToString(" ")).forEach {
                        add(LocalCandidate(passIndex, it))
                    }
                }
                if (index + 2 < lines.size) {
                    candidateVariants(lines.subList(index, index + 3).joinToString(" ")).forEach {
                        add(LocalCandidate(passIndex, it))
                    }
                }
            }
        }
    }.distinct()

    private fun candidateVariants(raw: String): List<String> {
        val normalized = OcrTextNormalizer.normalizeLine(raw)
        if (normalized.isBlank()) return emptyList()
        val compact = normalized.replace(
            Regex("(?<=[A-Za-z0-9OoIl|SsZzBbGg])\\s+(?=[A-Za-z0-9OoIl|SsZzBbGg])"),
            ""
        )
        return listOf(normalized, compact).distinct()
    }

    private fun failed(records: List<PosRecord>) = UniversalTemplateResult(
        records = records,
        message = "อ่านข้อความได้ แต่หลักฐานจากหลายรอบยังไม่พอแยกข้อมูลอย่างปลอดภัย",
        usedUniversalTemplate = true
    )
}
