package com.receiptocr.app.ocr

import com.receiptocr.app.config.PosIdentityRule
import com.receiptocr.app.config.UniversalOcrTemplate

object DuplicatePosEvidenceDetector {
    private data class Sighting(
        val passIndex: Int,
        val lineIndex: Int,
        val templateId: String,
        val storeId: String
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
            val sourceLines = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()

            sourceLines.forEachIndexed { lineIndex, line ->
                templates.filter { it.active && it.validation.pos.mustBeUnique }.forEach { template ->
                    TemplateSequenceFallback.parseText(line, template)
                        .mapNotNull { fields ->
                            val resolved = PosIdentityResolver.resolve(fields["POS_NUMBER"].orEmpty(), posIdentityRule)
                                ?: return@mapNotNull null
                            val pos = resolved.workPos
                            if (allowedPos.isNotEmpty() && pos !in allowedPos) return@mapNotNull null
                            if (!hasCompleteReceipt(fields)) return@mapNotNull null
                            pos to normalizeStore(fields["STORE_ID"].orEmpty())
                        }
                        .distinct()
                        .forEach { (pos, store) ->
                            sightingsByPos.getOrPut(pos) { mutableListOf() }
                                .add(Sighting(passIndex, lineIndex, template.templateId, store))
                        }
                }
            }
        }

        return buildMap {
            sightingsByPos.forEach { (pos, sightings) ->
                var sameStoreDuplicate = false
                var unknownStoreDuplicate = false

                sightings.groupBy { it.passIndex to it.templateId }.values.forEach { samePassTemplate ->
                    val lines = samePassTemplate.distinctBy { it.lineIndex }
                    for (i in lines.indices) {
                        for (j in (i + 1) until lines.size) {
                            val first = lines[i]
                            val second = lines[j]
                            if (first.lineIndex == second.lineIndex) continue
                            val bothKnown = first.storeId.isNotBlank() && second.storeId.isNotBlank()
                            when {
                                bothKnown && sameStore(first.storeId, second.storeId) -> sameStoreDuplicate = true
                                !bothKnown -> unknownStoreDuplicate = true
                            }
                        }
                    }
                }

                when {
                    sameStoreDuplicate -> put(pos, "พบบิลซ้ำในร้านเดียวกัน • POS $pos มีมากกว่า 1 ใบ")
                    unknownStoreDuplicate -> put(pos, "พบ POS $pos มากกว่า 1 ใบในภาพ • กรุณาตรวจบิล")
                }
            }
        }
    }

    private fun hasCompleteReceipt(fields: Map<String, String>): Boolean {
        val customer = fields["CUSTOMER_VALUE"].orEmpty().filter(Char::isDigit)
        val date = OcrTextNormalizer.normalizeDigits(fields["BILL_DATE"].orEmpty()).filter(Char::isDigit)
        val time = OcrTextNormalizer.normalizeDigits(fields["BILL_TIME"].orEmpty()).filter(Char::isDigit)
        return customer.isNotBlank() && date.isNotBlank() && time.isNotBlank()
    }

    private fun normalizeStore(raw: String): String = raw.trim().uppercase()
        .replace(Regex("[\\s._/-]+"), "")
        .filter { it.isLetterOrDigit() }

    private fun sameStore(first: String, second: String): Boolean {
        val a = normalizeStore(first)
        val b = normalizeStore(second)
        if (a.isBlank() || b.isBlank()) return false
        if (a.all(Char::isDigit) && b.all(Char::isDigit)) {
            return a.trimStart('0').ifBlank { "0" } == b.trimStart('0').ifBlank { "0" }
        }
        return a == b
    }
}
