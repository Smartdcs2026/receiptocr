package com.receiptocr.app.ocr

import com.receiptocr.app.model.PosRecord

/**
 * รวมข้อมูลจากภาพบิลหลายช่องแบบสะสมตามเครื่องที่อ่านได้จริง
 *
 * หลักการ:
 * - ภาพใหม่เติมเฉพาะเครื่องที่ตรวจพบในภาพนั้น
 * - ข้อมูลที่ผู้ใช้กรอกเองและไม่ว่างจะไม่ถูกทับ
 * - ข้อมูลเดิมที่ครบและไม่มีปัญหาจะถูกเก็บไว้
 * - ถ้าข้อมูลเดิมมีปัญหา ภาพใหม่สามารถช่วยแก้เฉพาะช่องที่เกี่ยวข้องได้
 * - ถ้าภาพใหม่ขัดกับข้อมูลเดิมที่ดี ระบบไม่ทับเงียบ ๆ แต่ให้ผู้ใช้ตรวจ
 */
object OcrAccumulationPolicy {
    data class Result(
        val records: List<PosRecord>,
        val improvedPos: Set<Int>,
        val conflictsByPos: Map<Int, String>
    )

    fun merge(
        originals: List<PosRecord>,
        templateRecords: List<PosRecord>,
        profileRecords: List<PosRecord>,
        currentDetectedPos: Set<Int>
    ): Result {
        val improved = linkedSetOf<Int>()
        val conflicts = linkedMapOf<Int, String>()

        val merged = originals.map { original ->
            if (original.posNumber !in currentDetectedPos) return@map original

            val template = templateRecords.firstOrNull { it.posNumber == original.posNumber } ?: original
            val profile = profileRecords.firstOrNull { it.posNumber == original.posNumber } ?: original

            val candidateCustomer = changedValue(original.customerNo, template.customerNo, profile.customerNo)
            val candidateDate = changedValue(original.billDate, template.billDate, profile.billDate)
            val candidateTime = changedValue(original.billTime, template.billTime, profile.billTime)
            val candidateReceiptPos = listOf(template.receiptPosNumber, profile.receiptPosNumber)
                .firstOrNull { it.isNotBlank() }
                .orEmpty()

            val customer = chooseField(original, original.customerNo, candidateCustomer, listOf("ลูกค้า", "ยอด"))
            val date = chooseField(
                original,
                original.billDate,
                candidateDate,
                listOf("วันที่", "เดือน", "ปี", "ย้อนหลัง", "ก่อนวันงาน", "หลังวันงาน", "ใช้ร่วมกับบิล")
            )
            val time = chooseField(original, original.billTime, candidateTime, listOf("เวลา"))
            val receiptPos = chooseReceiptPos(original, candidateReceiptPos)

            val didImprove = customer != original.customerNo ||
                date != original.billDate ||
                time != original.billTime ||
                receiptPos != original.receiptPosNumber
            val candidateDiffers = listOf(
                candidateCustomer.takeIf { it.isNotBlank() && original.customerNo.isNotBlank() && it != original.customerNo },
                candidateDate.takeIf { it.isNotBlank() && original.billDate.isNotBlank() && it != original.billDate },
                candidateTime.takeIf { it.isNotBlank() && original.billTime.isNotBlank() && it != original.billTime }
            ).count { it != null }

            if (!didImprove && candidateDiffers > 0 && isTrustedExistingOcr(original)) {
                conflicts[original.posNumber] =
                    "ภาพใหม่อ่านข้อมูล POS ${original.displayPosNumber} ต่างจากข้อมูลเดิม • ระบบยังไม่เปลี่ยนข้อมูลเดิม"
            }

            if (!didImprove) return@map original
            improved += original.posNumber

            val candidateSource = when {
                template.ocrSourceImagePath.isNotBlank() -> template
                profile.ocrSourceImagePath.isNotBlank() -> profile
                template.source.startsWith("OCR", ignoreCase = true) -> template
                else -> profile
            }

            original.copy(
                receiptPosNumber = receiptPos,
                customerNo = customer,
                billDate = date,
                billTime = time,
                noReceipt = false,
                noReceiptReason = "",
                source = candidateSource.source.ifBlank { "OCR-ACCUMULATED" },
                ocrSourceImagePath = candidateSource.ocrSourceImagePath.ifBlank { original.ocrSourceImagePath },
                ocrWarnings = ""
            )
        }

        return Result(merged, improved, conflicts)
    }

    fun isCoreComplete(record: PosRecord): Boolean =
        record.noReceipt || (
            record.customerNo.isNotBlank() &&
                record.billDate.isNotBlank() &&
                record.billTime.isNotBlank()
            )

    private fun changedValue(original: String, primary: String, secondary: String): String = when {
        primary.isNotBlank() && primary != original -> primary
        secondary.isNotBlank() && secondary != original -> secondary
        else -> ""
    }

    private fun chooseReceiptPos(original: PosRecord, candidate: String): String {
        if (candidate.isBlank()) return original.receiptPosNumber
        if (original.receiptPosNumber.isBlank()) return candidate
        return if (samePosIdentity(original.receiptPosNumber, candidate)) original.receiptPosNumber
        else original.receiptPosNumber
    }

    private fun samePosIdentity(a: String, b: String): Boolean {
        val aa = OcrTextNormalizer.parsePosNumber(a)
        val bb = OcrTextNormalizer.parsePosNumber(b)
        return aa != null && bb != null && aa == bb
    }

    private fun chooseField(
        originalRecord: PosRecord,
        originalValue: String,
        candidateValue: String,
        warningKeywords: List<String>
    ): String {
        if (candidateValue.isBlank()) return originalValue
        if (originalValue.isBlank()) return candidateValue
        if (!originalRecord.source.startsWith("OCR", ignoreCase = true)) return originalValue

        val hasRelevantProblem = warningKeywords.any { key -> originalRecord.ocrWarnings.contains(key) }
        return if (hasRelevantProblem) candidateValue else originalValue
    }

    private fun isTrustedExistingOcr(record: PosRecord): Boolean =
        record.source.startsWith("OCR", ignoreCase = true) &&
            isCoreComplete(record) &&
            record.ocrWarnings.isBlank()
}
