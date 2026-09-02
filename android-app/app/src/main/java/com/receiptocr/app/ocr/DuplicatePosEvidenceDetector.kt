package com.receiptocr.app.ocr

import com.receiptocr.app.config.UniversalOcrTemplate

/**
 * ตรวจว่าภาพเดียวมีข้อมูลมากกว่าหนึ่งชุดที่อ้างถึง POS เดียวกันหรือไม่
 *
 * จุดสำคัญคือ OCR อ่านภาพเดิมหลายรอบ จึงห้ามถือ "พบ POS เดิมหลายครั้ง" เป็นบิลซ้ำทันที
 * จะเตือนเมื่อพบลายเซ็นข้อมูลคนละชุดอย่างมีหลักฐานจริง เช่น
 * - ในรอบอ่านข้อความเดียวกันพบ POS 1 สองชุดที่ลูกค้า/วัน/เวลาต่างกัน หรือ
 * - ชุดข้อมูลที่ต่างกันแต่ละชุดถูกยืนยันจากอย่างน้อยสองรอบอ่าน
 */
object DuplicatePosEvidenceDetector {
    private data class Sighting(
        val passIndex: Int,
        val templateId: String,
        val signature: String
    )

    fun detect(
        rawTexts: List<String>,
        templates: List<UniversalOcrTemplate>,
        allowedPos: Set<Int>
    ): Map<Int, String> {
        if (rawTexts.isEmpty() || templates.none { it.active }) return emptyMap()

        val sightingsByPos = linkedMapOf<Int, MutableList<Sighting>>()
        rawTexts.forEachIndexed { passIndex, raw ->
            if (raw.isBlank()) return@forEachIndexed
            templates.filter { it.active && it.validation.pos.mustBeUnique }.forEach { template ->
                TemplateSequenceFallback.parseText(raw, template).forEach { fields ->
                    val pos = OcrTextNormalizer.parsePosNumber(fields["POS_NUMBER"].orEmpty())
                        ?: return@forEach
                    if (allowedPos.isNotEmpty() && pos !in allowedPos) return@forEach
                    val signature = signature(fields)
                    if (signature.isBlank()) return@forEach
                    sightingsByPos.getOrPut(pos) { mutableListOf() }
                        .add(Sighting(passIndex, template.templateId, signature))
                }
            }
        }

        return buildMap {
            sightingsByPos.forEach { (pos, sightings) ->
                // One brand can have several active templates. A single receipt can be parsed
                // differently by two templates, so that alone must never be called a duplicate.
                val conflict = sightings.groupBy { it.templateId }.values.any { templateSightings ->
                    val distinct = templateSightings.distinct()
                    val samePassConflict = distinct.groupBy { it.passIndex }.values.any { passSightings ->
                        passSightings.map { it.signature }.distinct().size >= 2
                    }
                    val repeatedSignatures = distinct.groupBy { it.signature }
                        .filterValues { group -> group.map { it.passIndex }.distinct().size >= 2 }
                        .keys
                    samePassConflict || repeatedSignatures.size >= 2
                }
                if (conflict) {
                    put(
                        pos,
                        "พบข้อมูลมากกว่าหนึ่งชุดสำหรับ POS $pos • กรุณาตรวจว่ามีบิล POS ซ้ำหรือไม่"
                    )
                }
            }
        }
    }

    private fun signature(fields: Map<String, String>): String {
        val customer = fields["CUSTOMER_VALUE"].orEmpty().filter(Char::isDigit)
        // Separator and spacing differences are not different receipts.
        val date = OcrTextNormalizer.normalizeDigits(fields["BILL_DATE"].orEmpty()).filter(Char::isDigit)
        val time = OcrTextNormalizer.normalizeDigits(fields["BILL_TIME"].orEmpty()).filter(Char::isDigit)
        val store = fields["STORE_ID"].orEmpty().filter(Char::isDigit)
        if (customer.isBlank() || date.isBlank() || time.isBlank()) return ""
        return listOf(customer, date, time, store).joinToString("|")
    }
}
