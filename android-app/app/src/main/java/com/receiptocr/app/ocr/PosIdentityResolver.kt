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
    fun resolve(
        raw: String,
        rule: PosIdentityRule,
        availableWorkPos: Collection<Int> = emptyList()
    ): ResolvedPosIdentity? {
        val display = OcrTextNormalizer.displayPosIdentity(raw) ?: return null
        val key = OcrTextNormalizer.normalizePosIdentity(raw) ?: return null
        val numeric = OcrTextNormalizer.parsePosNumber(display) ?: return null

        val active = rule.enabled || rule.mappings.isNotEmpty() || rule.lastWorkPosPrefixes.isNotEmpty()
        if (!active) {
            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)
        }

        val prefix = key.takeWhile(Char::isLetter)
        if (prefix.isBlank()) {
            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)
        }

        val terminalPrefixes = rule.lastWorkPosPrefixes.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        if (prefix in terminalPrefixes) {
            val lastPos = availableWorkPos.filter { it > 0 }.maxOrNull()
                ?: rule.runtimeLastWorkPos.takeIf { it > 0 }
                ?: return null
            return ResolvedPosIdentity(display, key, lastPos, mappedByBrandRule = true)
        }

        // Exact mapping remains available for prefixes that are not terminal-prefix rules.
        val mapping = rule.mappings.firstOrNull { item ->
            OcrTextNormalizer.normalizePosIdentity(item.receiptPos) == key &&
                (item.workPos > 0 || item.useLastWorkPos)
        } ?: return null

        val resolvedWorkPos = when {
            mapping.useLastWorkPos -> availableWorkPos.filter { it > 0 }.maxOrNull()
                ?: rule.runtimeLastWorkPos.takeIf { it > 0 }
                ?: return null
            mapping.workPos > 0 -> mapping.workPos
            else -> return null
        }

        return ResolvedPosIdentity(display, key, resolvedWorkPos, mappedByBrandRule = true)
    }

    fun findUnmappedIdentities(
        rawTexts: List<String>,
        templates: List<UniversalOcrTemplate>,
        rule: PosIdentityRule,
        availableWorkPos: Collection<Int> = emptyList()
    ): List<String> {
        if (!rule.enabled && rule.mappings.isEmpty() && rule.lastWorkPosPrefixes.isEmpty()) return emptyList()
        val found = linkedSetOf<String>()
        templates.filter { it.active }.forEach { template ->
            rawTexts.filter { it.isNotBlank() }.forEach { raw ->
                TemplateSequenceFallback.parseText(raw, template).forEach { fields ->
                    val value = fields["POS_NUMBER"].orEmpty()
                    val display = OcrTextNormalizer.displayPosIdentity(value) ?: return@forEach
                    val key = OcrTextNormalizer.normalizePosIdentity(value) ?: return@forEach
                    if (key.any(Char::isLetter) && resolve(value, rule, availableWorkPos) == null) found += display
                }
            }
        }
        return found.toList().sorted()
    }
}
