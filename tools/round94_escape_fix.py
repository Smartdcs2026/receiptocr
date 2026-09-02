from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def fix(rel, replacements):
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    for old, new in replacements:
        if old not in text:
            raise SystemExit(f"{rel}: missing expected generated text: {old!r}")
        text = text.replace(old, new)
    path.write_text(text, encoding="utf-8")


fix(
    "android-app/app/src/main/java/com/receiptocr/app/ocr/OcrTextNormalizer.kt",
    [
        ('replace(Regex("\\s+"), "")', 'replace(Regex("""\\s+"""), "")'),
    ],
)

fix(
    "android-app/app/src/main/java/com/receiptocr/app/ui/UserFacingOcrMessages.kt",
    [
        ('Regex("หมายเลขเครื่อง\\s+([A-Za-z0-9]+)")', 'Regex("""หมายเลขเครื่อง\\s+([A-Za-z0-9]+)""")'),
        ('Regex("POS\\s*(\\d+)")', 'Regex("""POS\\s*(\\d+)""")'),
    ],
)

print("Round94 Kotlin escape fix applied")
