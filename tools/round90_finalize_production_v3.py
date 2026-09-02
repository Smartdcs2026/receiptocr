from pathlib import Path

# Apply the complete v2 production finalizer first.
exec(Path("tools/round90_finalize_production_v2.py").read_text(encoding="utf-8"), {})


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def replace_function(text, start_signature, next_signature, new_function, label):
    start = text.find(start_signature)
    require(start >= 0, f"{label}: start not found")
    end = text.find(next_signature, start + len(start_signature))
    require(end >= 0, f"{label}: next function not found")
    return text[:start] + new_function.rstrip() + "\n\n" + text[end:]


# Repair generated regex strings with Kotlin raw strings so \s remains a regex escape,
# not a Kotlin string escape.
path = "android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt"
s = read(path)
new_find_date = r'''    private fun findDate(
        text: String,
        field: OcrTemplateField,
        referenceDate: LocalDate
    ): Pair<String, IntRange>? {
        val layouts = dateLayouts(field)

        fun accept(raw: String, range: IntRange): Pair<String, IntRange>? {
            val normalized = ReceiptDateOcrNormalizer.normalizeForField(
                raw = raw,
                field = field,
                referenceDate = referenceDate
            )
            return normalized.value?.let { it to range }
        }

        layouts.forEach { lengths ->
            val separated = Regex(
                """${fixedDigits(lengths[0])}\s*[./-]\s*${fixedDigits(lengths[1])}\s*[./-]\s*${fixedDigits(lengths[2])}""",
                RegexOption.IGNORE_CASE
            )
            separated.findAll(text).forEach { match ->
                accept(match.value, match.range)?.let { return it }
            }

            val total = lengths.sum()
            val compact = Regex("(?<![0-9OoIl|SsZzBbGg])${fixedDigits(total)}(?![0-9OoIl|SsZzBbGg])")
            compact.findAll(text).forEach { match ->
                val digits = normalizeDigits(match.value).filter(Char::isDigit)
                if (digits.length == total) {
                    var cursor = 0
                    val raw = lengths.map { length ->
                        digits.substring(cursor, cursor + length).also { cursor += length }
                    }.joinToString("/")
                    accept(raw, match.range)?.let { return it }
                }
            }
        }

        // OCR บิลความร้อนอาจทำ '/' หายหรือมีเลขแทรก เช่น 20/08769
        // ส่งค่าดิบให้ date normalizer ตัดสินด้วยกฎ Admin + วันงาน
        val noisy = Regex(
            """(?<![0-9OoIl|SsZzBbGg])(?:$DIGIT\s*){1,4}\s*[./-]\s*(?:$DIGIT\s*){2,8}(?![0-9OoIl|SsZzBbGg])""",
            RegexOption.IGNORE_CASE
        )
        noisy.findAll(text).forEach { match ->
            accept(match.value, match.range)?.let { return it }
        }
        return null
    }'''
s = replace_function(s, "    private fun findDate(\n", "    private fun findTime(", new_find_date, "fusion findDate raw regex")
new_date_helpers = r'''    private fun dateLayouts(field: OcrTemplateField): List<List<Int>> {
        val order = field.dateOrder.trim().uppercase().let {
            if (it in setOf("DMY", "MDY", "YMD")) it else "DMY"
        }
        val yearLengths = when (field.dateYearDigits) {
            2 -> listOf(2)
            4 -> listOf(4)
            else -> listOf(2, 4)
        }
        return yearLengths.map { yearLength ->
            when (order) {
                "YMD" -> listOf(yearLength, 2, 2)
                else -> listOf(2, 2, yearLength)
            }
        }
    }

    private fun datePattern(field: OcrTemplateField): String = dateLayouts(field)
        .joinToString("|", "(?:", ")") { lengths ->
            """${fixedDigits(lengths[0])}\s*[./-]\s*${fixedDigits(lengths[1])}\s*[./-]\s*${fixedDigits(lengths[2])}"""
        }'''
s = replace_function(s, "    private fun dateLayouts(", "    private fun timePattern(", new_date_helpers, "fusion date helpers raw regex")
write(path, s)

path = "android-app/app/src/main/java/com/receiptocr/app/ocr/TemplateSequenceFallback.kt"
s = read(path)
new_sequence_date = r'''    private fun datePattern(field: OcrTemplateField): String {
        val order = field.dateOrder.trim().uppercase().let {
            if (it in setOf("DMY", "MDY", "YMD")) it else "DMY"
        }
        val yearLengths = when (field.dateYearDigits) {
            2 -> listOf(2)
            4 -> listOf(4)
            else -> listOf(2, 4)
        }
        val layouts = yearLengths.map { yearLength ->
            when (order) {
                "YMD" -> listOf(yearLength, 2, 2)
                else -> listOf(2, 2, yearLength)
            }
        }
        return layouts.joinToString("|", "(?:", ")") { lengths ->
            val first = fixedDigits(lengths[0])
            val second = fixedDigits(lengths[1])
            val third = fixedDigits(lengths[2])
            val exact = """$first\s*[./-]\s*$second\s*[./-]\s*$third"""
            val mergedTail = """$first\s*[./-]\s*${rangedDigits(lengths[1] + lengths[2], lengths[1] + lengths[2] + 2)}"""
            val mergedHead = """${rangedDigits(lengths[0] + lengths[1], lengths[0] + lengths[1] + 2)}\s*[./-]\s*$third"""
            "(?:$exact|$mergedTail|$mergedHead)"
        }
    }'''
s = replace_function(s, "    private fun datePattern(field: OcrTemplateField)", "    private fun timePattern(", new_sequence_date, "sequence datePattern raw regex")
write(path, s)

print("Round90 v3 Kotlin regex escape repair applied")
