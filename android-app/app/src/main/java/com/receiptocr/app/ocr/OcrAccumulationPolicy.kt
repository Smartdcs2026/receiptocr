package com.receiptocr.app.ocr

import com.receiptocr.app.model.PosRecord

/**
 * รวมผล OCR จากภาพบิลหลายช่องแบบสะสมตาม POS
 *
 * หลักการ:
 * - ภาพใหม่เติมเฉพาะ POS ที่ตรวจพบในภาพนั้น
 * - ข้อมูลที่ผู้ใช้กรอกเองและไม่ว่างจะไม่ถูก OCR ทับ
 * - ข้อมูล OCR เดิมที่ครบและไม่มีคำเตือนจะถูกเก็บไว้
 * - ถ้าข้อมูล OCR เดิมมีปัญหา ระบบอนุญาตให้ภาพใหม่แก้เฉพาะช่องที่เกี่ยวข้อง
 * - ถ้าภาพใหม่ขัดกับข้อมูล OCR เดิมที่ดี ระบบไม่ทับเงียบ ๆ แต่คืน conflict ให้หน้าตรวจทาน
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

        val workingOriginals = originals.toMutableList()
        currentDetectedPos.sorted().forEach { detectedPos ->
            if (workingOriginals.none { it.posNumber == detectedPos }) {
                val freeIndex = workingOriginals.indexOfFirst { record ->
                    record.customerNo.isBlank() && record.billDate.isBlank() && record.billTime.isBlank() &&
                        !record.noReceipt && record.ocrSourceImagePath.isBlank()
                }
                if (freeIndex >= 0) workingOriginals[freeIndex] = workingOriginals[freeIndex].copy(posNumber = detectedPos)
            }
        }

        val merged = workingOriginals.map { original ->
            if (original.posNumber !in currentDetectedPos) return@map original
            if (original.noReceipt) {
                conflicts[original.posNumber] =
                    "พบข้อมูลของ POS ${original.posNumber} ในภาพ แต่ POS นี้ถูกระบุว่าไม่ได้บิล • ระบบยังไม่เปลี่ยนข้อมูลเดิม"
                return@map original
            }

            val template = templateRecords.firstOrNull { it.posNumber == original.posNumber } ?: original
            val profile = profileRecords.firstOrNull { it.posNumber == original.posNumber } ?: original

            val candidateCustomer = changedValue(original.customerNo, template.customerNo, profile.customerNo)
            val candidateDate = changedValue(original.billDate, template.billDate, profile.billDate)
            val candidateTime = changedValue(original.billTime, template.billTime, profile.billTime)

            val customer = chooseField(original, original.customerNo, candidateCustomer, listOf("ลูกค้า", "ยอด"))
            val date = chooseField(
                original,
                original.billDate,
                candidateDate,
                listOf("วันที่", "เดือน", "ปี", "ย้อนหลัง", "ก่อนวันงาน", "หลังวันงาน", "ใช้ร่วมกับบิล")
            )
            val time = chooseField(original, original.billTime, candidateTime, listOf("เวลา"))

            val didImprove = customer != original.customerNo || date != original.billDate || time != original.billTime
            val candidateDiffers = listOf(
                candidateCustomer.takeIf { it.isNotBlank() && original.customerNo.isNotBlank() && it != original.customerNo },
                candidateDate.takeIf { it.isNotBlank() && original.billDate.isNotBlank() && it != original.billDate },
                candidateTime.takeIf { it.isNotBlank() && original.billTime.isNotBlank() && it != original.billTime }
            ).count { it != null }

            if (!didImprove && candidateDiffers > 0 && isTrustedExistingOcr(original)) {
                conflicts[original.posNumber] =
                    "ภาพใหม่อ่านข้อมูล POS ${original.posNumber} ต่างจากข้อมูลเดิม • ระบบยังไม่ทับข้อมูลที่ยืนยันไว้"
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
                customerNo = customer,
                billDate = date,
                billTime = time,
                noReceipt = false,
                noReceiptReason = "",
                source = candidateSource.source.ifBlank { "OCR-ACCUMULATED" },
                ocrSourceImagePath = candidateSource.ocrSourceImagePath.ifBlank { original.ocrSourceImagePath },
                // ต้องรักษาว่าค่าแต่ละ POS มาจากรูปแบบบิลใด เพราะแบรนด์เดียวกันอาจมี
                // Mb_01/Mb_02 ที่ใช้ลำดับวันที่หรือระบบปีต่างกัน
                ocrTemplateName = candidateSource.ocrTemplateName.ifBlank { original.ocrTemplateName },
                ocrCounterCycle = candidateSource.ocrCounterCycle.ifBlank { original.ocrCounterCycle },
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
