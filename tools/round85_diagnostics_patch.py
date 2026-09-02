from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    if old not in text:
        raise RuntimeError(f"Round85 patch marker not found: {label}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# 1) TemplateSequenceFallback: add non-invasive diagnostics.
# ---------------------------------------------------------------------------
parser_path = "android-app/app/src/main/java/com/receiptocr/app/ocr/TemplateSequenceFallback.kt"
parser = read(parser_path)

parser = replace_once(
    parser,
    """    private data class Candidate(\n        val template: UniversalOcrTemplate,\n        val fields: Map<String, String>,\n        val score: Int\n    )\n""",
    """    private data class Candidate(\n        val template: UniversalOcrTemplate,\n        val fields: Map<String, String>,\n        val score: Int\n    )\n\n    private data class DiagnosticField(\n        val rowIndex: Int,\n        val field: OcrTemplateField\n    )\n""",
    "parser diagnostic field model",
)

diagnostics_block = r'''
    /**
     * Round85: อธิบายว่าข้อความจากภาพจริงไปได้ถึงช่องใดของรูปแบบที่ Admin กำหนด
     * ฟังก์ชันนี้ไม่มีผลต่อการตัดสินผล OCR ใช้เพื่อแสดงรายละเอียดเมื่ออ่านไม่สำเร็จเท่านั้น
     */
    fun diagnose(
        rawTexts: List<String>,
        templates: List<UniversalOcrTemplate>
    ): List<String> {
        val candidates = buildTextCandidates(rawTexts)
        if (candidates.isEmpty()) return listOf("ไม่พบข้อความจากภาพสำหรับตรวจรายละเอียด")

        val activeTemplates = templates.filter { it.active }.sortedByDescending { it.priority }
        if (activeTemplates.isEmpty()) return listOf("ยังไม่มีรูปแบบบิลที่เปิดใช้งานสำหรับแบรนด์นี้")

        return activeTemplates.map { template ->
            val compiled = compileTemplate(template)
            if (compiled == null) {
                return@map "${template.templateName}: รูปแบบบิลนี้ยังไม่พร้อมสำหรับการอ่าน"
            }

            val fullMatches = candidates.flatMap { candidate ->
                compiled.regex.findAll(candidate).map { it }.toList()
            }
            val complete = fullMatches.firstOrNull { result ->
                coreFieldsArePlausible(extract(compiled, result), template)
            }
            if (complete != null) {
                return@map "${template.templateName}: อ่านลำดับครบและข้อมูลหลักอยู่ในรูปแบบที่ใช้ได้"
            }

            if (fullMatches.isNotEmpty()) {
                val fields = extract(compiled, fullMatches.first())
                return@map "${template.templateName}: พบลำดับครบ แต่${plausibilityIssue(fields, template)}"
            }

            diagnosticProgress(template, candidates)
        }
    }

'''
parser = replace_once(
    parser,
    "    private fun failed(records: List<PosRecord>) = UniversalTemplateResult(\n",
    diagnostics_block + "    private fun failed(records: List<PosRecord>) = UniversalTemplateResult(\n",
    "insert parser diagnose function",
)

helpers_block = r'''
    private fun plausibilityIssue(
        fields: Map<String, String>,
        template: UniversalOcrTemplate
    ): String {
        val pos = fields["POS_NUMBER"]?.let(OcrTextNormalizer::parsePosNumber)
        if (pos == null || pos <= 0) return "หมายเลขเครื่องยังไม่อยู่ในรูปแบบที่ใช้ได้"

        val core = template.validation.requiredCore
        if (core.customerValue && fields["CUSTOMER_VALUE"].isNullOrBlank()) {
            return "ยังไม่พบยอด/เลขลูกค้า"
        }
        if (core.date && fields["BILL_DATE"].isNullOrBlank()) {
            return "ยังไม่พบวันที่ในบิล"
        }
        if (core.time && fields["BILL_TIME"].isNullOrBlank()) {
            return "ยังไม่พบเวลาในบิล"
        }
        val time = fields["BILL_TIME"]
        if (!time.isNullOrBlank() && !isValidClockTime(time)) {
            return "เวลาที่อ่านได้ ${normalizeCaptured("BILL_TIME", time)} ใช้ไม่ได้"
        }
        return "ข้อมูลหลักบางช่องยังไม่ผ่านการตรวจ"
    }

    private fun diagnosticProgress(
        template: UniversalOcrTemplate,
        candidates: List<String>
    ): String {
        val fields = template.recognition.rows
            .sortedBy { it.row }
            .flatMapIndexed { rowIndex, row ->
                row.fields.sortedBy { it.order }.map { DiagnosticField(rowIndex, it) }
            }
        if (fields.isEmpty()) return "${template.templateName}: ยังไม่มีช่องข้อมูลในรูปแบบบิล"

        var matchedCount = 0
        for (count in 1..fields.size) {
            val regex = compileDiagnosticPrefix(template, count) ?: break
            if (candidates.any { regex.containsMatchIn(it) }) matchedCount = count else break
        }

        if (matchedCount <= 0) {
            val first = fields.first().field
            return "${template.templateName}: ยังไม่ผ่านช่อง 1 ${diagnosticFieldLabel(first)}${diagnosticExpectation(first)}"
        }
        if (matchedCount >= fields.size) {
            return "${template.templateName}: ลำดับช่องตรงครบ แต่ข้อมูลหลักบางช่องยังไม่ผ่านการตรวจ"
        }

        val previous = fields[matchedCount - 1].field
        val next = fields[matchedCount].field
        return "${template.templateName}: อ่านผ่านถึงช่อง $matchedCount ${diagnosticFieldLabel(previous)} • " +
            "หยุดก่อนช่อง ${matchedCount + 1} ${diagnosticFieldLabel(next)}${diagnosticExpectation(next)}"
    }

    private fun compileDiagnosticPrefix(
        template: UniversalOcrTemplate,
        fieldLimit: Int
    ): Regex? {
        val ordered = template.recognition.rows
            .sortedBy { it.row }
            .flatMapIndexed { rowIndex, row ->
                row.fields.sortedBy { it.order }.map { DiagnosticField(rowIndex, it) }
            }
            .take(fieldLimit)
        if (ordered.isEmpty()) return null

        val captures = mutableListOf<String>()
        val parts = mutableListOf<String>()
        var previousRow = -1
        ordered.forEachIndexed { index, item ->
            if (index > 0) parts += if (item.rowIndex == previousRow) FIELD_GAP else ROW_GAP
            val built = fieldPattern(item.field, captures) ?: return null
            val tokenGap = if (item.field.tokenGap > 0) {
                "(?:\\s+\\S+){0,${item.field.tokenGap.coerceIn(0, 8)}}?\\s*"
            } else ""
            parts += when {
                item.field.type == "IGNORE" -> built
                item.field.required -> tokenGap + built
                else -> "(?:$tokenGap$built)?"
            }
            previousRow = item.rowIndex
        }

        return runCatching {
            Regex(parts.joinToString(""), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        }.getOrNull()
    }

    private fun diagnosticFieldLabel(field: OcrTemplateField): String = when (field.type.uppercase()) {
        "BILL_DATE" -> "วันที่ในบิล"
        "BILL_TIME" -> "เวลาในบิล"
        "CUSTOMER_VALUE" -> "ยอด/เลขลูกค้า"
        "STORE_ID" -> "รหัสร้าน"
        "POS_NUMBER" -> "หมายเลขเครื่อง"
        "YEAR_VALUE", "YEAR" -> "ปี"
        "MONTH_VALUE", "MONTH" -> "เดือน"
        "DAY_VALUE", "DAY" -> "วัน"
        "EMPLOYEE_CODE" -> "รหัสพนักงาน"
        "COMPOSITE_CODE" -> "รหัสประกอบ"
        "LITERAL" -> "ข้อความคงที่"
        "SEPARATOR" -> "ตัวคั่น"
        "NUMBER_TEXT" -> "ตัวเลขทั่วไป"
        "ALNUM_TEXT" -> "ตัวอักษร+ตัวเลข"
        "IGNORE" -> "ข้อมูลที่ข้ามได้"
        else -> field.type
    }

    private fun diagnosticExpectation(field: OcrTemplateField): String {
        val expected = when (field.type.uppercase()) {
            "LITERAL" -> field.literal ?: field.example
            "SEPARATOR" -> field.separatorValue ?: field.example
            else -> field.example
        }?.trim().orEmpty()
        return if (expected.isBlank()) "" else " (ตัวอย่าง $expected)"
    }

'''
parser = replace_once(
    parser,
    "    private fun chooseWholeRecordConsensus(candidates: List<Candidate>): Candidate? {\n",
    helpers_block + "    private fun chooseWholeRecordConsensus(candidates: List<Candidate>): Candidate? {\n",
    "insert parser diagnostic helpers",
)
write(parser_path, parser)


# ---------------------------------------------------------------------------
# 2) RealOcrPipeline: carry diagnostic explanations only on failure.
# ---------------------------------------------------------------------------
pipeline_path = "android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt"
pipeline = read(pipeline_path)
pipeline = replace_once(
    pipeline,
    """    val canConfirm: Boolean = false,\n    val warnings: List<String> = emptyList()\n)\n""",
    """    val canConfirm: Boolean = false,\n    val warnings: List<String> = emptyList(),\n    val diagnostics: List<String> = emptyList()\n)\n""",
    "pipeline result diagnostics field",
)

old_failure = """                warnings = listOf(\n                    *imageQualityWarnings.toTypedArray(),\n                    if (templates.isEmpty()) \"ยังไม่มีเงื่อนไขสำหรับแบรนด์นี้ กรุณาแจ้งผู้ดูแล\"\n                    else \"ยังแยกข้อมูลบิลไม่ได้ครบ • ลองเพิ่มภาพบิลอีกช่องหรือถ่ายใหม่ให้ชัดขึ้น\"\n                )\n"""
new_failure = """                warnings = listOf(\n                    *imageQualityWarnings.toTypedArray(),\n                    if (templates.isEmpty()) \"ยังไม่มีเงื่อนไขสำหรับแบรนด์นี้ กรุณาแจ้งผู้ดูแล\"\n                    else \"ยังแยกข้อมูลบิลไม่ได้ครบ • ลองเพิ่มภาพบิลอีกช่องหรือถ่ายใหม่ให้ชัดขึ้น\"\n                ),\n                diagnostics = TemplateSequenceFallback.diagnose(\n                    rawTexts = mlTexts.map { it.text },\n                    templates = templates\n                )\n"""
pipeline = replace_once(pipeline, old_failure, new_failure, "pipeline failure diagnostics")
write(pipeline_path, pipeline)


# ---------------------------------------------------------------------------
# 3) UI: show a friendly diagnostic dialog automatically after OCR failure.
# ---------------------------------------------------------------------------
ui_path = "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt"
ui = read(ui_path)

ui = replace_once(
    ui,
    "import com.receiptocr.app.data.remote.WorkPlanSource\n",
    "import com.receiptocr.app.data.remote.WorkPlanSource\nimport com.receiptocr.app.config.TemplateSource\n",
    "UI TemplateSource import",
)

ui = replace_once(
    ui,
    """private data class PhotoPreviewTarget(\n    val kind: String,\n    val index: Int,\n    val path: String\n)\n""",
    """private data class PhotoPreviewTarget(\n    val kind: String,\n    val index: Int,\n    val path: String\n)\n\nprivate data class OcrReadDetails(\n    val rawText: String,\n    val templateNames: String,\n    val sourceLabel: String,\n    val updatedAt: String,\n    val diagnostics: List<String>\n)\n""",
    "UI read details model",
)

ui = replace_once(
    ui,
    """    var ocrBusy by remember { mutableStateOf(false) }\n    var pendingOcrResult by remember { mutableStateOf<RealOcrPipelineResult?>(null) }\n""",
    """    var ocrBusy by remember { mutableStateOf(false) }\n    var pendingOcrResult by remember { mutableStateOf<RealOcrPipelineResult?>(null) }\n    var ocrReadDetails by remember { mutableStateOf<OcrReadDetails?>(null) }\n    var ocrReadDetailsOpen by remember { mutableStateOf(false) }\n""",
    "UI diagnostic states",
)

old_proposal = """                if (proposal.canConfirm) pendingOcrResult = proposal\n                message = proposal.message\n                ocrBusy = false\n"""
new_proposal = """                val sourceLabel = when (loadedTemplates.source) {\n                    TemplateSource.CLOUD -> \"ข้อมูลล่าสุดจากระบบ\"\n                    TemplateSource.CACHE -> \"ข้อมูลที่บันทึกไว้ในเครื่อง\"\n                    TemplateSource.REFERENCE -> \"รูปแบบสำรองในแอป\"\n                    TemplateSource.NONE -> \"ไม่พบรูปแบบบิล\"\n                }\n                ocrReadDetails = OcrReadDetails(\n                    rawText = scan.rawText,\n                    templateNames = loadedTemplates.templates\n                        .filter { it.active }\n                        .joinToString(\" / \") { it.templateName },\n                    sourceLabel = sourceLabel,\n                    updatedAt = loadedTemplates.updatedAt.orEmpty(),\n                    diagnostics = proposal.diagnostics\n                )\n                if (proposal.canConfirm) {\n                    pendingOcrResult = proposal\n                } else {\n                    ocrReadDetailsOpen = true\n                }\n                message = proposal.message\n                ocrBusy = false\n"""
ui = replace_once(ui, old_proposal, new_proposal, "UI capture diagnostic details")

read_dialog = r'''
    if (ocrReadDetailsOpen) {
        ocrReadDetails?.let { details ->
            AlertDialog(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .widthIn(max = 560.dp),
                properties = DialogProperties(usePlatformDefaultWidth = false),
                onDismissRequest = { ocrReadDetailsOpen = false },
                icon = {
                    Icon(
                        Icons.Outlined.ReceiptLong,
                        contentDescription = null,
                        tint = Primary
                    )
                },
                title = {
                    Text(
                        "รายละเอียดการอ่าน",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 620.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = PrimarySoft,
                            border = BorderStroke(1.dp, Border)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("รูปแบบบิลที่ใช้", color = TextSub, fontSize = 11.sp)
                                Text(
                                    details.templateNames.ifBlank { "ยังไม่พบรูปแบบบิล" },
                                    color = TextMain,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text("ข้อมูลเงื่อนไข: ${details.sourceLabel}", color = TextSub, fontSize = 11.sp)
                                if (details.updatedAt.isNotBlank()) {
                                    Text("ปรับปรุงล่าสุด: ${details.updatedAt}", color = TextSub, fontSize = 10.sp)
                                }
                            }
                        }

                        if (details.diagnostics.isNotEmpty()) {
                            Text("ตรวจลำดับข้อมูล", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            details.diagnostics.forEach { line ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(9.dp),
                                    color = Color(0xFFFFF8E8),
                                    border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.35f))
                                ) {
                                    Text(
                                        line,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        color = Color(0xFF7A4B00),
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    )
                                }
                            }
                        }

                        Text("ข้อความที่เครื่องอ่านได้จากภาพ", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "ข้อความด้านล่างแสดงผลจากแต่ละรอบที่เครื่องอ่านภาพ ใช้สำหรับตรวจว่าตัวอักษรหรือเลขตัวใดถูกอ่านต่างจากบิลจริง",
                            color = TextSub,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFF7F8FA),
                            border = BorderStroke(1.dp, Border)
                        ) {
                            Text(
                                details.rawText.ifBlank { "ไม่พบข้อความที่อ่านได้" },
                                modifier = Modifier.padding(10.dp),
                                color = TextMain,
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { ocrReadDetailsOpen = false }) {
                        Text("ปิด")
                    }
                }
            )
        }
    }

'''
ui = replace_once(
    ui,
    "    pendingOcrResult?.let { proposal ->\n",
    read_dialog + "    pendingOcrResult?.let { proposal ->\n",
    "insert OCR diagnostics dialog",
)
write(ui_path, ui)


# ---------------------------------------------------------------------------
# 4) Version bump.
# ---------------------------------------------------------------------------
gradle_path = "android-app/app/build.gradle.kts"
gradle = read(gradle_path)
gradle = replace_once(gradle, 'versionCode = 85', 'versionCode = 86', "versionCode")
gradle = replace_once(gradle, 'versionName = "0.84.0"', 'versionName = "0.85.0"', "versionName")
write(gradle_path, gradle)


# ---------------------------------------------------------------------------
# 5) Diagnostic unit tests.
# ---------------------------------------------------------------------------
test_path = ROOT / "android-app/app/src/test/java/com/receiptocr/app/ocr/TemplateSequenceDiagnosticsRound85Test.kt"
test_path.write_text(r'''package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.OcrTemplateRecognition
import com.receiptocr.app.config.OcrTemplateRow
import com.receiptocr.app.config.UniversalOcrTemplate
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateSequenceDiagnosticsRound85Test {

    private val mb02 = UniversalOcrTemplate(
        templateId = "mb02-r85",
        brandId = "brand-test",
        templateName = "Mb_02",
        recognition = OcrTemplateRecognition(
            rowCount = 1,
            rows = listOf(
                OcrTemplateRow(
                    row = 1,
                    fields = listOf(
                        OcrTemplateField(order = 1, type = "LITERAL", example = "R", literal = "R"),
                        OcrTemplateField(order = 2, type = "NUMBER_TEXT", example = "10", minLength = 2, maxLength = 2),
                        OcrTemplateField(order = 3, type = "POS_NUMBER", example = "1", minLength = 1, maxLength = 1),
                        OcrTemplateField(order = 4, type = "CUSTOMER_VALUE", example = "219931", minLength = 6, maxLength = 6),
                        OcrTemplateField(order = 5, type = "LITERAL", example = "U", literal = "U"),
                        OcrTemplateField(order = 6, type = "NUMBER_TEXT", example = "400040", minLength = 6, maxLength = 6),
                        OcrTemplateField(order = 7, type = "BILL_DATE", example = "22/08/69"),
                        OcrTemplateField(order = 8, type = "BILL_TIME", example = "18:37")
                    )
                )
            )
        )
    )

    @Test
    fun reportsCompleteSequenceForRealMb02Text() {
        val detail = TemplateSequenceFallback.diagnose(
            rawTexts = listOf("R202039030U400072 20/08/69 17:18"),
            templates = listOf(mb02)
        ).joinToString(" ")

        assertTrue(detail.contains("อ่านลำดับครบ"))
    }

    @Test
    fun reportsWhereSequenceStopsWhenLiteralIsDifferent() {
        val detail = TemplateSequenceFallback.diagnose(
            rawTexts = listOf("R202039030X400072 20/08/69 17:18"),
            templates = listOf(mb02)
        ).joinToString(" ")

        assertTrue(detail.contains("หยุดก่อนช่อง 5"))
        assertTrue(detail.contains("ข้อความคงที่"))
    }

    @Test
    fun reportsImpossibleClockTimeInsteadOfAcceptingIt() {
        val detail = TemplateSequenceFallback.diagnose(
            rawTexts = listOf("R202039030U400072 20/08/69 36:00"),
            templates = listOf(mb02)
        ).joinToString(" ")

        assertTrue(detail.contains("36:00"))
        assertTrue(detail.contains("ใช้ไม่ได้"))
    }
}
''', encoding="utf-8")

print("Round85 diagnostics patch applied")
