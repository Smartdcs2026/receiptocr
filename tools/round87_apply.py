from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

fusion = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt"
text = fusion.read_text(encoding="utf-8")

old = '''    private data class ResolvedValue(
        val value: String,
        val support: Int,
        val score: Int
    )
'''
new = '''    private data class ResolvedValue(
        val value: String,
        val support: Int,
        val score: Int
    )

    private data class ResolvedPosCandidate(
        val template: UniversalOcrTemplate,
        val values: Map<String, ResolvedValue>,
        val completePassSupport: Int,
        val weakestCoreSupport: Int,
        val score: Int
    )
'''
if old not in text:
    raise SystemExit("ResolvedValue block not found")
text = text.replace(old, new, 1)

start = text.index('        byPos.forEach { (pos, posEvidence) ->')
end = text.index('        if (detected.isEmpty()) return failed(records)', start)
replacement = '''        byPos.forEach { (pos, posEvidence) ->
            // Round87: ตัดสินทีละ POS อย่างอิสระ และลองทุก template ที่มีหลักฐาน
            // ห้ามเลือก template ที่มี noise เยอะกว่าแล้วทำให้ template ที่ครบจริงถูกทิ้ง
            val resolved = resolvePosCandidate(
                evidence = posEvidence,
                passCount = rawTexts.count { it.isNotBlank() }
            ) ?: return@forEach

            val template = resolved.template
            val customer = resolved.values["CUSTOMER_VALUE"]
            val date = resolved.values["BILL_DATE"]
            val time = resolved.values["BILL_TIME"]
            val store = resolved.values["STORE_ID"]

            val index = updated.indexOfFirst { it.posNumber == pos }
            if (index < 0) return@forEach
            val current = updated[index]

            val fields = linkedMapOf("POS_NUMBER" to pos.toString())
            customer?.let { fields["CUSTOMER_VALUE"] = it.value }
            date?.let { fields["BILL_DATE"] = it.value }
            time?.let { fields["BILL_TIME"] = it.value }
            store?.let { fields["STORE_ID"] = it.value }

            val warning = if (resolved.weakestCoreSupport <= 1 && rawTexts.count { it.isNotBlank() } >= 3) {
                "POS นี้มีข้อมูลครบจากอย่างน้อยหนึ่งรอบ แต่บางช่องมีหลักฐานยืนยันซ้ำไม่ถึง 2 รอบ กรุณาตรวจเทียบกับภาพก่อนส่ง"
            } else ""

            updated[index] = current.copy(
                customerNo = customer?.value ?: current.customerNo,
                billDate = date?.value ?: current.billDate,
                billTime = time?.value ?: current.billTime,
                noReceipt = false,
                noReceiptReason = "",
                source = "OCR-EVIDENCE",
                ocrSourceImagePath = imagePath,
                ocrTemplateName = template.templateName,
                ocrWarnings = warning,
                ocrStoreId = store?.value ?: current.ocrStoreId,
                ocrStoreIdExpected = templateHasStoreId(template),
                ocrCounterCycle = template.duplicatePolicy.customerCounterCycle.uppercase()
            )

            detected += pos
            usedNames += template.templateName
            extractedByPos[pos] = fields
            if (warning.isNotBlank()) warnings[pos] = listOf(warning)
        }

'''
text = text[:start] + replacement + text[end:]

start = text.index('    internal fun fuseTextPasses(')
end = text.index('    private fun collectTemplateEvidence(', start)
replacement = '''    internal fun fuseTextPasses(
        rawTexts: List<String>,
        template: UniversalOcrTemplate,
        allowedPos: Set<Int>
    ): Map<Int, Map<String, String>> {
        val candidates = buildLocalCandidates(rawTexts)
        val evidence = collectTemplateEvidence(template, candidates, allowedPos)
        val passCount = rawTexts.count { it.isNotBlank() }
        return evidence.groupBy { it.pos }.mapNotNull { (pos, all) ->
            val resolved = resolvePosCandidate(all, passCount) ?: return@mapNotNull null
            pos to buildMap {
                put("POS_NUMBER", pos.toString())
                resolved.values["CUSTOMER_VALUE"]?.let { put("CUSTOMER_VALUE", it.value) }
                resolved.values["BILL_DATE"]?.let { put("BILL_DATE", it.value) }
                resolved.values["BILL_TIME"]?.let { put("BILL_TIME", it.value) }
                resolved.values["STORE_ID"]?.let { put("STORE_ID", it.value) }
            }
        }.toMap()
    }

'''
text = text[:start] + replacement + text[end:]

start = text.index('    private fun chooseTemplateGroup(')
end = text.index('    private fun resolveField(', start)
replacement = '''    private fun resolvePosCandidate(
        evidence: List<Evidence>,
        passCount: Int
    ): ResolvedPosCandidate? {
        if (evidence.isEmpty()) return null
        return evidence.groupBy { it.template.templateId }
            .values
            .mapNotNull { group -> resolveTemplateCandidate(group, passCount) }
            .maxWithOrNull(
                compareBy<ResolvedPosCandidate> { it.completePassSupport }
                    .thenBy { it.weakestCoreSupport }
                    .thenBy { it.score }
            )
    }

    private fun resolveTemplateCandidate(
        group: List<Evidence>,
        passCount: Int
    ): ResolvedPosCandidate? {
        if (group.isEmpty()) return null
        val template = group.first().template
        val minimumSupport = if (passCount >= 3) 2 else 1

        val completeEvidence = group
            .filter { isEvidenceCoreComplete(it, template) }
            .maxByOrNull { it.score }

        fun resolveWithCompleteFallback(
            type: String,
            validator: (String) -> Boolean
        ): ResolvedValue? {
            val consensus = resolveField(group, type, minimumSupport, validator)
            if (consensus != null) return consensus
            val fallbackValue = completeEvidence?.fields?.get(type)
                ?.takeIf { it.isNotBlank() && validator(it) }
                ?: return null
            return ResolvedValue(
                value = fallbackValue,
                support = 1,
                score = completeEvidence.score
            )
        }

        val values = linkedMapOf<String, ResolvedValue>()
        resolveWithCompleteFallback("CUSTOMER_VALUE") { it.isNotBlank() && it.all(Char::isDigit) }
            ?.let { values["CUSTOMER_VALUE"] = it }
        resolveWithCompleteFallback("BILL_DATE", ::isValidDate)
            ?.let { values["BILL_DATE"] = it }
        resolveWithCompleteFallback("BILL_TIME", ::isValidTime)
            ?.let { values["BILL_TIME"] = it }

        // STORE_ID ไม่ใช่ core field แต่ถ้ามีให้เลือกจาก consensus ก่อน แล้วค่อยใช้ pass ที่ครบ
        val store = resolveField(group, "STORE_ID", minimumSupport) { it.isNotBlank() }
            ?: completeEvidence?.fields?.get("STORE_ID")
                ?.takeIf { it.isNotBlank() }
                ?.let { ResolvedValue(it, 1, completeEvidence.score) }
        store?.let { values["STORE_ID"] = it }

        val core = template.validation.requiredCore
        if (core.customerValue && values["CUSTOMER_VALUE"] == null) return null
        if (core.date && values["BILL_DATE"] == null) return null
        if (core.time && values["BILL_TIME"] == null) return null

        val completePassSupport = group
            .filter { isEvidenceCoreComplete(it, template) }
            .map { it.passIndex }
            .distinct()
            .size

        val coreSupports = buildList {
            if (core.customerValue) values["CUSTOMER_VALUE"]?.support?.let(::add)
            if (core.date) values["BILL_DATE"]?.support?.let(::add)
            if (core.time) values["BILL_TIME"]?.support?.let(::add)
        }
        val weakest = coreSupports.minOrNull() ?: 1
        val score = completePassSupport * 10000 +
            weakest * 1000 +
            values.values.sumOf { it.support * 100 + it.score.coerceAtMost(999) } +
            (group.maxOfOrNull { it.depth } ?: 0) * 10

        return ResolvedPosCandidate(
            template = template,
            values = values,
            completePassSupport = completePassSupport,
            weakestCoreSupport = weakest,
            score = score
        )
    }

    private fun isEvidenceCoreComplete(
        evidence: Evidence,
        template: UniversalOcrTemplate
    ): Boolean {
        val core = template.validation.requiredCore
        val customerOk = !core.customerValue || evidence.fields["CUSTOMER_VALUE"]
            ?.let { it.isNotBlank() && it.all(Char::isDigit) } == true
        val dateOk = !core.date || evidence.fields["BILL_DATE"]?.let(::isValidDate) == true
        val timeOk = !core.time || evidence.fields["BILL_TIME"]?.let(::isValidTime) == true
        return customerOk && dateOk && timeOk
    }

'''
text = text[:start] + replacement + text[end:]

text = text.replace(' * Round86 POS evidence fusion', ' * Round87 POS evidence fusion + independent POS resolution', 1)
fusion.write_text(text, encoding="utf-8")

build = ROOT / "android-app/app/build.gradle.kts"
build_text = build.read_text(encoding="utf-8")
build_text = build_text.replace('versionCode = 87', 'versionCode = 88')
build_text = build_text.replace('versionName = "0.86.0"', 'versionName = "0.87.0"')
if 'versionCode = 88' not in build_text or 'versionName = "0.87.0"' not in build_text:
    raise SystemExit("Round87 version bump failed")
build.write_text(build_text, encoding="utf-8")
