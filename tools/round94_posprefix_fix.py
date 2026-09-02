from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/TemplateSequenceFallback.kt"
text = path.read_text(encoding="utf-8")

old = '''            "POS_NUMBER" -> {\n                val prefix = compact.takeWhile {\n                    it.isLetter() && it.uppercaseChar() !in setOf('O', 'I', 'S', 'Z', 'B', 'G')\n                }\n                prefix + normalizeDigits(compact.drop(prefix.length))\n            }'''

new = '''            "POS_NUMBER" -> {\n                // Round94: ในช่องหมายเลขเครื่อง อักษรนำหน้าเป็น identity จริงของเครื่อง\n                // เช่น N01 และ B01 เป็นคนละเครื่อง จึงห้ามแปลง B -> 8 ก่อนทำ Mapping\n                val prefixed = Regex("""^([A-Za-z]{1,4})([0-9OoIl|SsZzBbGg]+)$""")\n                    .matchEntire(compact)\n                if (prefixed != null) {\n                    prefixed.groupValues[1].uppercase() + normalizeDigits(prefixed.groupValues[2])\n                } else {\n                    normalizeDigits(compact)\n                }\n            }'''

if old not in text:
    raise SystemExit("Round94 POS prefix anchor not found")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Round94 POS prefix preservation fix applied")
