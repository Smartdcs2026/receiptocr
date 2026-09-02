from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(rel, old, new):
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"{rel}: expected one anchor, got {text.count(old)}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


interpreter = "android-app/app/src/main/java/com/receiptocr/app/ocr/UniversalTemplateInterpreter.kt"
replace_once(
    interpreter,
    '''        workDate: LocalDate,\n        imagePath: String,\n        templates: List<UniversalOcrTemplate>\n    ): UniversalTemplateResult {''',
    '''        workDate: LocalDate,\n        imagePath: String,\n        templates: List<UniversalOcrTemplate>,\n        posIdentityRule: PosIdentityRule = PosIdentityRule()\n    ): UniversalTemplateResult {'''
)

replace_once(
    interpreter,
    '''        val accepted = mutableListOf<TemplateMatch>()\n        val warningsByPos = mutableMapOf<Int, MutableList<String>>()''',
    '''        val accepted = mutableListOf<TemplateMatch>()\n        val acceptedWorkPos = mutableListOf<Int>()\n        val warningsByPos = mutableMapOf<Int, MutableList<String>>()'''
)

replace_once(
    interpreter,
    '''        val bestMatches = templateMatches\n            .mapNotNull { match ->\n                val pos = match.fields["POS_NUMBER"]?.let(::parsePosNumber) ?: return@mapNotNull null\n                pos to match\n            }''',
    '''        val bestMatches = templateMatches\n            .mapNotNull { match ->\n                val rawPos = match.fields["POS_NUMBER"].orEmpty()\n                val resolved = PosIdentityResolver.resolve(rawPos, posIdentityRule) ?: return@mapNotNull null\n                resolved.workPos to match\n            }'''
)

replace_once(
    interpreter,
    '''                ocrWarnings = posWarnings.distinct().joinToString(" • "),\n                ocrRawBillDate = dateRaw?.trim().orEmpty().ifBlank { current.ocrRawBillDate },\n                ocrCounterCycle = match.template.duplicatePolicy.customerCounterCycle.uppercase()\n            )\n            accepted += match''',
    '''                ocrWarnings = posWarnings.distinct().joinToString(" • "),\n                ocrRawBillDate = dateRaw?.trim().orEmpty().ifBlank { current.ocrRawBillDate },\n                ocrCounterCycle = match.template.duplicatePolicy.customerCounterCycle.uppercase(),\n                ocrRawPosIdentity = match.fields["POS_NUMBER"].orEmpty().ifBlank { current.ocrRawPosIdentity }\n            )\n            accepted += match\n            acceptedWorkPos += pos'''
)

replace_once(
    interpreter,
    '''        val acceptedTemplateNames = accepted.map { it.template.templateName }.distinct()\n        val posList = accepted.mapNotNull { parsePosNumber(it.fields["POS_NUMBER"].orEmpty()) }.distinct().sorted()''',
    '''        val acceptedTemplateNames = accepted.map { it.template.templateName }.distinct()\n        val posList = acceptedWorkPos.distinct().sorted()'''
)

pipeline = "android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt"
replace_once(
    pipeline,
    '''            imagePath = imagePath,\n            templates = templates\n        )''',
    '''            imagePath = imagePath,\n            templates = templates,\n            posIdentityRule = receiptRule.posIdentityRule\n        )'''
)

print("Round94 strict interpreter POS mapping fix applied")
