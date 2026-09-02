package com.receiptocr.app.validation

import android.content.Context
import com.receiptocr.app.config.BrandReceiptRule
import com.receiptocr.app.config.DemoReceiptRules
import com.receiptocr.app.config.ReceiptDateWindowRule
import com.receiptocr.app.config.ReceiptGroupDateRule
import com.receiptocr.app.config.RuleAction
import com.receiptocr.app.data.DemoRepository
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem
import com.receiptocr.app.ocr.ReceiptTimeOcrNormalizer
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter


enum class ValidationSeverity { BLOCK, WARNING }

data class ValidationIssue(
    val code: String,
    val severity: ValidationSeverity,
    val message: String
)

data class ValidationResult(val issues: List<ValidationIssue>) {
    val blockers: List<ValidationIssue> get() = issues.filter { it.severity == ValidationSeverity.BLOCK }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == ValidationSeverity.WARNING }
    val canSubmit: Boolean get() = blockers.isEmpty()
}

data class ReceiptGroupDateWindow(
    val earliestBillDate: LocalDate,
    val allowedStartDate: LocalDate,
    val allowedEndDate: LocalDate
)

object ReceiptValidationEngine {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val canonicalDateShape = Regex("^(\\d{2})/(\\d{2})/(\\d{4})$")

    private data class DateParseResult(
        val date: LocalDate? = null,
        val code: String? = null,
        val message: String? = null
    )

    private fun parseCanonicalReceiptDate(value: String): DateParseResult {
        val raw = value.trim()
        val match = canonicalDateShape.matchEntire(raw)
            ?: return DateParseResult(
                code = "DATE_FORMAT",
                message = "รูปแบบวันที่ไม่ถูกต้อง • กรุณาใช้ dd/MM/yyyy"
            )
        val day = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val year = match.groupValues[3].toInt()

        if (month !in 1..12) {
            return DateParseResult(
                code = "DATE_INVALID",
                message = "เดือนที่อ่านได้ (${match.groupValues[2]}) ไม่มีอยู่จริง • เดือนต้องอยู่ระหว่าง 01-12"
            )
        }
        if (day < 1) {
            return DateParseResult(
                code = "DATE_INVALID",
                message = "วันที่ที่อ่านได้ ($raw) ไม่มีอยู่จริง"
            )
        }

        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull()
            ?: return DateParseResult(
                code = "DATE_INVALID",
                message = "วันที่ที่อ่านได้ ($raw) ไม่มีอยู่จริง • กรุณาตรวจจำนวนวันของเดือน ${"%02d".format(month)}"
            )
        return DateParseResult(date = date)
    }

    fun individualDateIssue(
        record: PosRecord,
        workDate: LocalDate,
        rule: ReceiptGroupDateRule
    ): String? {
        if (!rule.enabled || record.noReceipt) return null
        if (record.billDate.isBlank()) return "ยังไม่มีวันที่บิล"
        val parsed = parseCanonicalReceiptDate(record.billDate)
        val date = parsed.date ?: return parsed.message ?: "ตรวจวันที่อีกครั้ง"
        if (rule.resetAtMonthEnd && (date.year != workDate.year || date.monthValue != workDate.monthValue)) {
            return "วันที่บิลต้องอยู่เดือนเดียวกับวันงาน"
        }
        val minDate = workDate.minusDays(rule.maxBeforeDays.coerceAtLeast(0).toLong())
        val maxDate = workDate.plusDays(rule.afterDaysWhenOldestIsWorkDay.coerceAtLeast(0).toLong())
        return when {
            date.isBefore(minDate) -> "ย้อนหลังเกิน ${rule.maxBeforeDays} วัน"
            date.isAfter(maxDate) -> "หลังวันงานเกิน ${rule.afterDaysWhenOldestIsWorkDay} วัน"
            else -> null
        }
    }

    fun datePositionLabel(billDate: String, workDate: LocalDate): String {
        val parsed = parseCanonicalReceiptDate(billDate)
        val date = parsed.date ?: return parsed.message ?: "ตรวจวันที่อีกครั้ง"
        val offset = java.time.temporal.ChronoUnit.DAYS.between(workDate, date).toInt()
        return when {
            offset < 0 -> "ก่อนวันงาน ${-offset} วัน"
            offset > 0 -> "หลังวันงาน $offset วัน"
            else -> "ตรงวันงาน"
        }
    }

    fun validateBeforeSubmit(
        context: Context,
        work: WorkItem,
        workDate: LocalDate,
        records: List<PosRecord>,
        receiptPaths: List<String?>,
        rule: BrandReceiptRule = DemoReceiptRules.forBrand(work.brand)
    ): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        validateRequiredFields(records, issues)
        validateReceiptStoreIds(work, records, issues)
        if (rule.groupDateRule.enabled) {
            issues += groupDateIssues(records, workDate, rule.groupDateRule)
        } else {
            validateDateWindow(records, workDate, rule.dateWindowRule, issues)
        }
        if (rule.preventDuplicateReceiptData) {
            validateDuplicateDataInsideCurrentWork(records, issues)
            validatePreviouslySubmittedData(context, work, records, issues)
        }
        if (rule.preventDuplicateImage) {
            validateDuplicateImages(context, receiptPaths, issues)
        }
        validateStoreIdentityWhenConfigured(context, work, workDate, receiptPaths, rule, issues)

        return ValidationResult(issues.distinctBy { "${it.code}|${it.message}" })
    }

    fun markSubmissionAccepted(
        context: Context,
        work: WorkItem,
        records: List<PosRecord>,
        receiptPaths: List<String?>
    ) {
        records.filter { !it.noReceipt }.forEach { record ->
            receiptDataFingerprint(work, record)?.let {
                DemoRepository.markSubmittedReceiptFingerprint(context, it)
            }
        }
        receiptPaths.filterNotNull().forEach { path ->
            fileSha256(path)?.let { DemoRepository.markSubmittedImageHash(context, it) }
        }
    }

    private fun validateRequiredFields(records: List<PosRecord>, issues: MutableList<ValidationIssue>) {
        records.forEach { record ->
            if (record.noReceipt) {
                if (record.noReceiptReason.isBlank()) {
                    issues += block("NO_RECEIPT_REASON", "POS ${record.posNumber}: กรุณาเลือกเหตุผลที่ไม่ได้บิล")
                }
                if (record.noReceiptReason == "อื่น ๆ" && record.note.isBlank()) {
                    issues += block("NO_RECEIPT_NOTE", "POS ${record.posNumber}: กรุณากรอกหมายเหตุ")
                }
            } else {
                if (record.customerNo.isBlank()) issues += block("CUSTOMER_REQUIRED", "POS ${record.posNumber}: ยังไม่มีเลข/ยอดลูกค้า")
                if (record.billDate.isBlank()) issues += block("DATE_REQUIRED", "POS ${record.posNumber}: ยังไม่มีวันที่")
                if (record.billTime.isBlank()) {
                    issues += block("TIME_REQUIRED", "POS ${record.posNumber}: ยังไม่มีเวลา")
                } else {
                    val normalizedTime = ReceiptTimeOcrNormalizer.normalize(record.billTime)
                    if (normalizedTime.value == null || normalizedTime.value != record.billTime) {
                        issues += block("TIME_FORMAT_POS_${record.posNumber}", "POS ${record.posNumber}: เวลาไม่อยู่ในรูปแบบ HH:mm")
                    }
                }
            }
        }
    }

    /**
     * ตรวจ STORE_ID เฉพาะรูปแบบบิลที่ Admin กำหนดว่ามีรหัสร้านจริง
     * การที่ข้อมูลมาจาก OCR เพียงอย่างเดียวไม่ได้หมายความว่าบิลต้องมี STORE_ID
     * เพราะบางแบรนด์/บางรูปแบบไม่มีรหัสร้านบนบิล
     */
    private fun validateReceiptStoreIds(
        work: WorkItem,
        records: List<PosRecord>,
        issues: MutableList<ValidationIssue>
    ) {
        val ocrRecords = records.filter { record ->
            if (record.noReceipt) return@filter false
            record.ocrStoreIdExpected ||
                record.ocrStoreId.isNotBlank() ||
                record.ocrWarnings.contains("รหัสร้าน") ||
                record.ocrWarnings.contains("ยืนยันร้านไม่ได้")
        }
        if (ocrRecords.isEmpty()) return

        val storeIdsByPos = ocrRecords
            .filter { it.ocrStoreId.isNotBlank() }
            .associate { it.posNumber to it.ocrStoreId }

        val missing = ocrRecords.filter { it.ocrStoreId.isBlank() }
        missing.forEach { record ->
            issues += block(
                "STORE_ID_REQUIRED_POS_${record.posNumber}",
                "POS ${record.posNumber}: ยังยืนยันร้านไม่ได้ เพราะยังอ่านรหัสร้านจากบิลไม่พบ"
            )
        }

        val assessment = StoreReceiptIdentity.evaluate(
            expectedStoreId = work.expectedReceiptStoreId,
            storeIdsByPos = storeIdsByPos
        )
        assessment.warningsByPos.toSortedMap().forEach { (pos, warning) ->
            val code = when (assessment.status) {
                StoreReceiptStatus.BILL_SWAPPED_STORE -> "BILL_SWAPPED_STORE_POS_$pos"
                StoreReceiptStatus.WRONG_STORE -> "WRONG_STORE_POS_$pos"
                else -> "STORE_IDENTITY_POS_$pos"
            }
            issues += block(code, "POS $pos: $warning")
        }
        if (assessment.status == StoreReceiptStatus.UNKNOWN && assessment.summaryWarnings.isNotEmpty()) {
            assessment.summaryWarnings.forEach { warning ->
                issues += block("STORE_IDENTITY_UNKNOWN", warning)
            }
        }
    }

    fun isBillDateWithinWindow(
        billDate: String,
        workDate: LocalDate,
        rule: ReceiptDateWindowRule
    ): Boolean? {
        if (!rule.enabled || billDate.isBlank()) return null
        val parsed = parseCanonicalReceiptDate(billDate).date ?: return false
        val minDate = workDate.minusDays(rule.beforeDays.coerceAtLeast(0).toLong())
        val maxDate = workDate.plusDays(rule.afterDays.coerceAtLeast(0).toLong())
        return !parsed.isBefore(minDate) && !parsed.isAfter(maxDate)
    }

    /** ตรวจวันที่แบบรวมทั้งร้าน และคืนปัญหาแยกตาม POS เพื่อใช้ตีกรอบแดงในหน้าจอ */
    fun groupDateWindow(
        records: List<PosRecord>,
        workDate: LocalDate,
        rule: ReceiptGroupDateRule
    ): ReceiptGroupDateWindow? {
        if (!rule.enabled) return null
        val validDates = records
            .filter { !it.noReceipt && it.billDate.isNotBlank() }
            .mapNotNull { parseCanonicalReceiptDate(it.billDate).date }
        if (validDates.isEmpty()) return null

        val earliest = validDates.minOrNull() ?: return null
        val oldestOffset = java.time.temporal.ChronoUnit.DAYS.between(earliest, workDate).toInt()
        if (oldestOffset < 0) {
            val allowedEnd = workDate.plusDays(rule.afterDaysWhenOldestIsWorkDay.coerceAtLeast(0).toLong())
            return if (validDates.all { !it.isAfter(allowedEnd) }) {
                ReceiptGroupDateWindow(
                    earliestBillDate = earliest,
                    allowedStartDate = workDate.plusDays(1),
                    allowedEndDate = allowedEnd
                )
            } else null
        }
        if (oldestOffset > rule.maxBeforeDays.coerceAtLeast(0)) return null

        val allowedAfterDays = when {
            oldestOffset >= 2 -> rule.afterDaysWhenOldestIsMaxBefore
            oldestOffset == 1 -> rule.afterDaysWhenOldestIsOneDayBefore
            else -> rule.afterDaysWhenOldestIsWorkDay
        }.coerceAtLeast(0)
        return ReceiptGroupDateWindow(
            earliestBillDate = earliest,
            allowedStartDate = earliest,
            allowedEndDate = workDate.plusDays(allowedAfterDays.toLong())
        )
    }

    /** ตรวจวันที่แบบรวมทั้งร้าน และคืนปัญหาแยกตาม POS เพื่อใช้ตีกรอบแดงในหน้าจอ */
    fun groupDateIssues(
        records: List<PosRecord>,
        workDate: LocalDate,
        rule: ReceiptGroupDateRule
    ): List<ValidationIssue> {
        if (!rule.enabled) return emptyList()
        val dated = records.filter { !it.noReceipt && it.billDate.isNotBlank() }.map { record ->
            record to parseCanonicalReceiptDate(record.billDate)
        }
        val issues = mutableListOf<ValidationIssue>()
        dated.filter { it.second.date == null }.forEach { (record, parsed) ->
            val code = parsed.code ?: "DATE_INVALID"
            issues += block("${code}_POS_${record.posNumber}", parsed.message ?: "ตรวจวันที่อีกครั้ง")
        }
        val valid = dated.mapNotNull { (record, parsed) -> parsed.date?.let { record to it } }
        if (valid.isEmpty()) return issues

        if (rule.resetAtMonthEnd) {
            valid.filter { (_, date) -> date.year != workDate.year || date.monthValue != workDate.monthValue }
                .forEach { (record, _) ->
                    issues += block(
                        "DATE_CROSS_MONTH_POS_${record.posNumber}",
                        "คนละเดือนกับวันงาน • แบรนด์นี้ตัดยอดสิ้นเดือน"
                    )
                }
        }

        val earliest = valid.minOf { it.second }
        val oldestOffset = java.time.temporal.ChronoUnit.DAYS.between(earliest, workDate).toInt()
        val minDate = workDate.minusDays(rule.maxBeforeDays.coerceAtLeast(0).toLong())
        if (oldestOffset < 0) {
            val maxFutureDate = workDate.plusDays(rule.afterDaysWhenOldestIsWorkDay.coerceAtLeast(0).toLong())
            valid.filter { (_, date) -> date.isAfter(maxFutureDate) }.forEach { (record, date) ->
                val afterDays = java.time.temporal.ChronoUnit.DAYS.between(workDate, date).toInt()
                issues += block(
                    "DATE_TOO_NEW_POS_${record.posNumber}",
                    "หลังวันงาน $afterDays วัน • ใช้ได้ถึง ${maxFutureDate.format(dateFormatter)}"
                )
            }
            return issues.distinctBy { it.code }
        }
        if (oldestOffset > rule.maxBeforeDays.coerceAtLeast(0)) {
            valid.filter { (_, date) -> date.isBefore(minDate) }.forEach { (record, date) ->
                val daysBefore = java.time.temporal.ChronoUnit.DAYS.between(date, workDate).toInt()
                issues += block(
                    "DATE_TOO_OLD_POS_${record.posNumber}",
                    "ก่อนวันงาน $daysBefore วัน • ย้อนหลังได้ไม่เกิน ${rule.maxBeforeDays} วัน"
                )
            }
            return issues.distinctBy { it.code }
        }

        val allowedAfterDays = when {
            oldestOffset >= 2 -> rule.afterDaysWhenOldestIsMaxBefore
            oldestOffset == 1 -> rule.afterDaysWhenOldestIsOneDayBefore
            else -> rule.afterDaysWhenOldestIsWorkDay
        }.coerceAtLeast(0)
        val allowedMinDate = earliest
        val maxDate = workDate.plusDays(allowedAfterDays.toLong())
        val tooNewRecords = valid.filter { (_, date) -> date.isAfter(maxDate) }
        if (oldestOffset >= 2 && tooNewRecords.isNotEmpty()) {
            valid.filter { (_, date) -> date == earliest }.forEach { (record, date) ->
                val daysBefore = java.time.temporal.ChronoUnit.DAYS.between(date, workDate).toInt()
                issues += block(
                    "DATE_GROUP_CONFLICT_POS_${record.posNumber}",
                    "ก่อนวันงาน $daysBefore วัน • ใช้ร่วมกับบิลหลังวันงานไม่ได้"
                )
            }
        }
        valid.filter { (_, date) -> date.isBefore(allowedMinDate) || date.isAfter(maxDate) }.forEach { (record, date) ->
            val conciseMessage = if (date.isAfter(maxDate)) {
                val daysAfter = java.time.temporal.ChronoUnit.DAYS.between(workDate, date).toInt()
                if (oldestOffset >= 2) {
                    "หลังวันงาน $daysAfter วัน • ใช้ร่วมกับบิล ${earliest.format(dateFormatter)} ไม่ได้"
                } else {
                    "หลังวันงาน $daysAfter วัน • ใช้ได้ถึง ${maxDate.format(dateFormatter)}"
                }
            } else {
                val daysBefore = java.time.temporal.ChronoUnit.DAYS.between(date, workDate).toInt()
                "ก่อนวันงาน $daysBefore วัน • ใช้ได้ตั้งแต่ ${allowedMinDate.format(dateFormatter)}"
            }
            issues += block(
                "DATE_OUTSIDE_GROUP_POS_${record.posNumber}",
                conciseMessage
            )
        }
        return issues.distinctBy { it.code }
    }

    private fun validateDateWindow(
        records: List<PosRecord>,
        workDate: LocalDate,
        rule: ReceiptDateWindowRule,
        issues: MutableList<ValidationIssue>
    ) {
        if (!rule.enabled) return
        records.filter { !it.noReceipt && it.billDate.isNotBlank() }.forEach { record ->
            val parsedResult = parseCanonicalReceiptDate(record.billDate)
            val parsed = parsedResult.date

            if (parsed == null) {
                issues += block(
                    parsedResult.code ?: "DATE_INVALID",
                    "POS ${record.posNumber}: ${parsedResult.message ?: "ตรวจวันที่อีกครั้ง"}"
                )
                return@forEach
            }

            val minDate = workDate.minusDays(rule.beforeDays.coerceAtLeast(0).toLong())
            val maxDate = workDate.plusDays(rule.afterDays.coerceAtLeast(0).toLong())
            val inRange = !parsed.isBefore(minDate) && !parsed.isAfter(maxDate)
            if (!inRange) {
                val message = "POS ${record.posNumber}: ${rule.warningText} (${minDate.format(dateFormatter)} - ${maxDate.format(dateFormatter)})"
                issues += if (rule.action == RuleAction.BLOCK) {
                    block("DATE_OUTSIDE_WINDOW", message)
                } else {
                    ValidationIssue("DATE_OUTSIDE_WINDOW", ValidationSeverity.WARNING, message)
                }
            }
        }
    }

    /**
     * ไม่รวม POS number ใน fingerprint เพื่อจับกรณีเอาบิลเครื่องเดิมไปใส่ POS อื่น
     */
    private fun validateDuplicateDataInsideCurrentWork(
        records: List<PosRecord>,
        issues: MutableList<ValidationIssue>
    ) {
        val seen = mutableMapOf<String, Int>()
        records.filter { !it.noReceipt }.forEach { record ->
            val key = localRecordKey(record) ?: return@forEach
            val oldPos = seen[key]
            if (oldPos != null && oldPos != record.posNumber) {
                issues += block(
                    "DUPLICATE_RECEIPT_CURRENT",
                    "ข้อมูลบิลซ้ำกันระหว่าง POS $oldPos และ POS ${record.posNumber} กรุณาตรวจว่ามีการใช้บิลเดิมซ้ำหรือไม่"
                )
            } else {
                seen[key] = record.posNumber
            }
        }
    }

    private fun validatePreviouslySubmittedData(
        context: Context,
        work: WorkItem,
        records: List<PosRecord>,
        issues: MutableList<ValidationIssue>
    ) {
        records.filter { !it.noReceipt }.forEach { record ->
            val fp = receiptDataFingerprint(work, record) ?: return@forEach
            if (DemoRepository.isSubmittedReceiptFingerprintUsed(context, fp)) {
                issues += block(
                    "DUPLICATE_RECEIPT_HISTORY",
                    "POS ${record.posNumber}: พบข้อมูลบิลชุดนี้เคยถูกส่งแล้ว กรุณาตรวจสอบก่อนส่งซ้ำ"
                )
            }
        }
    }

    private fun validateDuplicateImages(
        context: Context,
        receiptPaths: List<String?>,
        issues: MutableList<ValidationIssue>
    ) {
        val hashes = mutableMapOf<String, Int>()
        receiptPaths.forEachIndexed { index, path ->
            if (path.isNullOrBlank()) return@forEachIndexed
            val hash = fileSha256(path) ?: return@forEachIndexed
            val oldIndex = hashes[hash]
            if (oldIndex != null) {
                issues += block(
                    "DUPLICATE_IMAGE_CURRENT",
                    "ภาพบิล ${oldIndex + 1} และภาพบิล ${index + 1} เป็นไฟล์เดียวกัน กรุณาลบภาพที่ซ้ำ"
                )
            } else {
                hashes[hash] = index
            }
            if (DemoRepository.isSubmittedImageHashUsed(context, hash)) {
                issues += block(
                    "DUPLICATE_IMAGE_HISTORY",
                    "ภาพบิล ${index + 1} เคยถูกใช้กับงานที่ส่งแล้ว กรุณาตรวจสอบว่าเป็นบิลเดิมหรือไม่"
                )
            }
        }
    }

    /**
     * กฎ token/ชื่อร้านจาก Admin เป็นชั้นเสริม นอกเหนือจาก STORE_ID ที่บังคับเทียบจากแผนงาน
     */
    private fun validateStoreIdentityWhenConfigured(
        context: Context,
        work: WorkItem,
        workDate: LocalDate,
        receiptPaths: List<String?>,
        rule: BrandReceiptRule,
        issues: MutableList<ValidationIssue>
    ) {
        val identity = rule.storeIdentityRule
        if (!identity.enabled || identity.requiredTokens.isEmpty()) return

        receiptPaths.filterNotNull().forEachIndexed { index, path ->
            val raw = DemoRepository.loadOcrRawText(context, work.id, workDate, path)
            if (raw.isBlank()) {
                issues += block(
                    "STORE_IDENTITY_NO_OCR",
                    "ภาพบิล ${index + 1}: ยังไม่ได้อ่านข้อมูลสำหรับตรวจสอบร้าน"
                )
                return@forEachIndexed
            }

            val matches = identity.requiredTokens.map { token ->
                raw.contains(token, ignoreCase = true)
            }
            val passed = if (identity.requireAll) matches.all { it } else matches.any { it }
            if (!passed) {
                issues += block(
                    "WRONG_STORE",
                    "ภาพบิล ${index + 1}: ไม่พบข้อมูลร้านตามเงื่อนไขที่กำหนดสำหรับร้าน ${work.storeCode}"
                )
            }
        }
    }

    private fun localRecordKey(record: PosRecord): String? {
        if (record.customerNo.isBlank() || record.billDate.isBlank() || record.billTime.isBlank()) return null
        return listOf(
            normalize(record.customerNo),
            normalize(record.billDate),
            normalize(record.billTime)
        ).joinToString("|")
    }

    private fun receiptDataFingerprint(work: WorkItem, record: PosRecord): String? {
        if (record.customerNo.isBlank()) return null
        val cycle = counterCycleKey(record)
        return sha256(
            "${normalize(work.brand)}|${normalize(work.storeCode)}|${record.posNumber}|$cycle|${normalize(record.customerNo)}"
        )
    }

    private fun counterCycleKey(record: PosRecord): String {
        val date = parseCanonicalReceiptDate(record.billDate).date
        return when (record.ocrCounterCycle.uppercase()) {
            "DAILY" -> date?.toString() ?: normalize(record.billDate)
            "MONTHLY" -> date?.let { "%04d-%02d".format(it.year, it.monthValue) } ?: normalize(record.billDate).takeLast(7)
            "YEARLY" -> date?.year?.toString() ?: normalize(record.billDate).takeLast(4)
            else -> "CONTINUOUS"
        }
    }

    private fun normalize(value: String): String = value.trim().uppercase().replace(Regex("\\s+"), "")

    private fun fileSha256(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                null
            } else {
                val md = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        md.update(buffer, 0, read)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun block(code: String, message: String) =
        ValidationIssue(code, ValidationSeverity.BLOCK, message)
}
