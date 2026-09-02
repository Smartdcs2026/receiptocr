from pathlib import Path


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


# 1) ReceiptDateOcrNormalizer: recover dates where one separator is missing,
# preserving the token boundary that OCR still read correctly.
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/ReceiptDateOcrNormalizer.kt"
s = read(path)
s = replace_once(
    s,
    '''        if (hasExplicitStructuredYearDigitMismatch(cleaned, order, dateYearDigits)) {
            return exact
        }

        // กรณีเดือนถูก OCR แทรก/สลับเป็นเลขที่ไม่มีจริง เช่น 20/28/2026
''',
    '''        if (hasExplicitStructuredYearDigitMismatch(cleaned, order, dateYearDigits)) {
            return exact
        }

        // ถ้า OCR ทำตัวคั่นหายไปหนึ่งตำแหน่ง เช่น 20/0869 หรือ 20/08769
        // ให้รักษาขอบเขตที่ยังอ่านได้ แล้วเติม/แก้เฉพาะ token ถัดไปตามโครงสร้าง Admin
        // วิธีนี้ไม่ลบเลขจากทั้งวันที่แบบสุ่ม และไม่ดึงเลขจากเวลาเข้ามาปน
        recoverPartiallySeparatedDate(
            cleaned = cleaned,
            order = order,
            calendar = calendar,
            configuredYearDigits = dateYearDigits,
            referenceDate = referenceDate,
            maxDistanceDays = maxAutoCorrectionDistanceDays
        )?.let { return it }

        // กรณีเดือนถูก OCR แทรก/สลับเป็นเลขที่ไม่มีจริง เช่น 20/28/2026
''',
    "insert partial-date recovery call"
)
marker = '''    private fun recoverNoisyDate(
'''
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

            // ตัวคั่นที่ยังอยู่คือหลัง token แรก เช่น DMY: 20/08769
            // รักษา day=20 และ month=08 ตามจำนวนหลักที่ Admin กำหนด
            // แล้วแก้เฉพาะ token ปีที่มีเลขเกิน
            if (left.length == lengths[0] && right.length >= lengths[1] + lengths[2] &&
                right.length <= lengths[1] + lengths[2] + 2) {
                val second = right.take(lengths[1])
                val thirdRaw = right.drop(lengths[1])
                shrinkToLength(thirdRaw, lengths[2]).forEach { third ->
                    accept(listOf(left, second, third))
                }
            }

            // ตัวคั่นที่ยังอยู่คือก่อน token สุดท้าย เช่น DMY: 2008/69
            // รักษา token แรกและปี แล้วแก้เฉพาะ token กลางเมื่อมีเลขเกิน
            if (right.length == lengths[2] && left.length >= lengths[0] + lengths[1] &&
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
s = replace_once(s, marker, helper + marker, "add partial-date helper")
write(path, s)


# 2) PosEvidenceFusion: Admin date layout must win over example; U literal may be OCR O/0/V only in that literal slot.
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt"
s = read(path)
s = replace_once(s, '            "BILL_DATE" -> capture("BILL_DATE", datePattern(sample))\n', '            "BILL_DATE" -> capture("BILL_DATE", datePattern(field))\n', "evidence datePattern field")
old = '''        val lengths = Regex("\\\\d+").findAll(field.example.orEmpty()).map { it.value.length }.toList()
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
            "${fixedDigits(lengths[0])}\\\\s*[./-]\\\\s*${fixedDigits(lengths[1])}\\\\s*[./-]\\\\s*${fixedDigits(lengths[2])}",
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
'''
new = '''        val layouts = dateLayouts(field)

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
                "${fixedDigits(lengths[0])}\\\\s*[./-]\\\\s*${fixedDigits(lengths[1])}\\\\s*[./-]\\\\s*${fixedDigits(lengths[2])}",
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
'''
s = replace_once(s, old, new, "evidence findDate layouts")
old = '''    private fun datePattern(sample: String): String {
        val groups = Regex("\\\\d+").findAll(sample).map { it.value.length }.toList()
        val lengths = if (groups.size == 3) groups else listOf(2, 2, 4)
        return "${fixedDigits(lengths[0])}\\\\s*[./-]\\\\s*${fixedDigits(lengths[1])}\\\\s*[./-]\\\\s*${fixedDigits(lengths[2])}"
    }
'''
new = '''    private fun dateLayouts(field: OcrTemplateField): List<List<Int>> {
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
            "${fixedDigits(lengths[0])}\\\\s*[./-]\\\\s*${fixedDigits(lengths[1])}\\\\s*[./-]\\\\s*${fixedDigits(lengths[2])}"
        }
'''
s = replace_once(s, old, new, "evidence date layouts helper")
s = replace_once(s, "                'U', 'u', 'V', 'v' -> \"[UuVv]\"\n", "                'U', 'u', 'V', 'v' -> \"[UuVvOo0]\"\n", "evidence U confusion")
write(path, s)


# 3) Sequence fallback literal slot: use Admin position + surrounding fixed lengths, so only that U slot accepts O/0/V.
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/TemplateSequenceFallback.kt"
s = read(path)
s = replace_once(s, "                'U', 'u', 'V', 'v' -> \"[UuVv]\"\n", "                'U', 'u', 'V', 'v' -> \"[UuVvOo0]\"\n", "sequence U confusion")
write(path, s)


# 4) Strict interpreter: same Admin date layout and same literal-slot OCR confusion policy.
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/UniversalTemplateInterpreter.kt"
s = read(path)
s = replace_once(
    s,
    '            "BILL_DATE" -> capture("BILL_DATE", "$OCR_DIGIT{1,2}[./-]$OCR_DIGIT{1,2}[./-]$OCR_DIGIT{2,4}")\n',
    '            "BILL_DATE" -> capture("BILL_DATE", datePattern(field))\n',
    "strict Admin date pattern"
)
marker = '''    private fun literalPattern(raw: String): String? {
'''
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
s = replace_once(s, marker, helper + marker, "strict datePattern helper")
s = replace_once(
    s,
    '''                    '0' -> "[0Oo]"
                    '1' -> "[1Iil|]"
                    else -> Regex.escape(character.toString())
''',
    '''                    '0' -> "[0Oo]"
                    '1' -> "[1Iil|]"
                    'U', 'u', 'V', 'v' -> "[UuVvOo0]"
                    else -> Regex.escape(character.toString())
''',
    "strict literal U confusion"
)
s = replace_once(
    s,
    '        val date = fields["BILL_DATE"]?.let { normalizeDate(it, workDate) }\n',
    '''        val scoreDateField = template.recognition.rows.asSequence()
            .flatMap { it.fields.asSequence() }
            .firstOrNull { it.type == "BILL_DATE" }
        val date = fields["BILL_DATE"]?.let {
            ReceiptDateOcrNormalizer.normalizeForField(it, scoreDateField, workDate).value
        }
''',
    "strict scoring date uses Admin"
)
write(path, s)


# 5) Android regression tests from the real Mb_02 failure.
path = "android-app/app/src/test/java/com/receiptocr/app/ocr/ReceiptDateOcrNormalizerTest.kt"
s = read(path)
old = '''    @Test
    fun noisyThaiDateWithOneExtraDigitIsNotGuessedWhenAmbiguous() {
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
'''
new = '''    @Test
    fun partiallySeparatedThaiDatePreservesDayAndMonthBoundary() {
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
    fun missingSecondDateSeparatorIsRecoveredFromAdminLengths() {
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
s = replace_once(s, old, new, "date regression tests")
write(path, s)

path = "android-app/app/src/test/java/com/receiptocr/app/ocr/TemplateSequenceDiagnosticsRound85Test.kt"
s = read(path)
insert = '''
    @Test
    fun acceptsOcrLetterOInAdminLiteralUSlot() {
        val detail = TemplateSequenceFallback.diagnose(
            rawTexts = listOf("R202039030O400072 20/08/69 17:18"),
            templates = listOf(mb02)
        ).joinToString(" ")

        assertTrue(detail.contains("อ่านลำดับครบ"))
    }

    @Test
    fun acceptsOcrZeroInAdminLiteralUSlot() {
        val detail = TemplateSequenceFallback.diagnose(
            rawTexts = listOf("R2020390300400072 20/08/69 17:18"),
            templates = listOf(mb02)
        ).joinToString(" ")

        assertTrue(detail.contains("อ่านลำดับครบ"))
    }
'''
s = replace_once(s, '\n}\n', insert + '\n}\n', "sequence literal tests")
write(path, s)

path = "android-app/app/src/test/java/com/receiptocr/app/ocr/PosEvidenceFusionRound90Test.kt"
s = read(path)
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
s = replace_once(s, '\n}\n', insert + '\n}\n', "evidence noisy-only regression")
write(path, s)


# 6) Admin UI: make date choices visible with a live example so the operator knows exactly what was configured.
path = "web-admin/index.html"
s = read(path)
s = replace_once(
    s,
    '''        <div class="small">ตัวอย่าง: พ.ศ. 2569 / 69 และ ค.ศ. 2026 / 26 • เมื่ออ่านผ่าน ระบบจะเก็บเป็น dd/MM/yyyy เสมอ • ถ้าไม่ตรงเงื่อนไข ระบบจะแสดงค่าที่อ่านได้พร้อมคำเตือนและไม่เก็บเป็นวันที่ใช้งาน</div>
''',
    '''        <div class="small">ตัวอย่าง: พ.ศ. 2569 / 69 และ ค.ศ. 2026 / 26 • เมื่ออ่านผ่าน ระบบจะเก็บเป็น dd/MM/yyyy เสมอ • ถ้าไม่ตรงเงื่อนไข ระบบจะแสดงค่าที่อ่านได้พร้อมคำเตือนและไม่เก็บเป็นวันที่ใช้งาน</div>
        <div id="dateFormatPreview" class="dateRuleExample">รูปแบบที่เลือก: วัน / เดือน / ปี • รับทั้ง พ.ศ. และ ค.ศ. • ปี 2/4 หลัก</div>
''',
    "Admin date preview"
)
s = s.replace('ตัวอ่านรุ่น 68', 'ตัวอ่านรุ่น 90')
s = s.replace('ocr-simple.js?v=89', 'ocr-simple.js?v=90')
write(path, s)

path = "web-admin/ocr-simple.js"
s = read(path)
s = replace_once(
    s,
    '''  $("dateYearDigits").value=String(Number(f.dateYearDigits||0));
  $("posBox").classList.toggle("hidden",f.type!=="POS_NUMBER");
''',
    '''  $("dateYearDigits").value=String(Number(f.dateYearDigits||0));
  renderDateFieldPreview(f);
  $("posBox").classList.toggle("hidden",f.type!=="POS_NUMBER");
''',
    "render Admin date preview"
)
marker = '''function updateField(){
'''
helper = '''function renderDateFieldPreview(f){
  const host=$("dateFormatPreview");
  if(!host)return;
  if(!f||f.type!=="BILL_DATE"){host.textContent="";return}
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
  host.textContent=`รูปแบบที่เลือก: ${orderLabel} • ${calendarLabel} • ${digitLabel}${examples.length?` • ตัวอย่าง ${examples.join(", ")}`:""}`;
}

'''
s = replace_once(s, marker, helper + marker, "Admin date preview helper")
s = replace_once(s, '  renderRows();\n}\n["fieldExample"', '  renderDateFieldPreview(f);\n  renderRows();\n}\n["fieldExample"', "refresh Admin date preview")
write(path, s)

# Cache bust SPA iframe after Round90 Admin goes live.
path = "web-admin/admin-spa.js"
s = read(path)
s = replace_once(s, 'const VERSION="78"', 'const VERSION="90"', "Admin SPA cache version")
write(path, s)

# 7) Allow the Round90 test branch to deploy the live Admin without merging main yet.
path = ".github/workflows/pages.yml"
s = read(path)
s = replace_once(s, '    branches: ["main"]\n', '    branches: ["main", "round90-unified-date-admin-receipt-line"]\n', "Pages Round90 branch")
write(path, s)

print("Round90 mobile/Admin patch applied")
