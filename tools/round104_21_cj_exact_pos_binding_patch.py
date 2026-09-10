from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"Expected block not found: {label}")
    return text.replace(old, new, 1)


# 1) Strict universal interpreter must use the real work-plan POS set and must
# never rename an empty work row to an out-of-plan OCR POS (the observed POS8
# phantom in a 4-POS CJ store).
interp_path = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/UniversalTemplateInterpreter.kt"
interp = interp_path.read_text(encoding="utf-8")

interp = replace_once(
    interp,
    """        val bestMatches = templateMatches\n            .mapNotNull { match ->\n                val rawPos = match.fields[\"POS_NUMBER\"].orEmpty()\n                val resolved = PosIdentityResolver.resolve(rawPos, posIdentityRule) ?: return@mapNotNull null\n                resolved.workPos to match\n            }\n""",
    """        val availableWorkPos = records.map { it.posNumber }.filter { it > 0 }.toSet()\n        val bestMatches = templateMatches\n            .mapNotNull { match ->\n                val rawPos = match.fields[\"POS_NUMBER\"].orEmpty()\n                val resolved = PosIdentityResolver.resolve(rawPos, posIdentityRule, availableWorkPos)\n                    ?: return@mapNotNull null\n                resolved.workPos.takeIf { it in availableWorkPos }?.let { it to match }\n            }\n""",
    "strict resolver store POS context",
)

interp = replace_once(
    interp,
    """        bestMatches.forEach { (pos, match) ->\n            var index = updated.indexOfFirst { it.posNumber == pos }\n            if (index < 0) {\n                index = updated.indexOfFirst { record ->\n                    record.posNumber !in assignedPositions &&\n                        record.customerNo.isBlank() && record.billDate.isBlank() && record.billTime.isBlank() &&\n                        !record.noReceipt && record.ocrSourceImagePath.isBlank()\n                }\n                if (index >= 0) {\n                    updated[index] = updated[index].copy(posNumber = pos)\n                } else {\n                    unmappedPos += pos.toString()\n                    return@forEach\n                }\n            }\n""",
    """        bestMatches.forEach { (pos, match) ->\n            val index = updated.indexOfFirst { it.posNumber == pos }\n            if (index < 0) {\n                // Work Plan is authoritative. Never mutate POS1/2/3/4 into an\n                // OCR hallucination such as POS8; keep it only as diagnostic evidence.\n                unmappedPos += pos.toString()\n                return@forEach\n            }\n""",
    "strict phantom POS reassignment",
)

interp_path.write_text(interp, encoding="utf-8")


# 2) Sequence fallback must receive the exact same runtime CJ identity rule as
# strict/multi-template/evidence-fusion. Round104.20 still called the default rule.
pipeline_path = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt"
pipeline = pipeline_path.read_text(encoding="utf-8")
pipeline = replace_once(
    pipeline,
    """                imagePath = imagePath,\n                templates = templates\n            )\n        } else null\n        val templateResult = mergeUniversalTemplateResults(records, afterFusion, sequenceFallback)\n""",
    """                imagePath = imagePath,\n                templates = templates,\n                posIdentityRule = runtimePosIdentityRule\n            )\n        } else null\n        val templateResult = mergeUniversalTemplateResults(records, afterFusion, sequenceFallback)\n""",
    "sequence fallback runtime identity rule",
)
pipeline_path.write_text(pipeline, encoding="utf-8")


# 3) Field regression reproduces the actual CJ four-receipt content supplied by
# the user. Expected mapping is N01->1, N02->2, N03->3, B01->4 with no POS8.
test_path = ROOT / "android-app/app/src/test/java/com/receiptocr/app/ocr/CjFourReceiptBindingRound10421Test.kt"
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
import org.junit.Assert.assertFalse
import org.junit.Test

class CjFourReceiptBindingRound10421Test {
    private fun cjRule() = PosIdentityRule(
        enabled = true,
        allowedPrefixes = listOf("N", "B"),
        lastWorkPosPrefixes = listOf("B"),
        mappings = listOf(
            PosIdentityMapping("N01", workPos = 1),
            PosIdentityMapping("N02", workPos = 2),
            PosIdentityMapping("N03", workPos = 3)
        ),
        fallbackUnknownToLastWorkPos = true
    )

    private val template = UniversalOcrTemplate(
        templateId = "cj-four-receipts-r10421",
        brandId = "CJ",
        templateName = "CJ BNO one-line",
        recognition = OcrTemplateRecognition(
            rowCount = 1,
            rows = listOf(
                OcrTemplateRow(
                    row = 1,
                    fields = listOf(
                        OcrTemplateField(order = 1, type = "BILL_DATE", example = "05/09/2026", dateOrder = "DMY", dateCalendar = "GREGORIAN", dateYearDigits = 4),
                        OcrTemplateField(order = 2, type = "BILL_TIME", example = "17:37"),
                        OcrTemplateField(order = 3, type = "LITERAL", literal = "BNO:S"),
                        OcrTemplateField(order = 4, type = "YEAR_VALUE", example = "26", minLength = 2, maxLength = 2),
                        OcrTemplateField(order = 5, type = "MONTH_VALUE", example = "09", minLength = 2, maxLength = 2),
                        OcrTemplateField(order = 6, type = "STORE_ID", example = "0690", minLength = 4, maxLength = 4),
                        OcrTemplateField(order = 7, type = "POS_NUMBER", example = "N01", minLength = 3, maxLength = 3, posPrefixes = "N,B", posDigits = 2),
                        OcrTemplateField(order = 8, type = "SEPARATOR", separatorValue = "-"),
                        OcrTemplateField(order = 9, type = "CUSTOMER_VALUE", example = "000684", minLength = 6, maxLength = 6)
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
        brand = "CJ More",
        brandAbbr = "CJ",
        businessType = "",
        storeCode = "0690",
        storeName = "CJ field receipt test",
        posCount = 4,
        openClose = "",
        address = "",
        storeFormat = "",
        rank = "",
        latitude = "",
        longitude = "",
        receiptStoreId = "0690"
    )

    @Test
    fun actualFourReceiptTextBindsExactlyToPosOneThroughFour() {
        val result = TemplateSequenceFallback.apply(
            rawTexts = listOf(
                "05/09/2026 17:37 BNO:S26090690N01-000684",
                "05/09/2026 18:20 BNO:S26090690N02-001332",
                "05/09/2026 18:06 BNO:S26090690N03-000659",
                "05/09/2026 21:39 BNO:S26090690B01-000694"
            ),
            records = listOf(PosRecord(1), PosRecord(2), PosRecord(3), PosRecord(4)),
            work = work(),
            workDate = LocalDate.of(2026, 9, 8),
            imagePath = "cj-field-4-receipts.jpg",
            templates = listOf(template),
            posIdentityRule = cjRule()
        )

        assertEquals(listOf(1, 2, 3, 4), result.detectedPos)
        assertFalse(result.detectedPos.contains(8))

        val byPos = result.records.associateBy { it.posNumber }
        assertEquals("000684", byPos.getValue(1).customerNo)
        assertEquals("17:37", byPos.getValue(1).billTime)
        assertEquals("001332", byPos.getValue(2).customerNo)
        assertEquals("18:20", byPos.getValue(2).billTime)
        assertEquals("000659", byPos.getValue(3).customerNo)
        assertEquals("18:06", byPos.getValue(3).billTime)
        assertEquals("000694", byPos.getValue(4).customerNo)
        assertEquals("21:39", byPos.getValue(4).billTime)
        assertEquals(setOf(1, 2, 3, 4), byPos.keys)
    }

    @Test
    fun ocrEightCannotCreatePhantomPosWhenStoreHasOnlyOneToFour() {
        val available = setOf(1, 2, 3, 4)
        assertEquals(4, PosIdentityResolver.resolve("8", cjRule(), available)?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("801", cjRule(), available)?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("8O1", cjRule(), available)?.workPos)
    }
}
''',
    encoding="utf-8",
)

# 4) Version bump for a field-testable APK.
gradle_path = ROOT / "android-app/app/build.gradle.kts"
gradle = gradle_path.read_text(encoding="utf-8")
gradle = gradle.replace("versionCode = 112", "versionCode = 113", 1)
gradle = gradle.replace('versionName = "0.104.20"', 'versionName = "0.104.21"', 1)
if "versionCode = 113" not in gradle or 'versionName = "0.104.21"' not in gradle:
    raise SystemExit("Round104.21 version bump failed")
gradle_path.write_text(gradle, encoding="utf-8")

# Shared terminal guard must follow the intentional field build version.
js_path = ROOT / "tests/round10417-pos-terminal.test.js"
js = js_path.read_text(encoding="utf-8")
js = js.replace("versionCode = 112", "versionCode = 113", 1)
js = js.replace('versionName = "0.104.20"', 'versionName = "0.104.21"', 1)
js = js.replace("Round104.20", "Round104.21")
js_path.write_text(js, encoding="utf-8")

print("Round104.21 exact CJ POS binding patch applied")
