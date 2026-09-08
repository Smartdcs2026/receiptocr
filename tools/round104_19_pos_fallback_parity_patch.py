from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"Expected block not found: {label}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# PosEvidenceFusion: route fallback POS identity through the same resolver used
# by the strict/template/profile paths and keep the raw prefix long enough for
# brand mappings such as N01/B01 to remain meaningful.
# -----------------------------------------------------------------------------
fusion_path = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt"
fusion = fusion_path.read_text(encoding="utf-8")

if "import com.receiptocr.app.config.PosIdentityRule" not in fusion:
    fusion = fusion.replace(
        "import com.receiptocr.app.config.OcrTemplateField\n",
        "import com.receiptocr.app.config.OcrTemplateField\nimport com.receiptocr.app.config.PosIdentityRule\n",
        1,
    )

fusion = replace_once(
    fusion,
    """        imagePath: String,\n        templates: List<UniversalOcrTemplate>\n    ): UniversalTemplateResult {\n""",
    """        imagePath: String,\n        templates: List<UniversalOcrTemplate>,\n        posIdentityRule: PosIdentityRule = PosIdentityRule()\n    ): UniversalTemplateResult {\n""",
    "fusion apply signature",
)

fusion = replace_once(
    fusion,
    """        val allowedPos = records.map { it.posNumber }.toSet()\n""",
    """        val allowedPos = records.map { it.posNumber }.filter { it > 0 }.toSet()\n""",
    "fusion allowed POS",
)

fusion = replace_once(
    fusion,
    """        val evidence = templates\n            .filter { it.active }\n            .flatMap { template -> collectTemplateEvidence(template, candidates, allowedPos, workDate) }\n""",
    """        val evidence = templates\n            .filter { it.active }\n            .flatMap { template ->\n                collectTemplateEvidence(\n                    template = template,\n                    candidates = candidates,\n                    allowedPos = allowedPos,\n                    referenceDate = workDate,\n                    posIdentityRule = posIdentityRule\n                )\n            }\n""",
    "fusion evidence call",
)

helper_marker = """    /** Pure helper for unit tests. */\n    internal fun fuseTextPasses(\n"""
helper_block = """    /** Round104.19: one resolver for every OCR fallback path. */\n    internal fun resolveEvidencePosIdentity(\n        raw: String,\n        posIdentityRule: PosIdentityRule,\n        allowedPos: Collection<Int>\n    ): Int? = PosIdentityResolver.resolve(raw, posIdentityRule, allowedPos)\n        ?.workPos\n        ?.takeIf { it > 0 && it in allowedPos }\n\n    /** Pure helper for unit tests. */\n    internal fun fuseTextPasses(\n"""
if "internal fun resolveEvidencePosIdentity(" not in fusion:
    if helper_marker not in fusion:
        raise SystemExit("Expected block not found: fusion helper marker")
    fusion = fusion.replace(helper_marker, helper_block, 1)

fusion = replace_once(
    fusion,
    """        allowedPos: Set<Int>,\n        referenceDate: LocalDate\n    ): Map<Int, Map<String, String>> {\n        val candidates = buildLocalCandidates(rawTexts)\n        val evidence = collectTemplateEvidence(template, candidates, allowedPos, referenceDate)\n""",
    """        allowedPos: Set<Int>,\n        referenceDate: LocalDate,\n        posIdentityRule: PosIdentityRule = PosIdentityRule()\n    ): Map<Int, Map<String, String>> {\n        val candidates = buildLocalCandidates(rawTexts)\n        val evidence = collectTemplateEvidence(\n            template = template,\n            candidates = candidates,\n            allowedPos = allowedPos,\n            referenceDate = referenceDate,\n            posIdentityRule = posIdentityRule\n        )\n""",
    "fusion test helper signature",
)

fusion = replace_once(
    fusion,
    """        template: UniversalOcrTemplate,\n        candidates: List<LocalCandidate>,\n        allowedPos: Set<Int>,\n        referenceDate: LocalDate\n    ): List<Evidence> {\n""",
    """        template: UniversalOcrTemplate,\n        candidates: List<LocalCandidate>,\n        allowedPos: Set<Int>,\n        referenceDate: LocalDate,\n        posIdentityRule: PosIdentityRule\n    ): List<Evidence> {\n""",
    "fusion collect signature",
)

fusion = replace_once(
    fusion,
    """                    val fields = extract(prefix.captureTypes, match)\n                    val pos = fields[\"POS_NUMBER\"]?.let(OcrTextNormalizer::parsePosNumber)\n                        ?: return@forEach\n                    if (pos <= 0 || pos !in allowedPos) return@forEach\n""",
    """                    val fields = extract(prefix.captureTypes, match)\n                    val rawPos = fields[\"POS_NUMBER\"].orEmpty()\n                    val pos = resolveEvidencePosIdentity(rawPos, posIdentityRule, allowedPos)\n                        ?: return@forEach\n""",
    "fusion POS resolution",
)

fusion = replace_once(
    fusion,
    """            \"POS_NUMBER\" -> OcrTextNormalizer.normalizeDigits(compact).filter(Char::isDigit)\n""",
    """            \"POS_NUMBER\" -> OcrTextNormalizer.displayPosIdentity(compact) ?: compact\n""",
    "fusion preserve POS identity",
)

fusion_path.write_text(fusion, encoding="utf-8")


# -----------------------------------------------------------------------------
# TemplateSequenceFallback: resolve the POS once with store/brand context and
# carry that resolved work POS through consensus instead of reparsing raw text.
# -----------------------------------------------------------------------------
sequence_path = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/TemplateSequenceFallback.kt"
sequence = sequence_path.read_text(encoding="utf-8")

if "import com.receiptocr.app.config.PosIdentityRule" not in sequence:
    sequence = sequence.replace(
        "import com.receiptocr.app.config.OcrTemplateField\n",
        "import com.receiptocr.app.config.OcrTemplateField\nimport com.receiptocr.app.config.PosIdentityRule\n",
        1,
    )

sequence = replace_once(
    sequence,
    """    private data class Candidate(\n        val template: UniversalOcrTemplate,\n        val fields: Map<String, String>,\n        val score: Int\n    )\n""",
    """    private data class Candidate(\n        val template: UniversalOcrTemplate,\n        val fields: Map<String, String>,\n        val workPos: Int,\n        val score: Int\n    )\n""",
    "sequence candidate work POS",
)

sequence = replace_once(
    sequence,
    """        imagePath: String,\n        templates: List<UniversalOcrTemplate>\n    ): UniversalTemplateResult {\n        val allowedPos = records.map { it.posNumber }.toSet()\n""",
    """        imagePath: String,\n        templates: List<UniversalOcrTemplate>,\n        posIdentityRule: PosIdentityRule = PosIdentityRule()\n    ): UniversalTemplateResult {\n        val allowedPos = records.map { it.posNumber }.filter { it > 0 }.toSet()\n""",
    "sequence apply signature",
)

sequence = replace_once(
    sequence,
    """                        val fields = extract(compiled, result)\n                        val pos = fields[\"POS_NUMBER\"]?.let(OcrTextNormalizer::parsePosNumber)\n                            ?: return@mapNotNull null\n                        if (compiled.template.validation.pos.mustExistInStorePlan && pos !in allowedPos) {\n                            return@mapNotNull null\n                        }\n""",
    """                        val fields = extract(compiled, result)\n                        val rawPos = fields[\"POS_NUMBER\"].orEmpty()\n                        val pos = resolveSequencePosIdentity(rawPos, posIdentityRule, allowedPos)\n                            ?: return@mapNotNull null\n                        if (compiled.template.validation.pos.mustExistInStorePlan && pos !in allowedPos) {\n                            return@mapNotNull null\n                        }\n""",
    "sequence POS resolution",
)

sequence = replace_once(
    sequence,
    """                        Candidate(\n                            template = compiled.template,\n                            fields = fields,\n                            score = compiled.template.priority +\n""",
    """                        Candidate(\n                            template = compiled.template,\n                            fields = fields,\n                            workPos = pos,\n                            score = compiled.template.priority +\n""",
    "sequence candidate assignment",
)

sequence = replace_once(
    sequence,
    """        val bestByPos = matches\n            .mapNotNull { candidate ->\n                OcrTextNormalizer.parsePosNumber(candidate.fields[\"POS_NUMBER\"].orEmpty())?.let { it to candidate }\n            }\n            .groupBy({ it.first }, { it.second })\n""",
    """        val bestByPos = matches\n            .map { candidate -> candidate.workPos to candidate }\n            .groupBy({ it.first }, { it.second })\n""",
    "sequence consensus work POS",
)

sequence_helper_marker = """    fun apply(\n        rawTexts: List<String>,\n"""
sequence_helper_block = """    /** Round104.19: keep sequence fallback aligned with every other OCR path. */\n    internal fun resolveSequencePosIdentity(\n        raw: String,\n        posIdentityRule: PosIdentityRule,\n        allowedPos: Collection<Int>\n    ): Int? = PosIdentityResolver.resolve(raw, posIdentityRule, allowedPos)\n        ?.workPos\n        ?.takeIf { it > 0 && it in allowedPos }\n\n    fun apply(\n        rawTexts: List<String>,\n"""
if "internal fun resolveSequencePosIdentity(" not in sequence:
    if sequence_helper_marker not in sequence:
        raise SystemExit("Expected block not found: sequence helper marker")
    sequence = sequence.replace(sequence_helper_marker, sequence_helper_block, 1)

sequence_path.write_text(sequence, encoding="utf-8")


# -----------------------------------------------------------------------------
# Pipeline: supply the exact runtime identity rule that already contains the
# current store's last POS context.
# -----------------------------------------------------------------------------
pipeline_path = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt"
pipeline = pipeline_path.read_text(encoding="utf-8")

pipeline = replace_once(
    pipeline,
    """                imagePath = imagePath,\n                templates = templates\n            )\n""",
    """                imagePath = imagePath,\n                templates = templates,\n                posIdentityRule = runtimePosIdentityRule\n            )\n""",
    "pipeline evidence fusion identity rule",
)

# There are two nearby calls with the same tail. After the first replacement,
# replace the remaining sequence-fallback call tail once.
pipeline = replace_once(
    pipeline,
    """                imagePath = imagePath,\n                templates = templates\n            )\n""",
    """                imagePath = imagePath,\n                templates = templates,\n                posIdentityRule = runtimePosIdentityRule\n            )\n""",
    "pipeline sequence fallback identity rule",
)

pipeline_path.write_text(pipeline, encoding="utf-8")


# -----------------------------------------------------------------------------
# Round104.19 regression coverage: verify both real fallback paths, contextual
# 8/B recovery, and preservation of legitimate numeric POS 8/801.
# -----------------------------------------------------------------------------
test_path = ROOT / "android-app/app/src/test/java/com/receiptocr/app/ocr/PosFallbackIdentityRound10419Test.kt"
test_path.write_text(
'''package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRequiredCore
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.OcrTemplateValidation
import com.receiptocr.app.config.PosIdentityMapping
import com.receiptocr.app.config.PosIdentityRule
import com.receiptocr.app.config.UniversalOcrTemplate
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosFallbackIdentityRound10419Test {
    private fun identityRule() = PosIdentityRule(
        enabled = true,
        allowedPrefixes = listOf("N", "B"),
        lastWorkPosPrefixes = listOf("B"),
        mappings = listOf(
            PosIdentityMapping("N01", workPos = 1),
            PosIdentityMapping("N02", workPos = 2)
        )
    )

    private val terminalTemplate = UniversalOcrTemplate(
        templateId = "terminal-r10419",
        brandId = "test-brand",
        templateName = "Terminal fallback",
        recognition = OcrTemplateRecognition(
            rowCount = 1,
            rows = listOf(
                OcrTemplateRow(
                    row = 1,
                    fields = listOf(
                        OcrTemplateField(
                            order = 1,
                            type = "POS_NUMBER",
                            example = "B01",
                            minLength = 3,
                            maxLength = 3,
                            posPrefixes = "B",
                            posDigits = 2
                        ),
                        OcrTemplateField(order = 2, type = "CUSTOMER_VALUE", example = "123456", minLength = 6, maxLength = 6),
                        OcrTemplateField(
                            order = 3,
                            type = "BILL_DATE",
                            example = "20/08/69",
                            dateOrder = "DMY",
                            dateCalendar = "BUDDHIST",
                            dateYearDigits = 2
                        ),
                        OcrTemplateField(order = 4, type = "BILL_TIME", example = "17:18")
                    )
                )
            )
        ),
        validation = OcrTemplateValidation(
            requiredCore = OcrTemplateRequiredCore(date = true, time = true, customerValue = true)
        )
    )

    private fun work() = WorkItem(
        id = 1,
        brand = "TEST",
        brandAbbr = "T",
        businessType = "",
        storeCode = "1001",
        storeName = "Test store",
        posCount = 3,
        openClose = "",
        address = "",
        storeFormat = "",
        rank = "",
        latitude = "",
        longitude = ""
    )

    @Test
    fun evidenceFusionResolvesB01And801ToLastStorePos() {
        val b01 = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf("B01 123456 20/08/69 17:18"),
            template = terminalTemplate,
            allowedPos = setOf(1, 2, 3),
            referenceDate = LocalDate.of(2026, 9, 2),
            posIdentityRule = identityRule()
        )
        val misread801 = PosEvidenceFusion.fuseTextPasses(
            rawTexts = listOf("801 123456 20/08/69 17:18"),
            template = terminalTemplate,
            allowedPos = setOf(1, 2, 3),
            referenceDate = LocalDate.of(2026, 9, 2),
            posIdentityRule = identityRule()
        )

        assertTrue(b01.containsKey(3))
        assertTrue(misread801.containsKey(3))
        assertEquals("123456", misread801.getValue(3)["CUSTOMER_VALUE"])
    }

    @Test
    fun sequenceFallbackUsesResolvedWorkPosInsteadOfReparsingRawIdentity() {
        val result = TemplateSequenceFallback.apply(
            rawTexts = listOf("801 123456 20/08/69 17:18"),
            records = listOf(PosRecord(1), PosRecord(2), PosRecord(3)),
            work = work(),
            workDate = LocalDate.of(2026, 9, 2),
            imagePath = "round10419-test.jpg",
            templates = listOf(terminalTemplate),
            posIdentityRule = identityRule()
        )

        assertEquals(listOf(3), result.detectedPos)
        assertEquals("123456", result.records.first { it.posNumber == 3 }.customerNo)
        assertEquals("20/08/2026", result.records.first { it.posNumber == 3 }.billDate)
        assertEquals("17:18", result.records.first { it.posNumber == 3 }.billTime)
    }

    @Test
    fun realNumericEightAnd801StayNumericWhenTheyExistInStorePlan() {
        assertEquals(
            8,
            PosEvidenceFusion.resolveEvidencePosIdentity("8", identityRule(), setOf(1, 2, 3, 8))
        )
        assertEquals(
            801,
            PosEvidenceFusion.resolveEvidencePosIdentity("801", identityRule(), setOf(1, 2, 3, 801))
        )
        assertEquals(
            801,
            TemplateSequenceFallback.resolveSequencePosIdentity("801", identityRule(), setOf(1, 2, 3, 801))
        )
    }
}
''',
    encoding="utf-8",
)


# Version bump: field devices can distinguish the fallback-parity build.
gradle_path = ROOT / "android-app/app/build.gradle.kts"
gradle = gradle_path.read_text(encoding="utf-8")
gradle = gradle.replace("versionCode = 110", "versionCode = 111", 1)
gradle = gradle.replace('versionName = "0.104.18"', 'versionName = "0.104.19"', 1)
if "versionCode = 111" not in gradle or 'versionName = "0.104.19"' not in gradle:
    raise SystemExit("Round104.19 version bump failed")
gradle_path.write_text(gradle, encoding="utf-8")

# Keep the shared terminal POS regression test aligned with the intentional APK bump.
js_test_path = ROOT / "tests/round10417-pos-terminal.test.js"
js_test = js_test_path.read_text(encoding="utf-8")
js_test = js_test.replace("versionCode = 110", "versionCode = 111", 1)
js_test = js_test.replace('versionName = \\"0.104.18\\"', 'versionName = \\"0.104.19\\"', 1)
js_test = js_test.replace("Android versionCode remains Round104.18", "Android versionCode is Round104.19", 1)
js_test = js_test.replace("Android versionName remains 0.104.18", "Android versionName is 0.104.19", 1)
js_test_path.write_text(js_test, encoding="utf-8")

print("Round104.19 POS fallback identity parity patch applied")
