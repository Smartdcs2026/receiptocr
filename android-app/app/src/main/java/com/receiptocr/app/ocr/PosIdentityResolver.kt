package com.receiptocr.app.ocr

import com.receiptocr.app.config.PosIdentityRule
import com.receiptocr.app.config.UniversalOcrTemplate

data class ResolvedPosIdentity(
    val display: String,
    val key: String,
    val workPos: Int,
    val mappedByBrandRule: Boolean
)

object PosIdentityResolver {
    fun resolve(raw: String, rule: PosIdentityRule): ResolvedPosIdentity? {
        val display = OcrTextNormalizer.displayPosIdentity(raw) ?: return null
        val key = OcrTextNormalizer.normalizePosIdentity(raw) ?: return null
        val numeric = OcrTextNormalizer.parsePosNumber(display) ?: return null

        if (!rule.enabled) {
            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)
        }

        val prefix = key.takeWhile(Char::isLetter)
        if (prefix.isBlank()) {
            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)
        }

        val allowed = rule.allowedPrefixes.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        if (allowed.isNotEmpty() && prefix !in allowed) return null

        val mapping = rule.mappings.firstOrNull { item ->
            OcrTextNormalizer.normalizePosIdentity(item.receiptPos) == key && item.workPos > 0
        } ?: return null

        return ResolvedPosIdentity(display, key, mapping.workPos, mappedByBrandRule = true)
    }

    fun findUnmappedIdentities(
        rawTexts: List<String>,
        templates: List<UniversalOcrTemplate>,
        rule: PosIdentityRule
    ): List<String> {
        if (!rule.enabled) return emptyList()
        val found = linkedSetOf<String>()
        templates.filter { it.active }.forEach { template ->
            rawTexts.filter { it.isNotBlank() }.forEach { raw ->
                TemplateSequenceFallback.parseText(raw, template).forEach { fields ->
                    val value = fields["POS_NUMBER"].orEmpty()
                    val display = OcrTextNormalizer.displayPosIdentity(value) ?: return@forEach
                    val key = OcrTextNormalizer.normalizePosIdentity(value) ?: return@forEach
                    if (key.any(Char::isLetter) && resolve(value, rule) == null) found += display
                }
            }
        }
        return found.toList().sorted()
    }
}
