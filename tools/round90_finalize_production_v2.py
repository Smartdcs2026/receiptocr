from pathlib import Path
import re


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def replace_once(text, old, new, label):
    count = text.count(old)
    require(count == 1, f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


def replace_function(text, start_signature, next_signature, new_function, label):
    start = text.find(start_signature)
    require(start >= 0, f"{label}: start not found")
    end = text.find(next_signature, start + len(start_signature))
    require(end >= 0, f"{label}: next function not found")
    return text[:start] + new_function.rstrip() + "\n\n" + text[end:]


# 1) Central date normalizer --------------------------------------------------
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/ReceiptDateOcrNormalizer.kt"
s = read(path)
if "private fun recoverPartiallySeparatedDate(" not in s:
    call_anchor = '''        // กรณีเดือนถูก OCR แทรก/สลับเป็นเลขที่ไม่มีจริง เช่น 20/28/2026
'''
    call = '''        // OCR ของบิลความร้อนอาจทำตัวคั่นหายหนึ่งตำแหน่ง เช่น 20/0869 หรือ 20/08769
        // รักษาขอบเขตที่ยังอ่านได้ แล้วแก้เฉพาะ token ที่รวมกันตามจำนวนหลักจาก Admin
        recoverPartiallySeparatedDate(
            cleaned = cleaned,
            order = order,
            calendar = calendar,
            configuredYearDigits = dateYearDigits,
            referenceDate = referenceDate,
            maxDistanceDays = maxAutoCorrectionDistanceDays
        )?.let { return it }

'''
    require(call_anchor in s, "date recovery insertion anchor missing")
    s = s.replace(call_anchor, call + call_anchor, 1)
    helper_anchor = "    private fun recoverNoisyDate(\n"
    helper = '''    private fun recoverPartiallySeparatedDate(
        cleaned: String,
        order: DateOrder,
        calendar: DateCalendar,
        configuredYearDigits: Int,
        referenceDate: LocalDate,
        maxDistanceDays: Long
    ): Result? {
        val visibleParts = cleaned.split('/').map { it.trim() }
        if (visibleParts.size != 2 || visibleParts.any { it.isBlank() || !it.all(Char::isDigit) }) return null

        val yearLengths = when (configuredYearDigits) {
            2 -> listOf(2)
            4 -> listOf(4)
            else -> listOf(2, 4)
        }
        val accepted = linkedSetOf<LocalDate>()

        fun accept(parts: List<String>) {
            if (parts.size != 3) return
            val tokens = tokensByOrder(parts, order)
            val day = tokens.day.toIntOrNull() ?: return
            val month = tokens.month.toIntOrNull() ?: return
            val year = normalizeYear(tokens.year, calendar, referenceDate) ?: return
            val date = buildDate(year, month, day) ?: return
            val distance = abs(ChronoUnit.DAYS.between(referenceDate, date))
            if (distance <= maxDistanceDays) accepted += date
        }

        yearLengths.forEach { yearLength ->
            val lengths = layoutFor(order, yearLength)
            val left = visibleParts[0]
            val right = visibleParts[1]

            if (left.length == lengths[0] &&
                right.length >= lengths[1] + lengths[2] &&
                right.length <= lengths[1] + lengths[2] + 2) {
                val second = right.take(lengths[1])
                val thirdRaw = right.drop(lengths[1])
                shrinkToLength(thirdRaw, lengths[2]).forEach { third ->
                    accept(listOf(left, second, third))
                }
            }

            if (right.length == lengths[2] &&
                left.length >= lengths[0] + lengths[1] &&
                left.length <= lengths[0] + lengths[1] + 2) {
                val first = left.take(lengths[0])
                val secondRaw = left.drop(lengths[0])
                shrinkToLength(secondRaw, lengths[1]).forEach { second ->
                    accept(listOf(first, second, right))
                }
            }
        }

        if (accepted.size != 1) return null
        return Result(
            value = accepted.single().format(output),
            corrected = true,
            original = cleaned,
            warning = "เติม/ปรับตัวคั่นวันที่ตามลำดับและจำนวนหลักที่ Admin กำหนด"
        )
    }

'''
    require(helper_anchor in s, "date recovery helper anchor missing")
    s = s.replace(helper_anchor, helper + helper_anchor, 1)
write(path, s)


# 2) POS evidence fusion ------------------------------------------------------
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt"
s = read(path)
s = s.replace('            "BILL_DATE" -> capture("BILL_DATE", datePattern(sample))',
              '            "BILL_DATE" -> capture("BILL_DATE", datePattern(field))')
s = s.replace("                'U', 'u', 'V', 'v' -> \"[UuVv]\"",
              "                'U', 'u', 'V', 'v' -> \"[UuVvOo0]\"")
new_find_date = '''    private fun findDate(
        text: String,
        field: OcrTemplateField,
        referenceDate: LocalDate
    ): Pair<String, IntRange>? {
        val layouts = dateLayouts(field)

        fun accept(raw: String, range: IntRange): Pair<String, IntRange>? {
            val normalized = ReceiptDateOcrNormalizer.normalizeForField(
                raw = raw,
                field = field,
                referenceDate = referenceDate
            )
            return normalized.value?.let { it to range }
        }

        layouts.forEach { lengths ->
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
        }

        // OCR บิลความร้อนอาจทำ '/' หายหรือมีเลขแทรก เช่น 20/08769
        // ส่งค่าดิบให้ date normalizer ตัดสินด้วยกฎ Admin + วันงาน
        val noisy = Regex(
            "(?<![0-9OoIl|SsZzBbGg])(?:$DIGIT\\s*){1,4}\\s*[./-]\\s*(?:$DIGIT\\s*){2,8}(?![0-9OoIl|SsZzBbGg])",
            RegexOption.IGNORE_CASE
        )
        noisy.findAll(text).forEach { match ->
            accept(match.value, match.range)?.let { return it }
        }
        return null
    }'''
s = replace_function(s, "    private fun findDate(\n", "    private fun findTime(", new_find_date, "fusion findDate")
new_date_helpers = '''    private fun dateLayouts(field: OcrTemplateField): List<List<Int>> {
        val order = field.dateOrder.trim().uppercase().let {
            if (it in setOf("DMY", "MDY", "YMD")) it else "DMY"
        }
        val yearLengths = when (field.dateYearDigits) {
            2 -> listOf(2)
            4 -> listOf(4)
            else -> listOf(2, 4)
        }
        return yearLengths.map { yearLength ->
            when (order) {
                "YMD" -> listOf(yearLength, 2, 2)
                else -> listOf(2, 2, yearLength)
            }
        }
    }

    private fun datePattern(field: OcrTemplateField): String = dateLayouts(field)
        .joinToString("|", "(?:", ")") { lengths ->
            "${fixedDigits(lengths[0])}\\s*[./-]\\s*${fixedDigits(lengths[1])}\\s*[./-]\\s*${fixedDigits(lengths[2])}"
        }'''
s = replace_function(s, "    private fun datePattern(", "    private fun timePattern(", new_date_helpers, "fusion date helpers")
write(path, s)


# 3) Sequence fallback --------------------------------------------------------
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/TemplateSequenceFallback.kt"
s = read(path)
s = s.replace("                'U', 'u', 'V', 'v' -> \"[UuVv]\"",
              "                'U', 'u', 'V', 'v' -> \"[UuVvOo0]\"")
new_sequence_date = '''    private fun datePattern(field: OcrTemplateField): String {
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
            val first = fixedDigits(lengths[0])
            val second = fixedDigits(lengths[1])
            val third = fixedDigits(lengths[2])
            val exact = "$first\\s*[./-]\\s*$second\\s*[./-]\\s*$third"
            val mergedTail = "$first\\s*[./-]\\s*${rangedDigits(lengths[1] + lengths[2], lengths[1] + lengths[2] + 2)}"
            val mergedHead = "${rangedDigits(lengths[0] + lengths[1], lengths[0] + lengths[1] + 2)}\\s*[./-]\\s*$third"
            "(?:$exact|$mergedTail|$mergedHead)"
        }
    }'''
s = replace_function(s, "    private fun datePattern(field: OcrTemplateField)", "    private fun timePattern(", new_sequence_date, "sequence datePattern")
write(path, s)


# 4) Strict interpreter -------------------------------------------------------
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/UniversalTemplateInterpreter.kt"
s = read(path)
s = s.replace('            "BILL_DATE" -> capture("BILL_DATE", "$OCR_DIGIT{1,2}[./-]$OCR_DIGIT{1,2}[./-]$OCR_DIGIT{2,4}")',
              '            "BILL_DATE" -> capture("BILL_DATE", datePattern(field))')
s = s.replace('            // อ่านเลขเต็มก่อน แล้วใช้เงื่อนไขเป็นคำเตือนภายหลัง เพื่อไม่ตัดเลขท้ายทิ้ง\n            "CUSTOMER_VALUE" -> capture("CUSTOMER_VALUE", "$OCR_DIGIT{1,18}(?!$OCR_DIGIT)")',
              '            // จำนวนหลักของลูกค้ามาจาก Admin เพื่อรักษาขอบเขตช่องถัดไป\n            "CUSTOMER_VALUE" -> capture("CUSTOMER_VALUE", "$OCR_DIGIT{$min,$max}(?!$OCR_DIGIT)")')
if "    private fun datePattern(field: OcrTemplateField): String {" not in s:
    helper = '''    private fun datePattern(field: OcrTemplateField): String {
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

'''
    anchor = "    private fun literalPattern(raw: String): String? {\n"
    require(anchor in s, "strict literalPattern anchor missing")
    s = s.replace(anchor, helper + anchor, 1)
s = s.replace("                    '1' -> \"[1Iil|]\"\n                    else -> Regex.escape(character.toString())",
              "                    '1' -> \"[1Iil|]\"\n                    'U', 'u', 'V', 'v' -> \"[UuVvOo0]\"\n                    else -> Regex.escape(character.toString())")
old_score = '        val date = fields["BILL_DATE"]?.let { normalizeDate(it, workDate) }\n'
if old_score in s:
    s = s.replace(old_score, '''        val scoreDateField = template.recognition.rows.asSequence()
            .flatMap { it.fields.asSequence() }
            .firstOrNull { it.type == "BILL_DATE" }
        val date = fields["BILL_DATE"]?.let {
            ReceiptDateOcrNormalizer.normalizeForField(it, scoreDateField, workDate).value
        }
''', 1)
write(path, s)


# 5) Tests --------------------------------------------------------------------
path = "android-app/app/src/test/java/com/receiptocr/app/ocr/ReceiptDateOcrNormalizerTest.kt"
s = read(path)
start = s.find("    @Test\n    fun noisyThaiDateWithOneExtraDigitIsNotGuessedWhenAmbiguous()")
require(start >= 0, "old noisy date test not found")
end = s.find("    @Test\n", start + 10)
require(end >= 0, "next date test not found")
replacement = '''    @Test
    fun partiallySeparatedThaiDatePreservesAdminDayMonthBoundary() {
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
        assertEquals("20/08769", result.original)
    }

    @Test
    fun missingSecondSeparatorUsesAdminLengths() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/0869",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "BUDDHIST",
            dateYearDigits = 2
        )
        assertEquals("20/08/2026", result.value)
        assertTrue(result.corrected)
    }

'''
s = s[:start] + replacement + s[end:]
write(path, s)

path = "android-app/app/src/test/java/com/receiptocr/app/ocr/TemplateSequenceDiagnosticsRound85Test.kt"
s = read(path)
if "round90UsesAdminSlotsWhenUIsReadAsOAndDateLosesSeparator" not in s:
    insert = '''
    @Test
    fun round90UsesAdminSlotsWhenUIsReadAsOAndDateLosesSeparator() {
        val detail = TemplateSequenceFallback.diagnose(
            rawTexts = listOf("R202039030O400072 20/08769 17:18"),
            templates = listOf(mb02)
        ).joinToString(" ")
        assertTrue(detail.contains("อ่านลำดับครบ"))
    }

    @Test
    fun round90UsesAdminSlotsWhenUIsReadAsZero() {
        val detail = TemplateSequenceFallback.diagnose(
            rawTexts = listOf("R2020390300400072 20/0869 17:18"),
            templates = listOf(mb02)
        ).joinToString(" ")
        assertTrue(detail.contains("อ่านลำดับครบ"))
    }

    @Test
    fun round90RejectsShiftedCustomerLengthInsteadOfMovingFieldBoundary() {
        val detail = TemplateSequenceFallback.diagnose(
            rawTexts = listOf("R20203903U400072 20/08/69 17:18"),
            templates = listOf(mb02)
        ).joinToString(" ")
        assertFalse(detail.contains("อ่านลำดับครบ"))
    }
'''
    pos = s.rfind("\n}")
    require(pos >= 0, "sequence test class end not found")
    s = s[:pos] + insert + s[pos:]
write(path, s)

path = "android-app/app/src/test/java/com/receiptocr/app/ocr/PosEvidenceFusionRound90Test.kt"
s = read(path)
if "resolvesMb02WhenAllPassesHaveLiteralOrDateOcrNoise" not in s:
    insert = '''
    @Test
    fun resolvesMb02WhenAllPassesHaveLiteralOrDateOcrNoise() {
        val result = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf(
                "R2020390300400072 20/08769 17:18",
                "R202039030O400072 20/08769 17:18",
                "R202039030V400072 20/0869 17:18"
            ),
            template = mb02,
            allowedPos = setOf(1, 2, 3),
            referenceDate = LocalDate.of(2026, 9, 2)
        )

        assertTrue(result.containsKey(2))
        assertEquals("039030", result.getValue(2)["CUSTOMER_VALUE"])
        assertEquals("20/08/2026", result.getValue(2)["BILL_DATE"])
        assertEquals("17:18", result.getValue(2)["BILL_TIME"])
    }
'''
    pos = s.rfind("\n}")
    require(pos >= 0, "fusion test class end not found")
    s = s[:pos] + insert + s[pos:]
write(path, s)


# 6) Admin UI -----------------------------------------------------------------
path = "web-admin/index.html"
s = read(path)
if 'id="dateFormatPreview"' not in s:
    anchor = '        <div class="small">ตัวอย่าง: พ.ศ. 2569 / 69 และ ค.ศ. 2026 / 26 • เมื่ออ่านผ่าน ระบบจะเก็บเป็น dd/MM/yyyy เสมอ • ถ้าไม่ตรงเงื่อนไข ระบบจะแสดงค่าที่อ่านได้พร้อมคำเตือนและไม่เก็บเป็นวันที่ใช้งาน</div>\n'
    require(anchor in s, "Admin date preview anchor missing")
    s = s.replace(anchor, anchor + '        <div id="dateFormatPreview" class="small"><strong>รูปแบบที่เลือก:</strong> วัน / เดือน / ปี • รับทั้ง พ.ศ. และ ค.ศ. • ปี 2/4 หลัก</div>\n', 1)
s = s.replace('ocr-simple.js?v=89', 'ocr-simple.js?v=90.1')
write(path, s)

path = "web-admin/ocr-simple.js"
s = read(path)
if "function renderDateFieldPreview(f)" not in s:
    anchor = '  $("dateYearDigits").value=String(Number(f.dateYearDigits||0));\n'
    require(anchor in s, "Admin date value anchor missing")
    s = s.replace(anchor, anchor + '  renderDateFieldPreview(f);\n', 1)
    helper_anchor = "function updateField(){\n"
    helper = '''function renderDateFieldPreview(f){
  const host=$("dateFormatPreview");
  if(!host)return;
  if(!f||f.type!=="BILL_DATE"){host.innerHTML="";return}
  const order=String(f.dateOrder||"DMY").toUpperCase();
  const calendar=String(f.dateCalendar||"AUTO").toUpperCase();
  const yearDigits=Number(f.dateYearDigits||0);
  const orderLabel={DMY:"วัน / เดือน / ปี",MDY:"เดือน / วัน / ปี",YMD:"ปี / เดือน / วัน"}[order]||"วัน / เดือน / ปี";
  const calendarLabel={BUDDHIST:"พ.ศ. เท่านั้น",GREGORIAN:"ค.ศ. เท่านั้น",AUTO:"รับทั้ง พ.ศ. และ ค.ศ."}[calendar]||"รับทั้ง พ.ศ. และ ค.ศ.";
  const digitLabel=yearDigits===2?"ปี 2 หลัก":yearDigits===4?"ปี 4 หลัก":"ปี 2 และ 4 หลัก";
  const years=[];
  if(calendar!=="BUDDHIST"){if(yearDigits!==4)years.push("26");if(yearDigits!==2)years.push("2026")}
  if(calendar!=="GREGORIAN"){if(yearDigits!==4)years.push("69");if(yearDigits!==2)years.push("2569")}
  const examples=[...new Set(years)].map(y=>order==="MDY"?`08/31/${y}`:order==="YMD"?`${y}/08/31`:`31/08/${y}`);
  host.innerHTML=`<strong>รูปแบบที่เลือก:</strong> ${orderLabel} • ${calendarLabel} • ${digitLabel}${examples.length?` • ตัวอย่าง ${examples.join(", ")}`:""}`;
}

'''
    require(helper_anchor in s, "Admin updateField anchor missing")
    s = s.replace(helper_anchor, helper + helper_anchor, 1)
    update_anchor = '  if(f.type==="COMPOSITE_CODE"){f.prefix=$("compositePrefix").value;f.separator=$("compositeSeparator").value}\n  renderRows();\n'
    require(update_anchor in s, "Admin update preview anchor missing")
    s = s.replace(update_anchor, '  if(f.type==="COMPOSITE_CODE"){f.prefix=$("compositePrefix").value;f.separator=$("compositeSeparator").value}\n  renderDateFieldPreview(f);\n  renderRows();\n', 1)
write(path, s)


# 7) Version ------------------------------------------------------------------
path = "android-app/app/build.gradle.kts"
s = read(path)
if 'versionCode = 91' in s:
    s = s.replace('        versionCode = 91\n        versionName = "0.90.0"\n',
                  '        versionCode = 92\n        versionName = "0.90.1"\n', 1)
write(path, s)

print("Round90 production source finalized v2")
