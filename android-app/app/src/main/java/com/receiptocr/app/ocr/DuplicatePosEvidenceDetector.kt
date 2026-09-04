package com.receiptocr.app.ocr

import com.receiptocr.app.config.PosIdentityRule
import com.receiptocr.app.config.UniversalOcrTemplate

/**
 * ตรวจว่าภาพเดียวมีบิลมากกว่าหนึ่งใบสำหรับ POS เดียวกันหรือไม่
 *
 * หลัก Round94:
 * - OCR หลาย pass คือการอ่าน "ภาพเดียวกัน" หลายแบบ จึงห้ามใช้ความต่างระหว่าง pass เป็นหลักฐานว่ามีบิลซ้ำ
 * - การแตก candidate/การเว้นวรรค/ตัวคั่นต่างกันก็ไม่ใช่บิลซ้ำ
 * - N01/B01 ต้องผ่าน Brand POS Mapping ก่อนตัดสินว่าเป็น POS งานเดียวกันหรือไม่
 * - เตือนเฉพาะเมื่อใน pass เดียวกัน พบข้อมูลสมบูรณ์คนละชุดจากคนละบรรทัดต้นทาง
 *   สำหรับ POS งานเดียวกันและ Template เดียวกัน
 *
 * ถ้ายังพิสูจน์ไม่ได้ว่ามีบิลจริงสองใบ ให้เงียบไว้ก่อน เพื่อไม่ให้ผู้ใช้ได้รับ false warning
 */
object DuplicatePosEvidenceDetector {
    private data class Sighting(
        val passIndex: Int,
        val lineIndex: Int,
        val templateId: String,
        val signature: String
    )

    fun detect(
        rawTexts: List<String>,
        templates: List<UniversalOcrTemplate>,
        allowedPos: Set<Int>,
        posIdentityRule: PosIdentityRule = PosIdentityRule()
    ): Map<Int, String> {
        if (rawTexts.isEmpty() || templates.none { it.active }) return emptyMap()

        val sightingsByPos = linkedMapOf<Int, MutableList<Sighting>>()

        rawTexts.forEachIndexed { passIndex, raw ->
            if (raw.isBlank()) return@forEachIndexed

            val sourceLines = raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()

            sourceLines.forEachIndexed { lineIndex, line ->
                templates
                    .filter { it.active && it.validation.pos.mustBeUnique }
                    .forEach { template ->
                        val lineSightings = TemplateSequenceFallback.parseText(line, template)
                            .mapNotNull { fields ->
                                val resolved = PosIdentityResolver.resolve(
                                    fields["POS_NUMBER"].orEmpty(),
                                    posIdentityRule
                                ) ?: return@mapNotNull null
                                val pos = resolved.workPos
                                if (allowedPos.isNotEmpty() && pos !in allowedPos) return@mapNotNull null
                                val signature = signature(fields)
                                if (signature.isBlank()) return@mapNotNull null
                                pos to signature
                            }
                            .distinct()

                        lineSightings.forEach { (pos, signature) ->
                            sightingsByPos.getOrPut(pos) { mutableListOf() }
                                .add(Sighting(passIndex, lineIndex, template.templateId, signature))
                        }
                    }
            }
        }

        return buildMap {
            sightingsByPos.forEach { (pos, sightings) ->
                val conflict = sightings
                    .groupBy { it.passIndex to it.templateId }
                    .values
                    .any { samePassTemplate ->
                        val signaturesByLine = samePassTemplate
                            .groupBy { it.lineIndex }
                            .mapValues { (_, lineSightings) -> lineSightings.map { it.signature }.toSet() }

                        val distinctPhysicalLines = signaturesByLine.entries
                            .flatMap { (lineIndex, signatures) -> signatures.map { signature -> lineIndex to signature } }

                        distinctPhysicalLines
                            .groupBy { it.second }
                            .keys
                            .size >= 2 &&
                            distinctPhysicalLines.map { it.first }.distinct().size >= 2
                    }

                if (conflict) {
                    put(pos, "พบบิล POS $pos ซ้ำ • กรุณาตรวจภาพบิลก่อนส่ง")
                }
            }
        }
    }

    private fun signature(fields: Map<String, String>): String {
        val customer = fields["CUSTOMER_VALUE"].orEmpty().filter(Char::isDigit)
        val date = OcrTextNormalizer.normalizeDigits(fields["BILL_DATE"].orEmpty()).filter(Char::isDigit)
        val time = OcrTextNormalizer.normalizeDigits(fields["BILL_TIME"].orEmpty()).filter(Char::isDigit)
        val store = fields["STORE_ID"].orEmpty().filter(Char::isDigit)
        if (customer.isBlank() || date.isBlank() || time.isBlank()) return ""
        return listOf(customer, date, time, store).joinToString("|")
    }
}
