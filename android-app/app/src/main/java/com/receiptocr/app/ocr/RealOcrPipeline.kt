package com.receiptocr.app.ocr

import com.receiptocr.app.config.AdminOcrProfile
import com.receiptocr.app.config.BrandReceiptRule
import com.receiptocr.app.config.TemplateSource
import com.receiptocr.app.config.UniversalOcrTemplate
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem
import com.receiptocr.app.validation.ReceiptValidationEngine
import java.time.LocalDate

enum class OcrConfidence(val label: String) {
    HIGH("มั่นใจสูง"),
    MEDIUM("ควรตรวจทาน"),
    LOW("ยังไม่พร้อมใช้")
}

data class RealOcrPipelineResult(
    val proposedRecords: List<PosRecord>,
    val detectedPos: List<Int>,
    val confidence: OcrConfidence,
    val message: String,
    val templateName: String? = null,
    val canConfirm: Boolean = false,
    val warnings: List<String> = emptyList()
)

/**
 * จุดเข้าหลักของ OCR ภาพจริง: ML Kit -> ตำแหน่งข้อความ -> แม่แบบ -> ตรวจร้าน/POS/วันที่
 * ผลลัพธ์เป็นเพียงข้อเสนอ จนกว่าผู้ใช้จะกดยืนยันในหน้าตรวจทาน
 */
object RealOcrPipeline {
    fun analyze(
        mlTextPasses: List<OcrTextPass>,
        imageWidth: Int,
        imageHeight: Int,
        records: List<PosRecord>,
        work: WorkItem,
        workDate: LocalDate,
        imagePath: String,
        templates: List<UniversalOcrTemplate>,
        templateSource: TemplateSource,
        profile: AdminOcrProfile,
        receiptRule: BrandReceiptRule,
        imageQualityWarnings: List<String> = emptyList()
    ): RealOcrPipelineResult {
        val mlTexts = mlTextPasses.map { it.text }
        val primaryText = mlTexts.firstOrNull()
        if (primaryText == null || mlTexts.all { it.text.isBlank() }) {
            return RealOcrPipelineResult(
                records, emptyList(), OcrConfidence.LOW,
                "ไม่พบข้อความในภาพ กรุณาถ่ายใหม่ให้บิลชัดและเต็มภาพ"
            )
        }

        val templateResult = UniversalTemplateInterpreter.apply(
            mlTexts = mlTexts,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            records = records,
            work = work,
            workDate = workDate,
            imagePath = imagePath,
            templates = templates
        )

        // กฎตำแหน่งและกฎรูปแบบจาก Admin เป็นคนละแหล่งข้อมูลที่เสริมกัน
        // เดิมเมื่อมี template ระบบจะไม่เรียก profile ทำให้ตำแหน่งที่ Admin กำหนดไม่ถูกใช้
        val shouldRunProfile = profile.regions.isNotEmpty() &&
            (!profile.profileId.startsWith("demo-", ignoreCase = true) || templates.isEmpty())
        val profileResult = if (shouldRunProfile) {
            val passResults = mlTextPasses.filter { it.text.text.isNotBlank() }.map { pass ->
                RuleDrivenOcrEngine.apply(
                    mlText = pass.text,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    originX = pass.originX,
                    originY = pass.originY,
                    records = records,
                    work = work,
                    workDate = workDate,
                    imagePath = imagePath,
                    profile = profile,
                    receiptRule = receiptRule
                )
            }
            combineProfilePasses(records, passResults)
        } else null
        val profileFilledPos = profileResult?.records.orEmpty().mapNotNull { candidate ->
            val original = records.firstOrNull { it.posNumber == candidate.posNumber } ?: return@mapNotNull null
            candidate.posNumber.takeIf {
                candidate.customerNo != original.customerNo ||
                    candidate.billDate != original.billDate ||
                    candidate.billTime != original.billTime
            }
        }
        val detectedPos = (templateResult.detectedPos + profileFilledPos).distinct().sorted()
        val combinedRecords = mergeRecords(records, templateResult.records, profileResult?.records.orEmpty())

        if (detectedPos.isNotEmpty()) {
            val detectedRecords = combinedRecords.filter { it.posNumber in detectedPos }
            val completeCore = detectedRecords.isNotEmpty() && detectedRecords.all {
                it.billDate.isNotBlank() && it.billTime.isNotBlank() && it.customerNo.isNotBlank()
            }
            val storeValues = templateResult.extracted["STORE_ID"].orEmpty() +
                profileResult?.rawFieldSummary?.get(com.receiptocr.app.config.OcrFieldType.STORE_ID).orEmpty()
            val storeMatched = storeValues.any { sameStoreCode(it, work.storeCode) }
            val missingPos = records.map { it.posNumber }.filterNot { it in detectedPos }
            val warnings = buildList {
                addAll(imageQualityWarnings)
                templateResult.validationWarnings.toSortedMap().forEach { (pos, items) ->
                    items.forEach { add("POS $pos: $it") }
                }
                if (!storeMatched) add("ไม่พบรหัสร้านในภาพ กรุณาตรวจชื่อร้านกับภาพอีกครั้ง")
                if (!completeCore) add("ข้อมูลสำคัญบางช่องอ่านได้ไม่ครบ กรุณาตรวจแก้ก่อนยืนยัน")
                if (missingPos.isNotEmpty()) add("ยังไม่พบข้อมูลเครื่อง ${missingPos.joinToString(", ")} ในภาพ")
                addAll(
                    ReceiptValidationEngine.groupDateIssues(
                        records = combinedRecords.filter { it.posNumber in detectedPos },
                        workDate = workDate,
                        rule = receiptRule.groupDateRule
                    ).map { it.message }
                )
            }
            val confidence = if (completeCore && storeMatched && warnings.isEmpty()) OcrConfidence.HIGH else OcrConfidence.MEDIUM
            val successMessage = when {
                missingPos.isNotEmpty() ->
                    "อ่านข้อมูลได้ ${detectedPos.size} จาก ${records.size} เครื่อง • กรุณาตรวจเครื่อง ${missingPos.joinToString(", ")}"
                templateResult.detectedPos.isNotEmpty() -> templateResult.message
                else -> "อ่านข้อมูลจากภาพแล้ว • พบ ${detectedPos.size} เครื่อง • กรุณาตรวจทุกช่องก่อนส่ง"
            }
            return RealOcrPipelineResult(
                proposedRecords = stampOcrMetadata(
                    combinedRecords, detectedPos,
                    confidence, templateResult.templateName.orEmpty()
                ),
                detectedPos = detectedPos,
                confidence = confidence,
                message = successMessage,
                templateName = templateResult.templateName,
                canConfirm = true,
                warnings = warnings
            )
        }

        // ถ้า Admin มีรูปแบบบิลแล้ว ต้องไม่ข้ามไปใช้กฎสำรองเมื่อจับคู่ไม่ผ่าน
        // เพื่อให้ทุกแบรนด์และทุกเงื่อนไขยึดข้อมูลที่ผู้ดูแลตั้งไว้จริง
        if (templates.isNotEmpty() || templateSource in setOf(TemplateSource.CLOUD, TemplateSource.CACHE)) {
            return RealOcrPipelineResult(
                proposedRecords = records,
                detectedPos = emptyList(),
                confidence = OcrConfidence.LOW,
                message = templateResult.message,
                canConfirm = false,
                warnings = listOf(
                    *imageQualityWarnings.toTypedArray(),
                    if (templates.isEmpty()) "ยังไม่มีเงื่อนไขสำหรับแบรนด์นี้ กรุณาแจ้งผู้ดูแล"
                    else "ยังแยกข้อมูลบิลไม่ได้ครบ กรุณาถ่ายภาพใหม่ให้ชัดเจน"
                )
            )
        }

        return RealOcrPipelineResult(
            proposedRecords = records,
            detectedPos = emptyList(),
            confidence = OcrConfidence.LOW,
            message = "ยังอ่านข้อมูลจากภาพไม่ได้ครบ กรุณาถ่ายภาพใหม่ให้ชัดเจน",
            canConfirm = false,
            warnings = imageQualityWarnings + "ระบบจะไม่เดาหมายเลข POS หรือยอดลูกค้าเมื่อข้อมูลไม่ชัด"
        )
    }

    private fun stampOcrMetadata(
        records: List<PosRecord>,
        detectedPos: List<Int>,
        confidence: OcrConfidence,
        templateName: String
    ): List<PosRecord> = records.map { record ->
        if (record.posNumber in detectedPos) record.copy(
            ocrConfidence = confidence.name,
            ocrTemplateName = templateName
        ) else record
    }

    /** รวมผลอ่านข้อความหลายรอบ โดยไม่ให้ค่าจาก POS หนึ่งไหลไปอีก POS */
    private fun combineProfilePasses(
        originals: List<PosRecord>,
        passes: List<RuleDrivenOcrResult>
    ): RuleDrivenOcrResult? {
        if (passes.isEmpty()) return null
        val merged = originals.map { original ->
            passes.fold(original) { current, pass ->
                val candidate = pass.records.firstOrNull { it.posNumber == original.posNumber } ?: current
                val changed = candidate.customerNo != original.customerNo ||
                    candidate.billDate != original.billDate || candidate.billTime != original.billTime
                if (!changed) current else current.copy(
                    customerNo = current.customerNo.ifBlank { candidate.customerNo },
                    billDate = current.billDate.ifBlank { candidate.billDate },
                    billTime = current.billTime.ifBlank { candidate.billTime },
                    noReceipt = false,
                    noReceiptReason = "",
                    source = candidate.source,
                    ocrSourceImagePath = candidate.ocrSourceImagePath
                )
            }
        }
        val detected = passes.flatMap { it.detectedPos }.distinct().sorted()
        val summary = passes.flatMap { it.rawFieldSummary.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, values) -> values.flatten().distinct() }
        return RuleDrivenOcrResult(
            records = merged,
            message = "อ่านข้อมูลจากภาพแล้ว • พบ ${detected.size} เครื่อง • กรุณาตรวจทุกช่องก่อนส่ง",
            detectedPos = detected,
            rawFieldSummary = summary
        )
    }

    private fun sameStoreCode(first: String, second: String): Boolean {
        val a = OcrTextNormalizer.normalizeDigits(first).filter(Char::isDigit).trimStart('0').ifBlank { "0" }
        val b = OcrTextNormalizer.normalizeDigits(second).filter(Char::isDigit).trimStart('0').ifBlank { "0" }
        return a == b
    }

    /** Template มาก่อน แล้วเติมเฉพาะช่องว่างด้วยผลจากกฎตำแหน่งของ Admin */
    private fun mergeRecords(
        originals: List<PosRecord>,
        templateRecords: List<PosRecord>,
        profileRecords: List<PosRecord>
    ): List<PosRecord> = originals.map { original ->
        val template = templateRecords.firstOrNull { it.posNumber == original.posNumber } ?: original
        val profile = profileRecords.firstOrNull { it.posNumber == original.posNumber } ?: original
        val templateChanged = template.customerNo != original.customerNo ||
            template.billDate != original.billDate || template.billTime != original.billTime
        val profileChanged = profile.customerNo != original.customerNo ||
            profile.billDate != original.billDate || profile.billTime != original.billTime

        template.copy(
            customerNo = template.customerNo.ifBlank { profile.customerNo },
            billDate = template.billDate.ifBlank { profile.billDate },
            billTime = template.billTime.ifBlank { profile.billTime },
            noReceipt = if (templateChanged || profileChanged) false else template.noReceipt,
            noReceiptReason = if (templateChanged || profileChanged) "" else template.noReceiptReason,
            source = when {
                templateChanged && profileChanged -> "OCR-ADMIN"
                templateChanged -> template.source
                profileChanged -> profile.source
                else -> original.source
            },
            ocrSourceImagePath = when {
                template.ocrSourceImagePath.isNotBlank() -> template.ocrSourceImagePath
                profile.ocrSourceImagePath.isNotBlank() -> profile.ocrSourceImagePath
                else -> original.ocrSourceImagePath
            }
        )
    }
}
