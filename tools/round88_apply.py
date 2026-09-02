from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"{label}: target not found")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def sub_once(path: Path, pattern: str, replacement: str, label: str, flags=0):
    text = path.read_text(encoding="utf-8")
    changed, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 replacement, got {count}")
    path.write_text(changed, encoding="utf-8")


# 1) Date normalization: understand Thai 2-digit Buddhist years and reject implausibly distant OCR dates.
date_file = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/ReceiptDateOcrNormalizer.kt"
replace_once(
    date_file,
    "val year = normalizeYear(yearToken) ?: return Result(null, original = cleaned)",
    "val year = normalizeYear(yearToken, referenceDate) ?: return Result(null, original = cleaned)",
    "date year call"
)
replace_once(
    date_file,
    '''        // ค่าที่ถูกต้องอยู่แล้ว ต้องใช้ทันที ไม่ต้องแก้หรือเดา\n        buildDate(year, month, day)?.let {\n            return Result(it.format(output), corrected = false, original = cleaned)\n        }\n''',
    '''        // แม้เป็นวันที่จริงในปฏิทิน ก็ยังต้องอยู่ใกล้วันงานพอที่จะเป็นวันที่จากบิลนี้\n        // ป้องกัน OCR อ่านปี 69/61 แล้วกลายเป็น 2069/2061 และถูกนำไปใช้ต่อ\n        buildDate(year, month, day)?.let { date ->\n            val distance = abs(ChronoUnit.DAYS.between(referenceDate, date))\n            if (distance > maxAutoCorrectionDistanceDays) {\n                return Result(null, original = cleaned)\n            }\n            return Result(date.format(output), corrected = false, original = cleaned)\n        }\n''',
    "date valid distance gate"
)
sub_once(
    date_file,
    r'''    private fun normalizeYear\(token: String\): Int\? \{.*?\n    \}\n\n    private fun buildDate''',
    '''    private fun normalizeYear(token: String, referenceDate: LocalDate): Int? {\n        val raw = token.filter(Char::isDigit).toIntOrNull() ?: return null\n        if (raw in 2400..2999) return (raw - 543).takeIf { it in 1900..2200 }\n        if (raw >= 100) return raw.takeIf { it in 1900..2200 }\n\n        // ใบเสร็จไทยพบทั้ง ค.ศ. 2 หลัก (26 = 2026) และ พ.ศ. 2 หลัก (69 = 2569 = 2026)\n        // เลือกปีที่ใกล้วันงานที่สุด แทนการบังคับว่าเลข < 70 ต้องเป็น 20xx เสมอ\n        val candidates = listOf(\n            2000 + raw,\n            1900 + raw,\n            2500 + raw - 543\n        ).filter { it in 1900..2200 }.distinct()\n        return candidates.minByOrNull { abs(it - referenceDate.year) }\n    }\n\n    private fun buildDate''',
    "date normalizeYear",
    flags=re.S
)

# 2) Strict interpreter has its own 2-digit year conversion; align it with work-date context.
interp = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/UniversalTemplateInterpreter.kt"
sub_once(
    interp,
    r'''    private fun normalizeDate\(raw: String, referenceDate: LocalDate\): String\? \{.*?\n    \}\n\n    private fun parseDate''',
    '''    private fun normalizeDate(raw: String, referenceDate: LocalDate): String? {\n        val parts = raw.trim().replace('.', '/').replace('-', '/').split('/')\n        if (parts.size != 3) return null\n        val a = parts[0].toIntOrNull() ?: return null\n        val b = parts[1].toIntOrNull() ?: return null\n        val rawYear = parts[2].toIntOrNull() ?: return null\n\n        val years = when {\n            rawYear in 2400..2999 -> listOf(rawYear - 543)\n            rawYear < 100 -> listOf(\n                2000 + rawYear,\n                1900 + rawYear,\n                2500 + rawYear - 543\n            ).filter { it in 1900..2200 }.distinct()\n            else -> listOf(rawYear)\n        }\n\n        val candidates = mutableListOf<LocalDate>()\n        fun addCandidate(year: Int, day: Int, month: Int) {\n            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let(candidates::add)\n        }\n        years.forEach { year ->\n            when {\n                a > 12 && b in 1..12 -> addCandidate(year, a, b)\n                b > 12 && a in 1..12 -> addCandidate(year, b, a)\n                else -> {\n                    addCandidate(year, a, b)\n                    if (a != b) addCandidate(year, b, a)\n                }\n            }\n        }\n\n        // รูปแบบบิลในระบบอนุญาตวันที่ใกล้วันงานเท่านั้น (สูงสุดยังต่ำกว่า 45 วัน)\n        // จึงไม่ยอมให้ปีที่อ่านเพี้ยนแต่ยังเป็นวันที่จริง เช่น 2061 ผ่านเป็น core field\n        return candidates.distinct()\n            .filter { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(referenceDate, it)) <= 45 }\n            .minByOrNull { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(referenceDate, it)) }\n            ?.format(outDate)\n    }\n\n    private fun parseDate''',
    "interpreter normalizeDate",
    flags=re.S
)

# 3) Real pipeline: partial strict/fusion results must not stop other readers from filling missing POS.
pipeline = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt"
sub_once(
    pipeline,
    r'''        // Round86: ถ้า strict ไม่ผ่าน.*?        val templateResult = evidenceFusion\?\.takeIf \{ it\.detectedPos\.isNotEmpty\(\) \}\n            \?: sequenceFallback\?\.takeIf \{ it\.detectedPos\.isNotEmpty\(\) \}\n            \?: strictTemplateResult\n''',
    '''        // Round88: อย่าหยุดเพียงเพราะตัวอ่านหนึ่งวิธีพบ POS บางเครื่อง\n        // ทุกวิธีมีหน้าที่ช่วยเติมเฉพาะ POS ที่ยังขาด/ยังไม่ครบ แล้วค่อยรวมผลตาม POS\n        val expectedPosSet = records.map { it.posNumber }.toSet()\n        val evidenceFusion = if (templates.isNotEmpty() && needsTemplateHelp(strictTemplateResult, expectedPosSet)) {\n            PosEvidenceFusion.apply(\n                rawTexts = mlTexts.map { it.text },\n                records = records,\n                work = work,\n                imagePath = imagePath,\n                templates = templates\n            )\n        } else null\n        val afterFusion = mergeUniversalTemplateResults(records, strictTemplateResult, evidenceFusion)\n\n        val sequenceFallback = if (templates.isNotEmpty() && needsTemplateHelp(afterFusion, expectedPosSet)) {\n            TemplateSequenceFallback.apply(\n                rawTexts = mlTexts.map { it.text },\n                records = records,\n                work = work,\n                imagePath = imagePath,\n                templates = templates\n            )\n        } else null\n        val templateResult = mergeUniversalTemplateResults(records, afterFusion, sequenceFallback)\n''',
    "pipeline fallback gating",
    flags=re.S
)

sub_once(
    pipeline,
    r'''        val combinedRecords = accumulation\.records\.map \{ record ->.*?\n        \}\n\n        if \(currentDetectedPos\.isNotEmpty\(\)\) \{''',
    '''        val combinedRecords = accumulation.records.map { record ->\n            val original = recordsForAccumulation.firstOrNull { it.posNumber == record.posNumber } ?: record\n            val currentImagePos = record.posNumber in currentDetectedSet\n            val rawCandidateDate = record.billDate.trim()\n            val dateResult = if (currentImagePos && rawCandidateDate.isNotBlank()) {\n                ReceiptDateOcrNormalizer.normalize(\n                    raw = rawCandidateDate,\n                    configuredFormat = dateFormat,\n                    referenceDate = workDate\n                )\n            } else null\n            val storeId = mergeStoreId(\n                original = original,\n                candidateStoreId = currentStoreIdsByPos[record.posNumber].orEmpty(),\n                isCurrentPos = currentImagePos\n            )\n            val safeExistingDate = if (!original.source.startsWith("OCR", ignoreCase = true)) original.billDate else ""\n            val acceptedDate = when {\n                dateResult?.value != null -> dateResult.value\n                currentImagePos -> safeExistingDate\n                else -> record.billDate\n            }\n            val dateWarning = when {\n                !currentImagePos || rawCandidateDate.isBlank() -> ""\n                dateResult?.value == null ->\n                    "วันที่ที่อ่านจากภาพ ($rawCandidateDate) ห่างจากวันงานมากผิดปกติหรือไม่ใช่วันที่จริง จึงยังไม่นำมาใช้"\n                dateResult.corrected ->\n                    "วันที่ที่อ่านจากภาพ ${dateResult.original} ถูกปรับเป็น ${dateResult.value} ตามตำแหน่งวัน/เดือน กรุณาตรวจเทียบกับภาพ"\n                else -> ""\n            }\n            val inheritedWarning = sanitizeLegacyOcrWarnings(record.ocrWarnings)\n            record.copy(\n                billDate = acceptedDate,\n                ocrStoreId = storeId,\n                ocrWarnings = listOf(inheritedWarning, dateWarning)\n                    .map { it.trim() }\n                    .filter { it.isNotBlank() }\n                    .distinct()\n                    .joinToString(" • ")\n            )\n        }\n\n        if (currentDetectedPos.isNotEmpty()) {''',
    "pipeline date acceptance",
    flags=re.S
)

# Add merge helpers before existing metadata helper.
pipeline_text = pipeline.read_text(encoding="utf-8")
marker = "    private fun stampOcrMetadata("
if marker not in pipeline_text:
    raise SystemExit("pipeline helper marker not found")
helpers = '''    internal fun needsTemplateHelp(\n        result: UniversalTemplateResult,\n        expectedPos: Set<Int>\n    ): Boolean {\n        val detected = result.detectedPos.toSet()\n        return expectedPos.any { pos ->\n            pos !in detected || result.records\n                .firstOrNull { it.posNumber == pos }\n                ?.let(OcrAccumulationPolicy::isCoreComplete) != true\n        }\n    }\n\n    internal fun mergeUniversalTemplateResults(\n        originals: List<PosRecord>,\n        primary: UniversalTemplateResult,\n        supplement: UniversalTemplateResult?\n    ): UniversalTemplateResult {\n        if (supplement == null || supplement.detectedPos.isEmpty()) return primary\n        if (primary.detectedPos.isEmpty()) return supplement\n\n        val primaryPos = primary.detectedPos.toSet()\n        val supplementPos = supplement.detectedPos.toSet()\n        val detected = (primaryPos + supplementPos).sorted()\n        val sourceByPos = linkedMapOf<Int, String>()\n\n        fun record(result: UniversalTemplateResult, positions: Set<Int>, pos: Int): PosRecord? =\n            if (pos in positions) result.records.firstOrNull { it.posNumber == pos } else null\n\n        fun warnings(result: UniversalTemplateResult, pos: Int): List<String> =\n            result.validationWarnings[pos].orEmpty() +\n                result.records.firstOrNull { it.posNumber == pos }\n                    ?.ocrWarnings\n                    .orEmpty()\n                    .split(" • ")\n                    .filter { it.isNotBlank() }\n\n        fun strong(result: UniversalTemplateResult, candidate: PosRecord, pos: Int): Boolean =\n            OcrAccumulationPolicy.isCoreComplete(candidate) && warnings(result, pos).isEmpty()\n\n        val mergedRecords = originals.map { original ->\n            val pos = original.posNumber\n            val p = record(primary, primaryPos, pos)\n            val s = record(supplement, supplementPos, pos)\n            when {\n                p == null && s == null -> original\n                p == null -> { sourceByPos[pos] = "S"; s!! }\n                s == null -> { sourceByPos[pos] = "P"; p }\n                strong(primary, p, pos) -> { sourceByPos[pos] = "P"; p }\n                OcrAccumulationPolicy.isCoreComplete(s) -> { sourceByPos[pos] = "S"; s }\n                else -> {\n                    sourceByPos[pos] = "M"\n                    p.copy(\n                        customerNo = p.customerNo.ifBlank { s.customerNo },\n                        billDate = p.billDate.ifBlank { s.billDate },\n                        billTime = p.billTime.ifBlank { s.billTime },\n                        noReceipt = false,\n                        noReceiptReason = "",\n                        source = if (s.ocrSourceImagePath.isNotBlank()) s.source else p.source,\n                        ocrSourceImagePath = p.ocrSourceImagePath.ifBlank { s.ocrSourceImagePath },\n                        ocrWarnings = listOf(p.ocrWarnings, s.ocrWarnings)\n                            .filter { it.isNotBlank() }.distinct().joinToString(" • "),\n                        ocrStoreId = p.ocrStoreId.ifBlank { s.ocrStoreId },\n                        ocrStoreIdExpected = p.ocrStoreIdExpected || s.ocrStoreIdExpected,\n                        ocrCounterCycle = p.ocrCounterCycle.ifBlank { s.ocrCounterCycle }\n                    )\n                }\n            }\n        }\n\n        fun fieldAt(result: UniversalTemplateResult, type: String, pos: Int): String {\n            val index = result.detectedPos.indexOf(pos)\n            if (index < 0) return ""\n            return result.extracted[type].orEmpty().getOrNull(index).orEmpty()\n        }\n\n        val fieldTypes = (primary.extracted.keys + supplement.extracted.keys).toSet()\n        val extracted = linkedMapOf<String, List<String>>()\n        fieldTypes.forEach { type ->\n            extracted[type] = detected.map { pos ->\n                val p = fieldAt(primary, type, pos)\n                val s = fieldAt(supplement, type, pos)\n                when (sourceByPos[pos]) {\n                    "S" -> s.ifBlank { p }\n                    else -> p.ifBlank { s }\n                }\n            }\n        }\n\n        val validationWarnings = linkedMapOf<Int, List<String>>()\n        detected.forEach { pos ->\n            val combined = (primary.validationWarnings[pos].orEmpty() +\n                supplement.validationWarnings[pos].orEmpty()).distinct()\n            if (combined.isNotEmpty()) validationWarnings[pos] = combined\n        }\n\n        val names = listOf(primary.templateName, supplement.templateName)\n            .filterNotNull()\n            .flatMap { it.split(" / ") }\n            .map { it.trim() }\n            .filter { it.isNotBlank() }\n            .distinct()\n\n        return UniversalTemplateResult(\n            records = mergedRecords,\n            message = "รวมผลอ่านจากหลายวิธี • พบ ${detected.size} เครื่อง",\n            templateName = names.joinToString(" / ").ifBlank { null },\n            detectedPos = detected,\n            extracted = extracted,\n            validationWarnings = validationWarnings,\n            usedUniversalTemplate = primary.usedUniversalTemplate || supplement.usedUniversalTemplate\n        )\n    }\n\n'''
pipeline.write_text(pipeline_text.replace(marker, helpers + marker, 1), encoding="utf-8")

# 4) Active Admin: POS list means actual POS identifiers, never numeric range 1..count.
admin_html = ROOT / "web-admin/index.html"
replace_once(
    admin_html,
    '''          <label>จำนวนเครื่องของร้าน\n            <input id="testPosCount" type="number" min="1" max="99" value="5">\n          </label>''',
    '''          <label>เครื่องที่ร้านนี้มี\n            <input id="testAllowedPos" placeholder="เช่น 1,2,3" value="1,2,3">\n            <span class="small">กรอกหมายเลขเครื่องจริง คั่นด้วยเครื่องหมายจุลภาค</span>\n          </label>''',
    "admin POS test field"
)
replace_once(admin_html, '<script src="ocr-simple.js?v=68"></script>', '<script src="ocr-simple.js?v=88"></script>', "admin cache version")

admin_js = ROOT / "web-admin/ocr-simple.js"
replace_once(
    admin_js,
    '''    await SwalSmall.ok("บันทึกรูปแบบแล้ว",t.templateName);\n    $("editorPanel").classList.add("hidden");editing=null;await loadPatterns();''',
    '''    await SwalSmall.ok("บันทึกรูปแบบแล้ว",t.templateName);\n    // บันทึกแล้วอยู่หน้าเดิมต่อ เพื่อให้ตรวจ/แก้รูปแบบซ้ำได้ทันที\n    await loadPatterns();''',
    "admin save stays open"
)
admin_text = admin_js.read_text(encoding="utf-8")
admin_text = admin_text.replace('$("testPosCount").value="5";', '$("testAllowedPos").value="1,2,3,4,5";')
admin_text = admin_text.replace('$("testPosCount").value="3";', '$("testAllowedPos").value="1,2,3";')
old_pos_validation = '''  const posCount=Number($("testPosCount").value||0);\n  if(editing.validation.mustMatchPos&&posCount&&fields.POS_NUMBER){\n    const n=posNumberValue(fields.POS_NUMBER);\n    const ok=n!==null && n>=1 && n<=posCount;\n    checks.push({ok,text:ok?`${label}: หมายเลขเครื่องอยู่ในช่วง (${fields.POS_NUMBER})`:`${label}: หมายเลขเครื่อง ${fields.POS_NUMBER} ไม่อยู่ในช่วง 1-${posCount}`});\n    if(!ok)validationPassed=false;\n  }'''
new_pos_validation = '''  const allowedPos=String($("testAllowedPos")?.value||"")\n    .split(/[,;\\s]+/).map(value=>Number(value)).filter(value=>Number.isInteger(value)&&value>0);\n  if(editing.validation.mustMatchPos&&allowedPos.length&&fields.POS_NUMBER){\n    const n=posNumberValue(fields.POS_NUMBER);\n    const ok=n!==null&&allowedPos.includes(n);\n    checks.push({ok,text:ok?`${label}: หมายเลขเครื่องตรงกับรายการ (${fields.POS_NUMBER})`:`${label}: หมายเลขเครื่อง ${fields.POS_NUMBER} ไม่อยู่ในรายการ ${allowedPos.join(", ")}`});\n    if(!ok)validationPassed=false;\n  }'''
if old_pos_validation not in admin_text:
    raise SystemExit("admin POS validation target not found")
admin_text = admin_text.replace(old_pos_validation, new_pos_validation, 1)
if "testPosCount" in admin_text:
    raise SystemExit("admin still contains testPosCount")
admin_js.write_text(admin_text, encoding="utf-8")

# 5) Let testers open the already-captured OCR text even when a partial result can be reviewed.
ui = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt"
ui_text = ui.read_text(encoding="utf-8")
start = ui_text.index("    pendingOcrResult?.let { proposal ->")
target = '''            dismissButton = {\n                TextButton(onClick = { pendingOcrResult = null }) { Text("ยกเลิก") }\n            }'''
pos = ui_text.find(target, start)
if pos < 0:
    raise SystemExit("pending OCR dismiss block not found")
replacement = '''            dismissButton = {\n                Row(verticalAlignment = Alignment.CenterVertically) {\n                    TextButton(onClick = { ocrReadDetailsOpen = true }) { Text("ดูข้อความที่อ่านได้") }\n                    TextButton(onClick = { pendingOcrResult = null }) { Text("ยกเลิก") }\n                }\n            }'''
ui_text = ui_text[:pos] + replacement + ui_text[pos + len(target):]
ui.write_text(ui_text, encoding="utf-8")

# 6) Regression tests for MB real row and Thai 2-digit year.
normalizer_test = ROOT / "android-app/app/src/test/java/com/receiptocr/app/ocr/ReceiptDateOcrNormalizerTest.kt"
text = normalizer_test.read_text(encoding="utf-8")
insert = '''\n    @Test\n    fun thaiTwoDigitBuddhistYear69MapsTo2026NearWorkDate() {\n        val result = ReceiptDateOcrNormalizer.normalize(\n            raw = "20/08/69",\n            configuredFormat = "DD/MM/YY",\n            referenceDate = LocalDate.of(2026, 9, 2)\n        )\n\n        assertEquals("20/08/2026", result.value)\n        assertFalse(result.corrected)\n    }\n\n    @Test\n    fun distantTwoDigitYearMisreadIsRejectedInsteadOfBecoming2061() {\n        val result = ReceiptDateOcrNormalizer.normalize(\n            raw = "20/06/61",\n            configuredFormat = "DD/MM/YY",\n            referenceDate = LocalDate.of(2026, 9, 2)\n        )\n\n        assertNull(result.value)\n    }\n'''
idx = text.rfind("}\n")
if idx < 0:
    raise SystemExit("normalizer test closing brace not found")
normalizer_test.write_text(text[:idx] + insert + text[idx:], encoding="utf-8")

web_test = ROOT / "tests/ocr-pattern-engine.test.js"
text = web_test.read_text(encoding="utf-8")
marker = 'console.log("OCR pattern engine: Admin-driven CJ/L-go, noisy text, four POS and warning-value tests passed");'
if marker not in text:
    raise SystemExit("web test marker not found")
mb_tests = '''// MB: POS คือหลักสุดท้ายของรหัส 3 หลักหลัง R ไม่ใช่เลข 3 หลักทั้งชุด\nconst mbRow=[\n  field("LITERAL",{example:"R",literal:"R",minLength:1,maxLength:1}),\n  field("NUMBER_TEXT",{example:"20",minLength:2,maxLength:2}),\n  field("POS_NUMBER",{example:"1",posDigits:1,minLength:1,maxLength:1}),\n  field("CUSTOMER_VALUE",{example:"051846",minLength:6,maxLength:6}),\n  field("LITERAL",{example:"U",literal:"U",minLength:1,maxLength:1}),\n  field("NUMBER_TEXT",{example:"110030",minLength:6,maxLength:6}),\n  field("BILL_DATE",{example:"20/08/69",minLength:8,maxLength:8}),\n  field("BILL_TIME",{example:"17:51",minLength:5,maxLength:5})\n];\nconst mb=engine.findRecords([mbRow],[\n  "R201051846U110030 20/08/69 17:51",\n  "R202039030U400072 20/08/69 17:18"\n].join("\\n"));\nassert.equal(mb.records.length,2);\nassert.deepEqual(mb.records.map(record=>record.fields.POS_NUMBER),["1","2"]);\nassert.deepEqual(mb.records.map(record=>record.fields.CUSTOMER_VALUE),["051846","039030"]);\nassert.deepEqual(mb.records.map(record=>record.fields.BILL_DATE),["20/08/69","20/08/69"]);\nassert.deepEqual(mb.records.map(record=>record.fields.BILL_TIME),["17:51","17:18"]);\n\n'''
web_test.write_text(text.replace(marker, mb_tests + marker, 1), encoding="utf-8")

merge_test = ROOT / "android-app/app/src/test/java/com/receiptocr/app/ocr/TemplateResultMergeRound88Test.kt"
merge_test.write_text('''package com.receiptocr.app.ocr\n\nimport com.receiptocr.app.model.PosRecord\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass TemplateResultMergeRound88Test {\n    private val originals = listOf(PosRecord(1), PosRecord(2), PosRecord(3))\n\n    @Test\n    fun partialReaderMustNotBlockAnotherReaderFromAddingPos2() {\n        val primary = UniversalTemplateResult(\n            records = listOf(\n                PosRecord(1, customerNo = "051846", billDate = "20/08/2026", billTime = "17:51", source = "OCR-TEMPLATE"),\n                PosRecord(2), PosRecord(3)\n            ),\n            message = "primary",\n            detectedPos = listOf(1),\n            usedUniversalTemplate = true\n        )\n        val supplement = UniversalTemplateResult(\n            records = listOf(\n                PosRecord(1),\n                PosRecord(2, customerNo = "039030", billDate = "20/08/69", billTime = "17:18", source = "OCR-EVIDENCE"),\n                PosRecord(3)\n            ),\n            message = "supplement",\n            detectedPos = listOf(2),\n            usedUniversalTemplate = true\n        )\n\n        val merged = RealOcrPipeline.mergeUniversalTemplateResults(originals, primary, supplement)\n        assertEquals(listOf(1, 2), merged.detectedPos)\n        assertEquals("051846", merged.records.first { it.posNumber == 1 }.customerNo)\n        assertEquals("039030", merged.records.first { it.posNumber == 2 }.customerNo)\n        assertTrue(RealOcrPipeline.needsTemplateHelp(merged, setOf(1, 2, 3)))\n    }\n\n    @Test\n    fun noMoreHelpNeededWhenEveryExpectedPosIsComplete() {\n        val complete = UniversalTemplateResult(\n            records = listOf(\n                PosRecord(1, "1", "20/08/2026", "10:00"),\n                PosRecord(2, "2", "20/08/2026", "10:01"),\n                PosRecord(3, "3", "20/08/2026", "10:02")\n            ),\n            message = "complete",\n            detectedPos = listOf(1, 2, 3),\n            usedUniversalTemplate = true\n        )\n        assertFalse(RealOcrPipeline.needsTemplateHelp(complete, setOf(1, 2, 3)))\n    }\n}\n''', encoding="utf-8")

# Version Round88.
build = ROOT / "android-app/app/build.gradle.kts"
build_text = build.read_text(encoding="utf-8")
build_text = build_text.replace("versionCode = 88", "versionCode = 89")
build_text = build_text.replace('versionName = "0.87.0"', 'versionName = "0.88.0"')
if "versionCode = 89" not in build_text or 'versionName = "0.88.0"' not in build_text:
    raise SystemExit("Round88 version bump failed")
build.write_text(build_text, encoding="utf-8")

print("Round88 source patch applied")
