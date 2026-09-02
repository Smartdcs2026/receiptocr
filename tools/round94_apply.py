from pathlib import Path
import re

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
        raise SystemExit(f"{rel}: expected exactly one match, got {count}: {old[:120]!r}")
    write(rel, text.replace(old, new, 1))


def insert_after(rel, anchor, addition):
    text = read(rel)
    if addition.strip() in text:
        return
    count = text.count(anchor)
    if count != 1:
        raise SystemExit(f"{rel}: anchor count={count}: {anchor[:120]!r}")
    write(rel, text.replace(anchor, anchor + addition, 1))


# ---------------------------------------------------------------------------
# Version: Round93 is the immutable baseline; Round94 is additive only.
# ---------------------------------------------------------------------------
replace_once(
    "android-app/app/build.gradle.kts",
    '        versionCode = 95\n        versionName = "0.93.0"',
    '        versionCode = 96\n        versionName = "0.94.0"',
)


# ---------------------------------------------------------------------------
# Brand-level POS identity mapping model. Defaults are disabled so Round93
# numeric POS behavior is unchanged unless Admin explicitly enables mapping.
# ---------------------------------------------------------------------------
receipt_rules = "android-app/app/src/main/java/com/receiptocr/app/config/ReceiptRuleModels.kt"
insert_after(
    receipt_rules,
    "data class StoreIdentityRule(\n    val enabled: Boolean = false,\n    /** ข้อความ/รหัสจำเพาะที่ OCR ต้องพบเพื่อยืนยันว่าเป็นร้านที่ถูกต้อง */\n    val requiredTokens: List<String> = emptyList(),\n    /** true = ต้องพบครบทุก token, false = พบอย่างน้อยหนึ่ง token */\n    val requireAll: Boolean = false\n)\n",
    '''\n\n/** จับคู่รหัสเครื่องที่พิมพ์บนบิล เช่น N01/B01 ไปยังช่อง POS ในงาน */\ndata class PosIdentityMapping(\n    val receiptPos: String,\n    val workPos: Int\n)\n\ndata class PosIdentityRule(\n    /** ปิดไว้เป็นค่าเริ่มต้นเพื่อรักษาพฤติกรรม Round93 */\n    val enabled: Boolean = false,\n    /** ตัวอักษรนำหน้าที่แบรนด์นี้อนุญาต เช่น N,B,A */\n    val allowedPrefixes: List<String> = emptyList(),\n    /** จับคู่ เช่น N01 -> POS 1, B01 -> POS 2 */\n    val mappings: List<PosIdentityMapping> = emptyList(),\n    /** ถ้าเจอรหัสใหม่ ห้ามเดา POS เอง; ให้แจ้งผู้ใช้/ผู้ดูแล */\n    val allowUnmappedUserChoice: Boolean = true\n)\n''',
)
replace_once(
    receipt_rules,
    "    val storeIdentityRule: StoreIdentityRule = StoreIdentityRule(),\n    val customerCounterMode: CustomerCounterMode = CustomerCounterMode.UNSPECIFIED",
    "    val storeIdentityRule: StoreIdentityRule = StoreIdentityRule(),\n    val posIdentityRule: PosIdentityRule = PosIdentityRule(),\n    val customerCounterMode: CustomerCounterMode = CustomerCounterMode.UNSPECIFIED",
)


# ---------------------------------------------------------------------------
# Parse brand-level mapping returned by the existing receiptRule API.
# ---------------------------------------------------------------------------
repo = "android-app/app/src/main/java/com/receiptocr/app/data/remote/OcrTemplateRepository.kt"
replace_once(
    repo,
    "            preventDuplicateReceiptData = root.optBoolean(\"preventDuplicateReceiptData\", true),\n            customerCounterMode = enumValueOfOrNull<CustomerCounterMode>(root.optString(\"customerCounterMode\"))",
    "            preventDuplicateReceiptData = root.optBoolean(\"preventDuplicateReceiptData\", true),\n            posIdentityRule = parsePosIdentityRule(root.optJSONObject(\"posIdentityRule\")),\n            customerCounterMode = enumValueOfOrNull<CustomerCounterMode>(root.optString(\"customerCounterMode\"))",
)
insert_after(
    repo,
    "    private inline fun <reified T : Enum<T>> enumValueOfOrNull(raw: String?): T? =\n        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }\n",
    '''\n\n    private fun parsePosIdentityRule(root: JSONObject?): PosIdentityRule {\n        if (root == null) return PosIdentityRule()\n        val prefixes = root.optJSONArray("allowedPrefixes")\n        val mappings = root.optJSONArray("mappings")\n        return PosIdentityRule(\n            enabled = root.optBoolean("enabled", false),\n            allowedPrefixes = buildList {\n                if (prefixes != null) {\n                    for (i in 0 until prefixes.length()) {\n                        prefixes.optString(i).trim().uppercase().takeIf { it.isNotBlank() }?.let(::add)\n                    }\n                }\n            }.distinct(),\n            mappings = buildList {\n                if (mappings != null) {\n                    for (i in 0 until mappings.length()) {\n                        val item = mappings.optJSONObject(i) ?: continue\n                        val receiptPos = item.optString("receiptPos").trim().uppercase()\n                        val workPos = item.optInt("workPos", 0)\n                        if (receiptPos.isNotBlank() && workPos > 0) add(PosIdentityMapping(receiptPos, workPos))\n                    }\n                }\n            },\n            allowUnmappedUserChoice = root.optBoolean("allowUnmappedUserChoice", true)\n        )\n    }\n''',
)


# ---------------------------------------------------------------------------
# Preserve the original receipt-side POS identity on every OCR record.
# ---------------------------------------------------------------------------
models = "android-app/app/src/main/java/com/receiptocr/app/model/Models.kt"
replace_once(
    models,
    "    /** รอบที่ใช้ตรวจเลขลูกค้าซ้ำ มาจากแม่แบบเดียวกับหน้า Admin */\n    val ocrCounterCycle: String = \"CONTINUOUS\"",
    "    /** รอบที่ใช้ตรวจเลขลูกค้าซ้ำ มาจากแม่แบบเดียวกับหน้า Admin */\n    val ocrCounterCycle: String = \"CONTINUOUS\",\n    /** รหัส POS ที่เห็นบนบิลก่อนจับคู่กับช่องงาน เช่น N01 / B01 */\n    val ocrRawPosIdentity: String = \"\"",
)


# ---------------------------------------------------------------------------
# Captured POS fields may use any alphabetic prefix. We only make the
# standalone parser generic; broad raw-text scanning remains conservative.
# ---------------------------------------------------------------------------
normalizer = "android-app/app/src/main/java/com/receiptocr/app/ocr/OcrTextNormalizer.kt"
replace_once(
    normalizer,
    '    private val standalonePos = Regex("(?i)^\\\\s*(?:[NB]\\\\s*)?([$OCR_DIGITS]{1,3})\\\\s*$")',
    '    private val standalonePos = Regex("(?i)^\\\\s*(?:[A-Z]{1,4}\\\\s*)?([$OCR_DIGITS]{1,3})\\\\s*$")',
)
insert_after(
    normalizer,
    "    fun normalizeDigits(value: String): String = value.map { character ->\n        when (character) {\n            'O', 'o' -> '0'\n            'I', 'i', 'l', '|' -> '1'\n            else -> character\n        }\n    }.joinToString(\"\")\n",
    '''\n\n    /** แสดงรหัส POS ตามที่เห็นบนบิลโดยยังเก็บอักษรนำหน้าไว้ */\n    fun displayPosIdentity(value: String): String? {\n        var text = normalizeDigits(value).trim().uppercase().replace(Regex("\\s+"), "")\n        text = text.removePrefix("POS").trimStart(':', '#', '=', '-')\n        val match = Regex("^([A-Z]{1,4})?([0-9]{1,3})$").matchEntire(text) ?: return null\n        return match.groupValues[1] + match.groupValues[2]\n    }\n\n    /** key สำหรับเทียบ mapping: N01/N1 -> N1, B01 -> B1, 01 -> 1 */\n    fun normalizePosIdentity(value: String): String? {\n        val display = displayPosIdentity(value) ?: return null\n        val match = Regex("^([A-Z]{1,4})?([0-9]{1,3})$").matchEntire(display) ?: return null\n        val number = match.groupValues[2].toIntOrNull() ?: return null\n        if (number <= 0) return null\n        return match.groupValues[1] + number.toString()\n    }\n''',
)


# ---------------------------------------------------------------------------
# New resolver: mapping is brand-level and opt-in. Numeric-only POS continues
# to work exactly as before. Unknown prefixed identities are never guessed.
# ---------------------------------------------------------------------------
write(
    "android-app/app/src/main/java/com/receiptocr/app/ocr/PosIdentityResolver.kt",
    '''package com.receiptocr.app.ocr\n\nimport com.receiptocr.app.config.PosIdentityRule\nimport com.receiptocr.app.config.UniversalOcrTemplate\n\ndata class ResolvedPosIdentity(\n    val display: String,\n    val key: String,\n    val workPos: Int,\n    val mappedByBrandRule: Boolean\n)\n\nobject PosIdentityResolver {\n    fun resolve(raw: String, rule: PosIdentityRule): ResolvedPosIdentity? {\n        val display = OcrTextNormalizer.displayPosIdentity(raw) ?: return null\n        val key = OcrTextNormalizer.normalizePosIdentity(raw) ?: return null\n        val numeric = OcrTextNormalizer.parsePosNumber(display) ?: return null\n\n        if (!rule.enabled) {\n            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)\n        }\n\n        val prefix = key.takeWhile(Char::isLetter)\n        if (prefix.isBlank()) {\n            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)\n        }\n\n        val allowed = rule.allowedPrefixes.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()\n        if (allowed.isNotEmpty() && prefix !in allowed) return null\n\n        val mapping = rule.mappings.firstOrNull { item ->\n            OcrTextNormalizer.normalizePosIdentity(item.receiptPos) == key && item.workPos > 0\n        } ?: return null\n\n        return ResolvedPosIdentity(display, key, mapping.workPos, mappedByBrandRule = true)\n    }\n\n    fun findUnmappedIdentities(\n        rawTexts: List<String>,\n        templates: List<UniversalOcrTemplate>,\n        rule: PosIdentityRule\n    ): List<String> {\n        if (!rule.enabled) return emptyList()\n        val found = linkedSetOf<String>()\n        templates.filter { it.active }.forEach { template ->\n            rawTexts.filter { it.isNotBlank() }.forEach { raw ->\n                TemplateSequenceFallback.parseText(raw, template).forEach { fields ->\n                    val value = fields["POS_NUMBER"].orEmpty()\n                    val display = OcrTextNormalizer.displayPosIdentity(value) ?: return@forEach\n                    val key = OcrTextNormalizer.normalizePosIdentity(value) ?: return@forEach\n                    if (key.any(Char::isLetter) && resolve(value, rule) == null) found += display\n                }\n            }\n        }\n        return found.toList().sorted()\n    }\n}\n''',
)


# ---------------------------------------------------------------------------
# New all-template sequence collector. It supplements (never replaces) the
# Round93 strict result and therefore only fills missing/weak POS records.
# ---------------------------------------------------------------------------
write(
    "android-app/app/src/main/java/com/receiptocr/app/ocr/MultiTemplateSequenceCollector.kt",
    '''package com.receiptocr.app.ocr\n\nimport com.receiptocr.app.config.BrandReceiptRule\nimport com.receiptocr.app.config.UniversalOcrTemplate\nimport com.receiptocr.app.model.PosRecord\nimport com.receiptocr.app.model.WorkItem\nimport java.time.LocalDate\n\n/**\n * อ่านทุก Template ที่เปิดใช้ของแบรนด์จากภาพเดียว ไม่หยุดที่ Template แรก\n * Round93 strict interpreter ยังเป็นผลหลัก ตัวนี้มีหน้าที่เติม POS ที่ตกหล่น\n */\nobject MultiTemplateSequenceCollector {\n    private data class Candidate(\n        val template: UniversalOcrTemplate,\n        val fields: Map<String, String>,\n        val workPos: Int,\n        val rawIdentity: String,\n        val score: Int\n    )\n\n    fun apply(\n        rawTexts: List<String>,\n        records: List<PosRecord>,\n        work: WorkItem,\n        workDate: LocalDate,\n        imagePath: String,\n        templates: List<UniversalOcrTemplate>,\n        receiptRule: BrandReceiptRule\n    ): UniversalTemplateResult {\n        if (templates.none { it.active } || rawTexts.none { it.isNotBlank() }) return failed(records)\n        val allowedPos = records.map { it.posNumber }.toSet()\n\n        val candidates = buildList {\n            templates.filter { it.active }.forEach { template ->\n                rawTexts.filter { it.isNotBlank() }.forEach { raw ->\n                    TemplateSequenceFallback.parseText(raw, template).forEach { fields ->\n                        val rawPos = fields["POS_NUMBER"].orEmpty()\n                        val resolved = PosIdentityResolver.resolve(rawPos, receiptRule.posIdentityRule)\n                            ?: return@forEach\n                        if (template.validation.pos.mustExistInStorePlan && resolved.workPos !in allowedPos) {\n                            return@forEach\n                        }\n                        val coreCount = listOf("BILL_DATE", "BILL_TIME", "CUSTOMER_VALUE")\n                            .count { !fields[it].isNullOrBlank() }\n                        add(\n                            Candidate(\n                                template = template,\n                                fields = fields,\n                                workPos = resolved.workPos,\n                                rawIdentity = resolved.display,\n                                score = template.priority + coreCount * 100 +\n                                    if (fields["STORE_ID"].isNullOrBlank()) 0 else 20\n                            )\n                        )\n                    }\n                }\n            }\n        }.distinctBy { candidate ->\n            candidate.template.templateId + "|" + candidate.workPos + "|" +\n                candidate.fields.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" }\n        }\n\n        if (candidates.isEmpty()) return failed(records)\n\n        val bestByPos = candidates.groupBy { it.workPos }.mapValues { (_, items) ->\n            items.maxWithOrNull(\n                compareBy<Candidate> { item ->\n                    listOf("BILL_DATE", "BILL_TIME", "CUSTOMER_VALUE", "STORE_ID")\n                        .count { !item.fields[it].isNullOrBlank() }\n                }.thenBy { it.score }\n            )!!\n        }.toSortedMap()\n\n        val updated = records.toMutableList()\n        val warnings = linkedMapOf<Int, MutableList<String>>()\n        val usedNames = linkedSetOf<String>()\n        val detected = mutableListOf<Int>()\n\n        bestByPos.forEach { (pos, candidate) ->\n            val index = updated.indexOfFirst { it.posNumber == pos }\n            if (index < 0) return@forEach\n            val current = updated[index]\n            val posWarnings = warnings.getOrPut(pos) { mutableListOf() }\n            usedNames += candidate.template.templateName\n            detected += pos\n\n            if (current.noReceipt) {\n                posWarnings += "พบข้อมูลของ POS $pos ในภาพ แต่ POS นี้ถูกระบุว่าไม่ได้บิล • ระบบยังไม่เปลี่ยนข้อมูลเดิม"\n                return@forEach\n            }\n\n            val rawDate = candidate.fields["BILL_DATE"].orEmpty().trim()\n            val dateField = candidate.template.recognition.rows.asSequence()\n                .flatMap { it.fields.asSequence() }\n                .firstOrNull { it.type.equals("BILL_DATE", ignoreCase = true) }\n            val dateResult = rawDate.takeIf { it.isNotBlank() }?.let { value ->\n                ReceiptDateOcrNormalizer.normalizeForField(\n                    raw = value,\n                    field = dateField,\n                    referenceDate = workDate,\n                    allowCanonicalInput = false\n                )\n            }\n            val date = dateResult?.value.orEmpty()\n            val time = ReceiptTimeOcrNormalizer.normalize(candidate.fields["BILL_TIME"].orEmpty()).value.orEmpty()\n            val customer = candidate.fields["CUSTOMER_VALUE"].orEmpty().filter(Char::isDigit)\n            val store = candidate.fields["STORE_ID"].orEmpty().trim()\n\n            val core = candidate.template.validation.requiredCore\n            if (core.date && rawDate.isBlank()) posWarnings += "ไม่พบวันที่ตามเงื่อนไขที่กำหนด"\n            if (core.time && time.isBlank()) posWarnings += "ไม่พบเวลาตามเงื่อนไขที่กำหนด"\n            if (core.customerValue && customer.isBlank()) posWarnings += "ไม่พบยอด/เลขลูกค้าตามเงื่อนไขที่กำหนด"\n            if (rawDate.isNotBlank() && date.isBlank()) {\n                posWarnings += dateResult?.warning ?: "วันที่บิลยังไม่ตรงรูปแบบที่กำหนด"\n            }\n\n            val expectsStore = candidate.template.recognition.rows.asSequence()\n                .flatMap { it.fields.asSequence() }\n                .any { it.type.equals("STORE_ID", ignoreCase = true) }\n\n            updated[index] = current.copy(\n                customerNo = customer.ifBlank { current.customerNo },\n                billDate = date.ifBlank { current.billDate },\n                billTime = time.ifBlank { current.billTime },\n                source = "OCR-MULTI-TEMPLATE",\n                ocrSourceImagePath = imagePath,\n                ocrTemplateName = candidate.template.templateName,\n                ocrWarnings = posWarnings.distinct().joinToString(" • "),\n                ocrRawBillDate = rawDate.ifBlank { current.ocrRawBillDate },\n                ocrStoreId = store.ifBlank { current.ocrStoreId },\n                ocrStoreIdExpected = expectsStore || current.ocrStoreIdExpected,\n                ocrCounterCycle = candidate.template.duplicatePolicy.customerCounterCycle.uppercase(),\n                ocrRawPosIdentity = candidate.rawIdentity\n            )\n        }\n\n        val ordered = detected.distinct().sorted()\n        if (ordered.isEmpty()) return failed(records)\n        val fieldTypes = bestByPos.values.flatMap { it.fields.keys }.toSet()\n        val extracted = linkedMapOf<String, List<String>>()\n        fieldTypes.forEach { type ->\n            extracted[type] = ordered.map { pos -> bestByPos[pos]?.fields?.get(type).orEmpty() }\n        }\n\n        return UniversalTemplateResult(\n            records = updated,\n            message = "อ่านทุกเงื่อนไขที่ตรงในภาพแล้ว • พบ ${ordered.size} เครื่อง",\n            templateName = usedNames.joinToString(" / "),\n            detectedPos = ordered,\n            extracted = extracted,\n            validationWarnings = warnings.mapValues { it.value.distinct() },\n            usedUniversalTemplate = true\n        )\n    }\n\n    private fun failed(records: List<PosRecord>) = UniversalTemplateResult(\n        records = records,\n        message = "ยังไม่พบข้อมูลเพิ่มเติมจากเงื่อนไขอื่น",\n        usedUniversalTemplate = true\n    )\n}\n''',
)


# ---------------------------------------------------------------------------
# Integrate all-template collection as a supplement, then keep all existing
# Round93 fallbacks. Also surface unknown prefixed identities without guessing.
# ---------------------------------------------------------------------------
pipeline = "android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt"
replace_once(
    pipeline,
    '''        // Round88: อย่าหยุดเพียงเพราะตัวอ่านหนึ่งวิธีพบ POS บางเครื่อง\n        // ทุกวิธีมีหน้าที่ช่วยเติมเฉพาะ POS ที่ยังขาด/ยังไม่ครบ แล้วค่อยรวมผลตาม POS\n        val expectedPosSet = records.map { it.posNumber }.toSet()\n        val evidenceFusion = if (templates.isNotEmpty() && needsTemplateHelp(strictTemplateResult, expectedPosSet)) {\n            PosEvidenceFusion.apply(\n                rawTexts = mlTexts.map { it.text },\n                records = records,\n                work = work,\n                workDate = workDate,\n                imagePath = imagePath,\n                templates = templates\n            )\n        } else null\n        val afterFusion = mergeUniversalTemplateResults(records, strictTemplateResult, evidenceFusion)\n\n        val sequenceFallback = if (templates.isNotEmpty() && needsTemplateHelp(afterFusion, expectedPosSet)) {''',
    '''        // Round94: อ่านทุก Template ที่ตรงในภาพเดียวก่อน แล้วใช้ Round93 strict เป็นผลหลัก\n        // ตัวรวบรวมใหม่นี้เติมเฉพาะ POS ที่ strict ยังขาด/ยังอ่อน จึงไม่เขียนทับผลที่ผ่านแล้ว\n        val expectedPosSet = records.map { it.posNumber }.toSet()\n        val multiTemplateResult = if (templates.isNotEmpty()) {\n            MultiTemplateSequenceCollector.apply(\n                rawTexts = mlTexts.map { it.text },\n                records = records,\n                work = work,\n                workDate = workDate,\n                imagePath = imagePath,\n                templates = templates,\n                receiptRule = receiptRule\n            )\n        } else null\n        val afterMultiTemplate = mergeUniversalTemplateResults(records, strictTemplateResult, multiTemplateResult)\n\n        // Round88 safeguards remain unchanged after the Round94 supplement.\n        val evidenceFusion = if (templates.isNotEmpty() && needsTemplateHelp(afterMultiTemplate, expectedPosSet)) {\n            PosEvidenceFusion.apply(\n                rawTexts = mlTexts.map { it.text },\n                records = records,\n                work = work,\n                workDate = workDate,\n                imagePath = imagePath,\n                templates = templates\n            )\n        } else null\n        val afterFusion = mergeUniversalTemplateResults(records, afterMultiTemplate, evidenceFusion)\n\n        val sequenceFallback = if (templates.isNotEmpty() && needsTemplateHelp(afterFusion, expectedPosSet)) {''',
)
replace_once(
    pipeline,
    '''        val templateResult = mergeUniversalTemplateResults(records, afterFusion, sequenceFallback)\n        val duplicatePosWarnings = DuplicatePosEvidenceDetector.detect(\n            rawTexts = mlTexts.map { it.text },\n            templates = templates,\n            allowedPos = expectedPosSet\n        )''',
    '''        val templateResult = mergeUniversalTemplateResults(records, afterFusion, sequenceFallback)\n        val unmappedPosIdentities = PosIdentityResolver.findUnmappedIdentities(\n            rawTexts = mlTexts.map { it.text },\n            templates = templates,\n            rule = receiptRule.posIdentityRule\n        )\n        val duplicatePosWarnings = DuplicatePosEvidenceDetector.detect(\n            rawTexts = mlTexts.map { it.text },\n            templates = templates,\n            allowedPos = expectedPosSet,\n            posIdentityRule = receiptRule.posIdentityRule\n        )''',
)
replace_once(
    pipeline,
    '''                duplicatePosWarnings.toSortedMap().forEach { (_, warning) -> add(warning) }\n                storeAssessment?.warningsByPos?.toSortedMap()?.forEach { (pos, warning) -> add("POS $pos: $warning") }''',
    '''                duplicatePosWarnings.toSortedMap().forEach { (_, warning) -> add(warning) }\n                unmappedPosIdentities.forEach { identity ->\n                    add("พบหมายเลขเครื่อง $identity ที่ยังไม่ได้กำหนดว่าจะลง POS ใดในงานนี้")\n                }\n                storeAssessment?.warningsByPos?.toSortedMap()?.forEach { (pos, warning) -> add("POS $pos: $warning") }''',
)
replace_once(
    pipeline,
    '''                warnings = listOf(\n                    *imageQualityWarnings.toTypedArray(),\n                    if (templates.isEmpty()) "ยังไม่มีเงื่อนไขสำหรับแบรนด์นี้ กรุณาแจ้งผู้ดูแล"\n                    else "ยังแยกข้อมูลบิลไม่ได้ครบ • ลองเพิ่มภาพบิลอีกช่องหรือถ่ายใหม่ให้ชัดขึ้น"\n                ),''',
    '''                warnings = buildList {\n                    addAll(imageQualityWarnings)\n                    unmappedPosIdentities.forEach { identity ->\n                        add("พบหมายเลขเครื่อง $identity ที่ยังไม่ได้กำหนดว่าจะลง POS ใดในงานนี้")\n                    }\n                    if (templates.isEmpty()) add("ยังไม่มีเงื่อนไขสำหรับแบรนด์นี้ กรุณาแจ้งผู้ดูแล")\n                    else if (unmappedPosIdentities.isEmpty()) add("ยังแยกข้อมูลบิลไม่ได้ครบ • ลองเพิ่มภาพบิลอีกช่องหรือถ่ายใหม่ให้ชัดขึ้น")\n                },''',
)


# ---------------------------------------------------------------------------
# Duplicate POS detector respects brand mapping, so N01 and B01 are separate
# when Admin maps them to different work POS slots.
# ---------------------------------------------------------------------------
dup = "android-app/app/src/main/java/com/receiptocr/app/ocr/DuplicatePosEvidenceDetector.kt"
insert_after(dup, "package com.receiptocr.app.ocr\n", "\nimport com.receiptocr.app.config.PosIdentityRule\n")
replace_once(
    dup,
    '''        rawTexts: List<String>,\n        templates: List<UniversalOcrTemplate>,\n        allowedPos: Set<Int>\n    ): Map<Int, String> {''',
    '''        rawTexts: List<String>,\n        templates: List<UniversalOcrTemplate>,\n        allowedPos: Set<Int>,\n        posIdentityRule: PosIdentityRule = PosIdentityRule()\n    ): Map<Int, String> {''',
)
replace_once(
    dup,
    '''                    val pos = OcrTextNormalizer.parsePosNumber(fields["POS_NUMBER"].orEmpty())\n                        ?: return@forEach\n                    if (allowedPos.isNotEmpty() && pos !in allowedPos) return@forEach''',
    '''                    val resolved = PosIdentityResolver.resolve(\n                        fields["POS_NUMBER"].orEmpty(),\n                        posIdentityRule\n                    ) ?: return@forEach\n                    val pos = resolved.workPos\n                    if (allowedPos.isNotEmpty() && pos !in allowedPos) return@forEach''',
)


# ---------------------------------------------------------------------------
# A user-selected "ไม่ได้บิล" is authoritative. OCR may report that it found
# data, but must never silently clear the user's reason.
# ---------------------------------------------------------------------------
acc = "android-app/app/src/main/java/com/receiptocr/app/ocr/OcrAccumulationPolicy.kt"
replace_once(
    acc,
    '''        val merged = workingOriginals.map { original ->\n            if (original.posNumber !in currentDetectedPos) return@map original\n\n            val template = templateRecords.firstOrNull { it.posNumber == original.posNumber } ?: original''',
    '''        val merged = workingOriginals.map { original ->\n            if (original.posNumber !in currentDetectedPos) return@map original\n            if (original.noReceipt) {\n                conflicts[original.posNumber] =\n                    "พบข้อมูลของ POS ${original.posNumber} ในภาพ แต่ POS นี้ถูกระบุว่าไม่ได้บิล • ระบบยังไม่เปลี่ยนข้อมูลเดิม"\n                return@map original\n            }\n\n            val template = templateRecords.firstOrNull { it.posNumber == original.posNumber } ?: original''',
)


# ---------------------------------------------------------------------------
# User-facing language: remove technical duplicate phrasing and make store
# integrity explicit. Technical raw warnings remain internally for validation.
# ---------------------------------------------------------------------------
messages = "android-app/app/src/main/java/com/receiptocr/app/ui/UserFacingOcrMessages.kt"
replace_once(
    messages,
    '''        if (text.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS")) {\n            val pos = Regex("POS\\\\s*(\\\\d+)").find(text)?.groupValues?.getOrNull(1)\n            messages += if (pos != null) "พบข้อมูลบิลมากกว่าหนึ่งชุดสำหรับ POS $pos กรุณาตรวจภาพบิล" else "พบข้อมูลบิลมากกว่าหนึ่งชุด กรุณาตรวจภาพบิล"\n        }''',
    '''        if (text.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS")) {\n            val pos = Regex("POS\\\\s*(\\\\d+)").find(text)?.groupValues?.getOrNull(1)\n            messages += if (pos != null)\n                "พบ POS $pos ซ้ำในภาพ กรุณาตรวจว่ามีบิลของเครื่องเดียวกันมากกว่า 1 ใบ"\n            else "พบ POS ซ้ำในภาพ กรุณาตรวจบิลก่อนใช้ข้อมูล"\n        }''',
)
replace_once(
    messages,
    '''        if (text.contains("รหัสร้าน") && text.contains("ไม่ตรง")) messages += "รหัสร้านบนบิลไม่ตรงกับร้านในแผนงาน"\n        else if (text.contains("ยืนยันร้านไม่ได้") || text.contains("ไม่พบรหัสร้าน")) messages += "ยังตรวจสอบรหัสร้านจากบิลไม่ได้ กรุณาตรวจภาพบิล"''',
    '''        when {\n            text.contains("พบบิลสลับร้าน") -> messages += "พบบิลสลับร้าน กรุณาตรวจและเปลี่ยนเป็นบิลของร้านที่กำลังทำงาน"\n            text.contains("บิลผิดร้าน") -> messages += "บิลผิดร้าน รหัสร้านบนบิลไม่ตรงกับร้านที่กำลังทำงาน"\n            text.contains("รหัสร้าน") && text.contains("ไม่ตรง") -> messages += "รหัสร้านบนบิลไม่ตรงกับร้านที่กำลังทำงาน"\n            text.contains("ยืนยันร้านไม่ได้") || text.contains("ไม่พบรหัสร้าน") -> messages += "ยังตรวจสอบรหัสร้านจากบิลไม่ได้ กรุณาตรวจภาพบิล"\n        }''',
)
insert_after(
    messages,
    '''        if (text.contains("ยังอ่านไม่ครบ") && text.contains("ขาด POS")) {\n            Regex("ขาด POS\\\\s+([^•]+)").find(text)?.groupValues?.getOrNull(1)?.trim()?.let { messages += "ยังอ่านไม่ครบ • ขาด POS $it" }\n        }\n''',
    '''        if (text.contains("ยังไม่ได้กำหนดว่าจะลง POS ใด")) {\n            val identity = Regex("หมายเลขเครื่อง\\s+([A-Za-z0-9]+)").find(text)?.groupValues?.getOrNull(1)\n            messages += if (identity != null)\n                "พบเครื่อง $identity แต่ยังไม่ได้กำหนดช่อง POS กรุณาแจ้งผู้ดูแล"\n            else "พบหมายเลขเครื่องที่ยังไม่ได้กำหนดช่อง POS กรุณาแจ้งผู้ดูแล"\n        }\n        if (text.contains("ถูกระบุว่าไม่ได้บิล")) {\n            val pos = Regex("POS\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)\n            messages += if (pos != null)\n                "พบข้อมูลของ POS $pos แต่เครื่องนี้ถูกระบุว่าไม่ได้บิล ระบบยังไม่เปลี่ยนข้อมูลเดิม"\n            else "พบข้อมูลของเครื่องที่ถูกระบุว่าไม่ได้บิล ระบบยังไม่เปลี่ยนข้อมูลเดิม"\n        }\n''',
)


# ---------------------------------------------------------------------------
# APK UX: darker warning text and critical integrity warnings cannot be applied
# from the OCR review dialog. User must cancel/check the image first.
# ---------------------------------------------------------------------------
app = "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt"
replace_once(app, "private val WarningOrange = Color(0xFFF0A53A)", "private val WarningOrange = Color(0xFF9A4A00)")
insert_after(
    app,
    '''        val hasOcrReviewWarning = proposal.proposedRecords\n            .filter { it.posNumber in proposal.detectedPos }\n            .any { UserFacingOcrMessages.hasVisibleWarning(it.ocrWarnings) } ||\n            proposal.warnings.filterNot { it in dateWarningMessages }.any { UserFacingOcrMessages.warning(it).isNotBlank() }\n''',
    '''        val hasCriticalIntegrityWarning = proposal.warnings.any { raw ->\n            raw.contains("บิลผิดร้าน") ||\n                raw.contains("พบบิลสลับร้าน") ||\n                raw.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS")\n        } || proposal.proposedRecords.any { record ->\n            record.ocrWarnings.contains("บิลผิดร้าน") ||\n                record.ocrWarnings.contains("พบบิลสลับร้าน") ||\n                record.ocrWarnings.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS")\n        }\n''',
)
replace_once(
    app,
    "        val shouldVibrateForReview = hasDateWarning || hasOcrReviewWarning",
    "        val shouldVibrateForReview = hasDateWarning || hasOcrReviewWarning || hasCriticalIntegrityWarning",
)
replace_once(
    app,
    '''                    if (hasDateWarning) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,''',
    '''                    if (hasDateWarning || hasCriticalIntegrityWarning) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,''',
)
replace_once(
    app,
    '''                    tint = when {\n                        hasDateWarning -> MaterialTheme.colorScheme.error\n                        proposal.confidence == OcrConfidence.HIGH -> SuccessGreen''',
    '''                    tint = when {\n                        hasDateWarning || hasCriticalIntegrityWarning -> MaterialTheme.colorScheme.error\n                        proposal.confidence == OcrConfidence.HIGH -> SuccessGreen''',
)
replace_once(
    app,
    '''                    if (hasDateWarning) "ตรวจวันที่บิล" else "ตรวจทานผลอ่านบิล",''',
    '''                    when {\n                        hasCriticalIntegrityWarning -> "ตรวจข้อมูลบิล"\n                        hasDateWarning -> "ตรวจวันที่บิล"\n                        else -> "ตรวจทานผลอ่านบิล"\n                    },''',
)
replace_once(
    app,
    '''                            Text("• $warning", fontSize = 11.sp, color = WarningOrange)''',
    '''                            val critical = warning.contains("บิลผิดร้าน") ||\n                                warning.contains("บิลสลับร้าน") ||\n                                (warning.contains("POS") && warning.contains("ซ้ำ"))\n                            Text(\n                                "• $warning",\n                                fontSize = 11.sp,\n                                color = if (critical) MaterialTheme.colorScheme.error else WarningOrange\n                            )''',
)
replace_once(
    app,
    '''                    colors = ButtonDefaults.buttonColors(containerColor = Primary)\n                ) { Text(if (hasDateWarning) "นำข้อมูลไปแก้ไข" else "ใช้ข้อมูลนี้") }''',
    '''                    enabled = !hasCriticalIntegrityWarning,\n                    colors = ButtonDefaults.buttonColors(containerColor = Primary)\n                ) {\n                    Text(\n                        when {\n                            hasCriticalIntegrityWarning -> "ต้องตรวจภาพก่อน"\n                            hasDateWarning -> "นำข้อมูลไปแก้ไข"\n                            else -> "ใช้ข้อมูลนี้"\n                        }\n                    )\n                }''',
)
replace_once(
    app,
    '''                                ocrWarnings = "",\n                                ocrCounterCycle = "CONTINUOUS"''',
    '''                                ocrWarnings = "",\n                                ocrCounterCycle = "CONTINUOUS",\n                                ocrRawPosIdentity = ""''',
)


# ---------------------------------------------------------------------------
# Admin: brand-level POS mapping UI and literal whitespace normalization.
# ---------------------------------------------------------------------------
index = "web-admin/index.html"
insert_after(
    index,
    '''        <div class="dateRuleExample" id="dateRuleExample">ค่ามาตรฐาน: ย้อนหลัง 2 วันใช้ได้ถึงวันงาน ส่วนกรณีอื่นใช้ได้ถึง 2 วันหลังวันงาน</div>\n      </div>\n''',
    '''\n\n      <div class="dateRuleEditor posIdentityRuleEditor">\n        <div class="subSectionTitle">การจับคู่รหัสเครื่อง POS ของแบรนด์นี้</div>\n        <div class="small">ใช้เมื่อบิลมีรหัสเช่น N01 และ B01 ซึ่งเป็นคนละเครื่อง แม้เลขท้ายเหมือนกัน</div>\n        <div class="simpleFormGrid two dateRuleGrid">\n          <label>วิธีใช้หมายเลขเครื่อง\n            <select id="posIdentityMode">\n              <option value="NORMAL">แบบปกติ ใช้เลข POS ตามเดิม</option>\n              <option value="PREFIX_MAPPING">แยกตามอักษรนำหน้าและจับคู่ช่องงาน</option>\n            </select>\n          </label>\n          <label>อักษรนำหน้าที่อนุญาต\n            <input id="brandPosPrefixes" placeholder="เช่น N,B">\n          </label>\n          <label style="grid-column:1/-1">จับคู่รหัสบนบิล → POS ในงาน\n            <textarea id="brandPosMappings" rows="4" placeholder="N01=1&#10;N02=2&#10;B01=3"></textarea>\n            <span class="small">หนึ่งบรรทัดต่อหนึ่งรหัส เช่น B01=3 หมายถึงบิล B01 ให้ลง POS 3</span>\n          </label>\n          <label class="checkRow" style="grid-column:1/-1">\n            <input id="allowUnmappedPosChoice" type="checkbox" checked>\n            <span>ถ้าพบรหัสใหม่ที่ยังไม่จับคู่ ให้แจ้งผู้ใช้ทันทีและห้ามเดา POS เอง</span>\n          </label>\n        </div>\n        <div id="posIdentityRuleExample" class="dateRuleExample">ค่าเริ่มต้น: ใช้เลข POS แบบเดิม จึงไม่กระทบแบรนด์ที่ใช้งานอยู่</div>\n      </div>\n''',
)
replace_once(index, "ตัวอ่านรุ่น 68", "ตัวอ่านรุ่น 69")
replace_once(index, 'styles.css?v=68', 'styles.css?v=69')
replace_once(index, 'admin-auth.js?v=68', 'admin-auth.js?v=69')
replace_once(index, 'ocr-template-contract.js?v=68', 'ocr-template-contract.js?v=69')
replace_once(index, 'ocr-pattern-engine.js?v=68', 'ocr-pattern-engine.js?v=69')
replace_once(index, 'receipt-date-rules.js?v=68', 'receipt-date-rules.js?v=69')
replace_once(index, 'ocr-simple.js?v=90.1', 'ocr-simple.js?v=94')

rules_js = "web-admin/receipt-date-rules.js"
replace_once(
    rules_js,
    'function defaultRule(brandId=""){return{brandId,customerCounterMode:"CONTINUOUS",preventDuplicateImage:true,preventDuplicateReceiptData:true,groupDateRule:',
    'function defaultRule(brandId=""){return{brandId,customerCounterMode:"CONTINUOUS",preventDuplicateImage:true,preventDuplicateReceiptData:true,posIdentityRule:{enabled:false,allowedPrefixes:[],mappings:[],allowUnmappedUserChoice:true},groupDateRule:',
)

simple = "web-admin/ocr-simple.js"
insert_after(
    simple,
    "let brandReceiptRule=ReceiptDateRules.defaultRule(\"\");\n",
    '''\nfunction normalizePosIdentityKey(value){\n  const text=String(value||"").toUpperCase().replace(/\\s+/g,"").replace(/^POS[:#=\\-]?/,"");\n  const m=text.match(/^([A-Z]{1,4})?(\\d{1,3})$/);\n  if(!m)return null;\n  const n=Number(m[2]);\n  if(!Number.isInteger(n)||n<=0)return null;\n  return `${m[1]||""}${n}`;\n}\nfunction parseBrandPosMappings(value){\n  return String(value||"").split(/\\n+/).map(line=>line.trim()).filter(Boolean).map(line=>{\n    const m=line.match(/^([^=:\\s]+)\\s*(?:=|:)\\s*(\\d+)$/);\n    if(!m)return null;\n    const key=normalizePosIdentityKey(m[1]);\n    const workPos=Number(m[2]);\n    return key&&workPos>0?{receiptPos:m[1].trim().toUpperCase(),workPos}:null;\n  }).filter(Boolean);\n}\nfunction formatBrandPosMappings(items){\n  return (items||[]).filter(x=>x&&x.receiptPos&&Number(x.workPos)>0).map(x=>`${x.receiptPos}=${x.workPos}`).join("\\n");\n}\nfunction resolveConfiguredPos(value){\n  const numeric=posNumberValue(value);\n  const rule=buildReceiptRule().posIdentityRule||{};\n  if(!rule.enabled)return numeric;\n  const key=normalizePosIdentityKey(value);\n  if(!key)return null;\n  const prefix=(key.match(/^[A-Z]+/)||[""])[0];\n  if(!prefix)return numeric;\n  const allowed=(rule.allowedPrefixes||[]).map(x=>String(x).toUpperCase());\n  if(allowed.length&&!allowed.includes(prefix))return null;\n  const item=(rule.mappings||[]).find(x=>normalizePosIdentityKey(x.receiptPos)===key);\n  return item?Number(item.workPos):null;\n}\n''',
)
replace_once(
    simple,
    '''  $("afterOldestWork").value=r.afterDaysWhenOldestIsWorkDay;\n  $("dateRuleExample").textContent=r.resetAtMonthEnd\n    ?"แบรนด์นี้ห้ามใช้วันที่บิลข้ามเดือน แม้อยู่ในช่วงจำนวนวันที่กำหนด"\n    :"แบรนด์นี้ใช้วันที่ข้ามเดือนได้ตามช่วงจำนวนวันที่กำหนด";''',
    '''  $("afterOldestWork").value=r.afterDaysWhenOldestIsWorkDay;\n  $("dateRuleExample").textContent=r.resetAtMonthEnd\n    ?"แบรนด์นี้ห้ามใช้วันที่บิลข้ามเดือน แม้อยู่ในช่วงจำนวนวันที่กำหนด"\n    :"แบรนด์นี้ใช้วันที่ข้ามเดือนได้ตามช่วงจำนวนวันที่กำหนด";\n  const p=brandReceiptRule.posIdentityRule||{enabled:false,allowedPrefixes:[],mappings:[],allowUnmappedUserChoice:true};\n  $("posIdentityMode").value=p.enabled?"PREFIX_MAPPING":"NORMAL";\n  $("brandPosPrefixes").value=(p.allowedPrefixes||[]).join(",");\n  $("brandPosMappings").value=formatBrandPosMappings(p.mappings);\n  $("allowUnmappedPosChoice").checked=p.allowUnmappedUserChoice!==false;\n  $("posIdentityRuleExample").textContent=p.enabled\n    ?`แยกรหัสเครื่องตามอักษรนำหน้า • ตั้งไว้ ${(p.mappings||[]).length} รายการ`\n    :"ค่าเริ่มต้น: ใช้เลข POS แบบเดิม จึงไม่กระทบแบรนด์ที่ใช้งานอยู่";''',
)
replace_once(
    simple,
    '''    preventDuplicateImage:true,\n    preventDuplicateReceiptData:true,\n    groupDateRule:{''',
    '''    preventDuplicateImage:true,\n    preventDuplicateReceiptData:true,\n    posIdentityRule:{\n      enabled:$("posIdentityMode").value==="PREFIX_MAPPING",\n      allowedPrefixes:String($("brandPosPrefixes").value||"").split(/[,;\\s]+/).map(x=>x.trim().toUpperCase()).filter(Boolean),\n      mappings:parseBrandPosMappings($("brandPosMappings").value),\n      allowUnmappedUserChoice:$("allowUnmappedPosChoice").checked\n    },\n    groupDateRule:{''',
)
replace_once(
    simple,
    '''    const values=parsed.records.map(record=>posNumberValue(record.fields.POS_NUMBER)).filter(value=>value!==null);''',
    '''    const values=parsed.records.map(record=>resolveConfiguredPos(record.fields.POS_NUMBER)).filter(value=>value!==null);''',
)
replace_once(
    simple,
    '''    const n=posNumberValue(fields.POS_NUMBER);\n    const ok=n!==null&&allowedPos.includes(n);\n    checks.push({ok,text:ok?`${label}: หมายเลขเครื่องตรงกับรายการ (${fields.POS_NUMBER})`:`${label}: หมายเลขเครื่อง ${fields.POS_NUMBER} ไม่อยู่ในรายการ ${allowedPos.join(", ")}`});''',
    '''    const n=resolveConfiguredPos(fields.POS_NUMBER);\n    const ok=n!==null&&allowedPos.includes(n);\n    const mapped=n!==null&&String(fields.POS_NUMBER).match(/[A-Za-z]/)?` → POS ${n}`:"";\n    checks.push({ok,text:ok?`${label}: หมายเลขเครื่องตรงกับรายการ (${fields.POS_NUMBER}${mapped})`:`${label}: หมายเลขเครื่อง ${fields.POS_NUMBER} ยังไม่ตรงกับ POS ที่กำหนด`});''',
)

engine = "web-admin/ocr-pattern-engine.js"
replace_once(
    engine,
    '''  function literalPattern(value){\n    const text=String(value||"").trim();''',
    '''  function literalPattern(value){\n    // ใช้ข้อความหลัง normalize เพื่อให้ Date :, Date: และช่องว่างรอบ : / - . เทียบกันได้\n    const text=normalizeText(value);''',
)

# Pages preview should follow Round94 after the repository environment allows it.
pages = ".github/workflows/pages.yml"
replace_once(
    pages,
    '    branches: ["main", "round93-admin-date-variants-ui-duplicate-fix"]',
    '    branches: ["main", "round93-admin-date-variants-ui-duplicate-fix", "round94-multitemplate-pos-identity-integrity-ui"]',
)


# ---------------------------------------------------------------------------
# Regression tests: preserve Round93 behavior, verify N/B mapping, multi-
# template image collection, no-receipt preservation, and user wording.
# ---------------------------------------------------------------------------
write(
    "android-app/app/src/test/java/com/receiptocr/app/ocr/PosIdentityResolverRound94Test.kt",
    '''package com.receiptocr.app.ocr\n\nimport com.receiptocr.app.config.PosIdentityMapping\nimport com.receiptocr.app.config.PosIdentityRule\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertNull\nimport org.junit.Test\n\nclass PosIdentityResolverRound94Test {\n    @Test fun round93_numeric_behavior_remains_when_rule_disabled() {\n        assertEquals(1, PosIdentityResolver.resolve("N01", PosIdentityRule())?.workPos)\n        assertEquals(1, PosIdentityResolver.resolve("B01", PosIdentityRule())?.workPos)\n        assertEquals(2, PosIdentityResolver.resolve("C02", PosIdentityRule())?.workPos)\n    }\n\n    @Test fun brand_mapping_keeps_same_number_prefixes_as_different_work_pos() {\n        val rule = PosIdentityRule(\n            enabled = true,\n            allowedPrefixes = listOf("N", "B"),\n            mappings = listOf(\n                PosIdentityMapping("N01", 1),\n                PosIdentityMapping("B01", 2)\n            )\n        )\n        assertEquals(1, PosIdentityResolver.resolve("N01", rule)?.workPos)\n        assertEquals(2, PosIdentityResolver.resolve("B01", rule)?.workPos)\n        assertNull(PosIdentityResolver.resolve("B02", rule))\n    }\n}\n''',
)

write(
    "android-app/app/src/test/java/com/receiptocr/app/ocr/MultiTemplateSequenceCollectorRound94Test.kt",
    '''package com.receiptocr.app.ocr\n\nimport com.receiptocr.app.config.*\nimport com.receiptocr.app.model.PosRecord\nimport com.receiptocr.app.model.WorkItem\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\nimport java.time.LocalDate\n\nclass MultiTemplateSequenceCollectorRound94Test {\n    private fun field(order:Int,type:String,example:String,min:Int=1,max:Int=20, literal:String?=null, dateOrder:String="DMY", calendar:String="AUTO", yearDigits:Int=0, prefixes:String?=null, posDigits:Int?=null)=\n        OcrTemplateField(order,type,example,true,min,max,when(type){"BILL_DATE"->"DATE";"BILL_TIME"->"TIME";"CUSTOMER_VALUE","NUMBER_TEXT"->"DIGITS";else->"ALNUM"},dateOrder,calendar,yearDigits,literal,"NONE",prefixes,posDigits)\n\n    private fun template(id:String,name:String,fields:List<OcrTemplateField>)=UniversalOcrTemplate(\n        templateId=id,brandId="Mini",templateName=name,priority=100,active=true,\n        recognition=OcrTemplateRecognition(rows=listOf(OcrTemplateRow(1,fields)))\n    )\n\n    private val work=WorkItem(1,"Mini","Mb","","001","ร้านทดสอบ",3,"","","","","","","",receiptStoreId="")\n\n    @Test fun one_image_can_fill_multiple_pos_from_different_templates() {\n        val mb02=template("mb02","Mb_02",listOf(\n            field(1,"LITERAL","R",1,1,"R"), field(2,"NUMBER_TEXT","20",2,2),\n            field(3,"POS_NUMBER","2",1,1,posDigits=1), field(4,"CUSTOMER_VALUE","039030",6,6),\n            field(5,"LITERAL","U",1,1,"U"), field(6,"NUMBER_TEXT","400072",6,6),\n            field(7,"BILL_DATE","20/08/69",8,8,dateOrder="DMY",calendar="BUDDHIST",yearDigits=2),\n            field(8,"BILL_TIME","17:18",5,5)\n        ))\n        val mb03=template("mb03","Mb_03",listOf(\n            field(1,"LITERAL","Date:",5,5,"Date:"),\n            field(2,"BILL_DATE","14-08-26",8,8,dateOrder="DMY",calendar="GREGORIAN",yearDigits=2),\n            field(3,"BILL_TIME","22:05",5,5), field(4,"NUMBER_TEXT","20",2,2),\n            field(5,"POS_NUMBER","1",1,1,posDigits=1), field(6,"CUSTOMER_VALUE","157464",6,6)\n        ))\n        val result=MultiTemplateSequenceCollector.apply(\n            rawTexts=listOf("Date : 14-08-26 22:05 201157464\\nR202039030U400072 20/08/69 17:18"),\n            records=listOf(PosRecord(1),PosRecord(2),PosRecord(3)), work=work,\n            workDate=LocalDate.of(2026,8,20), imagePath="x.jpg", templates=listOf(mb02,mb03),\n            receiptRule=BrandReceiptRule("Mini")\n        )\n        assertTrue(result.detectedPos.containsAll(listOf(1,2)))\n        assertEquals("157464", result.records.first{it.posNumber==1}.customerNo)\n        assertEquals("039030", result.records.first{it.posNumber==2}.customerNo)\n        assertTrue(result.templateName.orEmpty().contains("Mb_02"))\n        assertTrue(result.templateName.orEmpty().contains("Mb_03"))\n    }\n\n    @Test fun prefix_mapping_sends_n01_and_b01_to_different_work_pos() {\n        val t=template("prefix","Prefix",listOf(\n            field(1,"POS_NUMBER","N01",3,3,prefixes="N,B",posDigits=2),\n            field(2,"CUSTOMER_VALUE","123456",6,6),\n            field(3,"BILL_DATE","03/09/26",8,8,dateOrder="DMY",calendar="GREGORIAN",yearDigits=2),\n            field(4,"BILL_TIME","10:00",5,5)\n        ))\n        val rule=BrandReceiptRule("Brand",posIdentityRule=PosIdentityRule(\n            enabled=true,allowedPrefixes=listOf("N","B"),mappings=listOf(\n                PosIdentityMapping("N01",1),PosIdentityMapping("B01",2)\n            )\n        ))\n        val result=MultiTemplateSequenceCollector.apply(\n            listOf("N01 123456 03/09/26 10:00\\nB01 654321 03/09/26 10:05"),\n            listOf(PosRecord(1),PosRecord(2)),work,LocalDate.of(2026,9,3),"p.jpg",listOf(t),rule\n        )\n        assertEquals(listOf(1,2),result.detectedPos)\n        assertEquals("N01",result.records.first{it.posNumber==1}.ocrRawPosIdentity)\n        assertEquals("B01",result.records.first{it.posNumber==2}.ocrRawPosIdentity)\n    }\n}\n''',
)

write(
    "android-app/app/src/test/java/com/receiptocr/app/ocr/OcrAccumulationNoReceiptRound94Test.kt",
    '''package com.receiptocr.app.ocr\n\nimport com.receiptocr.app.model.PosRecord\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass OcrAccumulationNoReceiptRound94Test {\n    @Test fun ocr_never_clears_user_selected_no_receipt() {\n        val original=PosRecord(1,noReceipt=true,noReceiptReason="เครื่องปิด")\n        val candidate=PosRecord(1,customerNo="123456",billDate="03/09/2026",billTime="10:00",source="OCR-TEMPLATE")\n        val result=OcrAccumulationPolicy.merge(\n            originals=listOf(original), templateRecords=listOf(candidate), profileRecords=emptyList(), currentDetectedPos=setOf(1)\n        )\n        assertTrue(result.records.single().noReceipt)\n        assertEquals("เครื่องปิด",result.records.single().noReceiptReason)\n        assertTrue(result.conflictsByPos[1].orEmpty().contains("ถูกระบุว่าไม่ได้บิล"))\n    }\n}\n''',
)

write(
    "android-app/app/src/test/java/com/receiptocr/app/ui/UserFacingOcrMessagesRound94Test.kt",
    '''package com.receiptocr.app.ui\n\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass UserFacingOcrMessagesRound94Test {\n    @Test fun duplicate_warning_is_field_language_not_technical_phrase() {\n        val message=UserFacingOcrMessages.warning("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS 2 • กรุณาตรวจว่ามีบิล POS ซ้ำหรือไม่")\n        assertTrue(message.contains("POS 2 ซ้ำ"))\n        assertFalse(message.contains("มากกว่าหนึ่งชุด"))\n    }\n\n    @Test fun store_integrity_is_explicit() {\n        assertTrue(UserFacingOcrMessages.warning("บิลผิดร้าน • รหัสร้านที่อ่านได้ 999 ไม่ตรงกับรหัสร้านของงาน 123").contains("บิลผิดร้าน"))\n        assertTrue(UserFacingOcrMessages.warning("พบบิลสลับร้าน • รหัสร้านที่อ่านได้ 999 ควรเป็น 123").contains("บิลสลับร้าน"))\n    }\n}\n''',
)

write(
    "tests/round94-admin-pos-mapping.test.js",
    '''const assert=require('assert');\nconst Engine=require('../web-admin/ocr-pattern-engine.js');\n\nconst row=[\n  {type:'LITERAL',example:'Date :',literal:'Date :',minLength:5,maxLength:6,required:true},\n  {type:'BILL_DATE',example:'14-08-26',dateOrder:'DMY',dateCalendar:'GREGORIAN',dateYearDigits:2,minLength:8,maxLength:8,required:true},\n  {type:'BILL_TIME',example:'22:05',minLength:5,maxLength:5,required:true},\n  {type:'NUMBER_TEXT',example:'20',minLength:2,maxLength:2,required:true},\n  {type:'POS_NUMBER',example:'1',minLength:1,maxLength:1,posDigits:1,required:true},\n  {type:'CUSTOMER_VALUE',example:'157464',minLength:6,maxLength:6,required:true}\n];\nconst result=Engine.findRecords([row],'Date: 14-08-26 22:05 201157464');\nassert.strictEqual(result.records.length,1,'Date : and Date: must be equivalent after normalization');\nassert.strictEqual(result.records[0].fields.POS_NUMBER,'1');\nconsole.log('Round94 Admin POS/literal regression passed');\n''',
)

print("Round94 patch applied")
