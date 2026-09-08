from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"Expected block not found: {label}")
    return text.replace(old, new, 1)


def replace_range(text: str, start: str, end: str, new: str, label: str) -> str:
    if new in text:
        return text
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"Start marker not found: {label}")
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f"End marker not found: {label}")
    return text[:a] + new + text[b:]


# -----------------------------------------------------------------------------
# 1) Brand-configurable unknown POS -> last work POS fallback.
#    Exact mappings and real numeric POS always win first.
# -----------------------------------------------------------------------------
models_path = ROOT / "android-app/app/src/main/java/com/receiptocr/app/config/ReceiptRuleModels.kt"
models = models_path.read_text(encoding="utf-8")
models = replace_once(
    models,
    """    /** ถ้าเจอรหัสใหม่ ห้ามเดา POS เอง; ให้แจ้งผู้ใช้/ผู้ดูแล */\n    val allowUnmappedUserChoice: Boolean = true,\n    /** ค่า runtime จากแผนงานของร้าน ไม่ถูกบันทึกกลับ Admin/API */\n""",
    """    /** ถ้าเจอรหัสใหม่ ห้ามเดา POS เอง; ให้แจ้งผู้ใช้/ผู้ดูแล */\n    val allowUnmappedUserChoice: Boolean = true,\n    /** ถ้ารหัสเครื่องอ่านเพี้ยนและไม่ตรง mapping/ไม่ใช่ POS จริง ให้ใช้ POS สุดท้ายของร้าน */\n    val fallbackUnknownToLastWorkPos: Boolean = false,\n    /** ค่า runtime จากแผนงานของร้าน ไม่ถูกบันทึกกลับ Admin/API */\n""",
    "PosIdentityRule unknown fallback flag",
)
models_path.write_text(models, encoding="utf-8")

resolver_path = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/PosIdentityResolver.kt"
resolver = resolver_path.read_text(encoding="utf-8")
resolver = replace_once(
    resolver,
    """        val active = rule.enabled || rule.mappings.isNotEmpty() || rule.lastWorkPosPrefixes.isNotEmpty()\n        if (!active) {\n            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)\n        }\n\n        val prefix = key.takeWhile(Char::isLetter)\n        if (prefix.isBlank()) {\n            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)\n        }\n\n        if (prefix in terminalPrefixes) {\n            val lastPos = availableWorkPos.filter { it > 0 }.maxOrNull()\n                ?: rule.runtimeLastWorkPos.takeIf { it > 0 }\n                ?: return null\n            return ResolvedPosIdentity(display, key, lastPos, mappedByBrandRule = true)\n        }\n\n        // Exact mapping remains available for prefixes that are not terminal-prefix rules.\n        val mapping = rule.mappings.firstOrNull { item ->\n            OcrTextNormalizer.normalizePosIdentity(item.receiptPos) == key &&\n                (item.workPos > 0 || item.useLastWorkPos)\n        } ?: return null\n\n        val resolvedWorkPos = when {\n""",
    """        val active = rule.enabled || rule.mappings.isNotEmpty() || rule.lastWorkPosPrefixes.isNotEmpty() ||\n            rule.fallbackUnknownToLastWorkPos\n        if (!active) {\n            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)\n        }\n\n        val knownWorkPos = availableWorkPos.filter { it > 0 }.toSet()\n        fun lastWorkPos(): Int? = knownWorkPos.maxOrNull()\n            ?: rule.runtimeLastWorkPos.takeIf { it > 0 }\n        val allowedPrefixes = rule.allowedPrefixes\n            .map { it.trim().uppercase() }\n            .filter { it.isNotBlank() }\n            .toSet()\n\n        val prefix = key.takeWhile(Char::isLetter)\n        if (prefix.isBlank()) {\n            // A real numeric POS in this store always wins. If the OCR value is not\n            // an actual POS and this brand enables recovery, use the store's last POS.\n            val isRealNumericPos = numeric in knownWorkPos ||\n                (knownWorkPos.isEmpty() && rule.runtimeLastWorkPos == numeric)\n            if (isRealNumericPos || !rule.fallbackUnknownToLastWorkPos) {\n                return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)\n            }\n            val lastPos = lastWorkPos() ?: return null\n            return ResolvedPosIdentity(display, key, lastPos, mappedByBrandRule = true)\n        }\n\n        if (prefix in terminalPrefixes) {\n            val lastPos = lastWorkPos() ?: return null\n            return ResolvedPosIdentity(display, key, lastPos, mappedByBrandRule = true)\n        }\n\n        // Exact mapping remains available for prefixes that are not terminal-prefix rules.\n        val mapping = rule.mappings.firstOrNull { item ->\n            OcrTextNormalizer.normalizePosIdentity(item.receiptPos) == key &&\n                (item.workPos > 0 || item.useLastWorkPos)\n        }\n        if (mapping == null) {\n            // Do not silently convert a missing mapping inside an allowed family\n            // (for example N04 when N is configured). Unknown OCR prefixes may recover.\n            if (rule.fallbackUnknownToLastWorkPos && prefix !in allowedPrefixes) {\n                val lastPos = lastWorkPos() ?: return null\n                return ResolvedPosIdentity(display, key, lastPos, mappedByBrandRule = true)\n            }\n            return null\n        }\n\n        val resolvedWorkPos = when {\n""",
    "resolver fallback precedence",
)
resolver = replace_once(
    resolver,
    """        if (!rule.enabled && rule.mappings.isEmpty() && rule.lastWorkPosPrefixes.isEmpty()) return emptyList()\n""",
    """        if (!rule.enabled && rule.mappings.isEmpty() && rule.lastWorkPosPrefixes.isEmpty() &&\n            !rule.fallbackUnknownToLastWorkPos) return emptyList()\n""",
    "resolver unmapped active guard",
)
resolver_path.write_text(resolver, encoding="utf-8")

repo_path = ROOT / "android-app/app/src/main/java/com/receiptocr/app/data/remote/OcrTemplateRepository.kt"
repo = repo_path.read_text(encoding="utf-8")
repo = replace_once(
    repo,
    """            mappings = parsedMappings,\n            allowUnmappedUserChoice = root.optBoolean(\"allowUnmappedUserChoice\", true)\n        )\n""",
    """            mappings = parsedMappings,\n            allowUnmappedUserChoice = root.optBoolean(\"allowUnmappedUserChoice\", true),\n            fallbackUnknownToLastWorkPos = root.optBoolean(\"fallbackUnknownToLastWorkPos\", false)\n        )\n""",
    "cloud receipt rule fallback parser",
)
repo_path.write_text(repo, encoding="utf-8")


# -----------------------------------------------------------------------------
# 2) Web Admin rule editor + shared browser resolver parity.
# -----------------------------------------------------------------------------
web_rule_path = ROOT / "web-admin/receipt-date-rules.js"
web_rule = web_rule_path.read_text(encoding="utf-8")
web_rule = replace_once(
    web_rule,
    """      allowUnmappedUserChoice:raw.allowUnmappedUserChoice!==false,\n      runtimeLastWorkPos:Math.max(0,Number(raw.runtimeLastWorkPos)||0)\n""",
    """      allowUnmappedUserChoice:raw.allowUnmappedUserChoice!==false,\n      fallbackUnknownToLastWorkPos:raw.fallbackUnknownToLastWorkPos===true,\n      runtimeLastWorkPos:Math.max(0,Number(raw.runtimeLastWorkPos)||0)\n""",
    "web normalize fallback flag",
)
web_rule = replace_once(
    web_rule,
    """      enabled:raw.enabled===true||mappings.length>0||lastWorkPosPrefixes.length>0,\n""",
    """      enabled:raw.enabled===true||mappings.length>0||lastWorkPosPrefixes.length>0||raw.fallbackUnknownToLastWorkPos===true,\n""",
    "web normalize active fallback",
)
web_rule = replace_once(
    web_rule,
    """    const prefix=posPrefix(key);\n    const active=rule.enabled||rule.mappings.length>0||rule.lastWorkPosPrefixes.length>0;\n    if(!prefix)return numeric;\n    if(!active)return numeric;\n\n    // Brand rule has priority: every code beginning with this prefix uses the\n    // store's actual last POS; the digits after the letter are not the POS number.\n    if(rule.lastWorkPosPrefixes.includes(prefix)){\n      const last=(availableWorkPos||[]).map(Number).filter(x=>Number.isInteger(x)&&x>0).sort((a,b)=>b-a)[0]\n        ||(rule.runtimeLastWorkPos>0?rule.runtimeLastWorkPos:null);\n      return last||null;\n    }\n\n    const mapping=rule.mappings.find(x=>normalizePosIdentity(x.receiptPos)===key);\n    if(!mapping)return null;\n""",
    """    const prefix=posPrefix(key);\n    const active=rule.enabled||rule.mappings.length>0||rule.lastWorkPosPrefixes.length>0||rule.fallbackUnknownToLastWorkPos===true;\n    const known=(availableWorkPos||[]).map(Number).filter(x=>Number.isInteger(x)&&x>0);\n    const last=()=>known.slice().sort((a,b)=>b-a)[0]||(rule.runtimeLastWorkPos>0?rule.runtimeLastWorkPos:null);\n    if(!active)return numeric;\n    if(!prefix){\n      if(known.includes(numeric)||(known.length===0&&rule.runtimeLastWorkPos===numeric))return numeric;\n      return rule.fallbackUnknownToLastWorkPos?(last()||null):numeric;\n    }\n\n    // Brand rule has priority: every code beginning with this prefix uses the\n    // store's actual last POS; the digits after the letter are not the POS number.\n    if(rule.lastWorkPosPrefixes.includes(prefix))return last()||null;\n\n    const mapping=rule.mappings.find(x=>normalizePosIdentity(x.receiptPos)===key);\n    if(!mapping){\n      if(rule.fallbackUnknownToLastWorkPos&&!rule.allowedPrefixes.includes(prefix))return last()||null;\n      return null;\n    }\n""",
    "web resolver fallback precedence",
)
web_rule = replace_once(
    web_rule,
    """posIdentityRule:{enabled:false,allowedPrefixes:[],lastWorkPosPrefixes:[],mappings:[],allowUnmappedUserChoice:true}""",
    """posIdentityRule:{enabled:false,allowedPrefixes:[],lastWorkPosPrefixes:[],mappings:[],allowUnmappedUserChoice:true,fallbackUnknownToLastWorkPos:false}""",
    "web default fallback flag",
)
web_rule_path.write_text(web_rule, encoding="utf-8")

index_path = ROOT / "web-admin/index.html"
index = index_path.read_text(encoding="utf-8")
index = replace_once(
    index,
    """          <label class=\"checkRow\" style=\"grid-column:1/-1\">\n            <input id=\"allowUnmappedPosChoice\" type=\"checkbox\" checked>\n            <span>ถ้าพบรหัสใหม่ที่ยังไม่จับคู่ ให้แจ้งผู้ใช้ทันทีและห้ามเดา POS เอง</span>\n          </label>\n""",
    """          <label class=\"checkRow\" style=\"grid-column:1/-1\">\n            <input id=\"allowUnmappedPosChoice\" type=\"checkbox\" checked>\n            <span>ถ้าพบรหัสใหม่ที่ยังไม่จับคู่ ให้แจ้งผู้ใช้ทันทีและห้ามเดา POS เอง</span>\n          </label>\n          <label class=\"checkRow\" style=\"grid-column:1/-1\">\n            <input id=\"fallbackUnknownToLastWorkPos\" type=\"checkbox\">\n            <span><strong>รหัสเครื่องอ่านเพี้ยน → POS สุดท้ายของร้าน</strong><small>ใช้กับแบรนด์ที่มีเครื่องปลายทาง เช่น B01: ถ้า OCR อ่านเป็น 801, 8 หรืออักษรอื่นที่ไม่ตรงรหัส Nxx ที่จับคู่ไว้ ระบบจะใช้ POS สุดท้าย โดยยังให้ POS จริงและ mapping ที่กำหนดไว้มาก่อน</small></span>\n          </label>\n""",
    "admin unknown fallback checkbox",
)
index_path.write_text(index, encoding="utf-8")

simple_path = ROOT / "web-admin/ocr-simple.js"
simple = simple_path.read_text(encoding="utf-8")
simple = replace_once(
    simple,
    """  $(\"allowUnmappedPosChoice\").checked=p.allowUnmappedUserChoice!==false;\n  $(\"posIdentityRuleExample\").textContent=(p.enabled||lastPrefixes.length||visibleMappings.length)\n    ?`กติกาหมายเลขเครื่อง • POS สุดท้าย: ${lastPrefixes.length?lastPrefixes.join(\", \"): \"ไม่มี\"} • จับคู่เฉพาะ ${visibleMappings.length} รายการ`\n""".replace(': \"ไม่มี\"', ':"ไม่มี"'),
    """  $(\"allowUnmappedPosChoice\").checked=p.allowUnmappedUserChoice!==false;\n  $(\"fallbackUnknownToLastWorkPos\").checked=p.fallbackUnknownToLastWorkPos===true;\n  $(\"posIdentityRuleExample\").textContent=(p.enabled||lastPrefixes.length||visibleMappings.length||p.fallbackUnknownToLastWorkPos)\n    ?`กติกาหมายเลขเครื่อง • POS สุดท้าย: ${lastPrefixes.length?lastPrefixes.join(\", \"): \"ไม่มี\"} • จับคู่เฉพาะ ${visibleMappings.length} รายการ${p.fallbackUnknownToLastWorkPos?\" • รหัสอ่านเพี้ยนใช้ POS สุดท้าย\":\"\"}`\n""".replace(': \"ไม่มี\"', ':"ไม่มี"'),
    "admin render fallback rule",
)
simple = replace_once(
    simple,
    """      enabled:$(\"posIdentityMode\").value===\"PREFIX_MAPPING\"||posMappings.length>0||lastWorkPosPrefixes.length>0,\n""",
    """      enabled:$(\"posIdentityMode\").value===\"PREFIX_MAPPING\"||posMappings.length>0||lastWorkPosPrefixes.length>0||$(\"fallbackUnknownToLastWorkPos\")?.checked===true,\n""",
    "admin build active fallback",
)
simple = replace_once(
    simple,
    """      mappings:posMappings,\n      allowUnmappedUserChoice:$(\"allowUnmappedPosChoice\").checked\n""",
    """      mappings:posMappings,\n      allowUnmappedUserChoice:$(\"allowUnmappedPosChoice\").checked,\n      fallbackUnknownToLastWorkPos:$(\"fallbackUnknownToLastWorkPos\")?.checked===true\n""",
    "admin build fallback flag",
)
simple_path.write_text(simple, encoding="utf-8")


# -----------------------------------------------------------------------------
# 3) Simple old-style work flow: POS first, separate photo/note tabs, all POS
#    expanded by default, plus one-tap date/time pickers while keeping manual edit.
# -----------------------------------------------------------------------------
ui_path = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt"
ui = ui_path.read_text(encoding="utf-8")

if "import android.app.DatePickerDialog" not in ui:
    ui = ui.replace("import android.Manifest\n", "import android.Manifest\nimport android.app.DatePickerDialog\nimport android.app.TimePickerDialog\n", 1)
if "import androidx.compose.material.icons.outlined.EditNote" not in ui:
    ui = ui.replace("import androidx.compose.material.icons.outlined.ErrorOutline\n", "import androidx.compose.material.icons.outlined.ErrorOutline\nimport androidx.compose.material.icons.outlined.EditNote\n", 1)
if "import androidx.compose.material.icons.outlined.Schedule" not in ui:
    ui = ui.replace("import androidx.compose.material.icons.outlined.Save\n", "import androidx.compose.material.icons.outlined.Save\nimport androidx.compose.material.icons.outlined.Schedule\n", 1)
if "import java.time.LocalTime" not in ui:
    ui = ui.replace("import java.time.LocalDateTime\n", "import java.time.LocalDateTime\nimport java.time.LocalTime\n", 1)

ui = replace_once(
    ui,
    """private enum class WorkTab(val title: String) {\n    BILL_AND_DATA(\"งานบิล\"),\n    STORE_PHOTOS(\"ภาพร้าน\")\n}\n""",
    """private enum class WorkTab(val title: String) {\n    POS(\"POS\"),\n    RECEIPTS(\"รูปบิล\"),\n    STORE_PHOTOS(\"ภาพร้าน\"),\n    NOTES(\"หมายเหตุ\")\n}\n""",
    "work tab enum",
)
ui = replace_once(
    ui,
    """    var activeTab by remember { mutableStateOf(WorkTab.BILL_AND_DATA) }\n""",
    """    var activeTab by remember { mutableStateOf(WorkTab.POS) }\n""",
    "default POS tab",
)

old_when_start = """            when (activeTab) {\n"""
old_when_end = """        }\n    }\n\n\n    if (ocrReadDetailsOpen) {\n"""
new_when = """            when (activeTab) {\n                WorkTab.POS -> {\n                    itemsIndexed(records) { index, record ->\n                        PosCard(\n                            record = record,\n                            dateWarningText = individualDateWarningsByPos[record.posNumber],\n                            ocrBusy = ocrBusy,\n                            noteOptions = loadedNoteOptions.labels(NoteOptionCategory.POS_NOTE),\n                            noReceiptReasons = loadedNoteOptions.labels(NoteOptionCategory.NO_RECEIPT_REASON),\n                            expectedStoreId = work.expectedReceiptStoreId,\n                            user = user,\n                            onOcr = {\n                                val availableImages = receipts.mapIndexedNotNull { imageIndex, path ->\n                                    path?.let { imageIndex to it }\n                                }\n                                when {\n                                    availableImages.isEmpty() -> {\n                                        message = \"กรุณาเพิ่มภาพบิลก่อนอ่านข้อมูล\"\n                                        activeTab = WorkTab.RECEIPTS\n                                    }\n                                    availableImages.size == 1 -> runRealOcrForWholeImage(availableImages.first().second)\n                                    else -> ocrImagePickerOpen = true\n                                }\n                            },\n                            onChange = {\n                                records[index] = it\n                                saveDraft()\n                            }\n                        )\n                    }\n                }\n\n                WorkTab.RECEIPTS -> {\n                    item {\n                        ReceiptPhotoSection(\n                            receipts = receipts,\n                            ocrBusy = ocrBusy,\n                            onReadReceipt = {\n                                val availableImages = receipts.mapIndexedNotNull { imageIndex, path -> path?.let { imageIndex to it } }\n                                when {\n                                    availableImages.isEmpty() -> message = \"กรุณาเพิ่มภาพบิลก่อนอ่านข้อมูล\"\n                                    availableImages.size == 1 -> runRealOcrForWholeImage(availableImages.first().second)\n                                    else -> ocrImagePickerOpen = true\n                                }\n                            },\n                            onAdd = { index ->\n                                target = \"R\" to index\n                                sourceDialog = true\n                            },\n                            onImageClick = { index, path -> previewTarget = PhotoPreviewTarget(\"R\", index, path) }\n                        )\n                    }\n                }\n\n                WorkTab.STORE_PHOTOS -> {\n                    item {\n                        StorePhotoSection(\n                            stores = stores,\n                            storeCount = storeCount,\n                            onAdd = { index ->\n                                target = \"S\" to index\n                                sourceDialog = true\n                            },\n                            onAddSlot = {\n                                if (storeCount < 10) {\n                                    storeCount += 1\n                                    stores.add(null)\n                                }\n                            },\n                            onImageClick = { index, path -> previewTarget = PhotoPreviewTarget(\"S\", index, path) }\n                        )\n                    }\n                }\n\n                WorkTab.NOTES -> {\n                    item {\n                        CollapsibleAdminNoteField(\n                            value = storeWorkNote,\n                            options = loadedNoteOptions.labels(NoteOptionCategory.STORE_NOTE),\n                            title = \"หมายเหตุข้อมูลร้าน\",\n                            onValueChange = {\n                                storeWorkNote = it\n                                DemoRepository.saveStoreWorkNote(context, work.id, selectedDate, it)\n                                DemoRepository.saveStatus(context, work.id, selectedDate, WorkStatus.DRAFT)\n                            }\n                        )\n                    }\n                }\n            }\n        }\n    }\n\n\n    if (ocrReadDetailsOpen) {\n"""
ui = replace_range(ui, old_when_start, old_when_end, new_when, "work tab content")

ui = replace_once(
    ui,
    """                        pendingOcrResult = null\n                    },\n""",
    """                        activeTab = WorkTab.POS\n                        pendingOcrResult = null\n                    },\n""",
    "return to POS after OCR confirm",
)

ui = replace_once(
    ui,
    """                val shape = when (index) {\n                    0 -> RoundedCornerShape(topStart = 15.dp, bottomStart = 15.dp)\n                    else -> RoundedCornerShape(topEnd = 15.dp, bottomEnd = 15.dp)\n                }\n""",
    """                val shape = when (index) {\n                    0 -> RoundedCornerShape(topStart = 15.dp, bottomStart = 15.dp)\n                    WorkTab.entries.lastIndex -> RoundedCornerShape(topEnd = 15.dp, bottomEnd = 15.dp)\n                    else -> RoundedCornerShape(0.dp)\n                }\n""",
    "four tab shapes",
)
ui = replace_once(
    ui,
    """                            imageVector = if (tab == WorkTab.BILL_AND_DATA) Icons.Outlined.ReceiptLong else Icons.Outlined.Storefront,\n""",
    """                            imageVector = when (tab) {\n                                WorkTab.POS -> Icons.Outlined.PointOfSale\n                                WorkTab.RECEIPTS -> Icons.Outlined.ReceiptLong\n                                WorkTab.STORE_PHOTOS -> Icons.Outlined.Storefront\n                                WorkTab.NOTES -> Icons.Outlined.EditNote\n                            },\n""",
    "four tab icons",
)

ui = replace_once(
    ui,
    """    var reasonExpanded by remember { mutableStateOf(false) }\n    var expanded by remember(record.posNumber) {\n        mutableStateOf(\n            record.customerNo.isNotBlank() ||\n                record.note.isNotBlank() ||\n                record.noReceipt ||\n                record.source.startsWith(\"OCR\")\n        )\n    }\n\n    val customerMissing = !record.noReceipt && record.customerNo.isBlank()\n""",
    """    val context = LocalContext.current\n    var reasonExpanded by remember { mutableStateOf(false) }\n    var expanded by remember(record.posNumber) { mutableStateOf(true) }\n\n    fun manualRecordUpdate(customerNo: String = record.customerNo, billDate: String = record.billDate, billTime: String = record.billTime): PosRecord =\n        record.copy(\n            customerNo = customerNo,\n            billDate = billDate,\n            billTime = billTime,\n            noReceipt = false,\n            source = \"MANUAL\",\n            ocrSourceImagePath = \"\",\n            ocrConfidence = \"\",\n            ocrTemplateName = \"\",\n            ocrWarnings = \"\",\n            ocrCounterCycle = \"CONTINUOUS\"\n        )\n\n    fun openDatePicker() {\n        if (record.noReceipt) return\n        val zone = ZoneId.of(\"Asia/Bangkok\")\n        val current = runCatching { LocalDate.parse(record.billDate, DateTimeFormatter.ofPattern(\"dd/MM/yyyy\")) }\n            .getOrElse { LocalDate.now(zone) }\n        DatePickerDialog(\n            context,\n            { _, year, month, day ->\n                val value = LocalDate.of(year, month + 1, day).format(DateTimeFormatter.ofPattern(\"dd/MM/yyyy\"))\n                onChange(manualRecordUpdate(billDate = value))\n            },\n            current.year, current.monthValue - 1, current.dayOfMonth\n        ).show()\n    }\n\n    fun openTimePicker() {\n        if (record.noReceipt) return\n        val zone = ZoneId.of(\"Asia/Bangkok\")\n        val current = runCatching { LocalTime.parse(record.billTime, DateTimeFormatter.ofPattern(\"HH:mm\")) }\n            .getOrElse { LocalTime.now(zone) }\n        TimePickerDialog(\n            context,\n            { _, hour, minute ->\n                val value = LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(\"HH:mm\"))\n                onChange(manualRecordUpdate(billTime = value))\n            },\n            current.hour, current.minute, true\n        ).show()\n    }\n\n    val customerMissing = !record.noReceipt && record.customerNo.isBlank()\n""",
    "POS default expand and pickers",
)

ui = replace_once(
    ui,
    """                                supportingText = if (dateMissing || dateWarning) {\n                                    { Text(if (dateMissing) \"ยังอ่านไม่พบหรือยังไม่ได้กรอก\" else dateWarningText.orEmpty(), fontSize = 10.sp) }\n                                } else null,\n                                colors = OutlinedTextFieldDefaults.colors(\n""",
    """                                supportingText = if (dateMissing || dateWarning) {\n                                    { Text(if (dateMissing) \"ยังอ่านไม่พบหรือยังไม่ได้กรอก\" else dateWarningText.orEmpty(), fontSize = 10.sp) }\n                                } else null,\n                                trailingIcon = {\n                                    IconButton(onClick = { openDatePicker() }, enabled = !record.noReceipt) {\n                                        Icon(Icons.Outlined.CalendarMonth, contentDescription = \"เลือกวันที่\", tint = Primary)\n                                    }\n                                },\n                                colors = OutlinedTextFieldDefaults.colors(\n""",
    "date picker icon",
)
ui = replace_once(
    ui,
    """                            supportingText = if (timeMissing) {\n                                { Text(\"ยังอ่านไม่พบหรือยังไม่ได้กรอก\", fontSize = 10.sp) }\n                            } else null,\n                            singleLine = true\n""",
    """                            supportingText = if (timeMissing) {\n                                { Text(\"ยังอ่านไม่พบหรือยังไม่ได้กรอก\", fontSize = 10.sp) }\n                            } else null,\n                            trailingIcon = {\n                                IconButton(onClick = { openTimePicker() }, enabled = !record.noReceipt) {\n                                    Icon(Icons.Outlined.Schedule, contentDescription = \"เลือกเวลา\", tint = Primary)\n                                }\n                            },\n                            singleLine = true\n""",
    "time picker icon",
)
ui_path.write_text(ui, encoding="utf-8")


# -----------------------------------------------------------------------------
# 4) Regression tests + version bump.
# -----------------------------------------------------------------------------
test_path = ROOT / "android-app/app/src/test/java/com/receiptocr/app/ocr/CjTerminalFallbackRound10420Test.kt"
test_path.write_text(
'''package com.receiptocr.app.ocr

import com.receiptocr.app.config.PosIdentityMapping
import com.receiptocr.app.config.PosIdentityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CjTerminalFallbackRound10420Test {
    private fun cjRule() = PosIdentityRule(
        enabled = true,
        allowedPrefixes = listOf("N", "B"),
        lastWorkPosPrefixes = listOf("B"),
        mappings = listOf(
            PosIdentityMapping("N01", workPos = 1),
            PosIdentityMapping("N02", workPos = 2),
            PosIdentityMapping("N03", workPos = 3)
        ),
        fallbackUnknownToLastWorkPos = true
    )

    @Test
    fun knownNAndBIdentitiesKeepTheirConfiguredMeaning() {
        val available = setOf(1, 2, 3, 4)
        assertEquals(1, PosIdentityResolver.resolve("N01", cjRule(), available)?.workPos)
        assertEquals(2, PosIdentityResolver.resolve("N02", cjRule(), available)?.workPos)
        assertEquals(3, PosIdentityResolver.resolve("N03", cjRule(), available)?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("B01", cjRule(), available)?.workPos)
    }

    @Test
    fun b01OcrVariantsAndUnknownPrefixUseLastStorePos() {
        val available = setOf(1, 2, 3, 4)
        assertEquals(4, PosIdentityResolver.resolve("801", cjRule(), available)?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("8O1", cjRule(), available)?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("8", cjRule(), available)?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("X01", cjRule(), available)?.workPos)
        assertEquals(4, PosEvidenceFusion.resolveEvidencePosIdentity("8", cjRule(), available))
        assertEquals(4, TemplateSequenceFallback.resolveSequencePosIdentity("X01", cjRule(), available))
    }

    @Test
    fun realNumericPosAlwaysWinsBeforeUnknownFallback() {
        val available = setOf(1, 2, 3, 4, 8)
        assertEquals(8, PosIdentityResolver.resolve("8", cjRule(), available)?.workPos)
    }

    @Test
    fun missingMappingInsideAllowedNFamilyIsNotSilentlyMovedToLastPos() {
        assertNull(PosIdentityResolver.resolve("N04", cjRule(), setOf(1, 2, 3, 4)))
    }

    @Test
    fun brandsWithoutFallbackKeepPreviousBehavior() {
        val rule = cjRule().copy(fallbackUnknownToLastWorkPos = false)
        assertEquals(8, PosIdentityResolver.resolve("8", rule, setOf(1, 2, 3, 4))?.workPos)
        assertNull(PosIdentityResolver.resolve("X01", rule, setOf(1, 2, 3, 4)))
    }
}
''',
    encoding="utf-8",
)

js_test_path = ROOT / "tests/round10420-cj-terminal-fallback.test.js"
js_test_path.write_text(
'''const assert = require('assert');
const fs = require('fs');
const rules = require('../web-admin/receipt-date-rules.js');

const rule = rules.normalizePosIdentityRule({
  enabled: true,
  allowedPrefixes: ['N','B'],
  lastWorkPosPrefixes: ['B'],
  mappings: [
    {receiptPos:'N01',workPos:1},
    {receiptPos:'N02',workPos:2},
    {receiptPos:'N03',workPos:3}
  ],
  fallbackUnknownToLastWorkPos: true
});
const pos = [1,2,3,4];
assert.strictEqual(rules.resolvePosIdentity('N01', rule, pos), 1);
assert.strictEqual(rules.resolvePosIdentity('N03', rule, pos), 3);
assert.strictEqual(rules.resolvePosIdentity('B01', rule, pos), 4);
assert.strictEqual(rules.resolvePosIdentity('8', rule, pos), 4);
assert.strictEqual(rules.resolvePosIdentity('X01', rule, pos), 4);
assert.strictEqual(rules.resolvePosIdentity('N04', rule, pos), null);
assert.strictEqual(rules.resolvePosIdentity('8', rule, [1,2,3,4,8]), 8);

const oldRule = {...rule, fallbackUnknownToLastWorkPos:false};
assert.strictEqual(rules.resolvePosIdentity('8', oldRule, pos), 8);
assert.strictEqual(rules.resolvePosIdentity('X01', oldRule, pos), null);

const index = fs.readFileSync('web-admin/index.html','utf8');
const simple = fs.readFileSync('web-admin/ocr-simple.js','utf8');
const model = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/config/ReceiptRuleModels.kt','utf8');
const parser = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/data/remote/OcrTemplateRepository.kt','utf8');
const resolver = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/ocr/PosIdentityResolver.kt','utf8');
const ui = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt','utf8');
assert(index.includes('id="fallbackUnknownToLastWorkPos"'));
assert(simple.includes('fallbackUnknownToLastWorkPos'));
assert(model.includes('fallbackUnknownToLastWorkPos: Boolean = false'));
assert(parser.includes('root.optBoolean("fallbackUnknownToLastWorkPos", false)'));
assert(resolver.includes('prefix !in allowedPrefixes'));
assert(ui.includes('POS("POS")'));
assert(ui.includes('RECEIPTS("รูปบิล")'));
assert(ui.includes('NOTES("หมายเหตุ")'));
assert(ui.includes('mutableStateOf(true)'));
assert(ui.includes('DatePickerDialog('));
assert(ui.includes('TimePickerDialog('));
console.log('Round104.20 CJ terminal fallback and simple POS UI checks passed');
''',
    encoding="utf-8",
)

gradle_path = ROOT / "android-app/app/build.gradle.kts"
gradle = gradle_path.read_text(encoding="utf-8")
gradle = gradle.replace("versionCode = 111", "versionCode = 112", 1)
gradle = gradle.replace('versionName = "0.104.19"', 'versionName = "0.104.20"', 1)
if "versionCode = 112" not in gradle or 'versionName = "0.104.20"' not in gradle:
    raise SystemExit("Round104.20 version bump failed")
gradle_path.write_text(gradle, encoding="utf-8")

legacy_test_path = ROOT / "tests/round10417-pos-terminal.test.js"
legacy_test = legacy_test_path.read_text(encoding="utf-8")
legacy_test = legacy_test.replace("versionCode = 111", "versionCode = 112", 1)
legacy_test = legacy_test.replace('versionName = "0.104.19"', 'versionName = "0.104.20"', 1)
legacy_test = legacy_test.replace("Round104.19", "Round104.20")
legacy_test_path.write_text(legacy_test, encoding="utf-8")

print("Round104.20 CJ terminal fallback + simple POS UI patch applied")
