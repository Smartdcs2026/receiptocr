from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str):
    text = path.read_text(encoding="utf-8")
    if new in text:
        return False
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    return True


engine = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/RuleDrivenOcrEngine.kt"
text = engine.read_text(encoding="utf-8")

if "import com.receiptocr.app.config.PosIdentityRule" not in text:
    text = text.replace(
        "import com.receiptocr.app.config.OcrRegionRule\n",
        "import com.receiptocr.app.config.OcrRegionRule\nimport com.receiptocr.app.config.PosIdentityRule\n",
        1,
    )

old_call = """        val rules = profile.regions.sortedBy { it.priority }\n        val posRules = rules.filter { it.fieldType == OcrFieldType.POS_NUMBER }\n        val posCandidates = findPosCandidates(items, posRules)\n            .filter { candidate -> records.any { it.posNumber == candidate.posNumber } }\n"""
new_call = """        val rules = profile.regions.sortedBy { it.priority }\n        val posRules = rules.filter { it.fieldType == OcrFieldType.POS_NUMBER }\n        val availableWorkPos = records.map { it.posNumber }.filter { it > 0 }.toSet()\n        val posCandidates = findPosCandidates(\n            items = items,\n            rules = posRules,\n            posIdentityRule = receiptRule.posIdentityRule,\n            availableWorkPos = availableWorkPos\n        )\n            .filter { candidate -> records.any { it.posNumber == candidate.posNumber } }\n"""
if new_call not in text:
    if old_call not in text:
        raise SystemExit("RuleDriven apply POS call block not found")
    text = text.replace(old_call, new_call, 1)

old_find = """    private fun findPosCandidates(\n        items: List<SpatialOcrItem>,\n        rules: List<OcrRegionRule>\n    ): List<PosAnchor> {\n        val result = mutableListOf<PosAnchor>()\n        val activeRules = if (rules.isEmpty()) emptyList() else rules\n\n        activeRules.forEach { rule ->\n            val regionItems = items.filter { inside(it.box, rule.region) }\n            val regex = rule.regexPattern?.let { safeRegex(it) }\n\n            regionItems.forEach { item ->\n                regex?.findAll(item.text)?.forEach { match ->\n                    val captured = match.groupValues.getOrNull(1).orEmpty().ifBlank { match.value }\n                    OcrTextNormalizer.parsePosNumber(captured)?.let { number ->\n                        result += PosAnchor(number, item.centerX, item.centerY, item.lineIndex)\n                    }\n                }\n\n                OcrTextNormalizer.findPosNumbers(item.text).forEach { number ->\n                    result += PosAnchor(number, item.centerX, item.centerY, item.lineIndex)\n                }\n\n                val isLabel = rule.labelHints.any { item.text.contains(it, ignoreCase = true) } ||\n                    Regex(\"(?i)P\\\\s*\\\\.?\\\\s*O\\\\s*\\\\.?\\\\s*S|TERMINAL|เครื่อง\").containsMatchIn(item.text)\n                if (isLabel && OcrTextNormalizer.findPosNumbers(item.text).isEmpty()) {\n                    regionItems.asSequence()\n                        .filter { candidate -> candidate !== item }\n                        .filter { candidate ->\n                            abs(candidate.centerY - item.centerY) <= rule.searchRadiusY.coerceAtLeast(0.035f)\n                        }\n                        .sortedBy { candidate ->\n                            abs(candidate.centerY - item.centerY) + abs(candidate.centerX - item.centerX) * 0.35f\n                        }\n                        .mapNotNull { candidate ->\n                            OcrTextNormalizer.parseStandalonePosNumber(candidate.text)?.let { it to candidate }\n                        }\n                        .firstOrNull()\n                        ?.let { (number, candidate) ->\n                            result += PosAnchor(number, candidate.centerX, candidate.centerY, candidate.lineIndex)\n                        }\n                }\n            }\n        }\n        return result.distinctBy { \"${it.posNumber}|${it.centerX}|${it.centerY}\" }\n    }\n"""
new_find = """    /**\n     * Round104.18: profile/ROI fallback must resolve POS identity with the same\n     * brand rule as the universal-template path. This closes the case where\n     * dot-matrix B01 is read as 801/8O1 after the strict template path misses.\n     * The resolver remains contextual and never performs a global 8 -> B swap.\n     */\n    internal fun resolveSpatialPosCandidate(\n        raw: String,\n        posIdentityRule: PosIdentityRule,\n        availableWorkPos: Collection<Int>\n    ): Int? = PosIdentityResolver.resolve(raw, posIdentityRule, availableWorkPos)?.workPos\n\n    internal fun spatialPosIdentityCandidates(value: String): List<String> {\n        val prefixed = Regex(\"(?i)\\\\b[NB]\\\\s*[0-9OoIl|]{1,3}\\\\b\")\n            .findAll(value)\n            .map { it.value }\n            .toList()\n        if (prefixed.isNotEmpty()) return prefixed.distinct()\n\n        OcrTextNormalizer.displayPosIdentity(value)?.let { return listOf(it) }\n        return OcrTextNormalizer.findPosNumbers(value).map(Int::toString).distinct()\n    }\n\n    private fun findPosCandidates(\n        items: List<SpatialOcrItem>,\n        rules: List<OcrRegionRule>,\n        posIdentityRule: PosIdentityRule,\n        availableWorkPos: Collection<Int>\n    ): List<PosAnchor> {\n        val result = mutableListOf<PosAnchor>()\n        val activeRules = if (rules.isEmpty()) emptyList() else rules\n\n        activeRules.forEach { rule ->\n            val regionItems = items.filter { inside(it.box, rule.region) }\n            val regex = rule.regexPattern?.let { safeRegex(it) }\n\n            regionItems.forEach { item ->\n                regex?.findAll(item.text)?.forEach { match ->\n                    val captured = match.groupValues.getOrNull(1).orEmpty().ifBlank { match.value }\n                    resolveSpatialPosCandidate(captured, posIdentityRule, availableWorkPos)?.let { number ->\n                        result += PosAnchor(number, item.centerX, item.centerY, item.lineIndex)\n                    }\n                }\n\n                val identityCandidates = spatialPosIdentityCandidates(item.text)\n                identityCandidates.forEach { rawIdentity ->\n                    resolveSpatialPosCandidate(rawIdentity, posIdentityRule, availableWorkPos)?.let { number ->\n                        result += PosAnchor(number, item.centerX, item.centerY, item.lineIndex)\n                    }\n                }\n\n                val isLabel = rule.labelHints.any { item.text.contains(it, ignoreCase = true) } ||\n                    Regex(\"(?i)P\\\\s*\\\\.?\\\\s*O\\\\s*\\\\.?\\\\s*S|TERMINAL|เครื่อง\").containsMatchIn(item.text)\n                if (isLabel && identityCandidates.isEmpty()) {\n                    regionItems.asSequence()\n                        .filter { candidate -> candidate !== item }\n                        .filter { candidate ->\n                            abs(candidate.centerY - item.centerY) <= rule.searchRadiusY.coerceAtLeast(0.035f)\n                        }\n                        .sortedBy { candidate ->\n                            abs(candidate.centerY - item.centerY) + abs(candidate.centerX - item.centerX) * 0.35f\n                        }\n                        .mapNotNull { candidate ->\n                            resolveSpatialPosCandidate(candidate.text, posIdentityRule, availableWorkPos)\n                                ?.let { it to candidate }\n                        }\n                        .firstOrNull()\n                        ?.let { (number, candidate) ->\n                            result += PosAnchor(number, candidate.centerX, candidate.centerY, candidate.lineIndex)\n                        }\n                }\n            }\n        }\n        return result.distinctBy { \"${it.posNumber}|${it.centerX}|${it.centerY}\" }\n    }\n"""
if new_find not in text:
    if old_find not in text:
        raise SystemExit("RuleDriven findPosCandidates block not found")
    text = text.replace(old_find, new_find, 1)

engine.write_text(text, encoding="utf-8")

# Add direct unit coverage for the legacy profile/ROI fallback bridge.
test_path = ROOT / "android-app/app/src/test/java/com/receiptocr/app/ocr/RuleDrivenPosIdentityRound10418Test.kt"
test_path.write_text(
'''package com.receiptocr.app.ocr\n\nimport com.receiptocr.app.config.PosIdentityMapping\nimport com.receiptocr.app.config.PosIdentityRule\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass RuleDrivenPosIdentityRound10418Test {\n    private fun rule(lastWorkPos: Int = 0) = PosIdentityRule(\n        enabled = true,\n        allowedPrefixes = listOf("N", "B"),\n        lastWorkPosPrefixes = listOf("B"),\n        mappings = listOf(\n            PosIdentityMapping("N01", workPos = 1),\n            PosIdentityMapping("N02", workPos = 2)\n        ),\n        runtimeLastWorkPos = lastWorkPos\n    )\n\n    @Test fun profile_fallback_b01_uses_last_store_pos() {\n        assertEquals(3, RuleDrivenOcrEngine.resolveSpatialPosCandidate("B01", rule(), listOf(1, 2, 3)))\n    }\n\n    @Test fun profile_fallback_801_recovers_b01_with_store_context() {\n        assertEquals(3, RuleDrivenOcrEngine.resolveSpatialPosCandidate("801", rule(), listOf(1, 2, 3)))\n        assertEquals(5, RuleDrivenOcrEngine.resolveSpatialPosCandidate("8O1", rule(), listOf(1, 2, 3, 4, 5)))\n    }\n\n    @Test fun profile_fallback_preserves_real_numeric_pos_8_and_801() {\n        assertEquals(8, RuleDrivenOcrEngine.resolveSpatialPosCandidate("8", rule(), listOf(1, 2, 3, 8)))\n        assertEquals(801, RuleDrivenOcrEngine.resolveSpatialPosCandidate("801", rule(), listOf(1, 2, 3, 801)))\n    }\n\n    @Test fun prefixed_candidate_extraction_keeps_letters_for_brand_mapping() {\n        val values = RuleDrivenOcrEngine.spatialPosIdentityCandidates("BNO:S26080652 B01-004184 N02")\n        assertTrue(values.contains("B01"))\n        assertTrue(values.contains("N02"))\n    }\n\n    @Test fun standalone_ambiguous_numeric_candidate_reaches_contextual_resolver() {\n        assertEquals(listOf("801"), RuleDrivenOcrEngine.spatialPosIdentityCandidates("801"))\n        assertEquals(4, RuleDrivenOcrEngine.resolveSpatialPosCandidate("801", rule(), listOf(1, 2, 3, 4)))\n    }\n}\n''',
    encoding="utf-8",
)

# Bump APK so a field device can clearly install/identify the new fallback-parity build.
gradle = ROOT / "android-app/app/build.gradle.kts"
g = gradle.read_text(encoding="utf-8")
g = g.replace("versionCode = 109", "versionCode = 110", 1)
g = g.replace('versionName = "0.104.17"', 'versionName = "0.104.18"', 1)
gradle.write_text(g, encoding="utf-8")

# Keep the full regression guard aligned with the intentional new OCR file.
workflow = ROOT / ".github/workflows/round104-finalize.yml"
w = workflow.read_text(encoding="utf-8")
w = w.replace("Verify Round104.17 Android version and direct evidence upload", "Verify Round104.18 Android version and direct evidence upload")
w = w.replace("versionCode = 109", "versionCode = 110")
w = w.replace('versionName = \\\"0.104.17\\\"', 'versionName = \\\"0.104.18\\\"')
w = w.replace("Protect Round103 OCR baseline and allow Round104.17 POS identity files", "Protect Round103 OCR baseline and allow Round104.18 POS identity files")
w = w.replace(
    "(PosIdentityResolver|RealOcrPipeline)\\\\.kt$",
    "(PosIdentityResolver|RealOcrPipeline|RuleDrivenOcrEngine)\\\\.kt$",
)
needle = "          grep -q 'runtimeLastWorkPos = expectedPosSet.maxOrNull() ?: 0' android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt\n"
addition = needle + "          grep -q 'resolveSpatialPosCandidate' android-app/app/src/main/java/com/receiptocr/app/ocr/RuleDrivenOcrEngine.kt\n          grep -q 'PosIdentityResolver.resolve(raw, posIdentityRule, availableWorkPos)' android-app/app/src/main/java/com/receiptocr/app/ocr/RuleDrivenOcrEngine.kt\n"
if "grep -q 'resolveSpatialPosCandidate'" not in w:
    if needle not in w:
        raise SystemExit("round104-finalize insertion point not found")
    w = w.replace(needle, addition, 1)
w = w.replace("Upload Round104.17 debug APK", "Upload Round104.18 debug APK")
w = w.replace("name: ReceiptOCR-Round104-17-debug", "name: ReceiptOCR-Round104-18-debug")
workflow.write_text(w, encoding="utf-8")

print("Round104.18 spatial POS identity patch applied")
