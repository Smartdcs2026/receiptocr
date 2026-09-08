from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

engine = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/RuleDrivenOcrEngine.kt"
text = engine.read_text(encoding="utf-8")

if "import com.receiptocr.app.config.PosIdentityRule" not in text:
    text = text.replace(
        "import com.receiptocr.app.config.OcrRegionRule\n",
        "import com.receiptocr.app.config.OcrRegionRule\nimport com.receiptocr.app.config.PosIdentityRule\n",
        1,
    )

old_call = """        val rules = profile.regions.sortedBy { it.priority }
        val posRules = rules.filter { it.fieldType == OcrFieldType.POS_NUMBER }
        val posCandidates = findPosCandidates(items, posRules)
            .filter { candidate -> records.any { it.posNumber == candidate.posNumber } }
"""
new_call = """        val rules = profile.regions.sortedBy { it.priority }
        val posRules = rules.filter { it.fieldType == OcrFieldType.POS_NUMBER }
        val availableWorkPos = records.map { it.posNumber }.filter { it > 0 }.toSet()
        val posCandidates = findPosCandidates(
            items = items,
            rules = posRules,
            posIdentityRule = receiptRule.posIdentityRule,
            availableWorkPos = availableWorkPos
        )
            .filter { candidate -> records.any { it.posNumber == candidate.posNumber } }
"""
if new_call not in text:
    if old_call not in text:
        raise SystemExit("RuleDriven apply POS call block not found")
    text = text.replace(old_call, new_call, 1)

if "internal fun resolveSpatialPosCandidate(" not in text:
    start_marker = "    private fun findPosCandidates(\n"
    end_marker = "    private fun buildPosBands("
    start = text.find(start_marker)
    end = text.find(end_marker, start + 1)
    if start < 0 or end < 0:
        raise SystemExit("RuleDriven findPosCandidates function boundary not found")

    new_find = r'''    /**
     * Round104.18: profile/ROI fallback resolves POS identity with the same
     * brand rule as the universal-template path. Ambiguous 801/8O1 can map to
     * B01 only when store/POS context supports it; real POS 8/801 stay numeric.
     */
    internal fun resolveSpatialPosCandidate(
        raw: String,
        posIdentityRule: PosIdentityRule,
        availableWorkPos: Collection<Int>
    ): Int? = PosIdentityResolver.resolve(raw, posIdentityRule, availableWorkPos)?.workPos

    internal fun spatialPosIdentityCandidates(value: String): List<String> {
        val prefixed = Regex("(?i)\\b[NB]\\s*[0-9OoIl|]{1,3}\\b")
            .findAll(value)
            .map { it.value }
            .toList()
        if (prefixed.isNotEmpty()) return prefixed.distinct()

        OcrTextNormalizer.displayPosIdentity(value)?.let { return listOf(it) }
        return OcrTextNormalizer.findPosNumbers(value).map(Int::toString).distinct()
    }

    private fun findPosCandidates(
        items: List<SpatialOcrItem>,
        rules: List<OcrRegionRule>,
        posIdentityRule: PosIdentityRule,
        availableWorkPos: Collection<Int>
    ): List<PosAnchor> {
        val result = mutableListOf<PosAnchor>()
        val activeRules = if (rules.isEmpty()) emptyList() else rules

        activeRules.forEach { rule ->
            val regionItems = items.filter { inside(it.box, rule.region) }
            val regex = rule.regexPattern?.let { safeRegex(it) }

            regionItems.forEach { item ->
                regex?.findAll(item.text)?.forEach { match ->
                    val captured = match.groupValues.getOrNull(1).orEmpty().ifBlank { match.value }
                    resolveSpatialPosCandidate(captured, posIdentityRule, availableWorkPos)?.let { number ->
                        result += PosAnchor(number, item.centerX, item.centerY, item.lineIndex)
                    }
                }

                val identityCandidates = spatialPosIdentityCandidates(item.text)
                identityCandidates.forEach { rawIdentity ->
                    resolveSpatialPosCandidate(rawIdentity, posIdentityRule, availableWorkPos)?.let { number ->
                        result += PosAnchor(number, item.centerX, item.centerY, item.lineIndex)
                    }
                }

                val isLabel = rule.labelHints.any { item.text.contains(it, ignoreCase = true) } ||
                    Regex("(?i)P\\s*\\.?\\s*O\\s*\\.?\\s*S|TERMINAL|เครื่อง").containsMatchIn(item.text)
                if (isLabel && identityCandidates.isEmpty()) {
                    regionItems.asSequence()
                        .filter { candidate -> candidate !== item }
                        .filter { candidate ->
                            abs(candidate.centerY - item.centerY) <= rule.searchRadiusY.coerceAtLeast(0.035f)
                        }
                        .sortedBy { candidate ->
                            abs(candidate.centerY - item.centerY) + abs(candidate.centerX - item.centerX) * 0.35f
                        }
                        .mapNotNull { candidate ->
                            resolveSpatialPosCandidate(candidate.text, posIdentityRule, availableWorkPos)
                                ?.let { it to candidate }
                        }
                        .firstOrNull()
                        ?.let { (number, candidate) ->
                            result += PosAnchor(number, candidate.centerX, candidate.centerY, candidate.lineIndex)
                        }
                }
            }
        }
        return result.distinctBy { "${it.posNumber}|${it.centerX}|${it.centerY}" }
    }

'''
    text = text[:start] + new_find + text[end:]

engine.write_text(text, encoding="utf-8")

# Direct unit coverage for the legacy profile/ROI fallback bridge.
test_path = ROOT / "android-app/app/src/test/java/com/receiptocr/app/ocr/RuleDrivenPosIdentityRound10418Test.kt"
test_path.write_text(
'''package com.receiptocr.app.ocr

import com.receiptocr.app.config.PosIdentityMapping
import com.receiptocr.app.config.PosIdentityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDrivenPosIdentityRound10418Test {
    private fun rule(lastWorkPos: Int = 0) = PosIdentityRule(
        enabled = true,
        allowedPrefixes = listOf("N", "B"),
        lastWorkPosPrefixes = listOf("B"),
        mappings = listOf(
            PosIdentityMapping("N01", workPos = 1),
            PosIdentityMapping("N02", workPos = 2)
        ),
        runtimeLastWorkPos = lastWorkPos
    )

    @Test fun profile_fallback_b01_uses_last_store_pos() {
        assertEquals(3, RuleDrivenOcrEngine.resolveSpatialPosCandidate("B01", rule(), listOf(1, 2, 3)))
    }

    @Test fun profile_fallback_801_recovers_b01_with_store_context() {
        assertEquals(3, RuleDrivenOcrEngine.resolveSpatialPosCandidate("801", rule(), listOf(1, 2, 3)))
        assertEquals(5, RuleDrivenOcrEngine.resolveSpatialPosCandidate("8O1", rule(), listOf(1, 2, 3, 4, 5)))
    }

    @Test fun profile_fallback_preserves_real_numeric_pos_8_and_801() {
        assertEquals(8, RuleDrivenOcrEngine.resolveSpatialPosCandidate("8", rule(), listOf(1, 2, 3, 8)))
        assertEquals(801, RuleDrivenOcrEngine.resolveSpatialPosCandidate("801", rule(), listOf(1, 2, 3, 801)))
    }

    @Test fun prefixed_candidate_extraction_keeps_letters_for_brand_mapping() {
        val values = RuleDrivenOcrEngine.spatialPosIdentityCandidates("BNO:S26080652 B01-004184 N02")
        assertTrue(values.contains("B01"))
        assertTrue(values.contains("N02"))
    }

    @Test fun standalone_ambiguous_numeric_candidate_reaches_contextual_resolver() {
        assertEquals(listOf("801"), RuleDrivenOcrEngine.spatialPosIdentityCandidates("801"))
        assertEquals(4, RuleDrivenOcrEngine.resolveSpatialPosCandidate("801", rule(), listOf(1, 2, 3, 4)))
    }
}
''',
    encoding="utf-8",
)

# Bump APK so field devices can identify this fallback-parity build.
gradle = ROOT / "android-app/app/build.gradle.kts"
g = gradle.read_text(encoding="utf-8")
g = g.replace("versionCode = 109", "versionCode = 110", 1)
g = g.replace('versionName = "0.104.17"', 'versionName = "0.104.18"', 1)
gradle.write_text(g, encoding="utf-8")

# Do not mutate .github/workflows here. The Actions token has Contents:write but
# lacks workflows permission, and changing a workflow makes GitHub reject push.
print("Round104.18 spatial POS identity patch applied")
