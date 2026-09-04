from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel, text):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.rstrip() + "\n", encoding="utf-8")


def replace_once(rel, old, new):
    text = read(rel)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected exactly one match, got {count}: {old[:140]!r}")
    write(rel, text.replace(old, new, 1))


def insert_after(rel, anchor, addition):
    text = read(rel)
    if addition.strip() in text:
        return
    count = text.count(anchor)
    if count != 1:
        raise SystemExit(f"{rel}: anchor count={count}: {anchor[:140]!r}")
    write(rel, text.replace(anchor, anchor + addition, 1))


# ---------------------------------------------------------------------------
# Version: Round94 production is immutable baseline. Round95 is additive.
# ---------------------------------------------------------------------------
replace_once(
    "android-app/app/build.gradle.kts",
    '        versionCode = 96\n        versionName = "0.94.0"',
    '        versionCode = 97\n        versionName = "0.95.0"',
)


# ---------------------------------------------------------------------------
# Store-code human verification fields. Never changes OCR's read value.
# ---------------------------------------------------------------------------
models = "android-app/app/src/main/java/com/receiptocr/app/model/Models.kt"
replace_once(
    models,
    '    /** รหัส POS ที่เห็นบนบิลก่อนจับคู่กับช่องงาน เช่น N01 / B01 */\n    val ocrRawPosIdentity: String = ""\n)',
    '''    /** รหัส POS ที่เห็นบนบิลก่อนจับคู่กับช่องงาน เช่น N01 / B01 */\n    val ocrRawPosIdentity: String = "",\n    /** ผู้ใช้ตรวจจากภาพแล้วและยืนยันรหัสร้าน โดยยังเก็บค่าที่ระบบอ่านเดิมไว้ */\n    val storeReviewConfirmed: Boolean = false,\n    val storeReviewReadId: String = "",\n    val storeReviewExpectedId: String = "",\n    val storeReviewConfirmedId: String = "",\n    val storeReviewConfirmedAt: String = "",\n    val storeReviewConfirmedBy: String = ""\n)'''
)


# ---------------------------------------------------------------------------
# Persist Round94 raw POS identity + Round95 store review audit locally.
# ---------------------------------------------------------------------------
repo = "android-app/app/src/main/java/com/receiptocr/app/data/DemoRepository.kt"
replace_once(
    repo,
    '''                ocrStoreIdExpected = prefs.getBoolean("$k.ocrStoreIdExpected", false),\n                ocrCounterCycle = prefs.getString("$k.ocrCounterCycle", "CONTINUOUS") ?: "CONTINUOUS"\n            )''',
    '''                ocrStoreIdExpected = prefs.getBoolean("$k.ocrStoreIdExpected", false),\n                ocrCounterCycle = prefs.getString("$k.ocrCounterCycle", "CONTINUOUS") ?: "CONTINUOUS",\n                ocrRawPosIdentity = prefs.getString("$k.ocrRawPosIdentity", "") ?: "",\n                storeReviewConfirmed = prefs.getBoolean("$k.storeReviewConfirmed", false),\n                storeReviewReadId = prefs.getString("$k.storeReviewReadId", "") ?: "",\n                storeReviewExpectedId = prefs.getString("$k.storeReviewExpectedId", "") ?: "",\n                storeReviewConfirmedId = prefs.getString("$k.storeReviewConfirmedId", "") ?: "",\n                storeReviewConfirmedAt = prefs.getString("$k.storeReviewConfirmedAt", "") ?: "",\n                storeReviewConfirmedBy = prefs.getString("$k.storeReviewConfirmedBy", "") ?: ""\n            )'''
)
replace_once(
    repo,
    '''                .putString("$k.ocrStoreId", r.ocrStoreId)\n                .putBoolean("$k.ocrStoreIdExpected", r.ocrStoreIdExpected)\n                .putString("$k.ocrCounterCycle", r.ocrCounterCycle)''',
    '''                .putString("$k.ocrStoreId", r.ocrStoreId)\n                .putBoolean("$k.ocrStoreIdExpected", r.ocrStoreIdExpected)\n                .putString("$k.ocrCounterCycle", r.ocrCounterCycle)\n                .putString("$k.ocrRawPosIdentity", r.ocrRawPosIdentity)\n                .putBoolean("$k.storeReviewConfirmed", r.storeReviewConfirmed)\n                .putString("$k.storeReviewReadId", r.storeReviewReadId)\n                .putString("$k.storeReviewExpectedId", r.storeReviewExpectedId)\n                .putString("$k.storeReviewConfirmedId", r.storeReviewConfirmedId)\n                .putString("$k.storeReviewConfirmedAt", r.storeReviewConfirmedAt)\n                .putString("$k.storeReviewConfirmedBy", r.storeReviewConfirmedBy)'''
)


# ---------------------------------------------------------------------------
# Store review policy: exact normalized IDs only. No fuzzy 7<->1 correction.
# ---------------------------------------------------------------------------
write(
    "android-app/app/src/main/java/com/receiptocr/app/validation/StoreReceiptReview.kt",
    '''package com.receiptocr.app.validation\n\nimport com.receiptocr.app.model.PosRecord\n\n/**\n * การยืนยันจากคนใช้ได้เฉพาะ OCR read + expected store เดิมเท่านั้น\n * ถ้าภาพใหม่อ่านต่าง หรือแผนงานเปลี่ยน รหัสยืนยันเก่าจะใช้ไม่ได้ทันที\n */\nobject StoreReceiptReview {\n    fun isValid(record: PosRecord, expectedStoreId: String): Boolean {\n        if (!record.storeReviewConfirmed) return false\n        if (record.ocrStoreId.isBlank() || expectedStoreId.isBlank()) return false\n        return StoreReceiptIdentity.sameStoreId(record.storeReviewReadId, record.ocrStoreId) &&\n            StoreReceiptIdentity.sameStoreId(record.storeReviewExpectedId, expectedStoreId) &&\n            StoreReceiptIdentity.sameStoreId(record.storeReviewConfirmedId, expectedStoreId)\n    }\n\n    fun isMismatch(record: PosRecord, expectedStoreId: String): Boolean {\n        if (!record.ocrStoreIdExpected || record.ocrStoreId.isBlank() || expectedStoreId.isBlank()) return false\n        return !StoreReceiptIdentity.sameStoreId(record.ocrStoreId, expectedStoreId)\n    }\n}\n'''
)


# ---------------------------------------------------------------------------
# Validator accepts store mismatch only after exact human confirmation.
# ---------------------------------------------------------------------------
validation = "android-app/app/src/main/java/com/receiptocr/app/validation/ReceiptValidationEngine.kt"
replace_once(
    validation,
    '''        val storeIdsByPos = ocrRecords\n            .filter { it.ocrStoreId.isNotBlank() }\n            .associate { it.posNumber to it.ocrStoreId }''',
    '''        val storeIdsByPos = ocrRecords\n            .filter { it.ocrStoreId.isNotBlank() }\n            .associate { record ->\n                record.posNumber to if (StoreReceiptReview.isValid(record, work.expectedReceiptStoreId)) {\n                    work.expectedReceiptStoreId\n                } else {\n                    record.ocrStoreId\n                }\n            }'''
)


# ---------------------------------------------------------------------------
# Include local store review audit fields in submission. Backend may ignore
# unknown audit keys; validation still happens locally before the request.
# ---------------------------------------------------------------------------
submission = "android-app/app/src/main/java/com/receiptocr/app/data/remote/SubmissionRepository.kt"
replace_once(
    submission,
    '''                    .put("ocrConfidence", r.ocrConfidence).put("ocrTemplateName", r.ocrTemplateName)\n                    .put("ocrCounterCycle", r.ocrCounterCycle))''',
    '''                    .put("ocrConfidence", r.ocrConfidence).put("ocrTemplateName", r.ocrTemplateName)\n                    .put("ocrCounterCycle", r.ocrCounterCycle)\n                    .put("storeReviewConfirmed", r.storeReviewConfirmed)\n                    .put("storeReviewReadId", r.storeReviewReadId)\n                    .put("storeReviewExpectedId", r.storeReviewExpectedId)\n                    .put("storeReviewConfirmedId", r.storeReviewConfirmedId)\n                    .put("storeReviewConfirmedAt", r.storeReviewConfirmedAt)\n                    .put("storeReviewConfirmedBy", r.storeReviewConfirmedBy))'''
)


# ---------------------------------------------------------------------------
# Strict deterministic leading-digit date proof.
# Actual date may eliminate future dates; work/admin window may NOT choose a
# missing digit. If >1 historical candidate remains, reject and ask user.
# ---------------------------------------------------------------------------
date_file = "android-app/app/src/main/java/com/receiptocr/app/ocr/ReceiptDateOcrNormalizer.kt"
replace_once(
    date_file,
    'import java.time.LocalDate\n',
    'import java.time.LocalDate\nimport java.time.ZoneId\n'
)
replace_once(
    date_file,
    '''        referenceDate: LocalDate,\n        maxAutoCorrectionDistanceDays: Long = 45,\n        allowCanonicalInput: Boolean = false\n    ): Result = normalize(''',
    '''        referenceDate: LocalDate,\n        maxAutoCorrectionDistanceDays: Long = 45,\n        allowCanonicalInput: Boolean = false,\n        actualDate: LocalDate = LocalDate.now(ZoneId.of("Asia/Bangkok"))\n    ): Result = normalize('''
)
replace_once(
    date_file,
    '''        dateExample = field?.example,\n        allowCanonicalInput = allowCanonicalInput\n    )''',
    '''        dateExample = field?.example,\n        allowCanonicalInput = allowCanonicalInput,\n        actualDate = actualDate\n    )'''
)
replace_once(
    date_file,
    '''        dateYearDigits: Int = 0,\n        dateExample: String? = null,\n        allowCanonicalInput: Boolean = false\n    ): Result {''',
    '''        dateYearDigits: Int = 0,\n        dateExample: String? = null,\n        allowCanonicalInput: Boolean = false,\n        actualDate: LocalDate = LocalDate.now(ZoneId.of("Asia/Bangkok"))\n    ): Result {'''
)
replace_once(
    date_file,
    '''        val order = resolveOrder(dateOrder, configuredFormat)\n        val calendar = resolveCalendar(dateCalendar)\n        val exact = parseStructured(''',
    '''        val order = resolveOrder(dateOrder, configuredFormat)\n        val calendar = resolveCalendar(dateCalendar)\n\n        // Round95: ถ้า Template คาด 2 หลัก แต่เห็นวัน/เดือนเพียง 1 หลัก\n        // ห้ามปล่อย parseStructured ตีเป็นเลขหลักเดียวทันที เพราะ 2 อาจเป็น 02/12/22\n        // วันที่จริงใช้ตัดเฉพาะค่าที่ยังไม่เกิดขึ้น; วันงาน/ช่วง Admin ห้ามใช้เลือกเลขที่หาย\n        proveShortStructuredDate(\n            cleaned = cleaned,\n            order = order,\n            calendar = calendar,\n            configuredYearDigits = dateYearDigits,\n            dateExample = dateExample,\n            referenceDate = referenceDate,\n            actualDate = actualDate,\n            maxDistanceDays = maxAutoCorrectionDistanceDays\n        )?.let { return it }\n\n        val exact = parseStructured('''
)
insert_after(
    date_file,
    '''    private data class OrderedTokens(\n        val day: String,\n        val month: String,\n        val year: String\n    )\n''',
    '''\n    private data class ExpectedTokenLengths(\n        val day: Int,\n        val month: Int,\n        val year: Int\n    )\n\n    private fun proveShortStructuredDate(\n        cleaned: String,\n        order: DateOrder,\n        calendar: DateCalendar,\n        configuredYearDigits: Int,\n        dateExample: String?,\n        referenceDate: LocalDate,\n        actualDate: LocalDate,\n        maxDistanceDays: Long\n    ): Result? {\n        val parts = cleaned.split('/').map { it.trim() }\n        if (parts.size != 3 || parts.any { it.isBlank() || !it.all(Char::isDigit) }) return null\n        val tokens = tokensByOrder(parts, order)\n        val expected = expectedTokenLengths(order, configuredYearDigits, dateExample)\n\n        // ถ้า Template ตั้งใจยอมรับ 1 หลักอยู่แล้ว ไม่ใช่กรณีเลขหาย\n        val shortDay = expected.day == 2 && tokens.day.length == 1\n        val shortMonth = expected.month == 2 && tokens.month.length == 1\n        if (!shortDay && !shortMonth) return null\n\n        // รูปร่างส่วนอื่นต้องยังตรง Template จึงค่อยพิสูจน์เลขนำหน้าที่หาย\n        if ((!shortDay && tokens.day.length != expected.day) ||\n            (!shortMonth && tokens.month.length != expected.month) ||\n            tokens.year.length != expected.year) {\n            return Result(null, original = cleaned, warning = "วันที่บนบิลอ่านไม่ครบ กรุณาตรวจภาพบิล")\n        }\n\n        val year = normalizeYear(tokens.year, calendar, referenceDate)\n            ?: return Result(null, original = cleaned, warning = calendarWarning(calendar))\n\n        fun dayCandidates(): List<Int> = if (shortDay) {\n            (0..3).mapNotNull { tens -> ("$tens${tokens.day}").toIntOrNull() }.distinct()\n        } else listOfNotNull(tokens.day.toIntOrNull())\n\n        fun monthCandidates(): List<Int> = if (shortMonth) {\n            (0..1).mapNotNull { tens -> ("$tens${tokens.month}").toIntOrNull() }.distinct()\n        } else listOfNotNull(tokens.month.toIntOrNull())\n\n        val candidates = buildList {\n            dayCandidates().forEach { day ->\n                monthCandidates().forEach { month ->\n                    val date = buildDate(year, month, day) ?: return@forEach\n                    // ข้อเท็จจริงเพียงอย่างเดียวที่ใช้ตัด candidate คือวันที่นั้นเกิดขึ้นแล้วหรือยัง\n                    if (!date.isAfter(actualDate)) add(date)\n                }\n            }\n        }.distinct()\n\n        if (candidates.size != 1) {\n            return Result(null, original = cleaned, warning = "วันที่บนบิลอ่านไม่ครบ กรุณาตรวจภาพบิล")\n        }\n\n        val verified = verifyDistance(\n            date = candidates.single(),\n            original = cleaned,\n            referenceDate = referenceDate,\n            maxDistanceDays = maxDistanceDays\n        )\n        return if (verified.value != null) verified.copy(corrected = true) else verified\n    }\n\n    private fun expectedTokenLengths(\n        order: DateOrder,\n        configuredYearDigits: Int,\n        dateExample: String?\n    ): ExpectedTokenLengths {\n        val groups = Regex("\\\\d+").findAll(dateExample.orEmpty()).map { it.value }.toList()\n        if (groups.size == 3) {\n            val tokens = tokensByOrder(groups, order)\n            return ExpectedTokenLengths(\n                day = tokens.day.length,\n                month = tokens.month.length,\n                year = if (configuredYearDigits in setOf(2, 4)) configuredYearDigits else tokens.year.length\n            )\n        }\n        return ExpectedTokenLengths(\n            day = 2,\n            month = 2,\n            year = if (configuredYearDigits in setOf(2, 4)) configuredYearDigits else 4\n        )\n    }\n'''
)

# Strict interpreter must pass the actual Admin field/example into normalizer.
interpreter = "android-app/app/src/main/java/com/receiptocr/app/ocr/UniversalTemplateInterpreter.kt"
replace_once(
    interpreter,
    '''            val dateResult = dateRaw?.let {\n                ReceiptDateOcrNormalizer.normalize(\n                    raw = it,\n                    configuredFormat = dateField?.format,\n                    referenceDate = workDate,\n                    dateOrder = dateField?.dateOrder,\n                    dateCalendar = dateField?.dateCalendar,\n                    dateYearDigits = dateField?.dateYearDigits ?: 0\n                )\n            }''',
    '''            val dateResult = dateRaw?.let {\n                ReceiptDateOcrNormalizer.normalizeForField(\n                    raw = it,\n                    field = dateField,\n                    referenceDate = workDate\n                )\n            }'''
)


# ---------------------------------------------------------------------------
# New OCR pass invalidates an old store confirmation for that POS.
# ---------------------------------------------------------------------------
pipeline = "android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt"
replace_once(
    pipeline,
    '''        if (record.posNumber in detectedPos) record.copy(\n            ocrConfidence = confidence.name,\n            ocrTemplateName = templateName\n        ) else record''',
    '''        if (record.posNumber in detectedPos) record.copy(\n            ocrConfidence = confidence.name,\n            ocrTemplateName = templateName,\n            storeReviewConfirmed = false,\n            storeReviewReadId = "",\n            storeReviewExpectedId = "",\n            storeReviewConfirmedId = "",\n            storeReviewConfirmedAt = "",\n            storeReviewConfirmedBy = ""\n        ) else record'''
)


# ---------------------------------------------------------------------------
# APK field UX: calendar scrolls to calendar, whole-image read is primary,
# store mismatch goes to a controlled verification panel instead of discarding
# correctly-read date/time/customer data.
# ---------------------------------------------------------------------------
ui = "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt"
replace_once(ui, 'import androidx.compose.foundation.lazy.LazyColumn\n', 'import androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.rememberLazyListState\n')
replace_once(ui, 'import com.receiptocr.app.validation.ReceiptValidationEngine\n', 'import com.receiptocr.app.validation.ReceiptValidationEngine\nimport com.receiptocr.app.validation.StoreReceiptReview\n')
replace_once(ui, 'import java.time.LocalDate\n', 'import java.time.LocalDate\nimport java.time.LocalDateTime\nimport java.time.ZoneId\n')

replace_once(
    ui,
    '''                StoreWorkScreen(\n                    work = work,\n                    selectedDate = selectedDate,''',
    '''                StoreWorkScreen(\n                    work = work,\n                    selectedDate = selectedDate,\n                    user = user ?: UserProfile("0000", "ผู้ใช้งาน"),'''
)
replace_once(
    ui,
    '''private fun StoreWorkScreen(\n    work: WorkItem,\n    selectedDate: LocalDate,\n    onBack: () -> Unit\n) {''',
    '''private fun StoreWorkScreen(\n    work: WorkItem,\n    selectedDate: LocalDate,\n    user: UserProfile,\n    onBack: () -> Unit\n) {'''
)

# Calendar button should reveal the calendar itself, not merely expand off-screen.
replace_once(
    ui,
    '''    var calendarExpanded by remember { mutableStateOf(false) }\n    var month by remember { mutableStateOf(YearMonth.from(selectedDate)) }''',
    '''    var calendarExpanded by remember { mutableStateOf(false) }\n    var month by remember { mutableStateOf(YearMonth.from(selectedDate)) }\n    val homeListState = rememberLazyListState()\n\n    LaunchedEffect(calendarExpanded) {\n        if (calendarExpanded) homeListState.animateScrollToItem(0)\n    }'''
)
replace_once(
    ui,
    '''        LazyColumn(\n            modifier = Modifier\n                .fillMaxSize()\n                .padding(innerPadding),''',
    '''        LazyColumn(\n            state = homeListState,\n            modifier = Modifier\n                .fillMaxSize()\n                .padding(innerPadding),'''
)

# Central receipt read action: reads the whole selected image, not a POS-specific crop.
replace_once(
    ui,
    '''                        ReceiptPhotoSection(\n                            receipts = receipts,\n                            onAdd = { index ->''',
    '''                        ReceiptPhotoSection(\n                            receipts = receipts,\n                            ocrBusy = ocrBusy,\n                            onReadReceipt = {\n                                val availableImages = receipts.mapIndexedNotNull { imageIndex, path -> path?.let { imageIndex to it } }\n                                when {\n                                    availableImages.isEmpty() -> message = "กรุณาเพิ่มภาพบิลก่อนอ่านข้อมูล"\n                                    availableImages.size == 1 -> runRealOcrForWholeImage(availableImages.first().second)\n                                    else -> ocrImagePickerOpen = true\n                                }\n                            },\n                            onAdd = { index ->'''
)
replace_once(
    ui,
    '''private fun ReceiptPhotoSection(\n    receipts: List<String?>,\n    onAdd: (Int) -> Unit,\n    onImageClick: (Int, String) -> Unit\n) {''',
    '''private fun ReceiptPhotoSection(\n    receipts: List<String?>,\n    ocrBusy: Boolean,\n    onReadReceipt: () -> Unit,\n    onAdd: (Int) -> Unit,\n    onImageClick: (Int, String) -> Unit\n) {'''
)
replace_once(
    ui,
    '''        PhotoGrid(\n            paths = receipts,\n            count = 3,\n            emptyLabelPrefix = "บิล",\n            onEmpty = onAdd,\n            onImage = onImageClick\n        )''',
    '''        PhotoGrid(\n            paths = receipts,\n            count = 3,\n            emptyLabelPrefix = "บิล",\n            onEmpty = onAdd,\n            onImage = onImageClick\n        )\n        Button(\n            onClick = onReadReceipt,\n            enabled = !ocrBusy && receipts.any { !it.isNullOrBlank() },\n            modifier = Modifier.fillMaxWidth().height(50.dp),\n            shape = RoundedCornerShape(12.dp),\n            colors = ButtonDefaults.buttonColors(containerColor = Primary)\n        ) {\n            if (ocrBusy) {\n                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)\n                Spacer(Modifier.width(7.dp))\n                Text("กำลังอ่านข้อมูลจากบิล")\n            } else {\n                Icon(Icons.Outlined.ReceiptLong, contentDescription = null)\n                Spacer(Modifier.width(7.dp))\n                Text("อ่านข้อมูลจากบิล", fontWeight = FontWeight.Bold)\n            }\n        }'''
)

# Proposal dialog: true duplicate remains blocked. Store mismatch can be imported
# into the POS card for explicit human review while preserving other read fields.
replace_once(
    ui,
    '''        val hasCriticalIntegrityWarning = proposal.warnings.any { raw ->\n            raw.contains("บิลผิดร้าน") ||\n                raw.contains("พบบิลสลับร้าน") ||\n                raw.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS")\n        } || proposal.proposedRecords.any { record ->\n            record.ocrWarnings.contains("บิลผิดร้าน") ||\n                record.ocrWarnings.contains("พบบิลสลับร้าน") ||\n                record.ocrWarnings.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS")\n        }''',
    '''        val hasStoreReviewWarning = proposal.warnings.any { raw ->\n            raw.contains("บิลผิดร้าน") || raw.contains("พบบิลสลับร้าน") ||\n                (raw.contains("รหัสร้าน") && raw.contains("ไม่ตรง"))\n        } || proposal.proposedRecords.any { record ->\n            record.ocrWarnings.contains("บิลผิดร้าน") || record.ocrWarnings.contains("พบบิลสลับร้าน") ||\n                (record.ocrWarnings.contains("รหัสร้าน") && record.ocrWarnings.contains("ไม่ตรง"))\n        }\n        val hasHardIntegrityBlock = proposal.warnings.any { it.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS") || (it.contains("POS") && it.contains("ซ้ำ")) } ||\n            proposal.proposedRecords.any { it.ocrWarnings.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS") || (it.ocrWarnings.contains("POS") && it.ocrWarnings.contains("ซ้ำ")) }\n        val hasCriticalIntegrityWarning = hasStoreReviewWarning || hasHardIntegrityBlock'''
)
replace_once(
    ui,
    '''                    enabled = !hasCriticalIntegrityWarning,\n                    colors = ButtonDefaults.buttonColors(containerColor = Primary)''',
    '''                    enabled = !hasHardIntegrityBlock,\n                    colors = ButtonDefaults.buttonColors(containerColor = Primary)'''
)
replace_once(
    ui,
    '''                        when {\n                            hasCriticalIntegrityWarning -> "ต้องตรวจภาพก่อน"\n                            hasDateWarning -> "นำข้อมูลไปแก้ไข"\n                            else -> "ใช้ข้อมูลนี้"\n                        }''',
    '''                        when {\n                            hasHardIntegrityBlock -> "ต้องตรวจภาพก่อน"\n                            hasStoreReviewWarning -> "นำข้อมูลไปตรวจรหัสร้าน"\n                            hasDateWarning -> "นำข้อมูลไปแก้ไข"\n                            else -> "ใช้ข้อมูลนี้"\n                        }'''
)
replace_once(
    ui,
    '''                        message = if (hasDateWarning) {\n                            "วันที่แต่ละ POS ใช้ได้ แต่ชุดวันที่ยังใช้ร่วมกันไม่ได้"\n                        } else {\n                            "บันทึกข้อมูลจากบิลแล้ว"\n                        }''',
    '''                        message = when {\n                            hasStoreReviewWarning -> "เก็บข้อมูลที่อ่านได้แล้ว • กรุณาตรวจรหัสร้านใน POS ที่แจ้ง"\n                            hasDateWarning -> "วันที่แต่ละ POS ใช้ได้ แต่ชุดวันที่ยังใช้ร่วมกันไม่ได้"\n                            else -> "บันทึกข้อมูลจากบิลแล้ว"\n                        }'''
)

# PosCard gets expected store + user so verification is explicit/auditable.
replace_once(
    ui,
    '''                            noReceiptReasons = loadedNoteOptions.labels(NoteOptionCategory.NO_RECEIPT_REASON),\n                            onOcr = {''',
    '''                            noReceiptReasons = loadedNoteOptions.labels(NoteOptionCategory.NO_RECEIPT_REASON),\n                            expectedStoreId = work.expectedReceiptStoreId,\n                            user = user,\n                            onOcr = {'''
)
replace_once(
    ui,
    '''    noteOptions: List<String>,\n    noReceiptReasons: List<String>,\n    onOcr: () -> Unit,''',
    '''    noteOptions: List<String>,\n    noReceiptReasons: List<String>,\n    expectedStoreId: String,\n    user: UserProfile,\n    onOcr: () -> Unit,'''
)
replace_once(
    ui,
    '''    val visibleOcrWarning = UserFacingOcrMessages.warning(record.ocrWarnings)\n    val dateInfoText = UserFacingOcrMessages.dateInfo(record.ocrRawBillDate, record.billDate)''',
    '''    val storeReviewValid = StoreReceiptReview.isValid(record, expectedStoreId)\n    val storeMismatch = StoreReceiptReview.isMismatch(record, expectedStoreId)\n    val warningForUser = if (storeReviewValid) {\n        record.ocrWarnings.split(" • ").filterNot { part ->\n            part.contains("บิลผิดร้าน") || part.contains("บิลสลับร้าน") ||\n                (part.contains("รหัสร้าน") && part.contains("ไม่ตรง"))\n        }.joinToString(" • ")\n    } else record.ocrWarnings\n    val visibleOcrWarning = UserFacingOcrMessages.warning(warningForUser)\n    val dateInfoText = UserFacingOcrMessages.dateInfo(record.ocrRawBillDate, record.billDate)'''
)

# Insert store review panel before date-info/warnings.
insert_after(
    ui,
    '''                    }\n\n                    if (dateInfoText.isNotBlank()) {''',
    '''\n                    if (storeMismatch) {\n                        Spacer(Modifier.height(8.dp))\n                        Surface(\n                            modifier = Modifier.fillMaxWidth(),\n                            shape = RoundedCornerShape(10.dp),\n                            color = if (storeReviewValid) Color(0xFFEAF8F0) else Color(0xFFFFF1F1),\n                            border = BorderStroke(1.dp, if (storeReviewValid) SuccessGreen else MaterialTheme.colorScheme.error)\n                        ) {\n                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {\n                                Text(\n                                    if (storeReviewValid) "ยืนยันรหัสร้านแล้ว" else "ตรวจรหัสร้าน",\n                                    color = if (storeReviewValid) SuccessGreen else MaterialTheme.colorScheme.error,\n                                    fontWeight = FontWeight.Bold\n                                )\n                                Text("รหัสร้านในงาน: ${expectedStoreId.ifBlank { "ไม่พบ" }}", color = TextMain, fontSize = 12.sp)\n                                Text("รหัสที่อ่านจากบิล: ${record.ocrStoreId.ifBlank { "อ่านไม่พบ" }}", color = TextMain, fontSize = 12.sp)\n                                if (!storeReviewValid) {\n                                    Text(\n                                        "ตรวจตัวเลขจากภาพบิลก่อนยืนยัน ระบบจะไม่แก้รหัสร้านให้อัตโนมัติ",\n                                        color = TextSub, fontSize = 11.sp, lineHeight = 16.sp\n                                    )\n                                    Button(\n                                        onClick = {\n                                            val now = LocalDateTime.now(ZoneId.of("Asia/Bangkok"))\n                                                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))\n                                            onChange(\n                                                record.copy(\n                                                    storeReviewConfirmed = true,\n                                                    storeReviewReadId = record.ocrStoreId,\n                                                    storeReviewExpectedId = expectedStoreId,\n                                                    storeReviewConfirmedId = expectedStoreId,\n                                                    storeReviewConfirmedAt = now,\n                                                    storeReviewConfirmedBy = listOf(user.employeeCode, user.fullName)\n                                                        .filter { it.isNotBlank() }.joinToString(" ")\n                                                )\n                                            )\n                                        },\n                                        modifier = Modifier.fillMaxWidth(),\n                                        enabled = expectedStoreId.isNotBlank() && record.ocrStoreId.isNotBlank(),\n                                        colors = ButtonDefaults.buttonColors(containerColor = Primary)\n                                    ) {\n                                        Text("ตรวจจากภาพแล้ว รหัสบนบิลคือ $expectedStoreId", textAlign = TextAlign.Center)\n                                    }\n                                    OutlinedButton(\n                                        onClick = { /* คงข้อมูลที่อ่านถูกไว้ และปล่อยสถานะบล็อกจนเปลี่ยนบิล */ },\n                                        modifier = Modifier.fillMaxWidth()\n                                    ) { Text("บิลนี้เป็นร้านอื่น") }\n                                    Text("หากเป็นบิลร้านอื่น ให้เปลี่ยนภาพบิลก่อนส่งงาน", color = MaterialTheme.colorScheme.error, fontSize = 10.5.sp)\n                                } else {\n                                    Text(\n                                        "ยืนยันโดย ${record.storeReviewConfirmedBy.ifBlank { "ผู้ใช้งาน" }} • ${record.storeReviewConfirmedAt}",\n                                        color = SuccessGreen, fontSize = 10.5.sp\n                                    )\n                                }\n                            }\n                        }\n                    }\n\n                    if (dateInfoText.isNotBlank()) {'''
)

# Delete OCR image clears its store review audit too.
replace_once(
    ui,
    '''                                ocrWarnings = "",\n                                ocrCounterCycle = "CONTINUOUS",\n                                ocrRawPosIdentity = ""''',
    '''                                ocrWarnings = "",\n                                ocrCounterCycle = "CONTINUOUS",\n                                ocrRawPosIdentity = "",\n                                storeReviewConfirmed = false,\n                                storeReviewReadId = "",\n                                storeReviewExpectedId = "",\n                                storeReviewConfirmedId = "",\n                                storeReviewConfirmedAt = "",\n                                storeReviewConfirmedBy = ""'''
)


# ---------------------------------------------------------------------------
# Regression tests for deterministic date proof and store review validity.
# ---------------------------------------------------------------------------
write(
    "android-app/app/src/test/java/com/receiptocr/app/ocr/ReceiptDateDeterministicProofRound95Test.kt",
    '''package com.receiptocr.app.ocr\n\nimport com.receiptocr.app.config.OcrTemplateField\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertNull\nimport org.junit.Test\nimport java.time.LocalDate\n\nclass ReceiptDateDeterministicProofRound95Test {\n    private fun field(example: String, order: String = "DMY") = OcrTemplateField(\n        order = 1, type = "BILL_DATE", example = example, required = true,\n        minLength = 1, maxLength = 10, format = "DATE",\n        dateOrder = order, dateCalendar = "GREGORIAN", dateYearDigits = 4\n    )\n\n    @Test fun dayLeadingZeroMayRecoverOnlyWhenActualDateLeavesOneCandidate() {\n        val result = ReceiptDateOcrNormalizer.normalizeForField(\n            raw = "2/08/2026", field = field("02/08/2026"),\n            referenceDate = LocalDate.of(2026, 8, 3),\n            actualDate = LocalDate.of(2026, 8, 3)\n        )\n        assertEquals("02/08/2026", result.value)\n    }\n\n    @Test fun dayLeadingDigitIsRejectedWhenMoreThanOnePastCandidateExists() {\n        val result = ReceiptDateOcrNormalizer.normalizeForField(\n            raw = "2/08/2026", field = field("02/08/2026"),\n            referenceDate = LocalDate.of(2026, 8, 12),\n            actualDate = LocalDate.of(2026, 8, 12)\n        )\n        assertNull(result.value)\n        assertEquals("วันที่บนบิลอ่านไม่ครบ กรุณาตรวจภาพบิล", result.warning)\n    }\n\n    @Test fun adminWorkWindowMustNotSelect24FromAmbiguous4() {\n        val result = ReceiptDateOcrNormalizer.normalizeForField(\n            raw = "4/08/2026", field = field("04/08/2026"),\n            referenceDate = LocalDate.of(2026, 8, 25),\n            actualDate = LocalDate.of(2026, 8, 25)\n        )\n        assertNull(result.value)\n    }\n\n    @Test fun monthLeadingZeroMayRecoverWhenOnlyRealHistoricalMonthExists() {\n        val result = ReceiptDateOcrNormalizer.normalizeForField(\n            raw = "02/8/2026", field = field("02/08/2026"),\n            referenceDate = LocalDate.of(2026, 8, 3),\n            actualDate = LocalDate.of(2026, 8, 3)\n        )\n        assertEquals("02/08/2026", result.value)\n    }\n\n    @Test fun templateThatIntentionallyUsesOneDigitDayDoesNotTriggerRepair() {\n        val result = ReceiptDateOcrNormalizer.normalizeForField(\n            raw = "4/08/2026", field = field("4/08/2026"),\n            referenceDate = LocalDate.of(2026, 8, 4),\n            actualDate = LocalDate.of(2026, 8, 4)\n        )\n        assertEquals("04/08/2026", result.value)\n    }\n\n    @Test fun mdyUsesTemplateOrderBeforeProof() {\n        val result = ReceiptDateOcrNormalizer.normalizeForField(\n            raw = "8/02/2026", field = field("08/02/2026", order = "MDY"),\n            referenceDate = LocalDate.of(2026, 8, 3),\n            actualDate = LocalDate.of(2026, 8, 3)\n        )\n        assertEquals("02/08/2026", result.value)\n    }\n}\n'''
)

write(
    "android-app/app/src/test/java/com/receiptocr/app/validation/StoreReceiptReviewRound95Test.kt",
    '''package com.receiptocr.app.validation\n\nimport com.receiptocr.app.model.PosRecord\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass StoreReceiptReviewRound95Test {\n    @Test fun exactHumanConfirmationAllowsCurrentReadAndExpectedStore() {\n        val record = PosRecord(\n            posNumber = 3, ocrStoreId = "7600", ocrStoreIdExpected = true,\n            storeReviewConfirmed = true, storeReviewReadId = "7600",\n            storeReviewExpectedId = "1600", storeReviewConfirmedId = "1600"\n        )\n        assertTrue(StoreReceiptReview.isValid(record, "1600"))\n        assertTrue(StoreReceiptReview.isMismatch(record, "1600"))\n    }\n\n    @Test fun changedOcrReadInvalidatesOldConfirmation() {\n        val record = PosRecord(\n            posNumber = 3, ocrStoreId = "7601", ocrStoreIdExpected = true,\n            storeReviewConfirmed = true, storeReviewReadId = "7600",\n            storeReviewExpectedId = "1600", storeReviewConfirmedId = "1600"\n        )\n        assertFalse(StoreReceiptReview.isValid(record, "1600"))\n    }\n\n    @Test fun changedExpectedStoreInvalidatesOldConfirmation() {\n        val record = PosRecord(\n            posNumber = 3, ocrStoreId = "7600", ocrStoreIdExpected = true,\n            storeReviewConfirmed = true, storeReviewReadId = "7600",\n            storeReviewExpectedId = "1600", storeReviewConfirmedId = "1600"\n        )\n        assertFalse(StoreReceiptReview.isValid(record, "1700"))\n    }\n}\n'''
)

print("Round95 patch applied")
