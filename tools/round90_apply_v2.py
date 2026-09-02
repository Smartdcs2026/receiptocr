from pathlib import Path
import re
import runpy

path = Path(__file__).with_name("round90_apply.py")
text = path.read_text(encoding="utf-8")
start_marker = "rep(\n    simple,\n    'if(f.type===\"BILL_DATE\")return"
end_marker = "rep(\n    simple,\n    '''      checks.push"
start = text.find(start_marker)
end = text.find(end_marker, start + 1)
if start < 0 or end < 0:
    raise SystemExit("Round90 simple-fieldRegex wrapper markers not found")

# ocr-simple.js มี fieldRegex เก่าที่ไม่ได้ถูกใช้โดยหน้าทดสอบปัจจุบัน
# (หน้าทดสอบใช้ ReceiptOcrPatternEngine) จึงไม่แก้บล็อกซ้ำนี้ใน Round90
text = text[:start] + text[end:]
path.write_text(text, encoding="utf-8")
runpy.run_path(str(path), run_name="__main__")

# Python re.sub ในสคริปต์หลักตีความ backslash ของ replacement หนึ่งชั้น
# แก้เฉพาะ single \d / \s ที่หลงเหลือใน Kotlin Regex ให้เป็น \\d / \\s
# โดยไม่แตะ escape ที่ถูกต้องอยู่แล้ว
pos_path = path.parents[1] / "android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt"
pos_text = pos_path.read_text(encoding="utf-8")
pos_text = re.sub(r"(?<!\\)\\([ds])", r"\\\\\1", pos_text)
pos_path.write_text(pos_text, encoding="utf-8")

# ---------------------------------------------------------------------------
# Round90 safety rule:
# แก้วันที่อัตโนมัติได้เฉพาะเมื่อกฎ Admin + วันงานเหลือวันที่จริงเพียงคำตอบเดียว
# ห้ามเลือกคำตอบที่ใกล้วันงานที่สุดจากหลายคำตอบ เพราะจะกลายเป็นการเดาข้อมูล
# ---------------------------------------------------------------------------
date_path = path.parents[1] / "android-app/app/src/main/java/com/receiptocr/app/ocr/ReceiptDateOcrNormalizer.kt"
date_text = date_path.read_text(encoding="utf-8")

old_structured_month = '''        // กรณีโครงสร้างมี 3 ส่วนแต่เดือนถูก OCR แทรกตัวเกิน เช่น 20/28/2026
        val structuredParts = cleaned.split('/')
        if (structuredParts.size == 3) {
            val tokens = tokensByOrder(structuredParts, order)
            val day = tokens.day.toIntOrNull()
            val year = normalizeYear(tokens.year, calendar, referenceDate)
            val correctedMonth = recoverMonth(tokens.month)
            if (day != null && year != null && correctedMonth != null) {
                val correctedDate = buildDate(year, correctedMonth, day)
                if (correctedDate != null) {
                    val checked = verifyDistance(
                        date = correctedDate,
                        original = cleaned,
                        referenceDate = referenceDate,
                        maxDistanceDays = maxAutoCorrectionDistanceDays
                    )
                    if (checked.value != null) return checked.copy(corrected = true)
                }
            }
        }
'''
new_structured_month = '''        // กรณีเดือนถูก OCR แทรก/สลับเป็นเลขที่ไม่มีจริง เช่น 20/28/2026
        // จะยอมแก้ก็ต่อเมื่อการตัดตัวเลขหนึ่งหลัก + กฎ Admin + วันงาน
        // เหลือวันที่จริงเพียงคำตอบเดียวเท่านั้น
        recoverStructuredMonth(
            cleaned = cleaned,
            order = order,
            calendar = calendar,
            referenceDate = referenceDate,
            maxDistanceDays = maxAutoCorrectionDistanceDays
        )?.let { return it }
'''
if old_structured_month not in date_text:
    raise SystemExit("Round90 structured month block not found")
date_text = date_text.replace(old_structured_month, new_structured_month, 1)

old_best = '''        val bestRemoved = candidates.minOf { it.removed }
        val bestDistance = candidates.filter { it.removed == bestRemoved }.minOf { it.distance }
        val bestDates = candidates
            .filter { it.removed == bestRemoved && it.distance == bestDistance }
            .map { it.date }
            .distinct()
        if (bestDates.size != 1) return null
'''
new_best = '''        val bestRemoved = candidates.minOf { it.removed }
        val bestDates = candidates
            .filter { it.removed == bestRemoved }
            .map { it.date }
            .distinct()
        // ถ้ามีมากกว่าหนึ่งวันที่เป็นไปได้ ห้ามเลือกวันที่ใกล้วันงานที่สุด
        if (bestDates.size != 1) return null
'''
if old_best not in date_text:
    raise SystemExit("Round90 noisy-date candidate block not found")
date_text = date_text.replace(old_best, new_best, 1)

old_recover_month = '''    private fun recoverMonth(token: String): Int? {
        val digits = token.filter(Char::isDigit)
        if (digits.length != 2) return null
        val raw = digits.toIntOrNull() ?: return null
        if (raw in 1..12) return raw
        val last = digits.last().digitToIntOrNull() ?: return null
        return last.takeIf { it in 1..9 }
    }
'''
new_recover_month = '''    private fun recoverStructuredMonth(
        cleaned: String,
        order: DateOrder,
        calendar: DateCalendar,
        referenceDate: LocalDate,
        maxDistanceDays: Long
    ): Result? {
        val parts = cleaned.split('/').map { it.trim() }
        if (parts.size != 3) return null
        val tokens = tokensByOrder(parts, order)
        val day = tokens.day.toIntOrNull() ?: return null
        val year = normalizeYear(tokens.year, calendar, referenceDate) ?: return null
        val monthDigits = tokens.month.filter(Char::isDigit)
        val rawMonth = monthDigits.toIntOrNull() ?: return null
        if (rawMonth in 1..12 || monthDigits.length != 2) return null

        val candidates = monthDigits.indices
            .mapNotNull { index -> monthDigits.removeRange(index, index + 1).toIntOrNull() }
            .filter { it in 1..12 }
            .distinct()
            .mapNotNull { month -> buildDate(year, month, day) }
            .filter { date -> abs(ChronoUnit.DAYS.between(referenceDate, date)) <= maxDistanceDays }
            .distinct()

        if (candidates.size != 1) return null
        return Result(
            value = candidates.single().format(output),
            corrected = true,
            original = cleaned,
            warning = "ปรับเดือนตามเงื่อนไขที่ Admin กำหนดและวันงาน • กรุณาตรวจเทียบกับภาพ"
        )
    }
'''
if old_recover_month not in date_text:
    raise SystemExit("Round90 recoverMonth function not found")
date_text = date_text.replace(old_recover_month, new_recover_month, 1)
date_path.write_text(date_text, encoding="utf-8")

# ---------------------------------------------------------------------------
# Regression tests must enforce the same safety policy.
# 20/08769 can represent more than one calendar date, so the normalizer itself
# must not guess. Evidence fusion may still succeed when another OCR pass reads
# an exact date coherently for the same POS.
# ---------------------------------------------------------------------------
date_test_path = path.parents[1] / "android-app/app/src/test/java/com/receiptocr/app/ocr/ReceiptDateOcrNormalizerTest.kt"
date_test = date_test_path.read_text(encoding="utf-8")
date_test = date_test.replace(
'''    fun noisyThaiDateWithOneExtraDigitRecoversUsingAdminExampleAndWorkDate() {
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
''',
'''    fun noisyThaiDateWithOneExtraDigitIsNotGuessedWhenAmbiguous() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08769",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "BUDDHIST",
            dateYearDigits = 2,
            dateExample = "22/08/69"
        )
        assertNull(result.value)
        assertEquals("20/08769", result.original)
    }
''',
1,
)
date_test_path.write_text(date_test, encoding="utf-8")

fusion_test_path = path.parents[1] / "android-app/app/src/test/java/com/receiptocr/app/ocr/PosEvidenceFusionRound90Test.kt"
fusion_test = fusion_test_path.read_text(encoding="utf-8")
fusion_test = fusion_test.replace(
'"R201657846U110030 20/06/61 36:00\\nR202039030U400072 20/08769 1718",',
'"R201657846U110030 20/06/61 36:00\\nR202039030U400072 20/08/69 17:18",',
1,
)
fusion_test_path.write_text(fusion_test, encoding="utf-8")
