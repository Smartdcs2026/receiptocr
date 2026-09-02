from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


gradle = Path("android-app/app/build.gradle.kts")
text = gradle.read_text(encoding="utf-8")
text = text.replace('versionCode = 80\n        versionName = "0.80.0"', 'versionCode = 81\n        versionName = "0.80.1"')
gradle.write_text(text, encoding="utf-8")

normalizer = "android-app/app/src/main/java/com/receiptocr/app/ocr/OcrTextNormalizer.kt"
replace_once(
    normalizer,
    'return normalized.replace(Regex("\\\\s*([:/.-])\\\\s*"), "${\'$\'}1")',
    'return normalized.replace(Regex("\\\\s*([:/\\\\.#-])\\\\s*"), "${\'$\'}1")',
)

interp = "android-app/app/src/main/java/com/receiptocr/app/ocr/UniversalTemplateInterpreter.kt"
replace_once(
    interp,
    '''    private fun literalPattern(raw: String): String? {\n        val value = raw.trim()\n        if (value.isBlank()) return null\n        return when {\n            value.matches(Regex("BNO\\\\s*:\\\\s*S", RegexOption.IGNORE_CASE)) -> "[B8]N[O0]\\\\s*[:;]\\\\s*[S$5]"\n            value.matches(Regex("BNO\\\\s*:", RegexOption.IGNORE_CASE)) -> "[B8]N[O0]\\\\s*[:;]"\n            else -> Regex.escape(value).replace("\\\\ ", "\\\\s+")\n        }\n    }''',
    '''    private fun literalPattern(raw: String): String? {\n        val value = raw.trim()\n        if (value.isBlank()) return null\n        return when {\n            value.matches(Regex("BNO\\\\s*:\\\\s*S", RegexOption.IGNORE_CASE)) -> "[B8]N[O0]\\\\s*[:;]\\\\s*[S$5]"\n            value.matches(Regex("BNO\\\\s*:", RegexOption.IGNORE_CASE)) -> "[B8]N[O0]\\\\s*[:;]"\n            else -> value.map { character ->\n                when (character) {\n                    '0' -> "[0Oo]"\n                    '1' -> "[1Iil|]"\n                    else -> Regex.escape(character.toString())\n                }\n            }.joinToString("\\\\s*")\n        }\n    }''',
)

replace_once(
    interp,
    '''        fun collect(source: List<String>, joinPenalty: Int) {\n            val joined = normalizeLine(source.joinToString(" "))\n            row.regex.findAll(joined).forEach { match ->\n                val fields = extractFields(row, match)\n                val score = score(template, fields, work, workDate) - joinPenalty\n                if (score > 0) found += TemplateMatch(template, fields, score, source)\n            }\n        }''',
    '''        fun collect(source: List<String>, joinPenalty: Int) {\n            val joined = normalizeLine(source.joinToString(" "))\n            val compact = joined.replace(\n                Regex("(?<=[A-Za-z0-9OoIl|])\\\\s+(?=[A-Za-z0-9OoIl|])"),\n                ""\n            )\n            listOf(joined, compact).distinct().forEach { candidateText ->\n                row.regex.findAll(candidateText).forEach { match ->\n                    val fields = extractFields(row, match)\n                    val score = score(template, fields, work, workDate) - joinPenalty\n                    if (score > 0) found += TemplateMatch(template, fields, score, source)\n                }\n            }\n        }''',
)

print("Round80.1 source patch applied")
