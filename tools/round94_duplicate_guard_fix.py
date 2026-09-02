from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def write(rel: str, text: str) -> None:
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.rstrip() + "\n", encoding="utf-8")


# A different interpretation from another OCR pass is NOT proof of a second
# physical receipt. Only warn when two distinct complete receipt signatures for
# the same mapped work POS are visible as separate source lines within the same
# OCR pass. Keep Round94 brand POS identity mapping (N01/B01) intact.
write(
    "android-app/app/src/main/java/com/receiptocr/app/ocr/DuplicatePosEvidenceDetector.kt",
    r'''package com.receiptocr.app.ocr

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
'''
)

write(
    "android-app/app/src/test/java/com/receiptocr/app/ocr/DuplicatePosEvidenceDetectorTest.kt",
    r'''package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.PosIdentityMapping
import com.receiptocr.app.config.PosIdentityRule
import com.receiptocr.app.config.UniversalOcrTemplate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatePosEvidenceDetectorTest {
    private val mb = UniversalOcrTemplate(
        templateId = "mb-02-test",
        brandId = "MB",
        templateName = "Mb_02",
        recognition = OcrTemplateRecognition(
            rows = listOf(
                OcrTemplateRow(
                    row = 1,
                    fields = listOf(
                        OcrTemplateField(1, "LITERAL", example = "R", literal = "R"),
                        OcrTemplateField(2, "NUMBER_TEXT", example = "20", minLength = 2, maxLength = 2),
                        OcrTemplateField(3, "POS_NUMBER", example = "1", minLength = 1, maxLength = 3, posDigits = 1),
                        OcrTemplateField(4, "CUSTOMER_VALUE", example = "657846", minLength = 6, maxLength = 7),
                        OcrTemplateField(5, "EMPLOYEE_CODE", example = "U110030", minLength = 7, maxLength = 7),
                        OcrTemplateField(
                            6, "BILL_DATE", example = "20/08/69", minLength = 8, maxLength = 8,
                            dateOrder = "DMY", dateCalendar = "BUDDHIST", dateYearDigits = 2
                        ),
                        OcrTemplateField(7, "BILL_TIME", example = "17:51", minLength = 5, maxLength = 5)
                    )
                )
            )
        )
    )

    @Test
    fun sameReceiptRepeatedAcrossOcrPassesIsNotDuplicatePos() {
        val raw = "R201657846U110030 20/08/69 17:51"
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf(raw, raw, raw),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(1))
    }

    @Test
    fun onePhysicalReceiptWithDifferentPassInterpretationsIsNotDuplicate() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf(
                "R2020101809U110030 13/08/69 19:00",
                "R2020101809U110030 13/08/69 19:08"
            ),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(2))
    }

    @Test
    fun twoDifferentReceiptsForSamePosOnSeparateLinesInOnePassWarn() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf(
                "R201657846U110030 20/08/69 17:51\n" +
                    "R201111222U110030 21/08/69 09:05"
            ),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertTrue(warnings.containsKey(1))
    }

    @Test
    fun differentSignaturesSeenOnlyInDifferentPassesDoNotProveDuplicate() {
        val first = "R201657846U110030 20/08/69 17:51"
        val second = "R201111222U110030 21/08/69 09:05"
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf(first, second, first, second),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(1))
    }

    @Test
    fun differentPosAreNotTreatedAsDuplicate() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf(
                "R201657846U110030 20/08/69 17:51\n" +
                    "R202039030U400072 20/08/69 17:18"
            ),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(1))
        assertFalse(warnings.containsKey(2))
    }

    @Test
    fun differentTemplatesDoNotCreateFalseDuplicateForSamePos() {
        val second = mb.copy(
            templateId = "mb-alt-test",
            templateName = "Mb_alt",
            recognition = OcrTemplateRecognition(
                rows = listOf(OcrTemplateRow(row = 1, fields = listOf(
                    OcrTemplateField(1, "LITERAL", example = "X", literal = "X"),
                    OcrTemplateField(2, "NUMBER_TEXT", example = "20", minLength = 2, maxLength = 2),
                    OcrTemplateField(3, "POS_NUMBER", example = "1", minLength = 1, maxLength = 1, posDigits = 1),
                    OcrTemplateField(4, "CUSTOMER_VALUE", example = "111222", minLength = 6, maxLength = 6),
                    OcrTemplateField(5, "EMPLOYEE_CODE", example = "U110030", minLength = 7, maxLength = 7),
                    OcrTemplateField(6, "BILL_DATE", example = "20-08-69", minLength = 8, maxLength = 8, dateOrder = "DMY", dateCalendar = "BUDDHIST", dateYearDigits = 2),
                    OcrTemplateField(7, "BILL_TIME", example = "09:05", minLength = 5, maxLength = 5)
                )))
            )
        )
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf("R201657846U110030 20/08/69 17:51\nX201111222U110030 20-08-69 09:05"),
            templates = listOf(mb, second),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(1))
    }

    @Test
    fun separatorVariationAcrossPassesIsSameReceipt() {
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf("R201657846U110030 20/08/69 17:51", "R201657846U110030 20-08-69 17.51"),
            templates = listOf(mb),
            allowedPos = setOf(1, 2, 3)
        )
        assertFalse(warnings.containsKey(1))
    }

    @Test
    fun mappedN01AndB01AreDifferentWorkPosAndNotDuplicate() {
        val prefixed = UniversalOcrTemplate(
            templateId = "prefix-test",
            brandId = "P",
            templateName = "Prefix",
            recognition = OcrTemplateRecognition(
                rows = listOf(OcrTemplateRow(row = 1, fields = listOf(
                    OcrTemplateField(1, "POS_NUMBER", example = "N01", minLength = 3, maxLength = 3),
                    OcrTemplateField(2, "CUSTOMER_VALUE", example = "123456", minLength = 6, maxLength = 6),
                    OcrTemplateField(3, "BILL_DATE", example = "03/09/26", minLength = 8, maxLength = 8, dateOrder = "DMY", dateCalendar = "GREGORIAN", dateYearDigits = 2),
                    OcrTemplateField(4, "BILL_TIME", example = "10:00", minLength = 5, maxLength = 5)
                )))
            )
        )
        val rule = PosIdentityRule(
            enabled = true,
            allowedPrefixes = listOf("N", "B"),
            mappings = listOf(PosIdentityMapping("N01", 1), PosIdentityMapping("B01", 2))
        )
        val warnings = DuplicatePosEvidenceDetector.detect(
            rawTexts = listOf("N01 123456 03/09/26 10:00\nB01 654321 03/09/26 10:05"),
            templates = listOf(prefixed),
            allowedPos = setOf(1, 2),
            posIdentityRule = rule
        )
        assertFalse(warnings.containsKey(1))
        assertFalse(warnings.containsKey(2))
    }
}
'''
)
