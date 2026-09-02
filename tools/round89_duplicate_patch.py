from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def rep(path, old, new):
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'marker not found in {path}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

real = 'android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt'
rep(real,
'''        val templateResult = mergeUniversalTemplateResults(records, afterFusion, sequenceFallback)

        // เมื่อรูปแบบจาก Admin จับข้อมูลได้แล้ว''',
'''        val templateResult = mergeUniversalTemplateResults(records, afterFusion, sequenceFallback)
        val duplicatePosWarnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = mlTexts.map { it.text },
            templates = templates,
            allowedPos = expectedPosSet
        )

        // เมื่อรูปแบบจาก Admin จับข้อมูลได้แล้ว''')

rep(real,
'''                        accumulation.conflictsByPos[record.posNumber]?.let(::add)
                    }
                    storeAssessment?.warningsByPos?.get(record.posNumber)?.let(::add)''',
'''                        accumulation.conflictsByPos[record.posNumber]?.let(::add)
                    }
                    duplicatePosWarnings[record.posNumber]?.let(::add)
                    storeAssessment?.warningsByPos?.get(record.posNumber)?.let(::add)''')

rep(real,
'''                accumulation.conflictsByPos.toSortedMap().forEach { (pos, warning) -> add("POS $pos: $warning") }
                storeAssessment?.warningsByPos?.toSortedMap()?.forEach { (pos, warning) -> add("POS $pos: $warning") }''',
'''                accumulation.conflictsByPos.toSortedMap().forEach { (pos, warning) -> add("POS $pos: $warning") }
                duplicatePosWarnings.toSortedMap().forEach { (_, warning) -> add(warning) }
                storeAssessment?.warningsByPos?.toSortedMap()?.forEach { (pos, warning) -> add("POS $pos: $warning") }''')

validation = 'android-app/app/src/main/java/com/receiptocr/app/validation/ReceiptValidationEngine.kt'
rep(validation,
'''        validateRequiredFields(records, issues)
        validateReceiptStoreIds(work, records, issues)''',
'''        validateRequiredFields(records, issues)
        validateOcrEvidenceWarnings(records, issues)
        validateReceiptStoreIds(work, records, issues)''')

rep(validation,
'''    /**
     * ตรวจ STORE_ID เฉพาะรูปแบบบิลที่ Admin กำหนดว่ามีรหัสร้านจริง''',
'''    /** หลักฐานว่าภาพมีบิลคนละชุดแต่ชี้มาที่ POS เดียวกัน ต้องไม่ถูกกลืนเงียบ ๆ */
    private fun validateOcrEvidenceWarnings(
        records: List<PosRecord>,
        issues: MutableList<ValidationIssue>
    ) {
        records.filter { !it.noReceipt }.forEach { record ->
            if (record.ocrWarnings.contains("พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS")) {
                issues += block(
                    "DUPLICATE_POS_EVIDENCE_POS_${record.posNumber}",
                    "POS ${record.posNumber}: พบหลักฐานบิลมากกว่าหนึ่งชุดสำหรับเครื่องเดียวกัน • กรุณาตรวจบิลก่อนส่ง"
                )
            }
        }
    }

    /**
     * ตรวจ STORE_ID เฉพาะรูปแบบบิลที่ Admin กำหนดว่ามีรหัสร้านจริง''')

print('Round89 duplicate POS integration applied')
