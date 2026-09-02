package com.receiptocr.app.ocr

import com.receiptocr.app.config.AdminOcrProfile
import com.receiptocr.app.config.BrandReceiptRule
import com.receiptocr.app.config.OcrFieldType
import com.receiptocr.app.config.TemplateSource
import com.receiptocr.app.config.UniversalOcrTemplate
import com.receiptocr.app.config.OcrTemplateField
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
    val warnings: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList()
)

/**
 * จุดเข้าหลักของ OCR ภาพจริง: ML Kit -> ตำแหน่งข้อความ -> รูปแบบ Admin -> ตรวจร้าน/POS/วันที่
 * Round84: เมื่อรูปแบบ Admin จับ POS ได้แล้ว จะถือผลนั้นเป็นหลักและไม่ให้ profile เก่ามาผสมค่าคนละตำแหน่ง
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

        val strictTemplateResult = UniversalTemplateInterpreter.apply(
            mlTexts = mlTexts,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            records = records,
            work = work,
            workDate = workDate,
            imagePath = imagePath,
            templates = templates
        )

        // Round88: อย่าหยุดเพียงเพราะตัวอ่านหนึ่งวิธีพบ POS บางเครื่อง
        // ทุกวิธีมีหน้าที่ช่วยเติมเฉพาะ POS ที่ยังขาด/ยังไม่ครบ แล้วค่อยรวมผลตาม POS
        val expectedPosSet = records.map { it.posNumber }.toSet()
        val evidenceFusion = if (templates.isNotEmpty() && needsTemplateHelp(strictTemplateResult, expectedPosSet)) {
            PosEvidenceFusion.apply(
                rawTexts = mlTexts.map { it.text },
                records = records,
                work = work,
                imagePath = imagePath,
                templates = templates
            )
        } else null
        val afterFusion = mergeUniversalTemplateResults(records, strictTemplateResult, evidenceFusion)

        val sequenceFallback = if (templates.isNotEmpty() && needsTemplateHelp(afterFusion, expectedPosSet)) {
            TemplateSequenceFallback.apply(
                rawTexts = mlTexts.map { it.text },
                records = records,
                work = work,
                imagePath = imagePath,
                templates = templates
            )
        } else null
        val templateResult = mergeUniversalTemplateResults(records, afterFusion, sequenceFallback)

        // เมื่อรูปแบบจาก Admin จับข้อมูลได้แล้ว ห้าม profile แบบตำแหน่งเก่ามาผสมลูกค้า/วันที่/เวลา
        // เพราะสามารถทำให้ข้อมูลจากคนละส่วนของบิลเลื่อนไปอยู่ POS เดียวกันได้
        val shouldRunProfile = templateResult.detectedPos.isEmpty() &&
            profile.regions.isNotEmpty() &&
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
        val defaultDateField = configuredDateField(templates)

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
            val currentImagePos = record.posNumber in currentDetectedSet
            val rawCandidateDate = record.billDate.trim()
            val recordDateField = dateFieldForRecord(record, templates) ?: defaultDateField
            val dateResult = if (currentImagePos && rawCandidateDate.isNotBlank()) {
                ReceiptDateOcrNormalizer.normalize(
                    raw = rawCandidateDate,
                    configuredFormat = recordDateField?.format,
                    referenceDate = workDate,
                    dateOrder = recordDateField?.dateOrder,
                    dateCalendar = recordDateField?.dateCalendar,
                    dateYearDigits = recordDateField?.dateYearDigits ?: 0
                )
            } else null
            val storeId = mergeStoreId(
                original = original,
                candidateStoreId = currentStoreIdsByPos[record.posNumber].orEmpty(),
                isCurrentPos = currentImagePos
            )
            val safeExistingDate = if (!original.source.startsWith("OCR", ignoreCase = true)) original.billDate else ""
            val acceptedDate = when {
                dateResult?.value != null -> dateResult.value
                currentImagePos && rawCandidateDate.isNotBlank() -> rawCandidateDate
                currentImagePos -> safeExistingDate
                else -> record.billDate
            }
            val dateWarning = when {
                !currentImagePos || rawCandidateDate.isBlank() -> ""
                dateResult?.value == null ->
                    (dateResult?.warning ?: "วันที่ที่อ่านจากภาพ ($rawCandidateDate) ไม่ตรงเงื่อนไขที่กำหนด") + " • แสดงค่าที่อ่านได้ไว้ให้ตรวจแก้"
                dateResult.corrected ->
                    "วันที่ที่อ่านจากภาพ ${dateResult.original} ถูกปรับเป็น ${dateResult.value} ตามเงื่อนไขวันที่ กรุณาตรวจเทียบกับภาพ"
                else -> ""
            }
            val inheritedWarning = sanitizeLegacyOcrWarnings(record.ocrWarnings)
            val standardizedTime = if (currentImagePos && record.billTime.isNotBlank()) {
                ReceiptTimeOcrNormalizer.normalize(record.billTime).value ?: record.billTime
            } else record.billTime
            record.copy(
                billDate = acceptedDate,
                billTime = standardizedTime,
                ocrStoreId = storeId,
                ocrWarnings = listOf(inheritedWarning, dateWarning)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" • ")
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
            val matchedTemplates = templates.filter { it.active && it.templateName in usedTemplateNames }
            val activeTemplates = templates.filter { it.active }
            val profileHasStore = profileResult != null && profile.regions.any { it.fieldType == OcrFieldType.STORE_ID }

            // ถ้ารู้แน่ชัดว่าจับด้วยรูปแบบใด ให้ใช้กฎของรูปแบบนั้นเท่านั้น
            // ถ้ามีรูปแบบที่ระบุว่าไม่มี STORE_ID จะไม่เอา STORE_ID จาก profile หรือรูปแบบอื่นมาบังคับ
            val expectsStoreId = when {
                matchedTemplates.isNotEmpty() -> matchedTemplates.all(::templateHasStoreId)
                else -> activeTemplates.any(::templateHasStoreId) || profileHasStore
            }
            val requiresStoreMatch = when {
                matchedTemplates.isNotEmpty() -> matchedTemplates.all {
                    templateHasStoreId(it) && it.validation.store.mustMatchWorkPlan
                }
                else -> activeTemplates.any {
                    templateHasStoreId(it) && it.validation.store.mustMatchWorkPlan
                } || profileHasStore
            }
            val explicitNoStoreTemplate = matchedTemplates.isNotEmpty() && matchedTemplates.none(::templateHasStoreId)

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
                    if (record.posNumber in missingStorePos && requiresStoreMatch) {
                        add("ยังยืนยันร้านไม่ได้ • ไม่พบรหัสร้านตามตำแหน่งที่กำหนด")
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
                    add("ยังยืนยันร้านไม่ได้ • ไม่พบรหัสร้านตามตำแหน่งที่กำหนด")
                } else if (missingStorePos.isNotEmpty() && requiresStoreMatch) {
                    add("ยังยืนยันรหัสร้านไม่ได้ใน POS ${missingStorePos.joinToString(", ")}")
                }
                // ถ้า Admin กำหนดรูปแบบนี้ว่าไม่มีรหัสร้าน ถือเป็นกติกาที่ตั้งใจไว้ ไม่เตือนว่าอ่านรหัสร้านไม่ได้
                if (!expectsStoreId && !explicitNoStoreTemplate && work.expectedReceiptStoreId.isNotBlank()) {
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
                ),
                diagnostics = TemplateSequenceFallback.diagnose(
                    rawTexts = mlTexts.map { it.text },
                    templates = templates
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

    internal fun needsTemplateHelp(
        result: UniversalTemplateResult,
        expectedPos: Set<Int>
    ): Boolean {
        val detected = result.detectedPos.toSet()
        return expectedPos.any { pos ->
            pos !in detected || result.records
                .firstOrNull { it.posNumber == pos }
                ?.let(OcrAccumulationPolicy::isCoreComplete) != true
        }
    }

    internal fun mergeUniversalTemplateResults(
        originals: List<PosRecord>,
        primary: UniversalTemplateResult,
        supplement: UniversalTemplateResult?
    ): UniversalTemplateResult {
        if (supplement == null || supplement.detectedPos.isEmpty()) return primary
        if (primary.detectedPos.isEmpty()) return supplement

        val primaryPos = primary.detectedPos.toSet()
        val supplementPos = supplement.detectedPos.toSet()
        val detected = (primaryPos + supplementPos).sorted()
        val sourceByPos = linkedMapOf<Int, String>()

        fun record(result: UniversalTemplateResult, positions: Set<Int>, pos: Int): PosRecord? =
            if (pos in positions) result.records.firstOrNull { it.posNumber == pos } else null

        fun warnings(result: UniversalTemplateResult, pos: Int): List<String> =
            result.validationWarnings[pos].orEmpty() +
                result.records.firstOrNull { it.posNumber == pos }
                    ?.ocrWarnings
                    .orEmpty()
                    .split(" • ")
                    .filter { it.isNotBlank() }

        fun strong(result: UniversalTemplateResult, candidate: PosRecord, pos: Int): Boolean =
            OcrAccumulationPolicy.isCoreComplete(candidate) && warnings(result, pos).isEmpty()

        val mergedRecords = originals.map { original ->
            val pos = original.posNumber
            val p = record(primary, primaryPos, pos)
            val s = record(supplement, supplementPos, pos)
            when {
                p == null && s == null -> original
                p == null -> { sourceByPos[pos] = "S"; s!! }
                s == null -> { sourceByPos[pos] = "P"; p }
                strong(primary, p, pos) -> { sourceByPos[pos] = "P"; p }
                OcrAccumulationPolicy.isCoreComplete(s) -> { sourceByPos[pos] = "S"; s }
                else -> {
                    sourceByPos[pos] = "M"
                    p.copy(
                        customerNo = p.customerNo.ifBlank { s.customerNo },
                        billDate = p.billDate.ifBlank { s.billDate },
                        billTime = p.billTime.ifBlank { s.billTime },
                        noReceipt = false,
                        noReceiptReason = "",
                        source = if (s.ocrSourceImagePath.isNotBlank()) s.source else p.source,
                        ocrSourceImagePath = p.ocrSourceImagePath.ifBlank { s.ocrSourceImagePath },
                        ocrWarnings = listOf(p.ocrWarnings, s.ocrWarnings)
                            .filter { it.isNotBlank() }.distinct().joinToString(" • "),
                        ocrStoreId = p.ocrStoreId.ifBlank { s.ocrStoreId },
                        ocrStoreIdExpected = p.ocrStoreIdExpected || s.ocrStoreIdExpected,
                        ocrCounterCycle = p.ocrCounterCycle.ifBlank { s.ocrCounterCycle }
                    )
                }
            }
        }

        fun fieldAt(result: UniversalTemplateResult, type: String, pos: Int): String {
            val index = result.detectedPos.indexOf(pos)
            if (index < 0) return ""
            return result.extracted[type].orEmpty().getOrNull(index).orEmpty()
        }

        val fieldTypes = (primary.extracted.keys + supplement.extracted.keys).toSet()
        val extracted = linkedMapOf<String, List<String>>()
        fieldTypes.forEach { type ->
            extracted[type] = detected.map { pos ->
                val p = fieldAt(primary, type, pos)
                val s = fieldAt(supplement, type, pos)
                when (sourceByPos[pos]) {
                    "S" -> s.ifBlank { p }
                    else -> p.ifBlank { s }
                }
            }
        }

        val validationWarnings = linkedMapOf<Int, List<String>>()
        detected.forEach { pos ->
            val combined = (primary.validationWarnings[pos].orEmpty() +
                supplement.validationWarnings[pos].orEmpty()).distinct()
            if (combined.isNotEmpty()) validationWarnings[pos] = combined
        }

        val names = listOf(primary.templateName, supplement.templateName)
            .filterNotNull()
            .flatMap { it.split(" / ") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return UniversalTemplateResult(
            records = mergedRecords,
            message = "รวมผลอ่านจากหลายวิธี • พบ ${detected.size} เครื่อง",
            templateName = names.joinToString(" / ").ifBlank { null },
            detectedPos = detected,
            extracted = extracted,
            validationWarnings = validationWarnings,
            usedUniversalTemplate = primary.usedUniversalTemplate || supplement.usedUniversalTemplate
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

    private fun configuredDateField(templates: List<UniversalOcrTemplate>): OcrTemplateField? =
        templates.asSequence()
            .filter { it.active }
            .flatMap { it.recognition.rows.asSequence() }
            .flatMap { it.fields.asSequence() }
            .firstOrNull { it.type == "BILL_DATE" }

    private fun dateFieldForRecord(
        record: PosRecord,
        templates: List<UniversalOcrTemplate>
    ): OcrTemplateField? {
        val name = record.ocrTemplateName.trim()
        val template = templates.firstOrNull { it.active && name.isNotBlank() && it.templateName == name }
            ?: return null
        return template.recognition.rows.asSequence()
            .flatMap { it.fields.asSequence() }
            .firstOrNull { it.type == "BILL_DATE" }
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
