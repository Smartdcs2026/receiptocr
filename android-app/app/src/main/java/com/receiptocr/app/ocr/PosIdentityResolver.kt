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
        val terminalPrefixes = rule.lastWorkPosPrefixes
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .toSet()

        // Round104.17.7: ตัว B บนบิลบางแบบมีรูปทรงใกล้เลข 8 มาก
        // ห้ามแทน 8 -> B แบบทั่วระบบ เพราะ POS 8/88/801 อาจเป็นเลขจริงได้
        // จึงแก้เฉพาะช่อง POS เมื่อแบรนด์กำหนด B เป็น terminal prefix,
        // มีข้อมูล POS ของร้านให้เทียบ และเลขที่ OCR อ่านตรง ๆ ไม่ใช่ POS จริงของร้าน
        val recoveredLeadingB = recoverAmbiguousLeadingB(
            raw = raw,
            terminalPrefixes = terminalPrefixes,
            availableWorkPos = availableWorkPos,
            runtimeLastWorkPos = rule.runtimeLastWorkPos
        )

        val identityRaw = recoveredLeadingB ?: raw
        val display = OcrTextNormalizer.displayPosIdentity(identityRaw) ?: return null
        val key = OcrTextNormalizer.normalizePosIdentity(identityRaw) ?: return null
        val numeric = OcrTextNormalizer.parsePosNumber(display) ?: return null

        val active = rule.enabled || rule.mappings.isNotEmpty() || rule.lastWorkPosPrefixes.isNotEmpty() ||
            rule.fallbackUnknownToLastWorkPos
        if (!active) {
            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)
        }

        val knownWorkPos = availableWorkPos.filter { it > 0 }.toSet()
        fun lastWorkPos(): Int? = knownWorkPos.maxOrNull()
            ?: rule.runtimeLastWorkPos.takeIf { it > 0 }
        val allowedPrefixes = rule.allowedPrefixes
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .toSet()

        val prefix = key.takeWhile(Char::isLetter)
        if (prefix.isBlank()) {
            // A real numeric POS in this store always wins. If the OCR value is not
            // an actual POS and this brand enables recovery, use the store's last POS.
            val isRealNumericPos = numeric in knownWorkPos ||
                (knownWorkPos.isEmpty() && rule.runtimeLastWorkPos == numeric)
            if (isRealNumericPos || !rule.fallbackUnknownToLastWorkPos) {
                return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)
            }
            val lastPos = lastWorkPos() ?: return null
            return ResolvedPosIdentity(display, key, lastPos, mappedByBrandRule = true)
        }

        if (prefix in terminalPrefixes) {
            val lastPos = lastWorkPos() ?: return null
            return ResolvedPosIdentity(display, key, lastPos, mappedByBrandRule = true)
        }

        // Exact mapping remains available for prefixes that are not terminal-prefix rules.
        val mapping = rule.mappings.firstOrNull { item ->
            OcrTextNormalizer.normalizePosIdentity(item.receiptPos) == key &&
                (item.workPos > 0 || item.useLastWorkPos)
        }
        if (mapping == null) {
            // Do not silently convert a missing mapping inside an allowed family
            // (for example N04 when N is configured). Unknown OCR prefixes may recover.
            if (rule.fallbackUnknownToLastWorkPos && prefix !in allowedPrefixes) {
                val lastPos = lastWorkPos() ?: return null
                return ResolvedPosIdentity(display, key, lastPos, mappedByBrandRule = true)
            }
            return null
        }

        val resolvedWorkPos = when {
            mapping.useLastWorkPos -> availableWorkPos.filter { it > 0 }.maxOrNull()
                ?: rule.runtimeLastWorkPos.takeIf { it > 0 }
                ?: return null
            mapping.workPos > 0 -> mapping.workPos
            else -> return null
        }

        return ResolvedPosIdentity(display, key, resolvedWorkPos, mappedByBrandRule = true)
    }

    /**
     * แก้เฉพาะความกำกวมตัวแรก 8/B ในรหัส POS เช่น OCR อ่าน B01 เป็น 801 หรือ 8O1
     * โดยต้องมีหลักฐานจากกฎแบรนด์และแผน POS ร้านก่อนเสมอ
     */
    private fun recoverAmbiguousLeadingB(
        raw: String,
        terminalPrefixes: Set<String>,
        availableWorkPos: Collection<Int>,
        runtimeLastWorkPos: Int
    ): String? {
        if ("B" !in terminalPrefixes) return null

        val knownWorkPos = availableWorkPos.filter { it > 0 }.toSet()
        val hasStoreContext = knownWorkPos.isNotEmpty() || runtimeLastWorkPos > 0
        if (!hasStoreContext) return null

        var compact = raw.trim().uppercase().replace(Regex("\\s+"), "")
        compact = compact.removePrefix("POS").trimStart(':', '#', '=', '-')
        compact = OcrTextNormalizer.normalizeDigits(compact)

        // ต้องเป็นเลข 8 นำหน้าตามด้วยอย่างน้อย 1 หลักเท่านั้น
        // เลข 8 เดี่ยว ๆ จึงยังเป็น POS 8 เสมอ
        if (!Regex("^8[0-9]{1,3}$").matches(compact)) return null

        val numericAsRead = compact.toIntOrNull() ?: return null

        // ถ้าร้านมี POS เลขนี้จริง ให้เชื่อเลขจริงก่อน ไม่เปลี่ยนเป็น B
        if (numericAsRead in knownWorkPos) return null
        if (knownWorkPos.isEmpty() && runtimeLastWorkPos == numericAsRead) return null

        val candidate = "B${compact.drop(1)}"
        return candidate.takeIf {
            OcrTextNormalizer.displayPosIdentity(it) != null &&
                OcrTextNormalizer.normalizePosIdentity(it) != null
        }
    }

    fun findUnmappedIdentities(
        rawTexts: List<String>,
        templates: List<UniversalOcrTemplate>,
        rule: PosIdentityRule,
        availableWorkPos: Collection<Int> = emptyList()
    ): List<String> {
        if (!rule.enabled && rule.mappings.isEmpty() && rule.lastWorkPosPrefixes.isEmpty() &&
            !rule.fallbackUnknownToLastWorkPos) return emptyList()
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
