from pathlib import Path

path = Path("android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt")
text = path.read_text(encoding="utf-8")

old = '''        val customerIndex = ordered.indexOfFirst { it.field.type.equals("CUSTOMER_VALUE", true) }
        val minimumDepth = maxOf(posIndex + 1, if (customerIndex in 0..posIndex + 2) customerIndex + 1 else posIndex + 1)
        val compiled = (minimumDepth..ordered.size).mapNotNull { depth ->
'''
new = '''        val customerIndex = ordered.indexOfFirst { it.field.type.equals("CUSTOMER_VALUE", true) }
        val baseMinimumDepth = maxOf(
            posIndex + 1,
            if (customerIndex in 0..posIndex + 2) customerIndex + 1 else posIndex + 1
        )
        // Date/time recovery may only begin after every required structural field before
        // the first recoverable core field has been matched. This keeps Admin schema strict:
        // e.g. R | ... | CUSTOMER | U | 6 digits | DATE | TIME cannot skip U/6 digits.
        val firstRecoverableCoreIndex = ordered.indexOfFirst { item ->
            item.field.type.equals("BILL_DATE", true) ||
                item.field.type.equals("BILL_TIME", true)
        }
        val minimumDepth = if (firstRecoverableCoreIndex >= baseMinimumDepth) {
            firstRecoverableCoreIndex
        } else {
            baseMinimumDepth
        }
        val compiled = (minimumDepth..ordered.size).mapNotNull { depth ->
'''
if old in text:
    text = text.replace(old, new, 1)
elif "val baseMinimumDepth = maxOf(" not in text:
    raise SystemExit("minimumDepth block not found")

old = '''    private data class ResolvedPosCandidate(
        val template: UniversalOcrTemplate,
        val values: Map<String, ResolvedValue>,
        val completePassSupport: Int,
        val weakestCoreSupport: Int,
        val score: Int
    )
'''
new = '''    private data class ResolvedPosCandidate(
        val template: UniversalOcrTemplate,
        val values: Map<String, ResolvedValue>,
        val completePassSupport: Int,
        val weakestCoreSupport: Int,
        val coreSupportByField: Map<String, Int>,
        val score: Int
    )
'''
if old in text:
    text = text.replace(old, new, 1)
elif "coreSupportByField" not in text:
    raise SystemExit("ResolvedPosCandidate block not found")

old = '''            val warning = if (resolved.weakestCoreSupport <= 1 && rawTexts.count { it.isNotBlank() } >= 3) {
                "POS นี้มีข้อมูลครบจากอย่างน้อยหนึ่งรอบ แต่บางช่องมีหลักฐานยืนยันซ้ำไม่ถึง 2 รอบ กรุณาตรวจเทียบกับภาพก่อนส่ง"
            } else ""
'''
new = '''            val warning = buildConfidenceWarning(
                resolved = resolved,
                passCount = rawTexts.count { it.isNotBlank() }
            )
'''
if old in text:
    text = text.replace(old, new, 1)
elif "buildConfidenceWarning(" not in text:
    raise SystemExit("warning block not found")

old = '''        val coreSupports = buildList {
            if (core.customerValue) values["CUSTOMER_VALUE"]?.support?.let(::add)
            if (core.date) values["BILL_DATE"]?.support?.let(::add)
            if (core.time) values["BILL_TIME"]?.support?.let(::add)
        }
        val weakest = coreSupports.minOrNull() ?: 1
'''
new = '''        val coreSupportByField = linkedMapOf<String, Int>()
        if (core.customerValue) coreSupportByField["CUSTOMER_VALUE"] = values["CUSTOMER_VALUE"]?.support ?: 0
        if (core.date) coreSupportByField["BILL_DATE"] = values["BILL_DATE"]?.support ?: 0
        if (core.time) coreSupportByField["BILL_TIME"] = values["BILL_TIME"]?.support ?: 0
        val weakest = coreSupportByField.values.minOrNull() ?: 1
'''
if old in text:
    text = text.replace(old, new, 1)
elif "val coreSupportByField = linkedMapOf<String, Int>()" not in text:
    raise SystemExit("core support block not found")

old = '''        return ResolvedPosCandidate(
            template = template,
            values = values,
            completePassSupport = completePassSupport,
            weakestCoreSupport = weakest,
            score = score
        )
    }

    private fun isEvidenceCoreComplete(
'''
new = '''        return ResolvedPosCandidate(
            template = template,
            values = values,
            completePassSupport = completePassSupport,
            weakestCoreSupport = weakest,
            coreSupportByField = coreSupportByField,
            score = score
        )
    }

    private fun buildConfidenceWarning(
        resolved: ResolvedPosCandidate,
        passCount: Int
    ): String {
        if (passCount < 3 || resolved.weakestCoreSupport >= 2) return ""
        val labels = mapOf(
            "CUSTOMER_VALUE" to "ลูกค้า",
            "BILL_DATE" to "วันที่",
            "BILL_TIME" to "เวลา"
        )
        val detail = listOf("CUSTOMER_VALUE", "BILL_DATE", "BILL_TIME")
            .mapNotNull { type ->
                resolved.coreSupportByField[type]?.let { support ->
                    "${labels[type] ?: type} $support รอบ"
                }
            }
            .joinToString(" • ")
        val complete = if (resolved.completePassSupport > 0) {
            " • รอบที่อ่านข้อมูลหลักครบ ${resolved.completePassSupport} รอบ"
        } else ""
        return "หลักฐานยืนยัน OCR: $detail$complete • ช่องที่ยืนยันเพียง 1 รอบยังต้องตรวจเทียบกับภาพก่อนส่ง"
    }

    private fun isEvidenceCoreComplete(
'''
if old in text:
    text = text.replace(old, new, 1)
elif "private fun buildConfidenceWarning" not in text:
    raise SystemExit("ResolvedPosCandidate return block not found")

path.write_text(text, encoding="utf-8")
print("Round91 confidence + Admin structural guard patch applied")
