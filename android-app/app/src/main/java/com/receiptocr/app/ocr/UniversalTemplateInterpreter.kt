package com.receiptocr.app.ocr

import com.google.mlkit.vision.text.Text
import com.receiptocr.app.config.*
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private data class LineText(val index: Int, val text: String)

private data class CandidateDocument(
    val lines: List<LineText>,
    val scoreBonus: Int = 0
)

private data class CapturedField(val type: String, val value: String)

private data class CompiledRow(
    val regex: Regex,
    val captureTypes: List<String>
)

private data class TemplateMatch(
    val template: UniversalOcrTemplate,
    val fields: Map<String, String>,
    val score: Int,
    val sourceLines: List<String>,
    val recognitionWarnings: List<String> = emptyList()
)

data class UniversalTemplateResult(
    val records: List<PosRecord>,
    val message: String,
    val templateName: String? = null,
    val detectedPos: List<Int> = emptyList(),
    val extracted: Map<String, List<String>> = emptyMap(),
    val validationWarnings: Map<Int, List<String>> = emptyMap(),
    val usedUniversalTemplate: Boolean = false
)

/**
 * UniversalTemplateInterpreter
 *
 * อ่าน Template ที่ Admin สร้างแบบ 1–3 แถว แล้วใช้กับข้อความจริงจาก ML Kit
 * โดยไม่ hard-code รูปแบบเฉพาะแบรนด์
 *
 * รอบนี้เน้น:
 * - SAME RECORD = 1–3 แถว
 * - หลายชุดข้อมูลและหลายแม่แบบในภาพเดียว
 * - ปี/เดือน/ร้าน/POS ตามรูปแบบ และอ่านยอดลูกค้าเต็มก่อนตรวจจำนวนหลัก
 * - POS N02/B01 -> POS 2/1
 * - Store ต้องตรง Work Plan
 * - ปี พ.ศ. / ค.ศ. ตรวจเทียบได้
 */
object UniversalTemplateInterpreter {
    private val outDate = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private const val OCR_DIGIT = "[0-9OoIl|]"

    fun apply(
        mlTexts: List<Text>,
        imageWidth: Int,
        imageHeight: Int,
        records: List<PosRecord>,
        work: WorkItem,
        workDate: LocalDate,
        imagePath: String,
        templates: List<UniversalOcrTemplate>
    ): UniversalTemplateResult {
        if (templates.isEmpty()) {
            return UniversalTemplateResult(records, "ยังไม่มีเงื่อนไขสำหรับแบรนด์นี้ กรุณาแจ้งผู้ดูแล", usedUniversalTemplate = false)
        }

        if (mlTexts.all { it.text.isBlank() }) {
            return UniversalTemplateResult(records, "ไม่พบข้อความในภาพ กรุณาถ่ายใหม่ให้ชัดเจน", usedUniversalTemplate = true)
        }

        val allMatches = templates.filter { it.active }.sortedByDescending { it.priority }.flatMap { template ->
            buildDocuments(template, mlTexts, imageWidth, imageHeight).flatMap { document ->
                findMatches(template, document.lines, work, workDate).map { match ->
                    match.copy(score = match.score + document.scoreBonus)
                }
            }
        }
            .sortedByDescending { it.score }

        if (allMatches.isEmpty()) {
            return UniversalTemplateResult(
                records = records,
                message = "อ่านข้อความได้ แต่ยังแยกข้อมูลตามรูปแบบบิลนี้ไม่ได้",
                usedUniversalTemplate = true
            )
        }

        // ไม่ผูกทั้งภาพไว้กับแม่แบบเดียว เพราะหนึ่งแบรนด์มีหลายรูปแบบได้
        // เรียงคะแนนไว้แล้ว จึงเลือกผลที่ดีที่สุดแยกให้แต่ละ POS จากทุกแม่แบบที่เปิดใช้
        val templateMatches = allMatches

        val updated = records.toMutableList()
        val extracted = mutableMapOf<String, MutableList<String>>()
        val accepted = mutableListOf<TemplateMatch>()
        val warningsByPos = mutableMapOf<Int, MutableList<String>>()
        val unmappedPos = linkedSetOf<String>()
        var acceptedStore: String? = null

        val bestMatches = templateMatches
            .mapNotNull { match ->
                val pos = match.fields["POS_NUMBER"]?.let(::parsePosNumber) ?: return@mapNotNull null
                pos to match
            }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (pos, candidates) -> fuseMatches(candidates)?.let { pos to it } }
            .sortedBy { it.first }

        val assignedPositions = linkedSetOf<Int>()
        bestMatches.forEach { (pos, match) ->
            var index = updated.indexOfFirst { it.posNumber == pos }
            if (index < 0) {
                index = updated.indexOfFirst { record ->
                    record.posNumber !in assignedPositions &&
                        record.customerNo.isBlank() && record.billDate.isBlank() && record.billTime.isBlank() &&
                        !record.noReceipt && record.ocrSourceImagePath.isBlank()
                }
                if (index >= 0) {
                    updated[index] = updated[index].copy(posNumber = pos)
                } else {
                    unmappedPos += pos.toString()
                    return@forEach
                }
            }
            assignedPositions += pos
            val posWarnings = warningsByPos.getOrPut(pos) { mutableListOf() }
            posWarnings += match.recognitionWarnings

            val store = match.fields["STORE_ID"]
            if (match.template.validation.store.mustMatchWorkPlan && !store.isNullOrBlank()) {
                if (!sameStore(store, work.expectedReceiptStoreId)) {
                    posWarnings += "รหัสร้านที่อ่านได้ ($store) ไม่ตรงกับแผนงาน (${work.expectedReceiptStoreId})"
                }
            }
            if (match.template.validation.store.sameStoreAcrossAllMatches && !store.isNullOrBlank()) {
                val prior = acceptedStore
                if (prior != null && !sameStore(prior, store)) {
                    posWarnings += "รหัสร้านในภาพไม่ตรงกันทุกชุด"
                } else if (prior == null) {
                    acceptedStore = store
                }
            }

            val dateRaw = match.fields["BILL_DATE"]
            val dateField = match.template.recognition.rows.asSequence()
                .flatMap { it.fields.asSequence() }
                .firstOrNull { it.type == "BILL_DATE" }
            val dateResult = dateRaw?.let {
                ReceiptDateOcrNormalizer.normalize(
                    raw = it,
                    configuredFormat = dateField?.format,
                    referenceDate = workDate,
                    dateOrder = dateField?.dateOrder,
                    dateCalendar = dateField?.dateCalendar,
                    dateYearDigits = dateField?.dateYearDigits ?: 0
                )
            }
            val normalizedDate = dateResult?.value
            val time = match.fields["BILL_TIME"]?.let(ReceiptTimeOcrNormalizer::normalize)?.value
            val customer = match.fields["CUSTOMER_VALUE"]?.filter(Char::isDigit)

            val core = match.template.validation.requiredCore
            if (core.date && dateRaw.isNullOrBlank()) posWarnings += "ไม่พบวันที่ตามเงื่อนไขที่กำหนด"
            if (core.time && time.isNullOrBlank()) posWarnings += "ไม่พบเวลาตามเงื่อนไขที่กำหนด"
            if (core.customerValue && customer.isNullOrBlank()) posWarnings += "ไม่พบยอด/เลขลูกค้าตามเงื่อนไขที่กำหนด"
            val customerLength = expectedLength(match.template, "CUSTOMER_VALUE")
            if (!customer.isNullOrBlank() && customerLength != null && customer.length !in customerLength) {
                posWarnings += "ยอด/เลขลูกค้ามี ${customer.length} หลัก แต่กำหนดไว้ ${customerLength.first}-${customerLength.last} หลัก"
            }
            if (dateRaw != null && normalizedDate == null) {
                posWarnings += dateResult?.warning ?: "วันที่ที่อ่านได้ไม่ตรงเงื่อนไขที่กำหนด ($dateRaw)"
            }
            posWarnings += comparisonWarnings(match.template, match.fields, normalizedDate, workDate)

            if (dateRaw.isNullOrBlank() && time.isNullOrBlank() && customer.isNullOrBlank()) return@forEach

            val current = updated[index]
            updated[index] = current.copy(
                customerNo = customer ?: current.customerNo,
                billDate = normalizedDate ?: dateRaw?.trim().orEmpty().ifBlank { current.billDate },
                billTime = time ?: current.billTime,
                noReceipt = false,
                noReceiptReason = "",
                source = "OCR-TEMPLATE",
                ocrSourceImagePath = imagePath,
                ocrTemplateName = match.template.templateName,
                ocrWarnings = posWarnings.distinct().joinToString(" • "),
                ocrCounterCycle = match.template.duplicatePolicy.customerCounterCycle.uppercase()
            )
            accepted += match
            match.fields.forEach { (k, v) -> extracted.getOrPut(k) { mutableListOf() }.add(v) }
        }

        if (accepted.isEmpty()) {
            val posText = templateMatches.mapNotNull { it.fields["POS_NUMBER"] }.distinct()
            return UniversalTemplateResult(
                records = records,
                message = if (posText.isEmpty()) {
                    "พบข้อมูลในภาพ แต่ยังอ่านหมายเลขเครื่องไม่ได้"
                } else {
                    "อ่านหมายเลขเครื่องได้ ${posText.joinToString(", ")} แต่ไม่มีช่องที่ตรงกับแผนงาน"
                },
                templateName = templateMatches.first().template.templateName,
                extracted = templateMatches.first().fields.mapValues { listOf(it.value) },
                validationWarnings = warningsByPos,
                usedUniversalTemplate = true
            )
        }

        val acceptedTemplateNames = accepted.map { it.template.templateName }.distinct()
        val posList = accepted.mapNotNull { parsePosNumber(it.fields["POS_NUMBER"].orEmpty()) }.distinct().sorted()
        val message = buildString {
            append("อ่านข้อมูลจากภาพสำเร็จ")
            append(" • พบ ${posList.size} เครื่อง")
            if (posList.isNotEmpty()) append(" • เครื่อง ${posList.joinToString(", ")}")
            if (unmappedPos.isNotEmpty()) append(" • พบเครื่องนอกแผน ${unmappedPos.joinToString(", ")}")
            val warningCount = warningsByPos.values.sumOf { it.size }
            if (warningCount > 0) append(" • มี $warningCount จุดที่ต้องตรวจสอบ")
            append(" • กรุณาตรวจเทียบกับภาพก่อนส่ง")
        }

        return UniversalTemplateResult(
            records = updated,
            message = message,
            templateName = acceptedTemplateNames.joinToString(" / "),
            detectedPos = posList,
            extracted = extracted,
            validationWarnings = warningsByPos,
            usedUniversalTemplate = true
        )
    }

    private fun normalizeLine(value: String): String = OcrTextNormalizer.normalizeLine(value)

    /**
     * ใช้พื้นที่ที่ Admin กำหนดเป็นลำดับแรก แต่ยังคงอ่านทั้งภาพเมื่อเลือก
     * OPTIONAL_REGION_WITH_WHOLE_IMAGE_FALLBACK เพื่อให้ภาพที่มีหลาย POS
     * ไม่สูญเสียรายการซึ่งอยู่นอกพื้นที่ตัวอย่างใบแรก
     */
    private fun buildDocuments(
        template: UniversalOcrTemplate,
        mlTexts: List<Text>,
        imageWidth: Int,
        imageHeight: Int
    ): List<CandidateDocument> {
        val deskew = template.recognition.deskewEnabled
        val layouts = mlTexts.map { SpatialTextLayout.rebuild(text = it, deskew = deskew) }
        val wholeDocuments = buildList {
            layouts.forEach { layout ->
                val lines = layout.lines.map { LineText(it.index, normalizeLine(it.text)) }
                    .filter { it.text.isNotBlank() }
                if (lines.isNotEmpty()) add(CandidateDocument(lines))
            }
            mlTexts.forEach { text ->
                val lines = text.text.lineSequence()
                    .map(::normalizeLine)
                    .filter { it.isNotBlank() }
                    .mapIndexed { index, value -> LineText(index, value) }
                    .toList()
                if (lines.isNotEmpty()) add(CandidateDocument(lines))
            }
        }.distinctBy { document -> document.lines.joinToString("\n") { it.text } }

        val scope = template.recognition.searchScope.uppercase()
        val region = template.recognition.region
        if (region == null || scope == "WHOLE_IMAGE" || imageWidth <= 0 || imageHeight <= 0) {
            return wholeDocuments
        }

        // พิกัด region อ้างอิงภาพเต็ม จึงใช้ Text รอบแรกซึ่งเป็นภาพเต็มเท่านั้น
        val regionLines = layouts.firstOrNull()?.lines.orEmpty().filter { line ->
            val centerX = ((line.left + line.right) / 2f) / imageWidth.toFloat()
            val centerY = ((line.top + line.bottom) / 2f) / imageHeight.toFloat()
            centerX in minOf(region.left, region.right)..maxOf(region.left, region.right) &&
                centerY in minOf(region.top, region.bottom)..maxOf(region.top, region.bottom)
        }.map { LineText(it.index, normalizeLine(it.text)) }.filter { it.text.isNotBlank() }

        val preferred = if (regionLines.isEmpty()) emptyList() else listOf(CandidateDocument(regionLines, scoreBonus = 30))
        return when (scope) {
            "REGION_ONLY" -> preferred
            else -> (preferred + wholeDocuments).distinctBy { document ->
                "${document.scoreBonus}|" + document.lines.joinToString("\n") { it.text }
            }
        }
    }

    /** รวมช่องที่อ่านได้จากภาพเต็มและภาพย่อย แทนการทิ้งทุกผลยกเว้นคะแนนสูงสุด */
    private fun fuseMatches(candidates: List<TemplateMatch>): TemplateMatch? {
        val ranked = candidates.sortedWith(
            compareByDescending<TemplateMatch> { candidate ->
                listOf("POS_NUMBER", "STORE_ID", "BILL_DATE", "BILL_TIME", "CUSTOMER_VALUE")
                    .count { !candidate.fields[it].isNullOrBlank() }
            }.thenByDescending { it.score }
        )
        val base = ranked.firstOrNull() ?: return null
        val merged = linkedMapOf<String, String>().apply { putAll(base.fields) }
        val sourceLines = base.sourceLines.toMutableList()
        val warnings = base.recognitionWarnings.toMutableList()

        ranked.drop(1).forEach { candidate ->
            val currentStore = merged["STORE_ID"]
            val otherStore = candidate.fields["STORE_ID"]
            if (!currentStore.isNullOrBlank() && !otherStore.isNullOrBlank() && !sameStore(currentStore, otherStore)) {
                return@forEach
            }
            candidate.fields.forEach { (type, value) ->
                if (merged[type].isNullOrBlank() && value.isNotBlank()) merged[type] = value
            }
            sourceLines += candidate.sourceLines.filterNot { it in sourceLines }
            warnings += candidate.recognitionWarnings
        }

        return base.copy(
            fields = merged,
            score = ranked.maxOf { it.score } + (merged.size - base.fields.size).coerceAtLeast(0) * 5,
            sourceLines = sourceLines,
            recognitionWarnings = warnings.distinct()
        )
    }

    private fun findMatches(
        template: UniversalOcrTemplate,
        lines: List<LineText>,
        work: WorkItem,
        workDate: LocalDate
    ): List<TemplateMatch> {
        val rows = template.recognition.rows.sortedBy { it.row }
        if (rows.isEmpty()) return emptyList()
        val compiled = rows.map { compileRow(it, work) }
        if (compiled.any { it == null }) return emptyList()
        val safeCompiled = compiled.filterNotNull()

        return when (rows.size) {
            1 -> (findSingleRowMatches(template, safeCompiled.first(), lines, work, workDate) +
                findCompositeAnchorMatches(template, rows.first(), safeCompiled.first(), lines, work, workDate))
                .distinctBy { it.template.templateId + "|" + it.fields.entries.joinToString("|") + "|" + it.recognitionWarnings.joinToString("|") }
            else -> findMultiRowMatches(template, safeCompiled, lines, work, workDate)
        }
    }

    /**
     * กรณีหัวรหัส เช่น BNO: ถูกบังหรืออ่านเพี้ยน แต่เนื้อรหัสประกอบยังอยู่ครบ
     * ให้ดึงค่าที่อ่านได้ไว้ก่อน แล้วส่งคำเตือนไปที่ช่องข้อมูลแทนการทิ้งทั้ง POS
     * ใช้โครงสร้าง COMPOSITE_CODE จาก Admin เท่านั้น จึงไม่ผูกกับชื่อแบรนด์
     */
    private fun findCompositeAnchorMatches(
        template: UniversalOcrTemplate,
        row: OcrTemplateRow,
        strictRow: CompiledRow,
        lines: List<LineText>,
        work: WorkItem,
        workDate: LocalDate
    ): List<TemplateMatch> {
        val anchorField = row.fields.firstOrNull { field ->
            field.type == "COMPOSITE_CODE" &&
                field.composite?.segments?.any { it.type == "POS_NUMBER" } == true
        } ?: return emptyList()
        val relaxedField = anchorField.copy(
            composite = anchorField.composite?.copy(prefix = null),
            required = true,
            tokenGap = 0
        )
        val anchor = compileSingleField(relaxedField, work) ?: return emptyList()
        val auxiliary = row.fields
            .filter { it !== anchorField && it.type in setOf("BILL_DATE", "BILL_TIME") }
            .mapNotNull { field -> compileSingleField(field.copy(required = true, tokenGap = 0), work) }

        val found = mutableListOf<TemplateMatch>()
        // แม้ Admin ระบุว่าอยู่แถวเดียว ML Kit อาจแยกแถวจริงเป็น 2-3 บรรทัด
        val maxJoin = (template.recognition.lineTolerance + 3).coerceIn(3, 5)
        lines.indices.forEach { start ->
            for (count in 1..maxJoin) {
                if (start + count > lines.size) break
                val source = lines.subList(start, start + count).map { it.text }
                val joined = normalizeLine(source.joinToString(" "))
                anchor.regex.findAll(joined).forEach { match ->
                    val fields = linkedMapOf<String, String>()
                    fields.putAll(extractFields(anchor, match))

                    // เริ่มจากบรรทัดเดียวกับรหัส แล้วค่อยขยายหาบรรทัดข้างเคียง
                    val nearby = buildList {
                        add(joined)
                        for (distance in 1..2) {
                            val before = start - distance
                            val after = start + count - 1 + distance
                            if (before >= 0) add(lines[before].text)
                            if (after <= lines.lastIndex) add(lines[after].text)
                        }
                    }
                    auxiliary.forEach { compiled ->
                        val value = nearby.asSequence()
                            .mapNotNull { candidate -> compiled.regex.find(normalizeLine(candidate)) }
                            .map { extractFields(compiled, it) }
                            .firstOrNull { it.isNotEmpty() }
                        value?.forEach { (key, item) -> if (!fields.containsKey(key)) fields[key] = item }
                    }
                    if (fields["POS_NUMBER"] != null) {
                        val strict = strictRow.regex.containsMatchIn(joined)
                        val warning = if (strict) emptyList() else listOf(
                            "ข้อมูลบางส่วนไม่ตรงกับเงื่อนไขที่กำหนด แต่ยังเก็บค่าที่อ่านได้ไว้"
                        )
                        val matchScore = score(template, fields, work, workDate) - 15 - ((count - 1) * 4)
                        if (matchScore > 0) {
                            found += TemplateMatch(template, fields, matchScore, source, warning)
                        }
                    }
                }
            }
        }
        return found.distinctBy { it.template.templateId + "|" + it.fields.entries.joinToString("|") }
    }

    private fun findSingleRowMatches(
        template: UniversalOcrTemplate,
        row: CompiledRow,
        lines: List<LineText>,
        work: WorkItem,
        workDate: LocalDate
    ): List<TemplateMatch> {
        val found = mutableListOf<TemplateMatch>()
        // lineTolerance เป็นระยะระหว่างแถวของแม่แบบ ไม่ควรจำกัดการประกอบ
        // เศษบรรทัดที่ ML Kit แยกจากแถวจริงเดียวกัน
        val maxJoin = (template.recognition.lineTolerance + 3).coerceIn(3, 6)
        fun collect(source: List<String>, joinPenalty: Int) {
            val joined = normalizeLine(source.joinToString(" "))
            val compact = joined.replace(
                Regex("(?<=[A-Za-z0-9OoIl|])\\s+(?=[A-Za-z0-9OoIl|])"),
                ""
            )
            listOf(joined, compact).distinct().forEach { candidateText ->
                row.regex.findAll(candidateText).forEach { match ->
                    val fields = extractFields(row, match)
                    val score = score(template, fields, work, workDate) - joinPenalty
                    if (score > 0) found += TemplateMatch(template, fields, score, source)
                }
            }
        }
        lines.indices.forEach { start ->
            for (count in 1..maxJoin) {
                if (start + count > lines.size) break
                val source = lines.subList(start, start + count).map { it.text }
                collect(source, (count - 1) * 2)
            }
        }
        // ภาพที่มีบิลซ้อนกันอาจทำให้ ML Kit แยกหนึ่งแถวจริงออกเกิน lineTolerance
        // สแกนข้อความทั้งภาพเพิ่มหนึ่งครั้ง แล้ว findAll จะคืนทุก POS โดยไม่หยุดที่รายการแรก
        if (lines.size > maxJoin) collect(lines.map { it.text }, 8)
        return found.distinctBy { it.template.templateId + "|" + it.fields.entries.joinToString("|") }
    }

    private fun findMultiRowMatches(
        template: UniversalOcrTemplate,
        rows: List<CompiledRow>,
        lines: List<LineText>,
        work: WorkItem,
        workDate: LocalDate
    ): List<TemplateMatch> {
        val found = mutableListOf<TemplateMatch>()
        val gap = template.recognition.lineTolerance.coerceIn(0, 3)
        for (start in lines.indices) {
            val fields = linkedMapOf<String, String>()
            val sourceLines = mutableListOf<String>()
            var cursor = start
            var ok = true
            rows.forEachIndexed { i, compiled ->
                val end = if (i == 0) cursor else (cursor + gap).coerceAtMost(lines.lastIndex)
                var rowMatch: MatchResult? = null
                var rowStart = -1
                var rowCount = 0
                for (candidateStart in cursor..end) {
                    val maxJoin = (template.recognition.lineTolerance + 3).coerceIn(3, 6)
                    for (count in 1..maxJoin) {
                        if (candidateStart + count > lines.size) break
                        val joined = normalizeLine(
                            lines.subList(candidateStart, candidateStart + count).joinToString(" ") { it.text }
                        )
                        val candidate = compiled.regex.find(joined)
                        if (candidate != null) {
                            rowMatch = candidate
                            rowStart = candidateStart
                            rowCount = count
                            break
                        }
                    }
                    if (rowMatch != null) break
                }
                if (rowMatch == null) {
                    ok = false
                } else {
                    extractFields(compiled, rowMatch!!).forEach { (k, v) -> if (!fields.containsKey(k)) fields[k] = v }
                    sourceLines += lines.subList(rowStart, rowStart + rowCount).map { it.text }
                    cursor = rowStart + rowCount
                }
            }
            if (ok) {
                val score = score(template, fields, work, workDate)
                if (score > 0) found += TemplateMatch(template, fields, score, sourceLines)
            }
        }
        return found.distinctBy { it.template.templateId + "|" + it.fields.entries.joinToString("|") }
    }

    private fun compileRow(row: OcrTemplateRow, work: WorkItem): CompiledRow? {
        val captureTypes = mutableListOf<String>()
        val parts = row.fields.sortedBy { it.order }.mapNotNull { field ->
            fieldPattern(field, work, captureTypes)?.let { part ->
                val gap = if (field.tokenGap > 0) "(?:\\s+\\S+){0,${field.tokenGap}}?\\s*" else ""
                val combined = gap + part
                if (field.required) combined else "(?:$combined)?"
            }
        }
        if (parts.isEmpty()) return null
        return runCatching {
            CompiledRow(Regex(parts.joinToString("[\\s|,;:_-]*"), RegexOption.IGNORE_CASE), captureTypes)
        }.getOrNull()
    }

    private fun compileSingleField(field: OcrTemplateField, work: WorkItem): CompiledRow? {
        val captures = mutableListOf<String>()
        val pattern = fieldPattern(field, work, captures) ?: return null
        return runCatching { CompiledRow(Regex(pattern, RegexOption.IGNORE_CASE), captures) }.getOrNull()
    }

    private fun fieldPattern(field: OcrTemplateField, work: WorkItem, captures: MutableList<String>): String? {
        fun capture(type: String, inner: String): String {
            captures += type
            return "($inner)"
        }

        val useExampleLength = field.type in setOf("YEAR_VALUE", "MONTH_VALUE", "DAY_VALUE", "STORE_ID", "POS_NUMBER")
        val exactLen = field.example?.takeIf { it.isNotBlank() && useExampleLength }?.length
        val min = exactLen ?: field.minLength.coerceAtLeast(1)
        val max = exactLen ?: field.maxLength.coerceAtLeast(min)

        return when (field.type) {
            "BILL_DATE" -> capture("BILL_DATE", datePattern(field))
            "BILL_TIME" -> capture("BILL_TIME", "$OCR_DIGIT{1,2}[:.]$OCR_DIGIT{2}(?::$OCR_DIGIT{2})?")
            "YEAR_VALUE", "YEAR" -> capture("YEAR_VALUE", "$OCR_DIGIT{${exactLen ?: 2}}")
            "MONTH_VALUE", "MONTH" -> capture("MONTH_VALUE", "$OCR_DIGIT{2}")
            "DAY_VALUE", "DAY" -> capture("DAY_VALUE", "$OCR_DIGIT{2}")
            "STORE_ID" -> {
                val len = field.example?.length ?: work.storeCode.count(Char::isDigit).takeIf { it > 0 } ?: min
                capture("STORE_ID", "$OCR_DIGIT{$len}")
            }
            "POS_NUMBER" -> {
                val prefixList = field.posPrefixes.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
                val example = field.example.orEmpty().trim()
                val examplePrefix = example.takeWhile(Char::isLetter)
                val exampleDigits = example.takeLastWhile(Char::isDigit).length.takeIf { it > 0 }
                // ตัวอย่าง 002 ต้องอ่านเป็นเลข 3 หลัก แม้ข้อมูลเก่าจาก Admin จะมี posDigits=2
                val fixedNumericLength = field.minLength
                    .takeIf { prefixList.isEmpty() && field.minLength == field.maxLength && it > 0 }
                val digits = exampleDigits ?: fixedNumericLength ?: field.posDigits ?: 2
                val prefix = when {
                    prefixList.isNotEmpty() -> prefixList.joinToString("|", "(?:", ")") { Regex.escape(it) }
                    examplePrefix.isNotBlank() -> Regex.escape(examplePrefix)
                    else -> "[A-Za-z]?"
                }
                capture("POS_NUMBER", "$prefix$OCR_DIGIT{$digits}")
            }
            // จำนวนหลักของลูกค้ามาจาก Admin เพื่อรักษาขอบเขตช่องถัดไป
            "CUSTOMER_VALUE" -> capture("CUSTOMER_VALUE", "$OCR_DIGIT{$min,$max}(?!$OCR_DIGIT)")
            "EMPLOYEE_CODE" -> capture("EMPLOYEE_CODE", "[A-Za-z0-9]{$min,$max}")
            "NUMBER_TEXT" -> "$OCR_DIGIT{$min,$max}"
            "ALNUM_TEXT" -> "[A-Za-z0-9]{$min,$max}"
            "LITERAL" -> literalPattern(field.literal ?: field.example.orEmpty())
            "SEPARATOR" -> Regex.escape(field.separatorValue ?: field.example ?: "-")
            "IGNORE" -> ".{0,40}?"
            "COMPOSITE_CODE" -> compositePattern(field, captures, work)
            else -> null
        }
    }

    private fun compositePattern(field: OcrTemplateField, captures: MutableList<String>, work: WorkItem): String {
        val c = field.composite
        if (c == null || c.segments.isEmpty()) {
            val min = field.example?.length ?: field.minLength.coerceAtLeast(1)
            val max = field.example?.length ?: field.maxLength.coerceAtLeast(min)
            captures += "COMPOSITE_CODE"
            return "([A-Za-z0-9:_-]{$min,$max})"
        }

        return buildString {
            c.prefix?.let { prefix ->
                if (prefix.uppercase() == "BNO:" || prefix.uppercase() == "BNO:S") {
                    append("[B8]N[O0]\\s*[:;]\\s*[S$5]?")
                } else {
                    append(Regex.escape(prefix))
                }
            }
            val segments = c.segments.sortedBy { it.order }
            segments.forEachIndexed { index, s ->
                val len = when {
                    s.length > 0 -> s.length
                    !s.example.isNullOrBlank() -> s.example!!.length
                    s.type in setOf("YEAR_VALUE", "YEAR") -> 2
                    s.type in setOf("MONTH_VALUE", "MONTH", "DAY_VALUE", "DAY") -> 2
                    s.type == "STORE_ID" -> work.storeCode.count(Char::isDigit).coerceAtLeast(1)
                    else -> 1
                }
                if (index > 0 && s.type == "CUSTOMER_VALUE" && !c.separator.isNullOrBlank() && segments[index - 1].type != "SEPARATOR") {
                    append(Regex.escape(c.separator!!))
                }
                when (s.type) {
                    "YEAR_VALUE", "YEAR", "MONTH_VALUE", "MONTH", "DAY_VALUE", "DAY", "STORE_ID" -> {
                        captures += when (s.type) {
                            "YEAR" -> "YEAR_VALUE"
                            "MONTH" -> "MONTH_VALUE"
                            "DAY" -> "DAY_VALUE"
                            else -> s.type
                        }
                        append("($OCR_DIGIT{$len})")
                    }
                    // อ่านเลขเต็มก่อน จำนวนหลักที่ Admin ตั้งใช้เป็นคำเตือนภายหลัง
                    "CUSTOMER_VALUE" -> {
                        captures += "CUSTOMER_VALUE"
                        append("($OCR_DIGIT{1,18}(?!$OCR_DIGIT))")
                    }
                    "POS_NUMBER" -> {
                        captures += "POS_NUMBER"
                        val ex = s.example.orEmpty()
                        val prefix = ex.takeWhile { it.isLetter() }
                        val digits = ex.takeLastWhile { it.isDigit() }.length.takeIf { it > 0 } ?: (len - prefix.length).coerceAtLeast(1)
                        append("(")
                        append(if (prefix.isNotBlank()) Regex.escape(prefix) else "[A-Za-z]?")
                        append("$OCR_DIGIT{$digits})")
                    }
                    "EMPLOYEE_CODE" -> {
                        captures += "EMPLOYEE_CODE"
                        append("([A-Za-z0-9]{$len})")
                    }
                    "LITERAL" -> append(Regex.escape(s.example.orEmpty()))
                    "SEPARATOR" -> append(Regex.escape(s.example ?: c.separator ?: "-"))
                    "NUMBER_TEXT" -> append("$OCR_DIGIT{$len}")
                    "ALNUM_TEXT" -> append("[A-Za-z0-9]{$len}")
                    else -> append(".{$len}")
                }
            }
        }
    }

    private fun extractFields(compiled: CompiledRow, match: MatchResult): Map<String, String> {
        val result = linkedMapOf<String, String>()
        compiled.captureTypes.forEachIndexed { i, type ->
            val value = match.groupValues.getOrNull(i + 1).orEmpty()
            if (value.isNotBlank() && !result.containsKey(type)) result[type] = normalizeCaptured(type, value)
        }
        return result
    }

    private fun normalizeCaptured(type: String, raw: String): String {
        return when (type) {
            "BILL_DATE", "BILL_TIME", "YEAR_VALUE", "MONTH_VALUE", "DAY_VALUE",
            "STORE_ID", "CUSTOMER_VALUE" -> OcrTextNormalizer.normalizeDigits(raw)
            "POS_NUMBER" -> {
                val prefix = raw.takeWhile { it.isLetter() && it !in listOf('O', 'o', 'I', 'i', 'l') }
                prefix + OcrTextNormalizer.normalizeDigits(raw.drop(prefix.length))
            }
            else -> raw
        }
    }

    private fun datePattern(field: OcrTemplateField): String {
        val order = field.dateOrder.trim().uppercase().let {
            if (it in setOf("DMY", "MDY", "YMD")) it else "DMY"
        }
        val yearLengths = when (field.dateYearDigits) {
            2 -> listOf(2)
            4 -> listOf(4)
            else -> listOf(2, 4)
        }
        val layouts = yearLengths.map { yearLength ->
            when (order) {
                "YMD" -> listOf(yearLength, 2, 2)
                else -> listOf(2, 2, yearLength)
            }
        }
        return layouts.joinToString("|", "(?:", ")") { lengths ->
            "$OCR_DIGIT{${lengths[0]}}[./-]$OCR_DIGIT{${lengths[1]}}[./-]$OCR_DIGIT{${lengths[2]}}"
        }
    }

    private fun literalPattern(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return when {
            value.matches(Regex("BNO\\s*:\\s*S", RegexOption.IGNORE_CASE)) -> "[B8]N[O0]\\s*[:;]\\s*[S$5]"
            value.matches(Regex("BNO\\s*:", RegexOption.IGNORE_CASE)) -> "[B8]N[O0]\\s*[:;]"
            else -> value.map { character ->
                when (character) {
                    '0' -> "[0Oo]"
                    '1' -> "[1Iil|]"
                    'U', 'u', 'V', 'v' -> "[UuVvOo0]"
                    else -> Regex.escape(character.toString())
                }
            }.joinToString("\\s*")
        }
    }

    private fun score(
        template: UniversalOcrTemplate,
        fields: Map<String, String>,
        work: WorkItem,
        workDate: LocalDate
    ): Int {
        var score = template.priority
        if (fields["BILL_DATE"] != null) score += 25
        if (fields["BILL_TIME"] != null) score += 15
        if (fields["STORE_ID"] != null) score += 30
        if (fields["POS_NUMBER"] != null) score += 30
        if (fields["CUSTOMER_VALUE"] != null) score += 25
        if (fields["YEAR_VALUE"] != null) score += 10
        if (fields["MONTH_VALUE"] != null) score += 10
        val store = fields["STORE_ID"]
        if (!store.isNullOrBlank()) score += if (sameStore(store, work.expectedReceiptStoreId)) 50 else -40
        val pos = fields["POS_NUMBER"]?.let(::parsePosNumber)
        if (pos != null) score += 20

        val scoreDateField = template.recognition.rows.asSequence()
            .flatMap { it.fields.asSequence() }
            .firstOrNull { it.type == "BILL_DATE" }
        val date = fields["BILL_DATE"]?.let {
            ReceiptDateOcrNormalizer.normalizeForField(it, scoreDateField, workDate).value
        }
        if (date != null) {
            val parsed = parseDate(date)
            if (parsed != null) {
                val distance = kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(workDate, parsed))
                score += when {
                    distance == 0L -> 20
                    distance <= 2L -> 15
                    distance <= 31L -> 5
                    else -> 0
                }
            }
        }
        if (hasDateComparisons(template)) {
            score += if (comparisonWarnings(template, fields, date, workDate).isEmpty()) 10 else -20
        }
        val customer = fields["CUSTOMER_VALUE"]
        val expected = expectedLength(template, "CUSTOMER_VALUE")
        if (!customer.isNullOrBlank() && expected != null && customer.length in expected) score += 10
        return score
    }

    private fun dateComparisonFields(template: UniversalOcrTemplate): List<OcrTemplateField> =
        template.recognition.rows.asSequence()
            .flatMap { it.fields.asSequence() }
            .filter { field ->
                field.compareTo.uppercase() in setOf("BILL_DATE", "WORK_DATE") &&
                    field.type in setOf("YEAR_VALUE", "YEAR", "MONTH_VALUE", "MONTH", "DAY_VALUE", "DAY")
            }.toList()

    private fun hasDateComparisons(template: UniversalOcrTemplate): Boolean =
        dateComparisonFields(template).isNotEmpty()

    private fun comparisonWarnings(
        template: UniversalOcrTemplate,
        fields: Map<String, String>,
        normalizedBillDate: String?,
        workDate: LocalDate
    ): List<String> {
        val billDate = normalizedBillDate?.let(::parseDate)
        return dateComparisonFields(template).mapNotNull { field ->
            val key = when (field.type) {
                "YEAR", "YEAR_VALUE" -> "YEAR_VALUE"
                "MONTH", "MONTH_VALUE" -> "MONTH_VALUE"
                else -> "DAY_VALUE"
            }
            val raw = fields[key]?.filter(Char::isDigit).orEmpty()
            if (raw.isBlank()) return@mapNotNull null
            val target = if (field.compareTo.equals("WORK_DATE", true)) workDate else billDate
                ?: return@mapNotNull null
            val matched = when (key) {
                "YEAR_VALUE" -> {
                    val gregorian = target.year.toString().takeLast(raw.length)
                    val buddhist = (target.year + 543).toString().takeLast(raw.length)
                    raw == gregorian || raw == buddhist
                }
                "MONTH_VALUE" -> raw.toIntOrNull() == target.monthValue
                else -> raw.toIntOrNull() == target.dayOfMonth
            }
            if (matched) null else {
                val source = if (field.compareTo.equals("WORK_DATE", true)) "วันที่ทำงาน" else "วันที่ในบิล"
                val label = when (key) { "YEAR_VALUE" -> "ปี"; "MONTH_VALUE" -> "เดือน"; else -> "วัน" }
                "${label}ที่อ่านได้ ($raw) ไม่ตรงกับ$source"
            }
        }.distinct()
    }

    private fun parsePosNumber(raw: String): Int? = OcrTextNormalizer.parsePosNumber(raw)

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

    private fun sameStore(a: String, b: String): Boolean {
        val aa = a.filter(Char::isDigit).trimStart('0').ifBlank { "0" }
        val bb = b.filter(Char::isDigit).trimStart('0').ifBlank { "0" }
        return aa == bb
    }

    private fun normalizeDate(raw: String, referenceDate: LocalDate): String? {
        val parts = raw.trim().replace('.', '/').replace('-', '/').split('/')
        if (parts.size != 3) return null
        val a = parts[0].toIntOrNull() ?: return null
        val b = parts[1].toIntOrNull() ?: return null
        val rawYear = parts[2].toIntOrNull() ?: return null

        val years = when {
            rawYear in 2400..2999 -> listOf(rawYear - 543)
            rawYear < 100 -> listOf(
                2000 + rawYear,
                1900 + rawYear,
                2500 + rawYear - 543
            ).filter { it in 1900..2200 }.distinct()
            else -> listOf(rawYear)
        }

        val candidates = mutableListOf<LocalDate>()
        fun addCandidate(year: Int, day: Int, month: Int) {
            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let(candidates::add)
        }
        years.forEach { year ->
            when {
                a > 12 && b in 1..12 -> addCandidate(year, a, b)
                b > 12 && a in 1..12 -> addCandidate(year, b, a)
                else -> {
                    addCandidate(year, a, b)
                    if (a != b) addCandidate(year, b, a)
                }
            }
        }

        // รูปแบบบิลในระบบอนุญาตวันที่ใกล้วันงานเท่านั้น (สูงสุดยังต่ำกว่า 45 วัน)
        // จึงไม่ยอมให้ปีที่อ่านเพี้ยนแต่ยังเป็นวันที่จริง เช่น 2061 ผ่านเป็น core field
        return candidates.distinct()
            .filter { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(referenceDate, it)) <= 45 }
            .minByOrNull { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(referenceDate, it)) }
            ?.format(outDate)
    }

    private fun parseDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw, outDate)
    } catch (_: DateTimeParseException) {
        null
    }
}
