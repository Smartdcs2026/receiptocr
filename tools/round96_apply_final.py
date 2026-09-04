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
        raise SystemExit(f"{rel}: expected exactly one match, got {count}: {old[:160]!r}")
    write(rel, text.replace(old, new, 1))


# Round96 starts from verified Round95 production only.
replace_once(
    "android-app/app/build.gradle.kts",
    '        versionCode = 97\n        versionName = "0.95.0"',
    '        versionCode = 98\n        versionName = "0.96.0"',
)

# Duplicate detection: two complete physical lines for the same POS are important.
# If both lines carry a store code, they count as duplicate only when that store is the same.
write(
    "android-app/app/src/main/java/com/receiptocr/app/ocr/DuplicatePosEvidenceDetector.kt",
    r'''package com.receiptocr.app.ocr

import com.receiptocr.app.config.PosIdentityRule
import com.receiptocr.app.config.UniversalOcrTemplate

object DuplicatePosEvidenceDetector {
    private data class Sighting(
        val passIndex: Int,
        val lineIndex: Int,
        val templateId: String,
        val storeId: String
    )

    fun detect(
        rawTexts: List<String>,
        templates: List<UniversalOcrTemplate>,
        allowedPos: Set<Int>,
        posIdentityRule: PosIdentityRule = PosIdentityRule()
    ): Map<Int, String> {
        if (rawTexts.isEmpty() || templates.none { it.active }) return emptyMap()

        val sightingsByPos = linkedMapOf<Int, MutableList<Sighting>>()
        rawTexts.forEachIndexed { passIndex, raw ->
            if (raw.isBlank()) return@forEachIndexed
            val sourceLines = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()

            sourceLines.forEachIndexed { lineIndex, line ->
                templates.filter { it.active && it.validation.pos.mustBeUnique }.forEach { template ->
                    TemplateSequenceFallback.parseText(line, template)
                        .mapNotNull { fields ->
                            val resolved = PosIdentityResolver.resolve(fields["POS_NUMBER"].orEmpty(), posIdentityRule)
                                ?: return@mapNotNull null
                            val pos = resolved.workPos
                            if (allowedPos.isNotEmpty() && pos !in allowedPos) return@mapNotNull null
                            if (!hasCompleteReceipt(fields)) return@mapNotNull null
                            pos to normalizeStore(fields["STORE_ID"].orEmpty())
                        }
                        .distinct()
                        .forEach { (pos, store) ->
                            sightingsByPos.getOrPut(pos) { mutableListOf() }
                                .add(Sighting(passIndex, lineIndex, template.templateId, store))
                        }
                }
            }
        }

        return buildMap {
            sightingsByPos.forEach { (pos, sightings) ->
                var sameStoreDuplicate = false
                var unknownStoreDuplicate = false

                sightings.groupBy { it.passIndex to it.templateId }.values.forEach { samePassTemplate ->
                    val lines = samePassTemplate.distinctBy { it.lineIndex }
                    for (i in lines.indices) {
                        for (j in (i + 1) until lines.size) {
                            val first = lines[i]
                            val second = lines[j]
                            if (first.lineIndex == second.lineIndex) continue
                            val bothKnown = first.storeId.isNotBlank() && second.storeId.isNotBlank()
                            when {
                                bothKnown && sameStore(first.storeId, second.storeId) -> sameStoreDuplicate = true
                                !bothKnown -> unknownStoreDuplicate = true
                            }
                        }
                    }
                }

                when {
                    sameStoreDuplicate -> put(pos, "พบบิลซ้ำในร้านเดียวกัน • POS $pos มีมากกว่า 1 ใบ")
                    unknownStoreDuplicate -> put(pos, "พบ POS $pos มากกว่า 1 ใบในภาพ • กรุณาตรวจบิล")
                }
            }
        }
    }

    private fun hasCompleteReceipt(fields: Map<String, String>): Boolean {
        val customer = fields["CUSTOMER_VALUE"].orEmpty().filter(Char::isDigit)
        val date = OcrTextNormalizer.normalizeDigits(fields["BILL_DATE"].orEmpty()).filter(Char::isDigit)
        val time = OcrTextNormalizer.normalizeDigits(fields["BILL_TIME"].orEmpty()).filter(Char::isDigit)
        return customer.isNotBlank() && date.isNotBlank() && time.isNotBlank()
    }

    private fun normalizeStore(raw: String): String = raw.trim().uppercase()
        .replace(Regex("[\\s._/-]+"), "")
        .filter { it.isLetterOrDigit() }

    private fun sameStore(first: String, second: String): Boolean {
        val a = normalizeStore(first)
        val b = normalizeStore(second)
        if (a.isBlank() || b.isBlank()) return false
        if (a.all(Char::isDigit) && b.all(Char::isDigit)) {
            return a.trimStart('0').ifBlank { "0" } == b.trimStart('0').ifBlank { "0" }
        }
        return a == b
    }
}
'''
)

# Short field-facing messages and a single critical classifier shared by the UI.
write(
    "android-app/app/src/main/java/com/receiptocr/app/ui/UserFacingOcrMessages.kt",
    r'''package com.receiptocr.app.ui

object UserFacingOcrMessages {
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
        if (
            text.contains("หลักฐานยืนยัน OCR", ignoreCase = true) || text.contains("ช่องที่ยืนยันเพียง") ||
            Regex("(?:ลูกค้า|วันที่|เวลา)\\s+\\d+\\s+รอบ").containsMatchIn(text)
        ) messages += "ข้อมูลบางช่องไม่ชัด • ตรวจจากภาพบิล"

        if (
            text.contains("พบบิลซ้ำในร้านเดียวกัน") || text.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS") ||
            (text.contains("POS") && text.contains("ซ้ำ"))
        ) {
            val pos = Regex("POS\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)
            messages += if (pos != null) "พบบิลซ้ำในร้านเดียวกัน • POS $pos มีมากกว่า 1 ใบ" else "พบบิลซ้ำในร้านเดียวกัน • ตรวจบิล"
        } else if (text.contains("มากกว่า 1 ใบในภาพ") && text.contains("POS")) {
            val pos = Regex("POS\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)
            messages += if (pos != null) "พบ POS $pos มากกว่า 1 ใบ • ตรวจบิล" else "พบ POS มากกว่า 1 ใบ • ตรวจบิล"
        }

        if (text.contains("ภาพใหม่อ่านข้อมูล POS") && text.contains("ต่างจากข้อมูลเดิม")) {
            val pos = Regex("POS\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)
            messages += if (pos != null) "ข้อมูล POS $pos จากภาพล่าสุดไม่ตรงกับข้อมูลเดิม" else "ข้อมูลจากภาพล่าสุดไม่ตรงกับข้อมูลเดิม"
        }

        when {
            text.contains("พบบิลสลับร้าน") -> messages += "มีบิลจากร้านอื่นปะปน • ตรวจบิล"
            text.contains("บิลผิดร้าน") -> messages += "บิลผิดร้าน • รหัสร้านบนบิลไม่ตรงกับงาน"
            text.contains("รหัสร้าน") && text.contains("ไม่ตรง") -> messages += "รหัสร้านบนบิลไม่ตรงกับงาน"
            text.contains("ยืนยันร้านไม่ได้") || text.contains("ไม่พบรหัสร้าน") -> messages += "ยังอ่านรหัสร้านไม่ได้ • ตรวจภาพบิล"
        }

        if (text.contains("ค่านี้จะไม่ถูกใช้เป็นวันที่") || text.contains("วันที่ที่อ่านจากภาพ") && text.contains("ไม่ตรง")) messages += "วันที่บิลไม่ถูกต้อง • ตรวจจากภาพ"
        if (text.contains("ค่านี้จะไม่ถูกใช้เป็นเวล") || text.contains("เวลาที่อ่านจากภาพไม่ถูกต้อง")) messages += "เวลาในบิลไม่ถูกต้อง • ตรวจจากภาพ"
        if (text.contains("ไม่พบวันที่")) messages += "ยังไม่พบวันที่บิล"
        if (text.contains("ไม่พบเวลา")) messages += "ยังไม่พบเวลาในบิล"
        if (text.contains("ไม่พบยอด/เลขลูกค้า") || text.contains("ยังไม่มีเลข/ยอดลูกค้า")) messages += "ยังไม่พบเลข/ยอดลูกค้า"
        if (text.contains("ยังอ่านไม่ครบ") && text.contains("ขาด POS")) {
            Regex("ขาด POS\\s+([^•]+)").find(text)?.groupValues?.getOrNull(1)?.trim()?.let { messages += "ยังขาด POS $it" }
        }
        if (text.contains("ยังไม่ได้กำหนดว่าจะลง POS ใด")) {
            val identity = Regex("""หมายเลขเครื่อง\s+([A-Za-z0-9]+)""").find(text)?.groupValues?.getOrNull(1)
            messages += if (identity != null) "พบเครื่อง $identity แต่ยังไม่มีช่อง POS" else "พบเครื่องที่ยังไม่มีช่อง POS"
        }
        if (text.contains("ถูกระบุว่าไม่ได้บิล")) {
            val pos = Regex("""POS\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)
            messages += if (pos != null) "พบข้อมูล POS $pos แต่เลือกไว้ว่าไม่ได้บิล" else "พบข้อมูลในช่องที่เลือกไว้ว่าไม่ได้บิล"
        }
        if (messages.isEmpty()) messages += "ตรวจข้อมูลกับภาพบิลก่อนใช้"
        return messages.joinToString(" • ")
    }

    fun hasVisibleWarning(raw: String): Boolean = warning(raw).isNotBlank()

    fun isCritical(raw: String): Boolean {
        val text = raw.trim()
        return text.contains("บิลผิดร้าน") || text.contains("บิลสลับร้าน") || text.contains("ร้านอื่นปะปน") ||
            text.contains("พบบิลซ้ำ") || text.contains("มากกว่า 1 ใบในภาพ") ||
            (text.contains("POS") && text.contains("ซ้ำ")) || text.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS")
    }

    fun dateInfo(rawDate: String, canonicalDate: String): String {
        val raw = rawDate.trim()
        val canonical = canonicalDate.trim()
        if (raw.isBlank() || canonical.isBlank()) return ""
        val normalizedPunctuation = raw.replace('.', '/').replace('-', '/').replace(Regex("\\s+"), "")
        if (normalizedPunctuation == canonical) return ""
        val structured = Regex("""^\d{1,4}[./-]\d{1,2}[./-]\d{2,4}$""").matches(raw)
        return if (structured) "วันที่บนบิล $raw → $canonical" else "วันที่บิลใช้เป็น $canonical"
    }

    fun summary(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""
        return when {
            text.contains("ไม่พบข้อความในภาพ") -> "ยังอ่านบิลไม่ได้ • ถ่ายใหม่ให้ชัด"
            text.contains("ยังอ่านไม่ครบ") && text.contains("ขาด") -> text
            text.contains("ยังแยกข้อมูล") || text.contains("ยังอ่านหมายเลขเครื่องไม่ได้") -> "อ่านบิลยังไม่ครบ • ตรวจภาพแล้วลองอีกครั้ง"
            text.contains("สำเร็จ") || text.contains("พบ") -> "อ่านบิลแล้ว • ตรวจข้อมูลก่อนใช้"
            else -> "ตรวจข้อมูลกับภาพบิลก่อนใช้"
        }
    }
}
'''
)

# Submission blocker understands both old and new duplicate wording.
replace_once(
    "android-app/app/src/main/java/com/receiptocr/app/validation/ReceiptValidationEngine.kt",
    '''        records.filter { !it.noReceipt }.forEach { record ->\n            if (record.ocrWarnings.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS")) {\n                issues += block(\n                    "DUPLICATE_POS_EVIDENCE_POS_${record.posNumber}",\n                    "พบบิล POS ${record.posNumber} ซ้ำ • กรุณาตรวจภาพบิลก่อนส่ง"\n                )\n            }\n        }''',
    '''        records.filter { !it.noReceipt }.forEach { record ->\n            val warning = record.ocrWarnings\n            val duplicate = warning.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS") ||\n                warning.contains("พบบิลซ้ำ") || warning.contains("มากกว่า 1 ใบในภาพ") ||\n                (warning.contains("POS") && warning.contains("ซ้ำ"))\n            if (duplicate) {\n                issues += block(\n                    "DUPLICATE_POS_EVIDENCE_POS_${record.posNumber}",\n                    "พบบิลซ้ำ • POS ${record.posNumber} มีมากกว่า 1 ใบ"\n                )\n            }\n        }'''
)

ui = "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt"

# High-contrast semantic palette for light APK backgrounds.
replace_once(
    ui,
    '''private val SuccessGreen = Color(0xFF37A26C)\nprivate val DraftBlue = Color(0xFF93D4F7)\nprivate val WarningOrange = Color(0xFF9A4A00)\nprivate val DefaultBlue = Color(0xFF5D7FC8)\nprivate val DateBeforeBlue = Color(0xFF2563C9)\nprivate val DateExactGreen = Color(0xFF188A55)\nprivate val DateAfterOrange = Color(0xFFC66A05)\nprivate val ErrorSoft = Color(0xFFFFF1E4)''',
    '''private val SuccessGreen = Color(0xFF24885A)\nprivate val SuccessSoft = Color(0xFFEAF8F0)\nprivate val DraftBlue = Color(0xFF93D4F7)\nprivate val WarningOrange = Color(0xFF8A4B08)\nprivate val WarningSoft = Color(0xFFFFF7E6)\nprivate val WarningBorder = Color(0xFFE4B067)\nprivate val CriticalRed = Color(0xFFB42318)\nprivate val CriticalSoft = Color(0xFFFFF1F0)\nprivate val CriticalBorder = Color(0xFFE3A29B)\nprivate val DefaultBlue = Color(0xFF5D7FC8)\nprivate val DateBeforeBlue = Color(0xFF2563C9)\nprivate val DateExactGreen = Color(0xFF188A55)\nprivate val DateAfterOrange = Color(0xFFC66A05)\nprivate val ErrorSoft = CriticalSoft'''
)

# Bottom feedback color follows meaning instead of showing every problem in blue.
replace_once(
    ui,
    '''                    if (message.isNotBlank()) {\n                        Surface(\n                            modifier = Modifier.fillMaxWidth(),\n                            shape = RoundedCornerShape(10.dp),\n                            color = if (message.contains("สำเร็จ")) Color(0xFFEAF8F0) else PrimarySoft\n                        ) {\n                            Text(\n                                text = message,\n                                modifier = Modifier.padding(10.dp),\n                                color = if (message.contains("สำเร็จ")) SuccessGreen else Primary,\n                                fontSize = 12.sp\n                            )\n                        }\n                        Spacer(Modifier.height(8.dp))\n                    }''',
    '''                    if (message.isNotBlank()) {\n                        val messageCritical = message.contains("บิลผิดร้าน") || message.contains("บิลซ้ำ") ||\n                            message.contains("ร้านอื่น") || message.contains("ไม่สำเร็จ") || message.contains("ต้องแก้")\n                        val messageCaution = !messageCritical && (message.contains("ตรวจ") || message.contains("ยังขาด") ||\n                            message.contains("ยังอ่าน") || message.contains("กรุณา") || message.contains("แก้วันที่"))\n                        val messageSuccess = !messageCritical && !messageCaution && (message.contains("สำเร็จ") ||\n                            message.contains("บันทึก") || message.contains("ส่งข้อมูลแล้ว"))\n                        val messageBg = when {\n                            messageCritical -> CriticalSoft\n                            messageCaution -> WarningSoft\n                            messageSuccess -> SuccessSoft\n                            else -> PrimarySoft\n                        }\n                        val messageFg = when {\n                            messageCritical -> CriticalRed\n                            messageCaution -> WarningOrange\n                            messageSuccess -> SuccessGreen\n                            else -> Primary\n                        }\n                        Surface(\n                            modifier = Modifier.fillMaxWidth(),\n                            shape = RoundedCornerShape(10.dp),\n                            color = messageBg,\n                            border = BorderStroke(1.dp, messageFg.copy(alpha = 0.22f))\n                        ) {\n                            Text(\n                                text = message,\n                                modifier = Modifier.padding(10.dp),\n                                color = messageFg,\n                                fontSize = 12.sp,\n                                fontWeight = FontWeight.SemiBold\n                            )\n                        }\n                        Spacer(Modifier.height(8.dp))\n                    }'''
)

# Read-failure dialog: short and field-friendly.
replace_once(ui, '                    "ยังอ่านบิลไม่ครบ",\n', '                    "อ่านบิลยังไม่ครบ",\n')
replace_once(
    ui,
    '''                    Text(\n                        "ระบบยังอ่านข้อมูลที่จำเป็นจากภาพนี้ไม่ครบ",\n                        color = TextMain,\n                        fontWeight = FontWeight.Bold,\n                        fontSize = 14.sp\n                    )\n                    Text(\n                        "กรุณาตรวจว่าภาพเห็นวันที่ เวลา เลข/ยอดลูกค้า และหมายเลขเครื่องชัดเจน แล้วลองอ่านอีกครั้ง",\n                        color = TextSub,\n                        fontSize = 12.sp,\n                        lineHeight = 18.sp\n                    )''',
    '''                    Text(\n                        "ถ่ายใหม่ให้เห็น วันที่ เวลา เลขลูกค้า และ POS ชัดเจน",\n                        color = TextMain,\n                        fontWeight = FontWeight.SemiBold,\n                        fontSize = 13.sp,\n                        lineHeight = 19.sp\n                    )'''
)

# Critical receipt problems get a dedicated red notice at the top of the result dialog.
replace_once(
    ui,
    '''        val hasCriticalIntegrityWarning = hasStoreReviewWarning || hasHardIntegrityBlock\n        val unresolvedPos = proposal.proposedRecords''',
    '''        val hasCriticalIntegrityWarning = hasStoreReviewWarning || hasHardIntegrityBlock\n        val criticalReceiptMessages = buildList {\n            if (hasHardIntegrityBlock) add("พบบิลซ้ำ • แก้บิลซ้ำก่อน")\n            if (hasStoreReviewWarning) add("บิลไม่ตรงร้าน • ตรวจรหัสร้านจากภาพ")\n        }.distinct()\n        val unresolvedPos = proposal.proposedRecords'''
)
replace_once(
    ui,
    '''                    tint = when {\n                        hasDateWarning || hasCriticalIntegrityWarning -> MaterialTheme.colorScheme.error\n                        proposal.confidence == OcrConfidence.HIGH -> SuccessGreen\n                        else -> WarningOrange\n                    }''',
    '''                    tint = when {\n                        hasDateWarning || hasCriticalIntegrityWarning -> CriticalRed\n                        proposal.confidence == OcrConfidence.HIGH -> SuccessGreen\n                        else -> WarningOrange\n                    }'''
)
replace_once(
    ui,
    '''                    when {\n                        hasCriticalIntegrityWarning -> "ตรวจข้อมูลบิล"\n                        hasDateWarning -> "ตรวจวันที่บิล"\n                        else -> "ตรวจทานผลอ่านบิล"\n                    },''',
    '''                    when {\n                        hasHardIntegrityBlock -> "พบบิลซ้ำ"\n                        hasStoreReviewWarning -> "บิลไม่ตรงร้าน"\n                        hasDateWarning -> "ตรวจวันที่บิล"\n                        else -> "ตรวจข้อมูลบิล"\n                    },'''
)
replace_once(
    ui,
    '''                    Row(\n                        modifier = Modifier.fillMaxWidth(),\n                        horizontalArrangement = Arrangement.Center,\n                        verticalAlignment = Alignment.CenterVertically\n                    ) {\n                        DatePositionLegend("ก่อนวันงาน", DateBeforeBlue)''',
    '''                    if (criticalReceiptMessages.isNotEmpty()) {\n                        Surface(\n                            modifier = Modifier.fillMaxWidth(),\n                            shape = RoundedCornerShape(10.dp),\n                            color = CriticalSoft,\n                            border = BorderStroke(1.5.dp, CriticalBorder)\n                        ) {\n                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {\n                                criticalReceiptMessages.forEach { critical ->\n                                    Row(verticalAlignment = Alignment.Top) {\n                                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = CriticalRed, modifier = Modifier.size(17.dp))\n                                        Spacer(Modifier.width(6.dp))\n                                        Text(\n                                            critical, modifier = Modifier.weight(1f), color = CriticalRed,\n                                            fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold\n                                        )\n                                    }\n                                }\n                            }\n                        }\n                    }\n                    Row(\n                        modifier = Modifier.fillMaxWidth(),\n                        horizontalArrangement = Arrangement.Center,\n                        verticalAlignment = Alignment.CenterVertically\n                    ) {\n                        DatePositionLegend("ก่อนวันงาน", DateBeforeBlue)'''
)

# Critical warnings use red; ordinary review stays dark amber.
replace_once(
    ui,
    '''                            Text(\n                                "• $warning",\n                                fontSize = 11.sp,\n                                color = if (critical) MaterialTheme.colorScheme.error else WarningOrange\n                            )''',
    '''                            Text(\n                                "• $warning",\n                                fontSize = 11.sp,\n                                color = if (critical) CriticalRed else WarningOrange,\n                                fontWeight = FontWeight.SemiBold\n                            )'''
)
replace_once(
    ui,
    '''                    Text(\n                        if (hasDateWarning) {\n                            "เลือกใช้ชุดวันที่ให้ตรงกันก่อนส่ง"\n                        } else {\n                            "ตรวจเทียบกับภาพก่อนนำข้อมูลไปใช้งาน"\n                        },\n                        fontSize = 11.sp,\n                        color = TextSub\n                    )''',
    '''                    Text(\n                        when {\n                            hasHardIntegrityBlock -> "แก้บิลซ้ำก่อน"\n                            hasStoreReviewWarning -> "ตรวจรหัสร้านก่อน"\n                            hasDateWarning -> "แก้วันที่ก่อน"\n                            else -> "ตรวจข้อมูลก่อนใช้"\n                        },\n                        fontSize = 11.sp,\n                        color = TextSub,\n                        fontWeight = FontWeight.SemiBold\n                    )'''
)
replace_once(
    ui,
    '''                        message = when {\n                            hasStoreReviewWarning -> "เก็บข้อมูลที่อ่านได้แล้ว • กรุณาตรวจรหัสร้านใน POS ที่แจ้ง"\n                            hasDateWarning -> "วันที่แต่ละ POS ใช้ได้ แต่ชุดวันที่ยังใช้ร่วมกันไม่ได้"\n                            else -> "บันทึกข้อมูลจากบิลแล้ว"\n                        }''',
    '''                        message = when {\n                            hasStoreReviewWarning -> "เก็บข้อมูลแล้ว • ตรวจรหัสร้านก่อนส่ง"\n                            hasDateWarning -> "เก็บข้อมูลแล้ว • แก้วันที่ก่อนส่ง"\n                            else -> "บันทึกข้อมูลจากบิลแล้ว"\n                        }'''
)
replace_once(
    ui,
    '''                        when {\n                            hasHardIntegrityBlock -> "ต้องตรวจภาพก่อน"\n                            hasStoreReviewWarning -> "นำข้อมูลไปตรวจรหัสร้าน"\n                            hasDateWarning -> "นำข้อมูลไปแก้ไข"\n                            else -> "ใช้ข้อมูลนี้"\n                        }''',
    '''                        when {\n                            hasHardIntegrityBlock -> "แก้บิลซ้ำก่อน"\n                            hasStoreReviewWarning -> "ตรวจรหัสร้าน"\n                            hasDateWarning -> "แก้วันที่"\n                            else -> "ใช้ข้อมูล"\n                        }'''
)

# POS card severity: wrong store / duplicate / invalid date red; ordinary missing/review amber.
replace_once(
    ui,
    '''    val visibleOcrWarning = UserFacingOcrMessages.warning(warningForUser)\n    val dateInfoText = UserFacingOcrMessages.dateInfo(record.ocrRawBillDate, record.billDate)\n    val hasValidationWarning = customerMissing || dateMissing || timeMissing || dateWarning || visibleOcrWarning.isNotBlank()''',
    '''    val visibleOcrWarning = UserFacingOcrMessages.warning(warningForUser)\n    val criticalOcrWarning = UserFacingOcrMessages.isCritical(warningForUser)\n    val dateInfoText = UserFacingOcrMessages.dateInfo(record.ocrRawBillDate, record.billDate)\n    val hasValidationWarning = customerMissing || dateMissing || timeMissing || dateWarning || visibleOcrWarning.isNotBlank()\n    val hasCriticalWarning = dateWarning || criticalOcrWarning || (storeMismatch && !storeReviewValid)'''
)
replace_once(
    ui,
    '''        border = BorderStroke(\n            width = if (hasValidationWarning) 1.5.dp else 1.dp,\n            color = if (hasValidationWarning) MaterialTheme.colorScheme.error else Border\n        )''',
    '''        border = BorderStroke(\n            width = if (hasValidationWarning) 1.5.dp else 1.dp,\n            color = when {\n                hasCriticalWarning -> CriticalRed\n                hasValidationWarning -> WarningBorder\n                else -> Border\n            }\n        )'''
)

# Store mismatch card: important, concise, and no dead button.
replace_once(
    ui,
    '''                            color = if (storeReviewValid) Color(0xFFEAF8F0) else Color(0xFFFFF1F1),\n                            border = BorderStroke(1.dp, if (storeReviewValid) SuccessGreen else MaterialTheme.colorScheme.error)''',
    '''                            color = if (storeReviewValid) SuccessSoft else CriticalSoft,\n                            border = BorderStroke(1.5.dp, if (storeReviewValid) SuccessGreen else CriticalBorder)'''
)
replace_once(
    ui,
    '''                                    if (storeReviewValid) "ยืนยันรหัสร้านแล้ว" else "ตรวจรหัสร้าน",\n                                    color = if (storeReviewValid) SuccessGreen else MaterialTheme.colorScheme.error,''',
    '''                                    if (storeReviewValid) "ยืนยันรหัสร้านแล้ว" else "บิลไม่ตรงร้าน",\n                                    color = if (storeReviewValid) SuccessGreen else CriticalRed,'''
)
replace_once(
    ui,
    '''                                    Text(\n                                        "ตรวจตัวเลขจากภาพบิลก่อนยืนยัน ระบบจะไม่แก้รหัสร้านให้อัตโนมัติ",\n                                        color = TextSub, fontSize = 11.sp, lineHeight = 16.sp\n                                    )''',
    '''                                    Text(\n                                        "ตรวจรหัสจากภาพก่อนยืนยัน",\n                                        color = TextMain, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold\n                                    )'''
)
replace_once(
    ui,
    '''                                        Text("ตรวจจากภาพแล้ว รหัสบนบิลคือ $expectedStoreId", textAlign = TextAlign.Center)\n                                    }\n                                    OutlinedButton(\n                                        onClick = { /* คงข้อมูลที่อ่านถูกไว้ และปล่อยสถานะบล็อกจนเปลี่ยนบิล */ },\n                                        modifier = Modifier.fillMaxWidth()\n                                    ) { Text("บิลนี้เป็นร้านอื่น") }\n                                    Text("หากเป็นบิลร้านอื่น ให้เปลี่ยนภาพบิลก่อนส่งงาน", color = MaterialTheme.colorScheme.error, fontSize = 10.5.sp)''',
    '''                                        Text("ยืนยันรหัสร้าน $expectedStoreId", textAlign = TextAlign.Center)\n                                    }\n                                    Text("ถ้าเป็นร้านอื่น ให้เปลี่ยนภาพบิลก่อนส่ง", color = CriticalRed, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)'''
)

# POS warning card uses readable foreground/background pairing.
replace_once(
    ui,
    '''                    if (visibleOcrWarning.isNotBlank()) {\n                        Spacer(Modifier.height(8.dp))\n                        Surface(\n                            modifier = Modifier.fillMaxWidth(),\n                            shape = RoundedCornerShape(9.dp),\n                            color = Color(0xFFFFF1F1),\n                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)\n                        ) {\n                            Text(visibleOcrWarning, modifier = Modifier.padding(9.dp), color = MaterialTheme.colorScheme.error, fontSize = 10.5.sp)\n                        }\n                    }''',
    '''                    if (visibleOcrWarning.isNotBlank()) {\n                        Spacer(Modifier.height(8.dp))\n                        Surface(\n                            modifier = Modifier.fillMaxWidth(),\n                            shape = RoundedCornerShape(9.dp),\n                            color = if (criticalOcrWarning) CriticalSoft else WarningSoft,\n                            border = BorderStroke(1.dp, if (criticalOcrWarning) CriticalBorder else WarningBorder)\n                        ) {\n                            Text(\n                                visibleOcrWarning, modifier = Modifier.padding(9.dp),\n                                color = if (criticalOcrWarning) CriticalRed else WarningOrange,\n                                fontSize = 10.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold\n                            )\n                        }\n                    }'''
)

# Regression tests for same-store physical duplicate and short user language.
write(
    "android-app/app/src/test/java/com/receiptocr/app/ocr/DuplicatePosEvidenceRound96Test.kt",
    r'''package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.UniversalOcrTemplate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatePosEvidenceRound96Test {
    private val template = UniversalOcrTemplate(
        templateId = "store-pos-round96", brandId = "T", templateName = "StorePos",
        recognition = OcrTemplateRecognition(rows = listOf(OcrTemplateRow(row = 1, fields = listOf(
            OcrTemplateField(1, "STORE_ID", example = "1600", minLength = 4, maxLength = 4),
            OcrTemplateField(2, "POS_NUMBER", example = "1", minLength = 1, maxLength = 1, posDigits = 1),
            OcrTemplateField(3, "CUSTOMER_VALUE", example = "123456", minLength = 6, maxLength = 6),
            OcrTemplateField(4, "BILL_DATE", example = "05/09/26", minLength = 8, maxLength = 8, dateOrder = "DMY", dateCalendar = "GREGORIAN", dateYearDigits = 2),
            OcrTemplateField(5, "BILL_TIME", example = "10:00", minLength = 5, maxLength = 5)
        ))))
    )

    @Test fun sameStoreSamePosTwoPhysicalLinesWarn() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            listOf("1600 1 123456 05/09/26 10:00\n1600 1 654321 05/09/26 10:05"), listOf(template), setOf(1)
        )
        assertTrue(warnings[1]?.contains("พบบิลซ้ำในร้านเดียวกัน") == true)
    }

    @Test fun identicalValuesOnTwoPhysicalLinesStillWarn() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            listOf("1600 1 123456 05/09/26 10:00\n1600 1 123456 05/09/26 10:00"), listOf(template), setOf(1)
        )
        assertTrue(warnings.containsKey(1))
    }

    @Test fun samePosDifferentKnownStoreIsNotDuplicateWarning() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            listOf("1600 1 123456 05/09/26 10:00\n7600 1 654321 05/09/26 10:05"), listOf(template), setOf(1)
        )
        assertFalse(warnings.containsKey(1))
    }

    @Test fun repeatedPassesAreNotPhysicalDuplicates() {
        val raw = "1600 1 123456 05/09/26 10:00"
        assertFalse(DuplicatePosEvidenceDetector.detect(listOf(raw, raw, raw), listOf(template), setOf(1)).containsKey(1))
    }
}
'''
)

write(
    "android-app/app/src/test/java/com/receiptocr/app/ui/UserFacingOcrMessagesRound96Test.kt",
    r'''package com.receiptocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFacingOcrMessagesRound96Test {
    @Test fun wrongStoreIsShortAndCritical() {
        val raw = "บิลผิดร้าน • รหัสร้านที่อ่านได้ (7600) ไม่ตรงกับรหัสร้านของงาน (1600)"
        assertEquals("บิลผิดร้าน • รหัสร้านบนบิลไม่ตรงกับงาน", UserFacingOcrMessages.warning(raw))
        assertTrue(UserFacingOcrMessages.isCritical(raw))
    }

    @Test fun duplicateIsShortAndCritical() {
        val raw = "พบบิลซ้ำในร้านเดียวกัน • POS 2 มีมากกว่า 1 ใบ"
        assertEquals("พบบิลซ้ำในร้านเดียวกัน • POS 2 มีมากกว่า 1 ใบ", UserFacingOcrMessages.warning(raw))
        assertTrue(UserFacingOcrMessages.isCritical(raw))
    }

    @Test fun missingTimeIsCautionNotCritical() {
        val raw = "ไม่พบเวลา ตามเงื่อนไขที่กำหนด"
        assertEquals("ยังไม่พบเวลาในบิล", UserFacingOcrMessages.warning(raw))
        assertFalse(UserFacingOcrMessages.isCritical(raw))
    }
}
'''
)

print("Round96 final patch applied")
