from pathlib import Path

BRANCH = "round92-date-runtime-user-ui"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Round92 patch target not found: {label}")
    return text.replace(old, new, 1)


# 1) TemplateSequenceFallback: source-format dates must become canonical before leaving parser.
path = Path("android-app/app/src/main/java/com/receiptocr/app/ocr/TemplateSequenceFallback.kt")
text = path.read_text(encoding="utf-8")
if "import java.time.LocalDate" not in text:
    text = text.replace("import com.receiptocr.app.model.WorkItem\n", "import com.receiptocr.app.model.WorkItem\nimport java.time.LocalDate\n", 1)

text = replace_once(
    text,
    '''    fun apply(\n        rawTexts: List<String>,\n        records: List<PosRecord>,\n        work: WorkItem,\n        imagePath: String,\n        templates: List<UniversalOcrTemplate>\n    ): UniversalTemplateResult {''',
    '''    fun apply(\n        rawTexts: List<String>,\n        records: List<PosRecord>,\n        work: WorkItem,\n        workDate: LocalDate,\n        imagePath: String,\n        templates: List<UniversalOcrTemplate>\n    ): UniversalTemplateResult {''',
    "TemplateSequenceFallback.apply signature"
)

text = replace_once(
    text,
    '''            val customer = candidate.fields["CUSTOMER_VALUE"].orEmpty().filter(Char::isDigit)\n            val date = candidate.fields["BILL_DATE"].orEmpty()\n                .replace('.', '/')\n                .replace('-', '/')\n            val time = ReceiptTimeOcrNormalizer.normalize(candidate.fields["BILL_TIME"].orEmpty()).value.orEmpty()\n\n            updated[index] = current.copy(\n                customerNo = customer.ifBlank { current.customerNo },\n                billDate = date.ifBlank { current.billDate },\n                billTime = time.ifBlank { current.billTime },\n                noReceipt = false,\n                noReceiptReason = "",\n                source = "OCR-SEQUENCE",\n                ocrSourceImagePath = imagePath,\n                ocrTemplateName = candidate.template.templateName,\n                ocrWarnings = "",\n                ocrCounterCycle = candidate.template.duplicatePolicy.customerCounterCycle.uppercase()\n            )''',
    '''            val customer = candidate.fields["CUSTOMER_VALUE"].orEmpty().filter(Char::isDigit)\n            val rawDate = candidate.fields["BILL_DATE"].orEmpty().trim()\n            val dateField = candidate.template.recognition.rows.asSequence()\n                .flatMap { it.fields.asSequence() }\n                .firstOrNull { it.type.equals("BILL_DATE", ignoreCase = true) }\n            val dateResult = rawDate.takeIf { it.isNotBlank() }?.let { value ->\n                ReceiptDateOcrNormalizer.normalizeForField(\n                    raw = value,\n                    field = dateField,\n                    referenceDate = workDate,\n                    allowCanonicalInput = false\n                )\n            }\n            val date = dateResult?.value.orEmpty()\n            val time = ReceiptTimeOcrNormalizer.normalize(candidate.fields["BILL_TIME"].orEmpty()).value.orEmpty()\n            val dateWarning = when {\n                rawDate.isBlank() -> ""\n                dateResult?.value == null -> dateResult?.warning ?: "วันที่บิลยังไม่ตรงรูปแบบที่กำหนด"\n                dateResult.corrected -> "วันที่ที่อ่านจากภาพ ${dateResult.original} ถูกปรับเป็น ${dateResult.value} ตามเงื่อนไข Admin • กรุณาตรวจเทียบกับภาพ"\n                else -> ""\n            }\n\n            updated[index] = current.copy(\n                customerNo = customer.ifBlank { current.customerNo },\n                billDate = date.ifBlank { current.billDate },\n                billTime = time.ifBlank { current.billTime },\n                noReceipt = false,\n                noReceiptReason = "",\n                source = "OCR-SEQUENCE",\n                ocrSourceImagePath = imagePath,\n                ocrTemplateName = candidate.template.templateName,\n                ocrWarnings = dateWarning,\n                ocrRawBillDate = rawDate.ifBlank { current.ocrRawBillDate },\n                ocrCounterCycle = candidate.template.duplicatePolicy.customerCounterCycle.uppercase()\n            )''',
    "TemplateSequenceFallback canonical date output"
)
path.write_text(text, encoding="utf-8")


# 2) Real pipeline: never reinterpret canonical internal dates using source MDY/YMD rules.
path = Path("android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''            TemplateSequenceFallback.apply(\n                rawTexts = mlTexts.map { it.text },\n                records = records,\n                work = work,\n                imagePath = imagePath,\n                templates = templates\n            )''',
    '''            TemplateSequenceFallback.apply(\n                rawTexts = mlTexts.map { it.text },\n                records = records,\n                work = work,\n                workDate = workDate,\n                imagePath = imagePath,\n                templates = templates\n            )''',
    "RealOcrPipeline sequence fallback work date"
)

old = '''            val recordDateField = dateFieldForRecord(record, templates) ?: defaultDateField\n            val dateResult = if (currentImagePos && rawCandidateDate.isNotBlank()) {\n                ReceiptDateOcrNormalizer.normalizeForField(\n                    raw = rawCandidateDate,\n                    field = recordDateField,\n                    referenceDate = workDate,\n                    allowCanonicalInput = record.source.equals("OCR-EVIDENCE", ignoreCase = true)\n                )\n            } else null'''
new = '''            val dateResult = if (currentImagePos && rawCandidateDate.isNotBlank()) {\n                TemplateAwareDateResolver.resolve(\n                    raw = rawCandidateDate,\n                    templateName = record.ocrTemplateName,\n                    templates = templates,\n                    referenceDate = workDate,\n                    // All OCR parsers are required to store accepted values internally as dd/MM/yyyy.\n                    // Therefore a canonical value from OCR-TEMPLATE/OCR-SEQUENCE/OCR-EVIDENCE\n                    // must pass the second stage without being reinterpreted as source MDY/YMD.\n                    allowCanonicalInput = record.source.startsWith("OCR", ignoreCase = true)\n                )\n            } else null'''
text = replace_once(text, old, new, "RealOcrPipeline template-aware date normalization")
path.write_text(text, encoding="utf-8")


# 3) APK UI: technical OCR diagnostics remain internal, user sees only field-work language.
path = Path("android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt")
text = path.read_text(encoding="utf-8")
text = text.replace("message = proposal.message", "message = UserFacingOcrMessages.summary(proposal.message)", 1)
text = text.replace(
    'message = "อ่านบิลไม่สำเร็จ: ${error.message ?: "ไม่สามารถอ่านข้อความจากภาพได้"}"',
    'message = "อ่านบิลไม่สำเร็จ กรุณาตรวจความชัดของภาพแล้วลองอีกครั้ง"',
    1
)

start = text.index("\n    if (ocrReadDetailsOpen) {")
end = text.index("\n    pendingOcrResult?.let { proposal ->", start)
simple_dialog = '''\n    if (ocrReadDetailsOpen) {\n        AlertDialog(\n            modifier = Modifier\n                .fillMaxWidth(0.94f)\n                .widthIn(max = 520.dp),\n            properties = DialogProperties(usePlatformDefaultWidth = false),\n            onDismissRequest = { ocrReadDetailsOpen = false },\n            icon = {\n                Icon(\n                    Icons.Outlined.ErrorOutline,\n                    contentDescription = null,\n                    tint = WarningOrange\n                )\n            },\n            title = {\n                Text(\n                    "ยังอ่านบิลไม่ครบ",\n                    fontSize = 22.sp,\n                    fontWeight = FontWeight.Bold,\n                    textAlign = TextAlign.Center,\n                    modifier = Modifier.fillMaxWidth()\n                )\n            },\n            text = {\n                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {\n                    Text(\n                        "ระบบยังอ่านข้อมูลที่จำเป็นจากภาพนี้ไม่ครบ",\n                        color = TextMain,\n                        fontWeight = FontWeight.Bold,\n                        fontSize = 14.sp\n                    )\n                    Text(\n                        "กรุณาตรวจว่าภาพเห็นวันที่ เวลา เลข/ยอดลูกค้า และหมายเลขเครื่องชัดเจน แล้วลองอ่านอีกครั้ง",\n                        color = TextSub,\n                        fontSize = 12.sp,\n                        lineHeight = 18.sp\n                    )\n                }\n            },\n            confirmButton = {\n                TextButton(onClick = { ocrReadDetailsOpen = false }) { Text("ปิด") }\n            }\n        )\n    }\n'''
text = text[:start] + simple_dialog + text[end:]

# Display filtered user-facing warnings in both the review dialog and POS cards.
text = text.replace("                                        record.ocrWarnings,", "                                        UserFacingOcrMessages.warning(record.ocrWarnings),")
text = text.replace("                                record.ocrWarnings,", "                                UserFacingOcrMessages.warning(record.ocrWarnings),")

old_global = '''                    proposal.warnings.filterNot { it in dateWarningMessages }.forEach { warning ->\n                        Text("• $warning", fontSize = 11.sp, color = WarningOrange)\n                    }'''
new_global = '''                    proposal.warnings\n                        .filterNot { it in dateWarningMessages }\n                        .map(UserFacingOcrMessages::warning)\n                        .filter { it.isNotBlank() }\n                        .distinct()\n                        .forEach { warning ->\n                            Text("• $warning", fontSize = 11.sp, color = WarningOrange)\n                        }'''
text = replace_once(text, old_global, new_global, "proposal warning display")

text = text.replace(
    '"ยืนยันผลอ่านบิลแล้ว • ${proposal.confidence.label}"',
    '"บันทึกข้อมูลจากบิลแล้ว"',
    1
)
old_dismiss = '''            dismissButton = {\n                Row(verticalAlignment = Alignment.CenterVertically) {\n                    TextButton(onClick = { ocrReadDetailsOpen = true }) { Text("ดูข้อความที่อ่านได้") }\n                    TextButton(onClick = { pendingOcrResult = null }) { Text("ยกเลิก") }\n                }\n            }'''
new_dismiss = '''            dismissButton = {\n                TextButton(onClick = { pendingOcrResult = null }) { Text("ยกเลิก") }\n            }'''
text = replace_once(text, old_dismiss, new_dismiss, "remove technical details button")
path.write_text(text, encoding="utf-8")


# 4) Version bump.
path = Path("android-app/app/build.gradle.kts")
text = path.read_text(encoding="utf-8")
text = text.replace("versionCode = 93", "versionCode = 94", 1)
text = text.replace('versionName = "0.91.0"', 'versionName = "0.92.0"', 1)
path.write_text(text, encoding="utf-8")


# 5) Ensure final branch source is tested by normal push CI after verified commit.
path = Path(".github/workflows/android-ci.yml")
text = path.read_text(encoding="utf-8")
if "      - round92-date-runtime-user-ui\n" not in text:
    marker = "      - round90-unified-date-admin-receipt-line\n"
    if marker not in text:
        raise SystemExit("Round92 CI branch marker not found")
    text = text.replace(marker, marker + "      - round92-date-runtime-user-ui\n", 1)
path.write_text(text, encoding="utf-8")

print("Round92 production patch applied")
