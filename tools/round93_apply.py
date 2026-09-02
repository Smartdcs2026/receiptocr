from pathlib import Path


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"Round93 target not found: {label}")
    return text.replace(old, new, 1)


def replace_between(text, start_marker, end_marker, replacement, label):
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"Round93 start marker not found: {label}")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f"Round93 end marker not found: {label}")
    return text[:start] + replacement + text[end:]


# 1) Duplicate POS detector: compare variants only within the same exact template.
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/DuplicatePosEvidenceDetector.kt"
text = read(path)
text = replace_once(text, '''    private data class Sighting(\n        val passIndex: Int,\n        val signature: String\n    )''', '''    private data class Sighting(\n        val passIndex: Int,\n        val templateId: String,\n        val signature: String\n    )''', "duplicate sighting template id")
text = replace_once(text, '''                    sightingsByPos.getOrPut(pos) { mutableListOf() }\n                        .add(Sighting(passIndex, signature))''', '''                    sightingsByPos.getOrPut(pos) { mutableListOf() }\n                        .add(Sighting(passIndex, template.templateId, signature))''', "duplicate sighting creation")
text = replace_once(text, '''            sightingsByPos.forEach { (pos, sightings) ->\n                val distinct = sightings.distinct()\n                val samePassConflict = distinct.groupBy { it.passIndex }.values.any { passSightings ->\n                    passSightings.map { it.signature }.distinct().size >= 2\n                }\n                val repeatedSignatures = distinct.groupBy { it.signature }\n                    .filterValues { group -> group.map { it.passIndex }.distinct().size >= 2 }\n                    .keys\n                if (samePassConflict || repeatedSignatures.size >= 2) {\n                    put(\n                        pos,\n                        "พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS $pos • กรุณาตรวจว่ามีบิล POS ซ้ำหรือไม่"\n                    )\n                }\n            }''', '''            sightingsByPos.forEach { (pos, sightings) ->\n                // One brand can have several active templates. A single receipt can be parsed\n                // differently by two templates, so that alone must never be called a duplicate.\n                val conflict = sightings.groupBy { it.templateId }.values.any { templateSightings ->\n                    val distinct = templateSightings.distinct()\n                    val samePassConflict = distinct.groupBy { it.passIndex }.values.any { passSightings ->\n                        passSightings.map { it.signature }.distinct().size >= 2\n                    }\n                    val repeatedSignatures = distinct.groupBy { it.signature }\n                        .filterValues { group -> group.map { it.passIndex }.distinct().size >= 2 }\n                        .keys\n                    samePassConflict || repeatedSignatures.size >= 2\n                }\n                if (conflict) {\n                    put(\n                        pos,\n                        "พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS $pos • กรุณาตรวจว่ามีบิล POS ซ้ำหรือไม่"\n                    )\n                }\n            }''', "duplicate grouping")
text = replace_once(text, '''        val date = fields["BILL_DATE"].orEmpty().replace(Regex("\\\\s+"), "")\n        val time = fields["BILL_TIME"].orEmpty().replace(Regex("\\\\s+"), "")''', '''        // Separator and spacing differences are not different receipts.\n        val date = OcrTextNormalizer.normalizeDigits(fields["BILL_DATE"].orEmpty()).filter(Char::isDigit)\n        val time = OcrTextNormalizer.normalizeDigits(fields["BILL_TIME"].orEmpty()).filter(Char::isDigit)''', "duplicate normalized signature")
write(path, text)

# 2) Preserve raw date in strict template output.
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/UniversalTemplateInterpreter.kt"
text = read(path)
text = replace_once(text, '''                ocrTemplateName = match.template.templateName,\n                ocrWarnings = posWarnings.distinct().joinToString(" • "),\n                ocrCounterCycle = match.template.duplicatePolicy.customerCounterCycle.uppercase()''', '''                ocrTemplateName = match.template.templateName,\n                ocrWarnings = posWarnings.distinct().joinToString(" • "),\n                ocrRawBillDate = dateRaw?.trim().orEmpty().ifBlank { current.ocrRawBillDate },\n                ocrCounterCycle = match.template.duplicatePolicy.customerCounterCycle.uppercase()''', "strict raw date preservation")
write(path, text)

# 3) A successful date conversion is informational, not a warning.
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/TemplateSequenceFallback.kt"
text = read(path)
text = replace_once(text, '''                dateResult.corrected -> "วันที่ที่อ่านจากภาพ ${dateResult.original} ถูกปรับเป็น ${dateResult.value} ตามเงื่อนไข Admin • กรุณาตรวจเทียบกับภาพ"\n                else -> ""''', '''                dateResult.corrected -> ""\n                else -> ""''', "sequence corrected date is info")
write(path, text)

# 4) Evidence fusion preserves the source date that produced the accepted canonical date.
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt"
text = read(path)
text = replace_once(text, '''            val date = resolved.values["BILL_DATE"]\n            val time = resolved.values["BILL_TIME"]?.let { resolvedTime ->''', '''            val date = resolved.values["BILL_DATE"]\n            val rawDate = resolved.values["_RAW_BILL_DATE"]\n            val time = resolved.values["BILL_TIME"]?.let { resolvedTime ->''', "evidence raw date value")
text = replace_once(text, '''                ocrTemplateName = template.templateName,\n                ocrWarnings = warning,\n                ocrStoreId = store?.value ?: current.ocrStoreId,''', '''                ocrTemplateName = template.templateName,\n                ocrWarnings = warning,\n                ocrRawBillDate = rawDate?.value ?: current.ocrRawBillDate,\n                ocrStoreId = store?.value ?: current.ocrStoreId,''', "evidence record raw date")
text = replace_once(text, '''                    val enriched = fields.toMutableMap()\n                    val dateField = ordered.firstOrNull { it.field.type.equals("BILL_DATE", true) }?.field\n                    enriched["BILL_DATE"]?.takeIf { it.isNotBlank() }?.let { rawDate ->\n                        val normalized = ReceiptDateOcrNormalizer.normalizeForField(\n                            raw = rawDate,\n                            field = dateField,\n                            referenceDate = referenceDate\n                        )\n                        if (normalized.value != null) enriched["BILL_DATE"] = normalized.value\n                        else enriched.remove("BILL_DATE")\n                    }''', '''                    val enriched = fields.toMutableMap()\n                    val dateField = ordered.firstOrNull { it.field.type.equals("BILL_DATE", true) }?.field\n                    enriched["BILL_DATE"]?.takeIf { it.isNotBlank() }?.let { rawDate ->\n                        enriched["_RAW_BILL_DATE"] = rawDate\n                        val normalized = ReceiptDateOcrNormalizer.normalizeForField(\n                            raw = rawDate,\n                            field = dateField,\n                            referenceDate = referenceDate\n                        )\n                        if (normalized.value != null) enriched["BILL_DATE"] = normalized.value\n                        else {\n                            enriched.remove("BILL_DATE")\n                            enriched.remove("_RAW_BILL_DATE")\n                        }\n                    }''', "evidence prefix raw date")
text = replace_once(text, '''            findDate(localText, dateField, referenceDate)?.let { found ->\n                fields["BILL_DATE"] = found.first\n                dateRange = found.second\n            }''', '''            findDate(localText, dateField, referenceDate)?.let { found ->\n                fields["BILL_DATE"] = found.first\n                val start = found.second.first.coerceAtLeast(0)\n                val endExclusive = (found.second.last + 1).coerceAtMost(localText.length)\n                if (start < endExclusive) fields["_RAW_BILL_DATE"] = localText.substring(start, endExclusive)\n                dateRange = found.second\n            }''', "evidence local raw date")
text = replace_once(text, '''        val core = template.validation.requiredCore\n        if (core.customerValue && values["CUSTOMER_VALUE"] == null) return null''', '''        val acceptedDate = values["BILL_DATE"]?.value\n        if (!acceptedDate.isNullOrBlank()) {\n            group.asSequence()\n                .filter { it.fields["BILL_DATE"] == acceptedDate }\n                .sortedByDescending { it.score }\n                .mapNotNull { it.fields["_RAW_BILL_DATE"]?.trim()?.takeIf(String::isNotBlank) }\n                .firstOrNull()\n                ?.let { raw -> values["_RAW_BILL_DATE"] = ResolvedValue(raw, 1, group.maxOf { it.score }) }\n        }\n\n        val core = template.validation.requiredCore\n        if (core.customerValue && values["CUSTOMER_VALUE"] == null) return null''', "evidence resolve raw date")
write(path, text)

# 5) Real pipeline: correction is info; missing POS is explicit and not hidden by generic text.
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt"
text = read(path)
text = replace_once(text, '''                dateResult.corrected ->\n                    "วันที่ที่อ่านจากภาพ ${dateResult.original} ถูกปรับเป็น ${dateResult.value} ตามเงื่อนไข Admin • กรุณาตรวจเทียบกับภาพ"\n                else -> ""''', '''                dateResult.corrected -> ""\n                else -> ""''', "pipeline corrected date is info")
text = replace_once(text, '''                if (!currentComplete) add("ข้อมูลสำคัญบางช่องในภาพนี้อ่านได้ไม่ครบ กรุณาตรวจแก้ก่อนยืนยัน")\n                if (missingPos.isNotEmpty()) add("ยังขาดข้อมูลเครื่อง ${missingPos.joinToString(", ")} • สามารถเพิ่มภาพบิลช่องอื่นแล้วอ่านต่อได้")''', '''                if (!currentComplete && missingPos.isEmpty()) add("ข้อมูลสำคัญบางช่องในภาพนี้อ่านได้ไม่ครบ กรุณาตรวจแก้ก่อนยืนยัน")\n                if (missingPos.isNotEmpty()) add("ยังอ่านไม่ครบ • ขาด POS ${missingPos.joinToString(", ")} • สามารถเพิ่มภาพบิลช่องอื่นแล้วอ่านต่อได้")''', "pipeline missing pos warning")
text = replace_once(text, '''                repaired.isNotEmpty() ->\n                    "อ่านภาพเพิ่มแล้ว • ปรับข้อมูล ${repaired.joinToString(", ") { "POS $it" }} • กรุณาตรวจทาน"\n                else -> templateResult.message''', '''                repaired.isNotEmpty() ->\n                    "อ่านภาพเพิ่มแล้ว • ปรับข้อมูล ${repaired.joinToString(", ") { "POS $it" }} • กรุณาตรวจทาน"\n                missingPos.isNotEmpty() ->\n                    "อ่านได้ ${resolvedPos.size}/${records.size} POS • ยังขาด ${missingPos.joinToString(", ") { "POS $it" }}"\n                else -> templateResult.message''', "pipeline explicit count message")
write(path, text)

# 6) User-facing layer: correction-only metadata is not a warning; add blue date info text.
path = "android-app/app/src/main/java/com/receiptocr/app/ui/UserFacingOcrMessages.kt"
text = read(path)
start = text.find("object UserFacingOcrMessages {")
if start < 0:
    raise SystemExit("Round93 UserFacing object not found")
new_obj = r'''object UserFacingOcrMessages {
    private val correctionPattern = Regex(
        """วันที่ที่อ่านจากภาพ\s+\S+\s+ถูกปรับเป็น\s+\d{2}/\d{2}/\d{4}\s+ตามเงื่อนไข\s+Admin(?:\s*•\s*กรุณาตรวจเทียบกับภาพ)?""",
        RegexOption.IGNORE_CASE
    )

    fun warning(raw: String): String {
        val original = raw.trim()
        if (original.isBlank()) return ""
        val text = correctionPattern.replace(original, "")
            .split("•").map(String::trim).filter(String::isNotBlank).joinToString(" • ")
        if (text.isBlank()) return ""

        val messages = linkedSetOf<String>()
        if (text.contains("หลักฐานยืนยัน OCR", ignoreCase = true) || text.contains("ช่องที่ยืนยันเพียง") || Regex("(?:ลูกค้า|วันที่|เวลา)\\s+\\d+\\s+รอบ").containsMatchIn(text)) {
            messages += "ข้อมูลบางช่องควรตรวจเทียบกับภาพบิล"
        }
        if (text.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS")) {
            val pos = Regex("POS\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)
            messages += if (pos != null) "พบข้อมูลบิลมากกว่าหนึ่งชุดสำหรับ POS $pos กรุณาตรวจภาพบิล" else "พบข้อมูลบิลมากกว่าหนึ่งชุด กรุณาตรวจภาพบิล"
        }
        if (text.contains("ภาพใหม่อ่านข้อมูล POS") && text.contains("ต่างจากข้อมูลเดิม")) {
            val pos = Regex("POS\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)
            messages += if (pos != null) "ข้อมูลจากภาพล่าสุดของ POS $pos ต่างจากข้อมูลที่บันทึกไว้ กรุณาตรวจสอบก่อนเปลี่ยน" else "ข้อมูลจากภาพล่าสุดต่างจากข้อมูลที่บันทึกไว้ กรุณาตรวจสอบก่อนเปลี่ยน"
        }
        if (text.contains("รหัสร้าน") && text.contains("ไม่ตรง")) messages += "รหัสร้านบนบิลไม่ตรงกับร้านในแผนงาน"
        else if (text.contains("ยืนยันร้านไม่ได้") || text.contains("ไม่พบรหัสร้าน")) messages += "ยังตรวจสอบรหัสร้านจากบิลไม่ได้ กรุณาตรวจภาพบิล"
        if (text.contains("ค่านี้จะไม่ถูกใช้เป็นวันที่") || text.contains("วันที่ที่อ่านจากภาพ") && text.contains("ไม่ตรง")) messages += "วันที่บิลยังไม่ถูกต้อง กรุณาตรวจจากภาพบิล"
        if (text.contains("ค่านี้จะไม่ถูกใช้เป็นเวล") || text.contains("เวลาที่อ่านจากภาพไม่ถูกต้อง")) messages += "เวลาในบิลยังไม่ถูกต้อง กรุณาตรวจจากภาพบิล"
        if (text.contains("ไม่พบวันที่")) messages += "ยังไม่พบวันที่บิล"
        if (text.contains("ไม่พบเวลา")) messages += "ยังไม่พบเวลาในบิล"
        if (text.contains("ไม่พบยอด/เลขลูกค้า") || text.contains("ยังไม่มีเลข/ยอดลูกค้า")) messages += "ยังไม่พบเลข/ยอดลูกค้า"
        if (text.contains("ยังอ่านไม่ครบ") && text.contains("ขาด POS")) {
            Regex("ขาด POS\\s+([^•]+)").find(text)?.groupValues?.getOrNull(1)?.trim()?.let { messages += "ยังอ่านไม่ครบ • ขาด POS $it" }
        }
        if (messages.isEmpty()) messages += "กรุณาตรวจข้อมูลกับภาพบิลก่อนใช้งาน"
        return messages.joinToString(" • ")
    }

    fun hasVisibleWarning(raw: String): Boolean = warning(raw).isNotBlank()

    fun dateInfo(rawDate: String, canonicalDate: String): String {
        val raw = rawDate.trim()
        val canonical = canonicalDate.trim()
        if (raw.isBlank() || canonical.isBlank()) return ""
        val normalizedPunctuation = raw.replace('.', '/').replace('-', '/').replace(Regex("\\s+"), "")
        if (normalizedPunctuation == canonical) return ""
        val structured = Regex("""^\d{1,4}[./-]\d{1,2}[./-]\d{2,4}$""").matches(raw)
        return if (structured) "วันที่บนบิล $raw → ใช้เป็น $canonical" else "วันที่บนบิลถูกแปลงรูปแบบเป็น $canonical"
    }

    fun summary(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""
        return when {
            text.contains("ไม่พบข้อความในภาพ") -> "ยังอ่านข้อมูลจากภาพไม่ได้ กรุณาถ่ายใหม่ให้บิลชัดเจน"
            text.contains("ยังอ่านไม่ครบ") && text.contains("ขาด") -> text
            text.contains("ยังแยกข้อมูล") || text.contains("ยังอ่านหมายเลขเครื่องไม่ได้") -> "ยังอ่านข้อมูลที่จำเป็นจากภาพไม่ครบ กรุณาตรวจภาพแล้วลองอีกครั้ง"
            text.contains("สำเร็จ") || text.contains("พบ") -> "อ่านข้อมูลจากบิลแล้ว กรุณาตรวจความถูกต้องก่อนใช้"
            else -> "กรุณาตรวจข้อมูลจากภาพบิลก่อนใช้งาน"
        }
    }
}
'''
text = text[:start] + new_obj + "\n"
write(path, text)

# 7) APK UI uses visible warnings, blue date info, and explicit POS completeness.
path = "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt"
text = read(path)
text = replace_once(text, '''        val hasOcrReviewWarning = proposal.proposedRecords\n            .filter { it.posNumber in proposal.detectedPos }\n            .any { it.ocrWarnings.isNotBlank() } ||\n            proposal.warnings.any { it !in dateWarningMessages }''', '''        val hasOcrReviewWarning = proposal.proposedRecords\n            .filter { it.posNumber in proposal.detectedPos }\n            .any { UserFacingOcrMessages.hasVisibleWarning(it.ocrWarnings) } ||\n            proposal.warnings.filterNot { it in dateWarningMessages }.any { UserFacingOcrMessages.warning(it).isNotBlank() }\n        val unresolvedPos = proposal.proposedRecords\n            .filter { !it.noReceipt && (it.customerNo.isBlank() || it.billDate.isBlank() || it.billTime.isBlank()) }\n            .map { it.posNumber }.sorted()\n        val completedPosCount = proposal.proposedRecords.size - unresolvedPos.size''', "UI visible review warning and POS count")
text = replace_once(text, '''                    Text(\n                        "วันงาน ${selectedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}",\n                        color = TextMain,\n                        fontWeight = FontWeight.Bold,\n                        fontSize = 15.sp,\n                        textAlign = TextAlign.Center,\n                        modifier = Modifier.fillMaxWidth()\n                    )\n                    Row(''', '''                    Text(\n                        "วันงาน ${selectedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}",\n                        color = TextMain,\n                        fontWeight = FontWeight.Bold,\n                        fontSize = 15.sp,\n                        textAlign = TextAlign.Center,\n                        modifier = Modifier.fillMaxWidth()\n                    )\n                    Text(\n                        buildString {\n                            append("อ่านได้ $completedPosCount/${proposal.proposedRecords.size} POS")\n                            if (unresolvedPos.isNotEmpty()) append(" • ยังขาด POS ${unresolvedPos.joinToString(", ")}")\n                        },\n                        color = if (unresolvedPos.isEmpty()) SuccessGreen else WarningOrange,\n                        fontSize = 11.sp,\n                        fontWeight = FontWeight.SemiBold,\n                        textAlign = TextAlign.Center,\n                        modifier = Modifier.fillMaxWidth()\n                    )\n                    Row(''', "UI POS count line")
text = replace_once(text, '''                        val dateWarningText = proposalIndividualDateWarnings[record.posNumber]\n                        val dateInvalid = !dateWarningText.isNullOrBlank()\n                        val hasRecordWarning = dateInvalid || record.ocrWarnings.isNotBlank()\n                        val datePositionLabel = ReceiptValidationEngine.datePositionLabel(record.billDate, selectedDate)''', '''                        val dateWarningText = proposalIndividualDateWarnings[record.posNumber]\n                        val dateInvalid = !dateWarningText.isNullOrBlank()\n                        val visibleOcrWarning = UserFacingOcrMessages.warning(record.ocrWarnings)\n                        val dateInfoText = UserFacingOcrMessages.dateInfo(record.ocrRawBillDate, record.billDate)\n                        val hasRecordWarning = dateInvalid || visibleOcrWarning.isNotBlank()\n                        val datePositionLabel = ReceiptValidationEngine.datePositionLabel(record.billDate, selectedDate)''', "UI per record visible warning")
text = replace_once(text, '''                                        when {\n                                            dateInvalid -> "ต้องแก้ไข"\n                                            record.ocrWarnings.isNotBlank() -> "ควรตรวจ"\n                                            else -> "ใช้ได้"\n                                        },''', '''                                        when {\n                                            dateInvalid -> "ต้องแก้ไข"\n                                            visibleOcrWarning.isNotBlank() -> "ควรตรวจ"\n                                            else -> "ใช้ได้"\n                                        },''', "UI status label")
text = replace_once(text, '''                                        color = when {\n                                            dateInvalid -> MaterialTheme.colorScheme.error\n                                            record.ocrWarnings.isNotBlank() -> WarningOrange\n                                            else -> SuccessGreen\n                                        }''', '''                                        color = when {\n                                            dateInvalid -> MaterialTheme.colorScheme.error\n                                            visibleOcrWarning.isNotBlank() -> WarningOrange\n                                            else -> SuccessGreen\n                                        }''', "UI status color")
text = replace_once(text, '''                                if (record.ocrWarnings.isNotBlank()) {\n                                    Text(\n                                        UserFacingOcrMessages.warning(record.ocrWarnings),\n                                        fontSize = 10.sp,\n                                        color = MaterialTheme.colorScheme.error\n                                    )\n                                }''', '''                                if (dateInfoText.isNotBlank()) {\n                                    Text(dateInfoText, fontSize = 10.sp, color = Primary)\n                                }\n                                if (visibleOcrWarning.isNotBlank()) {\n                                    Text(visibleOcrWarning, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)\n                                }''', "UI proposal info and warning")
text = replace_once(text, '''    val dateWarning = !record.noReceipt && !dateWarningText.isNullOrBlank()\n    val hasValidationWarning = customerMissing || dateMissing || timeMissing || dateWarning || record.ocrWarnings.isNotBlank()\n\n    val hasData =''', '''    val dateWarning = !record.noReceipt && !dateWarningText.isNullOrBlank()\n    val visibleOcrWarning = UserFacingOcrMessages.warning(record.ocrWarnings)\n    val dateInfoText = UserFacingOcrMessages.dateInfo(record.ocrRawBillDate, record.billDate)\n    val hasValidationWarning = customerMissing || dateMissing || timeMissing || dateWarning || visibleOcrWarning.isNotBlank()\n\n    val hasData =''', "PosCard visible warning state")
text = replace_once(text, '''                    if (record.ocrWarnings.isNotBlank()) {\n                        Spacer(Modifier.height(8.dp))\n                        Surface(\n                            modifier = Modifier.fillMaxWidth(),\n                            shape = RoundedCornerShape(9.dp),\n                            color = Color(0xFFFFF1F1),\n                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)\n                        ) {\n                            Text(\n                                UserFacingOcrMessages.warning(record.ocrWarnings),\n                                modifier = Modifier.padding(9.dp),\n                                color = MaterialTheme.colorScheme.error,\n                                fontSize = 10.5.sp\n                            )\n                        }\n                    }\n\n                    Spacer(Modifier.height(8.dp))''', '''                    if (dateInfoText.isNotBlank()) {\n                        Spacer(Modifier.height(8.dp))\n                        Surface(\n                            modifier = Modifier.fillMaxWidth(),\n                            shape = RoundedCornerShape(9.dp),\n                            color = PrimarySoft,\n                            border = BorderStroke(1.dp, Primary.copy(alpha = 0.22f))\n                        ) {\n                            Text(dateInfoText, modifier = Modifier.padding(9.dp), color = Primary, fontSize = 10.5.sp)\n                        }\n                    }\n                    if (visibleOcrWarning.isNotBlank()) {\n                        Spacer(Modifier.height(8.dp))\n                        Surface(\n                            modifier = Modifier.fillMaxWidth(),\n                            shape = RoundedCornerShape(9.dp),\n                            color = Color(0xFFFFF1F1),\n                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)\n                        ) {\n                            Text(visibleOcrWarning, modifier = Modifier.padding(9.dp), color = MaterialTheme.colorScheme.error, fontSize = 10.5.sp)\n                        }\n                    }\n\n                    Spacer(Modifier.height(8.dp))''', "PosCard info/warning surfaces")
write(path, text)

# 8) Admin: explicit date semantics per template + interpretation preview + legacy warning.
path = "web-admin/index.html"
text = read(path)
text = replace_once(text, '''        <div class="small">ตัวอย่าง: พ.ศ. 2569 / 69 และ ค.ศ. 2026 / 26 • เมื่ออ่านผ่าน ระบบจะเก็บเป็น dd/MM/yyyy เสมอ • ถ้าไม่ตรงเงื่อนไข ระบบจะแสดงค่าที่อ่านได้พร้อมคำเตือนและไม่เก็บเป็นวันที่ใช้งาน</div>\n        <div id="dateFormatPreview" class="small"><strong>รูปแบบที่เลือก:</strong> วัน / เดือน / ปี • รับทั้ง พ.ศ. และ ค.ศ. • ปี 2/4 หลัก</div>''', '''        <div class="small">ตัวคั่น / - . ถือเป็นรูปแบบเดียวกัน • ถ้าลำดับวัน/เดือน/ปี หรือระบบปีต่างกันจริง ให้สร้าง “รูปแบบบิล” แยกกันในแบรนด์เดียวกัน</div>\n        <div class="small">เมื่ออ่านผ่าน ระบบจะเก็บเป็น dd/MM/yyyy เสมอ • ถ้าตีความได้มากกว่าหนึ่งวันที่ ระบบจะไม่เดา</div>\n        <div id="dateRuleLegacyNotice" class="ocrConfigNotice hidden"></div>\n        <div id="dateFormatPreview" class="small"><strong>รูปแบบที่เลือก:</strong> วัน / เดือน / ปี • รับทั้ง พ.ศ. และ ค.ศ. • ปี 2/4 หลัก</div>''', "Admin date help")
write(path, text)

path = "web-admin/ocr-simple.js"
text = read(path)
text = replace_once(text, '''  return {id:crypto.randomUUID(),type,example:"",minLength:m.min,maxLength:m.max,format:m.format,required:type!=="IGNORE",literal:"",prefix:"",separator:"",segments:[],compareTo:"NONE",posPrefixes:"",posDigits:2,separatorValue:"",dateOrder:"DMY",dateCalendar:"AUTO",dateYearDigits:0};''', '''  return {id:crypto.randomUUID(),type,example:"",minLength:m.min,maxLength:m.max,format:m.format,required:type!=="IGNORE",literal:"",prefix:"",separator:"",segments:[],compareTo:"NONE",posPrefixes:"",posDigits:2,separatorValue:"",dateOrder:"DMY",dateCalendar:"AUTO",dateYearDigits:0,dateRuleExplicit:type!=="BILL_DATE"};''', "Admin new date explicit flag")
text = replace_once(text, '''function summary(p){\n  const rows=p.recognition?.rows||[];\n  if(!rows.length)return "ยังไม่ได้จัดรูปแบบ";\n  return rows.map((r,ri)=>`แถว ${ri+1}: ${(r.fields||[]).map(f=>META[f.type]?.label||f.type).join(" → ")}`).join(" | ");\n}''', '''function dateRuleSummary(p){\n  const field=(p.recognition?.rows||[]).flatMap(r=>r.fields||[]).find(f=>f.type==="BILL_DATE");\n  if(!field)return "";\n  const explicit=["dateOrder","dateCalendar","dateYearDigits"].every(key=>Object.prototype.hasOwnProperty.call(field,key));\n  if(!explicit)return "วันที่: ยังไม่ได้ยืนยันกติกา";\n  const order={DMY:"วัน/เดือน/ปี",MDY:"เดือน/วัน/ปี",YMD:"ปี/เดือน/วัน"}[String(field.dateOrder||"DMY").toUpperCase()]||"วัน/เดือน/ปี";\n  const calendar={BUDDHIST:"พ.ศ.",GREGORIAN:"ค.ศ.",AUTO:"พ.ศ./ค.ศ."}[String(field.dateCalendar||"AUTO").toUpperCase()]||"พ.ศ./ค.ศ.";\n  const digits=Number(field.dateYearDigits||0);\n  return `วันที่: ${order} • ${calendar} • ปี ${digits===2?"2":digits===4?"4":"2/4"} หลัก`;\n}\nfunction summary(p){\n  const rows=p.recognition?.rows||[];\n  if(!rows.length)return "ยังไม่ได้จัดรูปแบบ";\n  const rowText=rows.map((r,ri)=>`แถว ${ri+1}: ${(r.fields||[]).map(f=>META[f.type]?.label||f.type).join(" → ")}`).join(" | ");\n  const dateText=dateRuleSummary(p);\n  return dateText?`${rowText} | ${dateText}`:rowText;\n}''', "Admin pattern summary")
text = replace_once(text, '''dateOrder:f.dateOrder||"DMY",dateCalendar:f.dateCalendar||"AUTO",dateYearDigits:Number(f.dateYearDigits||0)}))),''', '''dateOrder:f.dateOrder||"DMY",dateCalendar:f.dateCalendar||"AUTO",dateYearDigits:Number(f.dateYearDigits||0),dateRuleExplicit:["dateOrder","dateCalendar","dateYearDigits"].every(key=>Object.prototype.hasOwnProperty.call(f,key))}))),''', "Admin loaded explicit flag")
start_marker = "function renderDateFieldPreview(f){"
end_marker = "\nfunction updateField(){"
new_preview = r'''function renderDateFieldPreview(f){
  const host=$("dateFormatPreview");
  const notice=$("dateRuleLegacyNotice");
  if(!host)return;
  if(!f||f.type!=="BILL_DATE"){host.innerHTML="";notice?.classList.add("hidden");return;}
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
  const sample=String(f.example||"").trim();
  let sampleLine="";
  if(sample){
    const preview=normalizeTestDate(sample,f,$("testWorkDate")?.value);
    sampleLine=preview.value?`<div><strong>ตัวอย่างบนบิล:</strong> ${esc(sample)} → <strong>ระบบจะเก็บ ${esc(preview.value)}</strong></div>`:`<div><strong>ตัวอย่างบนบิล:</strong> ${esc(sample)} → ยังไม่ผ่าน (${esc(preview.warning||"ตรวจรูปแบบวันที่")})</div>`;
  }
  host.innerHTML=`<div><strong>รูปแบบที่เลือก:</strong> ${orderLabel} • ${calendarLabel} • ${digitLabel}</div>${sampleLine}${examples.length?`<div>ตัวอย่างที่รองรับ: ${examples.join(", ")} • ใช้ / - . ได้</div>`:""}`;
  if(notice){
    const needsConfirm=f.dateRuleExplicit!==true;
    notice.classList.toggle("hidden",!needsConfirm);
    notice.innerHTML=needsConfirm?"<strong>รูปแบบวันที่เดิมยังไม่ได้ยืนยัน</strong><span>กรุณาเลือก ลำดับวัน/เดือน/ปี, ระบบปี และจำนวนหลักของปีให้ตรงกับบิลจริง แล้วบันทึกใหม่</span>":"";
  }
}
'''
text = replace_between(text, start_marker, end_marker, new_preview, "Admin date preview")
text = replace_once(text, '''  f.example=$("fieldExample").value.trim();f.format=$("fieldFormat").value;f.minLength=+$("fieldMinLength").value||0;f.maxLength=+$("fieldMaxLength").value||1;f.required=$("fieldRequired").checked;f.literal=$("fieldLiteral").value;f.compareTo=$("fieldCompareTo").value;f.posPrefixes=$("posPrefixes").value.trim();f.posDigits=+$("posDigits").value||2;f.separatorValue=$("separatorValue").value;f.dateOrder=$("dateOrder").value;f.dateCalendar=$("dateCalendar").value;f.dateYearDigits=+$("dateYearDigits").value||0;''', '''  f.example=$("fieldExample").value.trim();f.format=$("fieldFormat").value;f.minLength=+$("fieldMinLength").value||0;f.maxLength=+$("fieldMaxLength").value||1;f.required=$("fieldRequired").checked;f.literal=$("fieldLiteral").value;f.compareTo=$("fieldCompareTo").value;f.posPrefixes=$("posPrefixes").value.trim();f.posDigits=+$("posDigits").value||2;f.separatorValue=$("separatorValue").value;f.dateOrder=$("dateOrder").value;f.dateCalendar=$("dateCalendar").value;f.dateYearDigits=+$("dateYearDigits").value||0;if(f.type==="BILL_DATE")f.dateRuleExplicit=true;''', "Admin update explicit rule")
text = replace_once(text, '''async function save(){\n  const t=build();''', '''async function save(){\n  const unconfirmedDate=(editing?.rows||[]).flat().find(f=>f.type==="BILL_DATE"&&f.dateRuleExplicit!==true);\n  if(unconfirmedDate)return SwalSmall.error("ยังบันทึกรูปแบบไม่ได้","กรุณาเปิดช่อง “วันที่ในบิล” แล้วเลือกกติกาวันที่ให้ตรงกับบิลจริงก่อนบันทึก");\n  const t=build();''', "Admin date confirmation save guard")
write(path, text)

# 9) Regression tests for duplicate false positives and user-facing date conversion.
path = "android-app/app/src/test/java/com/receiptocr/app/ocr/DuplicatePosEvidenceDetectorTest.kt"
text = read(path)
insert = r'''
    @Test
    fun differentTemplatesDoNotCreateFalseDuplicateForSamePos() {
        val second = mb.copy(
            templateId = "mb-alt-test",
            templateName = "Mb_alt",
            recognition = OcrTemplateRecognition(
                rows = listOf(OcrTemplateRow(row = 1, fields = listOf(
                    OcrTemplateField(1, "LITERAL", example = "X", literal = "X"),
                    OcrTemplateField(2, "NUMBER_TEXT", example = "20", minLength = 2, maxLength = 2),
                    OcrTemplateField(3, "POS_NUMBER", example = "1", minLength = 1, maxLength = 1, posDigits = 1),
                    OcrTemplateField(4, "CUSTOMER_VALUE", example = "111222", minLength = 6, maxLength = 6),
                    OcrTemplateField(5, "EMPLOYEE_CODE", example = "U110030", minLength = 7, maxLength = 7),
                    OcrTemplateField(6, "BILL_DATE", example = "20-08-69", minLength = 8, maxLength = 8, dateOrder = "DMY", dateCalendar = "BUDDHIST", dateYearDigits = 2),
                    OcrTemplateField(7, "BILL_TIME", example = "09:05", minLength = 5, maxLength = 5)
                )))
            )
        )
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf("R201657846U110030 20/08/69 17:51\nX201111222U110030 20-08-69 09:05"),
            templates = listOf(mb, second),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(1))
    }

    @Test
    fun separatorVariationAcrossPassesIsSameReceipt() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf("R201657846U110030 20/08/69 17:51", "R201657846U110030 20-08-69 17.51"),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(1))
    }
'''
idx = text.rfind("\n}")
if idx < 0: raise SystemExit("Duplicate test class end not found")
text = text[:idx] + insert + text[idx:]
write(path, text)

path = "android-app/app/src/test/java/com/receiptocr/app/ui/UserFacingOcrMessagesRound92Test.kt"
text = read(path)
text = replace_once(text, '''    @Test\n    fun correctedDateShowsOnlyAcceptedDate() {\n        val message = UserFacingOcrMessages.warning(\n            "วันที่ที่อ่านจากภาพ 20/08769 ถูกปรับเป็น 20/08/2026 ตามเงื่อนไข Admin • กรุณาตรวจเทียบกับภาพ"\n        )\n        assertTrue(message.contains("20/08/2026"))\n        assertFalse(message.contains("20/08769"))\n        assertFalse(message.contains("Admin", ignoreCase = true))\n    }''', '''    @Test\n    fun correctedDateAloneIsNotAWarning() {\n        val message = UserFacingOcrMessages.warning(\n            "วันที่ที่อ่านจากภาพ 20/08769 ถูกปรับเป็น 20/08/2026 ตามเงื่อนไข Admin • กรุณาตรวจเทียบกับภาพ"\n        )\n        assertTrue(message.isBlank())\n    }\n\n    @Test\n    fun normalPrintedDateConversionIsBlueInfoText() {\n        val message = UserFacingOcrMessages.dateInfo("08-21-2026", "21/08/2026")\n        assertTrue(message.contains("08-21-2026"))\n        assertTrue(message.contains("21/08/2026"))\n    }\n\n    @Test\n    fun noisyDateConversionDoesNotExposeNoisyRawValue() {\n        val message = UserFacingOcrMessages.dateInfo("20/08769", "20/08/2026")\n        assertTrue(message.contains("20/08/2026"))\n        assertFalse(message.contains("20/08769"))\n    }''', "UserFacing correction tests")
write(path, text)

# 10) Version bump.
path = "android-app/app/build.gradle.kts"
text = read(path)
text = replace_once(text, "versionCode = 94", "versionCode = 95", "version code")
text = replace_once(text, 'versionName = "0.92.0"', 'versionName = "0.93.0"', "version name")
write(path, text)

print("Round93 production patch applied")
