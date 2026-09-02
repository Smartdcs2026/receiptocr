package com.receiptocr.app.ocr

import com.receiptocr.app.config.AdminOcrProfile
import com.receiptocr.app.config.BrandReceiptRule
import com.receiptocr.app.config.OcrFieldType
import com.receiptocr.app.config.TemplateSource
import com.receiptocr.app.config.UniversalOcrTemplate
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem
import com.receiptocr.app.validation.ReceiptValidationEngine
import com.receiptocr.app.validation.StoreReceiptIdentity
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
 * Round78 hotfix: ใช้ลำดับการอ่านภาพแบบ Round77 ที่ผ่านการทดสอบจริง
 * แต่ยังคงการสะสมข้อมูลจากภาพบิลหลายช่องของ Round78
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
        val currentDetectedPos = (templateResult.detectedPos + profileFilledPos).distinct().sorted()
        val currentDetectedSet = currentDetectedPos.toSet()
        val currentStoreIdsByPos = buildStoreIdsByPos(templateResult, profileResult)
        val dateFormat = configuredDateFormat(templates)

        val priorDateWarningsByPos = ReceiptValidationEngine.groupDateIssues(
            records = records,
            workDate = workDate,
            rule = receiptRule.groupDateRule
        ).mapNotNull { issue ->
            validationPos(issue.code)?.let { it to issue.message }
        }.groupBy({ it.first }, { it.second })

        val recordsForAccumulation = records.map { record ->
            val dateWarnings = priorDateWarningsByPos[record.posNumber].orEmpty()
            if (!record.source.startsWith("OCR", ignoreCase = true) || dateWarnings.isEmpty()) return@map record
            val warnings = buildList {
                if (record.ocrWarnings.isNotBlank()) add(record.ocrWarnings)
                addAll(dateWarnings)
            }.distinct().joinToString(" • ")
            record.copy(ocrWarnings = warnings)
        }

        val accumulation = OcrAccumulationPolicy.merge(
            originals = recordsForAccumulation,
            templateRecords = templateResult.records,
            profileRecords = profileResult?.records.orEmpty(),
            currentDetectedPos = currentDetectedSet
        )

        val combinedRecords = accumulation.records.map { record ->
            val original = recordsForAccumulation.firstOrNull { it.posNumber == record.posNumber } ?: record
            val dateResult = if (record.posNumber in currentDetectedSet && record.billDate.isNotBlank()) {
                ReceiptDateOcrNormalizer.normalize(
                    raw = record.billDate,
                    configuredFormat = dateFormat,
                    referenceDate = workDate
                )
            } else null
            val storeId = mergeStoreId(
                original = original,
                candidateStoreId = currentStoreIdsByPos[record.posNumber].orEmpty(),
                isCurrentPos = record.posNumber in currentDetectedSet
            )
            record.copy(
                billDate = dateResult?.value ?: record.billDate,
                ocrStoreId = storeId,
                ocrWarnings = if (record.posNumber in currentDetectedSet) "" else sanitizeLegacyOcrWarnings(record.ocrWarnings)
            )
        }

        if (currentDetectedPos.isNotEmpty()) {
            val resolvedPos = combinedRecords.filter(OcrAccumulationPolicy::isCoreComplete).map { it.posNumber }.toSet()
            val missingPos = records.map { it.posNumber }.filterNot { it in resolvedPos }
            val allStoreIdsByPos = combinedRecords
                .filter { it.posNumber in resolvedPos && it.ocrStoreId.isNotBlank() }
                .associate { it.posNumber to it.ocrStoreId }

            val usedTemplateNames = templateResult.templateName.orEmpty()
                .split(" / ").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            val usedTemplates = templates.filter { it.active && it.templateName in usedTemplateNames }
                .ifEmpty { templates.filter { it.active } }
            val expectsStoreId = usedTemplates.any(::templateHasStoreId) ||
                profile.regions.any { it.fieldType == OcrFieldType.STORE_ID }
            val requiresStoreMatch = usedTemplates.any {
                it.validation.store.mustMatchWorkPlan && templateHasStoreId(it)
            } || profile.regions.any { it.fieldType == OcrFieldType.STORE_ID }

            val storeAssessment = if (expectsStoreId) {
                StoreReceiptIdentity.evaluate(
                    expectedStoreId = work.expectedReceiptStoreId,
                    storeIdsByPos = allStoreIdsByPos
                )
            } else null
            val missingStorePos = if (expectsStoreId) {
                resolvedPos.filter { allStoreIdsByPos[it].isNullOrBlank() }.sorted()
            } else emptyList()

            val recordsWithWarnings = combinedRecords.map { record ->
                val warningParts = buildList {
                    if (record.ocrWarnings.isNotBlank()) add(record.ocrWarnings)
                    if (record.posNumber in currentDetectedSet) {
                        templateResult.validationWarnings[record.posNumber]
                            .orEmpty()
                            .filterNot(::isLegacyInterpreterWarning)
                            .forEach(::add)
                        accumulation.conflictsByPos[record.posNumber]?.let(::add)
                    }
                    storeAssessment?.warningsByPos?.get(record.posNumber)?.let(::add)
                    if (record.posNumber in missingStorePos) {
                        add("ยังยืนยันร้านไม่ได้ • ไม่พบรหัสร้านตามตำแหน่งที่ Admin กำหนด")
                    }
                }.distinct()
                record.copy(
                    ocrWarnings = warningParts.joinToString(" • "),
                    ocrStoreIdExpected = if (record.posNumber in currentDetectedSet) expectsStoreId else record.ocrStoreIdExpected
                )
            }

            val dateIssues = ReceiptValidationEngine.groupDateIssues(
                records = recordsWithWarnings.filter { it.posNumber in resolvedPos },
                workDate = workDate,
                rule = receiptRule.groupDateRule
            )
            val allCoreComplete = missingPos.isEmpty()
            val currentComplete = recordsWithWarnings
                .filter { it.posNumber in currentDetectedSet }
                .all(OcrAccumulationPolicy::isCoreComplete)

            val warnings = buildList {
                addAll(imageQualityWarnings)
                templateResult.validationWarnings.toSortedMap().forEach { (pos, items) ->
                    items.filterNot(::isLegacyInterpreterWarning).forEach { add("POS $pos: $it") }
                }
                accumulation.conflictsByPos.toSortedMap().forEach { (pos, warning) -> add("POS $pos: $warning") }
                storeAssessment?.warningsByPos?.toSortedMap()?.forEach { (pos, warning) -> add("POS $pos: $warning") }
                storeAssessment?.summaryWarnings?.let(::addAll)
                if (expectsStoreId && allStoreIdsByPos.isEmpty() && requiresStoreMatch) {
                    add("ยังยืนยันร้านไม่ได้ • ไม่พบรหัสร้านตามตำแหน่งที่ Admin กำหนด")
                } else if (missingStorePos.isNotEmpty() && requiresStoreMatch) {
                    add("ยังยืนยันรหัสร้านไม่ได้ใน POS ${missingStorePos.joinToString(", ")}")
                }
                if (!expectsStoreId && work.expectedReceiptStoreId.isNotBlank()) {
                    add("รูปแบบบิลนี้ไม่มีรหัสร้านสำหรับตรวจอัตโนมัติ • กรุณาตรวจข้อมูลร้านจากหลักฐานประกอบ")
                }
                if (!currentComplete) add("ข้อมูลสำคัญบางช่องในภาพนี้อ่านได้ไม่ครบ กรุณาตรวจแก้ก่อนยืนยัน")
                if (missingPos.isNotEmpty()) add("ยังขาดข้อมูลเครื่อง ${missingPos.joinToString(", ")} • สามารถเพิ่มภาพบิลช่องอื่นแล้วอ่านต่อได้")
                addAll(dateIssues.map { it.message })
            }.distinct()

            val confidence = if (allCoreComplete && warnings.isEmpty()) OcrConfidence.HIGH else OcrConfidence.MEDIUM
            val beforeResolved = records.count(OcrAccumulationPolicy::isCoreComplete)
            val afterResolved = resolvedPos.size
            val newlyCompleted = currentDetectedPos.filter { pos ->
                records.firstOrNull { it.posNumber == pos }?.let(OcrAccumulationPolicy::isCoreComplete) != true &&
                    recordsWithWarnings.firstOrNull { it.posNumber == pos }?.let(OcrAccumulationPolicy::isCoreComplete) == true
            }
            val repaired = accumulation.improvedPos.filter { it !in newlyCompleted }.sorted()
            val successMessage = when {
                missingPos.isEmpty() && newlyCompleted.isNotEmpty() ->
                    "รวมข้อมูลจากภาพแล้ว • ครบ ${resolvedPos.size}/${records.size} POS • เพิ่ม ${newlyCompleted.joinToString(", ") { "POS $it" }}"
                missingPos.isEmpty() ->
                    "ตรวจภาพเพิ่มแล้ว • ข้อมูลครบ ${resolvedPos.size}/${records.size} POS"
                afterResolved > beforeResolved ->
                    "อ่านเพิ่มแล้ว • มีข้อมูล $afterResolved/${records.size} POS • ยังขาด ${missingPos.joinToString(", ") { "POS $it" }}"
                repaired.isNotEmpty() ->
                    "อ่านภาพเพิ่มแล้ว • ปรับข้อมูล ${repaired.joinToString(", ") { "POS $it" }} • กรุณาตรวจทาน"
                else -> templateResult.message
            }

            return RealOcrPipelineResult(
                proposedRecords = stampOcrMetadata(
                    recordsWithWarnings, currentDetectedPos,
                    confidence, templateResult.templateName.orEmpty()
                ),
                detectedPos = currentDetectedPos,
                confidence = confidence,
                message = successMessage,
                templateName = templateResult.templateName,
                canConfirm = true,
                warnings = warnings
            )
        }

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
                    else "ยังแยกข้อมูลบิลไม่ได้ครบ • ลองเพิ่มภาพบิลอีกช่องหรือถ่ายใหม่ให้ชัดขึ้น"
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

    private fun buildStoreIdsByPos(
        templateResult: UniversalTemplateResult,
        profileResult: RuleDrivenOcrResult?
    ): Map<Int, String> {
        val result = linkedMapOf<Int, String>()
        val templateValues = templateResult.extracted["STORE_ID"].orEmpty()
        if (templateValues.size == templateResult.detectedPos.size) {
            templateResult.detectedPos.zip(templateValues).forEach { (pos, storeId) ->
                if (storeId.isNotBlank()) result[pos] = storeId
            }
        }

        val profilePos = profileResult?.detectedPos.orEmpty()
        val profileValues = profileResult?.rawFieldSummary?.get(OcrFieldType.STORE_ID).orEmpty()
        if (profileValues.size == profilePos.size) {
            profilePos.zip(profileValues).forEach { (pos, storeId) ->
                if (storeId.isNotBlank() && result[pos].isNullOrBlank()) result[pos] = storeId
            }
        }
        return result
    }

    private fun mergeStoreId(
        original: PosRecord,
        candidateStoreId: String,
        isCurrentPos: Boolean
    ): String {
        if (!isCurrentPos || candidateStoreId.isBlank()) return original.ocrStoreId
        if (original.ocrStoreId.isBlank()) return candidateStoreId
        if (!original.source.startsWith("OCR", ignoreCase = true)) return original.ocrStoreId
        val oldStoreHasProblem = original.ocrWarnings.contains("ร้าน") ||
            original.ocrWarnings.contains("STORE", ignoreCase = true)
        return if (oldStoreHasProblem) candidateStoreId else original.ocrStoreId
    }

    private fun validationPos(code: String): Int? =
        Regex("_POS_(\\d+)$").find(code)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun configuredDateFormat(templates: List<UniversalOcrTemplate>): String {
        val formats = templates.asSequence()
            .filter { it.active }
            .flatMap { it.recognition.rows.asSequence() }
            .flatMap { it.fields.asSequence() }
            .filter { it.type == "BILL_DATE" }
            .map { it.format.trim() }
            .filter { it.isNotBlank() }
            .toList()
        return formats.firstOrNull { it.uppercase() !in setOf("DATE", "ANY") }
            ?: formats.firstOrNull()
            ?: "DD/MM/YYYY"
    }

    private fun templateHasStoreId(template: UniversalOcrTemplate): Boolean =
        template.recognition.rows.any { row ->
            row.fields.any { field ->
                field.type == "STORE_ID" ||
                    field.composite?.segments.orEmpty().any { it.type == "STORE_ID" }
            }
        }

    private fun isLegacyInterpreterWarning(value: String): Boolean {
        val warning = value.trim()
        return (warning.startsWith("รหัสร้านที่อ่านได้") && warning.contains("ไม่ตรงกับแผนงาน")) ||
            warning.contains("รหัสร้านในภาพไม่ตรงกันทุกชุด") ||
            warning.startsWith("วันที่ที่อ่านได้มีรูปแบบไม่ถูกต้อง")
    }

    private fun sanitizeLegacyOcrWarnings(raw: String): String =
        raw.split(" • ")
            .map { it.trim() }
            .filter { it.isNotBlank() && !isLegacyInterpreterWarning(it) }
            .distinct()
            .joinToString(" • ")
}
