package com.receiptocr.app.ocr

import com.receiptocr.app.config.BrandReceiptRule
import com.receiptocr.app.config.UniversalOcrTemplate
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem
import java.time.LocalDate

/**
 * อ่านทุก Template ที่เปิดใช้ของแบรนด์จากภาพเดียว ไม่หยุดที่ Template แรก
 * Round93 strict interpreter ยังเป็นผลหลัก ตัวนี้มีหน้าที่เติม POS ที่ตกหล่น
 */
object MultiTemplateSequenceCollector {
    private data class Candidate(
        val template: UniversalOcrTemplate,
        val fields: Map<String, String>,
        val workPos: Int,
        val rawIdentity: String,
        val score: Int
    )

    fun apply(
        rawTexts: List<String>,
        records: List<PosRecord>,
        work: WorkItem,
        workDate: LocalDate,
        imagePath: String,
        templates: List<UniversalOcrTemplate>,
        receiptRule: BrandReceiptRule
    ): UniversalTemplateResult {
        if (templates.none { it.active } || rawTexts.none { it.isNotBlank() }) return failed(records)
        val allowedPos = records.map { it.posNumber }.toSet()

        val candidates = buildList {
            templates.filter { it.active }.forEach { template ->
                rawTexts.filter { it.isNotBlank() }.forEach { raw ->
                    TemplateSequenceFallback.parseText(raw, template).forEach { fields ->
                        val rawPos = fields["POS_NUMBER"].orEmpty()
                        val resolved = PosIdentityResolver.resolve(rawPos, receiptRule.posIdentityRule)
                            ?: return@forEach
                        if (template.validation.pos.mustExistInStorePlan && resolved.workPos !in allowedPos) {
                            return@forEach
                        }
                        val coreCount = listOf("BILL_DATE", "BILL_TIME", "CUSTOMER_VALUE")
                            .count { !fields[it].isNullOrBlank() }
                        add(
                            Candidate(
                                template = template,
                                fields = fields,
                                workPos = resolved.workPos,
                                rawIdentity = resolved.display,
                                score = template.priority + coreCount * 100 +
                                    if (fields["STORE_ID"].isNullOrBlank()) 0 else 20
                            )
                        )
                    }
                }
            }
        }.distinctBy { candidate ->
            candidate.template.templateId + "|" + candidate.workPos + "|" +
                candidate.fields.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" }
        }

        if (candidates.isEmpty()) return failed(records)

        val bestByPos = candidates.groupBy { it.workPos }.mapValues { (_, items) ->
            items.maxWithOrNull(
                compareBy<Candidate> { item ->
                    listOf("BILL_DATE", "BILL_TIME", "CUSTOMER_VALUE", "STORE_ID")
                        .count { !item.fields[it].isNullOrBlank() }
                }.thenBy { it.score }
            )!!
        }.toSortedMap()

        val updated = records.toMutableList()
        val warnings = linkedMapOf<Int, MutableList<String>>()
        val usedNames = linkedSetOf<String>()
        val detected = mutableListOf<Int>()

        bestByPos.forEach { (pos, candidate) ->
            val index = updated.indexOfFirst { it.posNumber == pos }
            if (index < 0) return@forEach
            val current = updated[index]
            val posWarnings = warnings.getOrPut(pos) { mutableListOf() }
            usedNames += candidate.template.templateName
            detected += pos

            if (current.noReceipt) {
                posWarnings += "พบข้อมูลของ POS $pos ในภาพ แต่ POS นี้ถูกระบุว่าไม่ได้บิล • ระบบยังไม่เปลี่ยนข้อมูลเดิม"
                return@forEach
            }

            val rawDate = candidate.fields["BILL_DATE"].orEmpty().trim()
            val dateField = candidate.template.recognition.rows.asSequence()
                .flatMap { it.fields.asSequence() }
                .firstOrNull { it.type.equals("BILL_DATE", ignoreCase = true) }
            val dateResult = rawDate.takeIf { it.isNotBlank() }?.let { value ->
                ReceiptDateOcrNormalizer.normalizeForField(
                    raw = value,
                    field = dateField,
                    referenceDate = workDate,
                    allowCanonicalInput = false
                )
            }
            val date = dateResult?.value.orEmpty()
            val time = ReceiptTimeOcrNormalizer.normalize(candidate.fields["BILL_TIME"].orEmpty()).value.orEmpty()
            val customer = candidate.fields["CUSTOMER_VALUE"].orEmpty().filter(Char::isDigit)
            val store = candidate.fields["STORE_ID"].orEmpty().trim()

            val core = candidate.template.validation.requiredCore
            if (core.date && rawDate.isBlank()) posWarnings += "ไม่พบวันที่ตามเงื่อนไขที่กำหนด"
            if (core.time && time.isBlank()) posWarnings += "ไม่พบเวลาตามเงื่อนไขที่กำหนด"
            if (core.customerValue && customer.isBlank()) posWarnings += "ไม่พบยอด/เลขลูกค้าตามเงื่อนไขที่กำหนด"
            if (rawDate.isNotBlank() && date.isBlank()) {
                posWarnings += dateResult?.warning ?: "วันที่บิลยังไม่ตรงรูปแบบที่กำหนด"
            }

            val expectsStore = candidate.template.recognition.rows.asSequence()
                .flatMap { it.fields.asSequence() }
                .any { it.type.equals("STORE_ID", ignoreCase = true) }

            updated[index] = current.copy(
                customerNo = customer.ifBlank { current.customerNo },
                billDate = date.ifBlank { current.billDate },
                billTime = time.ifBlank { current.billTime },
                source = "OCR-MULTI-TEMPLATE",
                ocrSourceImagePath = imagePath,
                ocrTemplateName = candidate.template.templateName,
                ocrWarnings = posWarnings.distinct().joinToString(" • "),
                ocrRawBillDate = rawDate.ifBlank { current.ocrRawBillDate },
                ocrStoreId = store.ifBlank { current.ocrStoreId },
                ocrStoreIdExpected = expectsStore || current.ocrStoreIdExpected,
                ocrCounterCycle = candidate.template.duplicatePolicy.customerCounterCycle.uppercase(),
                ocrRawPosIdentity = candidate.rawIdentity
            )
        }

        val ordered = detected.distinct().sorted()
        if (ordered.isEmpty()) return failed(records)
        val fieldTypes = bestByPos.values.flatMap { it.fields.keys }.toSet()
        val extracted = linkedMapOf<String, List<String>>()
        fieldTypes.forEach { type ->
            extracted[type] = ordered.map { pos -> bestByPos[pos]?.fields?.get(type).orEmpty() }
        }

        return UniversalTemplateResult(
            records = updated,
            message = "อ่านทุกเงื่อนไขที่ตรงในภาพแล้ว • พบ ${ordered.size} เครื่อง",
            templateName = usedNames.joinToString(" / "),
            detectedPos = ordered,
            extracted = extracted,
            validationWarnings = warnings.mapValues { it.value.distinct() },
            usedUniversalTemplate = true
        )
    }

    private fun failed(records: List<PosRecord>) = UniversalTemplateResult(
        records = records,
        message = "ยังไม่พบข้อมูลเพิ่มเติมจากเงื่อนไขอื่น",
        usedUniversalTemplate = true
    )
}
