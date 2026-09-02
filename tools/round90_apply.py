from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def rep(path, old, new, count=1):
    text = read(path)
    if old not in text:
        raise SystemExit(f"marker not found in {path}: {old[:180]!r}")
    text = text.replace(old, new, count)
    write(path, text)


def sub(path, pattern, replacement, count=1):
    text = read(path)
    new, n = re.subn(pattern, replacement, text, count=count, flags=re.S)
    if n != count:
        raise SystemExit(f"regex marker not found in {path}: expected {count}, got {n}: {pattern[:160]!r}")
    write(path, new)


# ---------------------------------------------------------------------------
# Android: POS evidence fusion must use the same Admin-driven date engine.
# ---------------------------------------------------------------------------
pos = "android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt"
rep(pos, "import java.time.DateTimeException\nimport java.time.LocalDate", "import java.time.LocalDate")
rep(
    pos,
    """        work: WorkItem,\n        imagePath: String,\n        templates: List<UniversalOcrTemplate>\n""",
    """        work: WorkItem,\n        workDate: LocalDate,\n        imagePath: String,\n        templates: List<UniversalOcrTemplate>\n""",
)
rep(
    pos,
    ".flatMap { template -> collectTemplateEvidence(template, candidates, allowedPos) }",
    ".flatMap { template -> collectTemplateEvidence(template, candidates, allowedPos, workDate) }",
)
rep(
    pos,
    """    internal fun fuseTextPasses(\n        rawTexts: List<String>,\n        template: UniversalOcrTemplate,\n        allowedPos: Set<Int>\n    ): Map<Int, Map<String, String>> {\n        val candidates = buildLocalCandidates(rawTexts)\n        val evidence = collectTemplateEvidence(template, candidates, allowedPos)\n""",
    """    internal fun fuseTextPasses(\n        rawTexts: List<String>,\n        template: UniversalOcrTemplate,\n        allowedPos: Set<Int>,\n        referenceDate: LocalDate\n    ): Map<Int, Map<String, String>> {\n        val candidates = buildLocalCandidates(rawTexts)\n        val evidence = collectTemplateEvidence(template, candidates, allowedPos, referenceDate)\n""",
)
rep(
    pos,
    """    private fun collectTemplateEvidence(\n        template: UniversalOcrTemplate,\n        candidates: List<LocalCandidate>,\n        allowedPos: Set<Int>\n    ): List<Evidence> {\n""",
    """    private fun collectTemplateEvidence(\n        template: UniversalOcrTemplate,\n        candidates: List<LocalCandidate>,\n        allowedPos: Set<Int>,\n        referenceDate: LocalDate\n    ): List<Evidence> {\n""",
)
rep(
    pos,
    """                    val enriched = fields.toMutableMap()\n                    val anchorStart = match.range.first.coerceAtLeast(0)\n""",
    """                    val enriched = fields.toMutableMap()\n                    val dateField = ordered.firstOrNull { it.field.type.equals(\"BILL_DATE\", true) }?.field\n                    enriched[\"BILL_DATE\"]?.takeIf { it.isNotBlank() }?.let { rawDate ->\n                        val normalized = ReceiptDateOcrNormalizer.normalizeForField(\n                            raw = rawDate,\n                            field = dateField,\n                            referenceDate = referenceDate\n                        )\n                        if (normalized.value != null) enriched[\"BILL_DATE\"] = normalized.value\n                        else enriched.remove(\"BILL_DATE\")\n                    }\n                    val anchorStart = match.range.first.coerceAtLeast(0)\n""",
)
rep(
    pos,
    "enrichDateAndTime(template, ordered, enriched, localText)",
    "enrichDateAndTime(template, ordered, enriched, localText, referenceDate)",
)
rep(
    pos,
    """    private fun enrichDateAndTime(\n        template: UniversalOcrTemplate,\n        ordered: List<OrderedField>,\n        fields: MutableMap<String, String>,\n        localText: String\n    ) {\n""",
    """    private fun enrichDateAndTime(\n        template: UniversalOcrTemplate,\n        ordered: List<OrderedField>,\n        fields: MutableMap<String, String>,\n        localText: String,\n        referenceDate: LocalDate\n    ) {\n""",
)
rep(pos, "findDate(localText, dateField)?.let { found ->", "findDate(localText, dateField, referenceDate)?.let { found ->")
sub(
    pos,
    r"    private fun findDate\(text: String, field: OcrTemplateField\): Pair<String, IntRange>\? \{.*?\n    \}\n\n    private fun findTime",
    r'''    private fun findDate(
        text: String,
        field: OcrTemplateField,
        referenceDate: LocalDate
    ): Pair<String, IntRange>? {
        val lengths = Regex("\\d+").findAll(field.example.orEmpty()).map { it.value.length }.toList()
            .takeIf { it.size == 3 } ?: when (field.dateOrder.uppercase()) {
                "YMD" -> listOf(if (field.dateYearDigits == 2) 2 else 4, 2, 2)
                else -> listOf(2, 2, if (field.dateYearDigits == 2) 2 else 4)
            }

        fun accept(raw: String, range: IntRange): Pair<String, IntRange>? {
            val normalized = ReceiptDateOcrNormalizer.normalizeForField(
                raw = raw,
                field = field,
                referenceDate = referenceDate
            )
            return normalized.value?.let { it to range }
        }

        val separated = Regex(
            "${fixedDigits(lengths[0])}\\s*[./-]\\s*${fixedDigits(lengths[1])}\\s*[./-]\\s*${fixedDigits(lengths[2])}",
            RegexOption.IGNORE_CASE
        )
        separated.findAll(text).forEach { match ->
            accept(match.value, match.range)?.let { return it }
        }

        val total = lengths.sum()
        val compact = Regex("(?<![0-9OoIl|SsZzBbGg])${fixedDigits(total)}(?![0-9OoIl|SsZzBbGg])")
        compact.findAll(text).forEach { match ->
            val digits = normalizeDigits(match.value).filter(Char::isDigit)
            if (digits.length == total) {
                var cursor = 0
                val raw = lengths.map { length ->
                    digits.substring(cursor, cursor + length).also { cursor += length }
                }.joinToString("/")
                accept(raw, match.range)?.let { return it }
            }
        }

        // OCR ของบิลความร้อนมักทำ '/' หายหรือมีเลขแทรก เช่น 20/08769
        // ให้ date engine เป็นผู้ตัดสินตามลำดับ/ระบบปี/จำนวนหลักจาก Admin + วันงาน
        // ไม่ตัดเลขเองแบบ hard-code แบรนด์
        val noisy = Regex(
            "(?<![0-9OoIl|SsZzBbGg])(?:$DIGIT\\s*){1,4}\\s*[./-]\\s*(?:$DIGIT\\s*){2,8}(?![0-9OoIl|SsZzBbGg])",
            RegexOption.IGNORE_CASE
        )
        noisy.findAll(text).forEach { match ->
            accept(match.value, match.range)?.let { return it }
        }
        return null
    }

    private fun findTime''',
)
sub(
    pos,
    r"    private fun isValidDate\(value: String\): Boolean \{.*?\n    \}\n\n    private fun isValidTime",
    r'''    private fun isValidDate(value: String): Boolean =
        ReceiptDateOcrNormalizer.isCanonical(value)

    private fun isValidTime''',
)

# ---------------------------------------------------------------------------
# Android: pipeline stores only canonical date/time; raw OCR values stay separate.
# ---------------------------------------------------------------------------
real = "android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt"
rep(
    real,
    """                records = records,\n                work = work,\n                imagePath = imagePath,\n                templates = templates\n""",
    """                records = records,\n                work = work,\n                workDate = workDate,\n                imagePath = imagePath,\n                templates = templates\n""",
)
old_block = '''            val rawCandidateDate = record.billDate.trim()
            val recordDateField = dateFieldForRecord(record, templates) ?: defaultDateField
            val dateResult = if (currentImagePos && rawCandidateDate.isNotBlank()) {
                ReceiptDateOcrNormalizer.normalize(
                    raw = rawCandidateDate,
                    configuredFormat = recordDateField?.format,
                    referenceDate = workDate,
                    dateOrder = recordDateField?.dateOrder,
                    dateCalendar = recordDateField?.dateCalendar,
                    dateYearDigits = recordDateField?.dateYearDigits ?: 0
                )
            } else null
            val storeId = mergeStoreId(
                original = original,
                candidateStoreId = currentStoreIdsByPos[record.posNumber].orEmpty(),
                isCurrentPos = currentImagePos
            )
            val safeExistingDate = if (!original.source.startsWith("OCR", ignoreCase = true)) original.billDate else ""
            val acceptedDate = when {
                dateResult?.value != null -> dateResult.value
                currentImagePos && rawCandidateDate.isNotBlank() -> rawCandidateDate
                currentImagePos -> safeExistingDate
                else -> record.billDate
            }
            val dateWarning = when {
                !currentImagePos || rawCandidateDate.isBlank() -> ""
                dateResult?.value == null ->
                    (dateResult?.warning ?: "วันที่ที่อ่านจากภาพ ($rawCandidateDate) ไม่ตรงเงื่อนไขที่กำหนด") + " • แสดงค่าที่อ่านได้ไว้ให้ตรวจแก้"
                dateResult.corrected ->
                    "วันที่ที่อ่านจากภาพ ${dateResult.original} ถูกปรับเป็น ${dateResult.value} ตามเงื่อนไขวันที่ กรุณาตรวจเทียบกับภาพ"
                else -> ""
            }
            val inheritedWarning = sanitizeLegacyOcrWarnings(record.ocrWarnings)
            val standardizedTime = if (currentImagePos && record.billTime.isNotBlank()) {
                ReceiptTimeOcrNormalizer.normalize(record.billTime).value ?: record.billTime
            } else record.billTime
            record.copy(
                billDate = acceptedDate,
                billTime = standardizedTime,
                ocrStoreId = storeId,
                ocrWarnings = listOf(inheritedWarning, dateWarning)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" • ")
            )
'''
new_block = '''            val rawCandidateDate = record.billDate.trim()
            val rawCandidateTime = record.billTime.trim()
            val recordDateField = dateFieldForRecord(record, templates) ?: defaultDateField
            val dateResult = if (currentImagePos && rawCandidateDate.isNotBlank()) {
                ReceiptDateOcrNormalizer.normalizeForField(
                    raw = rawCandidateDate,
                    field = recordDateField,
                    referenceDate = workDate,
                    allowCanonicalInput = record.source.equals("OCR-EVIDENCE", ignoreCase = true)
                )
            } else null
            val timeResult = if (currentImagePos && rawCandidateTime.isNotBlank()) {
                ReceiptTimeOcrNormalizer.normalize(rawCandidateTime)
            } else null
            val storeId = mergeStoreId(
                original = original,
                candidateStoreId = currentStoreIdsByPos[record.posNumber].orEmpty(),
                isCurrentPos = currentImagePos
            )
            val safeExistingDate = if (!original.source.startsWith("OCR", ignoreCase = true)) original.billDate else ""
            val safeExistingTime = if (!original.source.startsWith("OCR", ignoreCase = true)) original.billTime else ""
            val acceptedDate = when {
                dateResult?.value != null -> dateResult.value
                currentImagePos -> safeExistingDate
                else -> record.billDate
            }
            val acceptedTime = when {
                timeResult?.value != null -> timeResult.value
                currentImagePos -> safeExistingTime
                else -> record.billTime
            }
            val dateWarning = when {
                !currentImagePos || rawCandidateDate.isBlank() -> ""
                dateResult?.value == null ->
                    (dateResult?.warning ?: "วันที่ที่อ่านจากภาพ ($rawCandidateDate) ไม่ตรงเงื่อนไขที่ Admin กำหนด") + " • ค่านี้จะไม่ถูกใช้เป็นวันที่ส่งงาน"
                dateResult.corrected ->
                    "วันที่ที่อ่านจากภาพ ${dateResult.original} ถูกปรับเป็น ${dateResult.value} ตามเงื่อนไข Admin • กรุณาตรวจเทียบกับภาพ"
                else -> ""
            }
            val timeWarning = when {
                !currentImagePos || rawCandidateTime.isBlank() -> ""
                timeResult?.value == null ->
                    (timeResult?.warning ?: "เวลาที่อ่านจากภาพไม่ถูกต้อง") + " • อ่านได้ $rawCandidateTime • ค่านี้จะไม่ถูกใช้เป็นเวลาส่งงาน"
                else -> ""
            }
            val inheritedWarning = sanitizeLegacyOcrWarnings(record.ocrWarnings)
            record.copy(
                billDate = acceptedDate,
                billTime = acceptedTime,
                ocrRawBillDate = if (currentImagePos && rawCandidateDate.isNotBlank()) rawCandidateDate else record.ocrRawBillDate,
                ocrRawBillTime = if (currentImagePos && rawCandidateTime.isNotBlank()) rawCandidateTime else record.ocrRawBillTime,
                ocrStoreId = storeId,
                ocrWarnings = listOf(inheritedWarning, dateWarning, timeWarning)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" • ")
            )
'''
rep(real, old_block, new_block)

# ---------------------------------------------------------------------------
# Android: final validation understands raw rejected OCR data and does not emit
# the old generic dd/MM/yyyy message over the Admin-specific warning.
# ---------------------------------------------------------------------------
validation = "android-app/app/src/main/java/com/receiptocr/app/validation/ReceiptValidationEngine.kt"
rep(
    validation,
    'message = "รูปแบบวันที่ไม่ถูกต้อง • กรุณาใช้ dd/MM/yyyy"',
    'message = "วันที่ยังไม่ผ่านเงื่อนไขที่ Admin กำหนดและยังแปลงเป็นมาตรฐานไม่ได้"',
)
rep(
    validation,
    '''                if (record.customerNo.isBlank()) issues += block("CUSTOMER_REQUIRED", "POS ${record.posNumber}: ยังไม่มีเลข/ยอดลูกค้า")
                if (record.billDate.isBlank()) issues += block("DATE_REQUIRED", "POS ${record.posNumber}: ยังไม่มีวันที่")
                if (record.billTime.isBlank()) {
                    issues += block("TIME_REQUIRED", "POS ${record.posNumber}: ยังไม่มีเวลา")
                } else {
''',
    '''                if (record.customerNo.isBlank()) issues += block("CUSTOMER_REQUIRED", "POS ${record.posNumber}: ยังไม่มีเลข/ยอดลูกค้า")
                if (record.billDate.isBlank()) {
                    if (record.ocrRawBillDate.isNotBlank()) {
                        issues += block(
                            "DATE_OCR_REJECTED_POS_${record.posNumber}",
                            "POS ${record.posNumber}: วันที่ที่อ่านได้ ${record.ocrRawBillDate} ยังไม่ผ่านเงื่อนไขวันที่ของร้าน • กรุณาตรวจแก้"
                        )
                    } else {
                        issues += block("DATE_REQUIRED", "POS ${record.posNumber}: ยังไม่มีวันที่")
                    }
                }
                if (record.billTime.isBlank()) {
                    if (record.ocrRawBillTime.isNotBlank()) {
                        issues += block(
                            "TIME_OCR_REJECTED_POS_${record.posNumber}",
                            "POS ${record.posNumber}: เวลาที่อ่านได้ ${record.ocrRawBillTime} ยังไม่ถูกต้อง • กรุณาตรวจแก้"
                        )
                    } else {
                        issues += block("TIME_REQUIRED", "POS ${record.posNumber}: ยังไม่มีเวลา")
                    }
                } else {
''',
)
rep(
    validation,
    'if (record.billDate.isBlank()) return "ยังไม่มีวันที่บิล"',
    'if (record.billDate.isBlank()) return record.ocrRawBillDate.takeIf { it.isNotBlank() }?.let { "วันที่ที่อ่านได้ $it ยังไม่ผ่านเงื่อนไขวันที่ของร้าน" } ?: "ยังไม่มีวันที่บิล"',
)

# ---------------------------------------------------------------------------
# Android: add thinner overlapping OCR strips for receipt-code lines.
# This is generic, not brand-specific, and keeps source scale/coordinates.
# ---------------------------------------------------------------------------
multi = "android-app/app/src/main/java/com/receiptocr/app/ocr/MultiPassOcrReader.kt"
marker = '''        tops.forEachIndexed { index, top ->
            val cropSource = when (index % 3) {
                0 -> highContrast
                1 -> sharpened
                else -> adaptive
            }
            val crop = Bitmap.createBitmap(cropSource, 0, top, cropSource.width, cropHeight)
            bitmaps += crop
            tasks += recognizer.process(InputImage.fromBitmap(crop, 0))
            passOrigins += 0 to top
        }

        return Tasks.whenAllSuccess<Text>(tasks).continueWith { completed ->
'''
replacement = '''        tops.forEachIndexed { index, top ->
            val cropSource = when (index % 3) {
                0 -> highContrast
                1 -> sharpened
                else -> adaptive
            }
            val crop = Bitmap.createBitmap(cropSource, 0, top, cropSource.width, cropHeight)
            bitmaps += crop
            tasks += recognizer.process(InputImage.fromBitmap(crop, 0))
            passOrigins += 0 to top
        }

        // Round90: เพิ่มแถบแนวนอนที่บางกว่าเพื่ออ่านข้อความรหัสบิลที่ตัวเลขชิดกัน
        // เช่น R201... / R202... บนบิลซ้อน โดยไม่ผูกกับแบรนด์หรือ prefix ใด
        // การ crop ที่บางช่วยให้ ML Kit ไม่ต้องลดรายละเอียดของทั้งภาพก่อนอ่านข้อความเล็ก
        val linePassCount = (expectedRecords * 2 + 5).coerceIn(9, 13)
        val lineRatio = (0.42f / expectedRecords.coerceAtLeast(1)).coerceIn(0.09f, 0.15f)
        val lineHeight = (source.height * lineRatio).toInt().coerceIn(1, source.height)
        val lineTravel = (source.height - lineHeight).coerceAtLeast(0)
        val lineTops = (0 until linePassCount).map { index ->
            if (linePassCount == 1) 0 else (lineTravel.toLong() * index / (linePassCount - 1)).toInt()
        }.distinct()
        lineTops.forEachIndexed { index, top ->
            val cropSource = if (index % 2 == 0) sharpened else highContrast
            val crop = Bitmap.createBitmap(cropSource, 0, top, cropSource.width, lineHeight)
            bitmaps += crop
            tasks += recognizer.process(InputImage.fromBitmap(crop, 0))
            passOrigins += 0 to top
        }

        return Tasks.whenAllSuccess<Text>(tasks).continueWith { completed ->
'''
rep(multi, marker, replacement)

# ---------------------------------------------------------------------------
# Web Admin: date regex must follow Admin order/year-length rather than a
# generic D/M/Y expression. Admin test keeps canonical preview.
# ---------------------------------------------------------------------------
engine = "web-admin/ocr-pattern-engine.js"
rep(
    engine,
    '''  function fieldRegex(field,occurrence){
    const {min,max}=exactOrRange(field);
''',
    '''  function dateFieldRegex(field,groupName){
    const order=String(field.dateOrder||"DMY").toUpperCase();
    const yearDigits=Number(field.dateYearDigits||0);
    const day=`${OCR_DIGIT}{1,2}`;
    const month=`${OCR_DIGIT}{1,2}`;
    const year=yearDigits===2?`${OCR_DIGIT}{2}`:yearDigits===4?`${OCR_DIGIT}{4}`:`(?:${OCR_DIGIT}{2}|${OCR_DIGIT}{4})`;
    const parts=order==="MDY"?[month,day,year]:order==="YMD"?[year,month,day]:[day,month,year];
    return `(?<${groupName}>${parts.join("[./-]")})`;
  }

  function fieldRegex(field,occurrence){
    const {min,max}=exactOrRange(field);
''',
)
rep(
    engine,
    'if(field.type==="BILL_DATE")return `(?<${group("BILL_DATE")}>${OCR_DIGIT}{1,2}[./-]${OCR_DIGIT}{1,2}[./-]${OCR_DIGIT}{2,4})`;',
    'if(field.type==="BILL_DATE")return dateFieldRegex(field,group("BILL_DATE"));',
)

# ---------------------------------------------------------------------------
# Web Admin simple test: enforce the same date order/calendar/year length,
# show canonical dd/MM/yyyy and explicitly flag source rule mismatch.
# ---------------------------------------------------------------------------
simple = "web-admin/ocr-simple.js"
rep(
    simple,
    'if(f.type==="BILL_DATE")return `(?<${group("BILL_DATE")}>\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})`;',
    '''if(f.type==="BILL_DATE"){
    const order=String(f.dateOrder||"DMY").toUpperCase();
    const yearDigits=Number(f.dateYearDigits||0);
    const y=yearDigits===2?"\\\\d{2}":yearDigits===4?"\\\\d{4}":"(?:\\\\d{2}|\\\\d{4})";
    const d="\\\\d{1,2}",m="\\\\d{1,2}";
    const parts=order==="MDY"?[m,d,y]:order==="YMD"?[y,m,d]:[d,m,y];
    return `(?<${group("BILL_DATE")}>${parts.join("[/-]")})`;
  }''',
)
rep(
    simple,
    '''      checks.push({ok:true,text:`${label}: วันที่ตรงรูปแบบที่กำหนด (${normalized.value})`});''',
    '''      checks.push({ok:true,text:`${label}: วันที่ตรงเงื่อนไข • เก็บเป็น ${normalized.value}`});''',
)
rep(
    simple,
    '''    }else{
      checks.push({ok:false,text:`${label}: ${normalized.warning} • อ่านได้ ${fields.BILL_DATE}`});
      validationPassed=false;
    }
''',
    '''    }else{
      checks.push({ok:false,text:`${label}: ${normalized.warning} • อ่านได้ ${fields.BILL_DATE} • ระบบจะไม่เก็บค่านี้เป็นวันที่ใช้งาน`});
      validationPassed=false;
    }
''',
)

# Admin copy: duplicate POS is a warning/block, not silent best-result selection.
index = "web-admin/index.html"
rep(
    index,
    '<label class="validationItem"><input id="noDuplicatePos" type="checkbox" checked disabled><span><strong>เครื่องห้ามซ้ำ</strong><small>APK เลือกผลที่ดีที่สุดเพียงชุดเดียวต่อ POS</small></span></label>',
    '<label class="validationItem"><input id="noDuplicatePos" type="checkbox" checked disabled><span><strong>เครื่องห้ามซ้ำ</strong><small>ถ้าพบบิลคนละชุดอ้างถึง POS เดียวกัน ระบบต้องแจ้งเตือนและไม่ให้ส่งจนตรวจแล้ว</small></span></label>',
)
rep(
    index,
    '<div class="small">ตัวอย่าง: พ.ศ. 2569 / 69 และ ค.ศ. 2026 / 26 • เมื่ออ่านผ่าน ระบบจะเก็บเป็น dd/MM/yyyy เสมอ</div>',
    '<div class="small">ตัวอย่าง: พ.ศ. 2569 / 69 และ ค.ศ. 2026 / 26 • เมื่ออ่านผ่าน ระบบจะเก็บเป็น dd/MM/yyyy เสมอ • ถ้าไม่ตรงเงื่อนไข ระบบจะแสดงค่าที่อ่านได้พร้อมคำเตือนและไม่เก็บเป็นวันที่ใช้งาน</div>',
)

# ---------------------------------------------------------------------------
# Android tests: evidence fusion now returns canonical dates and requires work date.
# Also make MB date convention explicit in the test templates.
# ---------------------------------------------------------------------------
for test_path in [
    "android-app/app/src/test/java/com/receiptocr/app/ocr/PosEvidenceFusionRound86Test.kt",
    "android-app/app/src/test/java/com/receiptocr/app/ocr/PosEvidenceFusionRound87Test.kt",
]:
    text = read(test_path)
    if "import java.time.LocalDate" not in text:
        text = text.replace("import org.junit", "import java.time.LocalDate\nimport org.junit", 1)
    text = text.replace(
        'OcrTemplateField(order = 7, type = "BILL_DATE", example = "22/08/69")',
        'OcrTemplateField(order = 7, type = "BILL_DATE", example = "22/08/69", dateOrder = "DMY", dateCalendar = "BUDDHIST", dateYearDigits = 2)'
    )
    text = text.replace(
        "allowedPos = setOf(1, 2, 3)\n        )",
        "allowedPos = setOf(1, 2, 3),\n            referenceDate = LocalDate.of(2026, 9, 2)\n        )"
    )
    text = text.replace('assertEquals("20/08/69",', 'assertEquals("20/08/2026",')
    write(test_path, text)

# Extra Round90 evidence test based on the actual diagnostic shape 20/08769.
round90_test = "android-app/app/src/test/java/com/receiptocr/app/ocr/PosEvidenceFusionRound90Test.kt"
write(round90_test, r'''package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRequiredCore
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.OcrTemplateValidation
import com.receiptocr.app.config.UniversalOcrTemplate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosEvidenceFusionRound90Test {
    private val mb02 = UniversalOcrTemplate(
        templateId = "mb02-r90",
        brandId = "brand-test",
        templateName = "Mb_02",
        recognition = OcrTemplateRecognition(
            rowCount = 1,
            rows = listOf(
                OcrTemplateRow(
                    row = 1,
                    fields = listOf(
                        OcrTemplateField(order = 1, type = "LITERAL", example = "R", literal = "R"),
                        OcrTemplateField(order = 2, type = "NUMBER_TEXT", example = "20", minLength = 2, maxLength = 2),
                        OcrTemplateField(order = 3, type = "POS_NUMBER", example = "1", minLength = 1, maxLength = 1, posDigits = 1),
                        OcrTemplateField(order = 4, type = "CUSTOMER_VALUE", example = "051846", minLength = 6, maxLength = 6),
                        OcrTemplateField(order = 5, type = "LITERAL", example = "U", literal = "U"),
                        OcrTemplateField(order = 6, type = "NUMBER_TEXT", example = "110030", minLength = 6, maxLength = 6),
                        OcrTemplateField(
                            order = 7,
                            type = "BILL_DATE",
                            example = "20/08/69",
                            dateOrder = "DMY",
                            dateCalendar = "BUDDHIST",
                            dateYearDigits = 2
                        ),
                        OcrTemplateField(order = 8, type = "BILL_TIME", example = "17:51")
                    )
                )
            )
        ),
        validation = OcrTemplateValidation(
            requiredCore = OcrTemplateRequiredCore(date = true, time = true, customerValue = true)
        )
    )

    @Test
    fun recoversNoisyThaiDateNearPos2WithoutInventingCustomer() {
        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R201657846U110030 20/06/61 36:00\nR2020390300400072 20/08769 17:18",
                "R201657846U110030 20/06/61 36:00\nR202039030U400072 20/08769 1718",
                "R2020390300400072 20/08769 17:18"
            ),
            template = mb02,
            allowedPos = setOf(1, 2, 3),
            referenceDate = LocalDate.of(2026, 9, 2)
        )

        assertTrue(result.containsKey(2))
        assertEquals("039030", result.getValue(2)["CUSTOMER_VALUE"])
        assertEquals("20/08/2026", result.getValue(2)["BILL_DATE"])
        assertEquals("17:18", result.getValue(2)["BILL_TIME"])
        assertFalse("POS1 bad evidence must not block or alter POS2", result.containsKey(1))
    }
}
''')

# ReceiptDateOcrNormalizer regression tests for noisy 2-digit BE date and canonical bypass.
date_test = "android-app/app/src/test/java/com/receiptocr/app/ocr/ReceiptDateOcrNormalizerTest.kt"
text = read(date_test)
insert = r'''
    @Test
    fun noisyThaiDateWithOneExtraDigitRecoversUsingAdminExampleAndWorkDate() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08769",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "BUDDHIST",
            dateYearDigits = 2,
            dateExample = "22/08/69"
        )
        assertEquals("20/08/2026", result.value)
        assertTrue(result.corrected)
    }

    @Test
    fun canonicalValueCanPassSecondStageWithoutBreakingSourceTwoDigitRule() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08/2026",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "BUDDHIST",
            dateYearDigits = 2,
            dateExample = "22/08/69",
            allowCanonicalInput = true
        )
        assertEquals("20/08/2026", result.value)
    }
'''
if insert.strip() not in text:
    idx = text.rfind("}")
    if idx < 0:
        raise SystemExit("ReceiptDateOcrNormalizerTest closing brace not found")
    text = text[:idx] + insert + text[idx:]
write(date_test, text)

# Admin pattern engine test: year position/length must follow Admin settings.
js_test = "tests/ocr-pattern-engine.test.js"
text = read(js_test)
needle = 'console.log("OCR pattern engine: Admin-driven CJ/L-go, noisy text, four POS and warning-value tests passed");'
extra = r'''
// Round90: วันที่ต้องจับตามลำดับและจำนวนหลักของปีที่ Admin ตั้ง ไม่ใช้ regex วันที่แบบเดียวทุกแบรนด์
const ymdRow=[
  field("BILL_DATE",{example:"2026/08/20",dateOrder:"YMD",dateCalendar:"GREGORIAN",dateYearDigits:4,minLength:10,maxLength:10}),
  field("BILL_TIME",{example:"07:55",minLength:5,maxLength:5}),
  field("LITERAL",{example:"Rcpt#10",literal:"Rcpt#10"}),
  field("POS_NUMBER",{example:"1",posDigits:1,minLength:1,maxLength:1}),
  field("CUSTOMER_VALUE",{example:"002715",minLength:6,maxLength:6})
];
const ymdOk=engine.findRecords([ymdRow],"2026/08/20 07:55 Rcpt#101002715");
assert.equal(ymdOk.records.length,1);
const ymdWrong=engine.findRecords([ymdRow],"20/08/2026 07:55 Rcpt#101002715");
assert.equal(ymdWrong.records.length,0);

'''
if extra.strip() not in text:
    text = text.replace(needle, extra + needle)
write(js_test, text)

print("Round90 source patches applied")
