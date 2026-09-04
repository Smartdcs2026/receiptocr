from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt"
text = path.read_text(encoding="utf-8")

# round95_apply originally appended the store-review panel after an anchor that
# already contained the opening date-info if. Remove that accidental opening so
# the store panel and all following composables remain at their intended scope.
bad = '''                    }

                    if (dateInfoText.isNotBlank()) {
                    if (storeMismatch) {'''
good = '''                    }

                    if (storeMismatch) {'''

count = text.count(bad)
if count != 1:
    raise SystemExit(f"Round95 UI scope anchor count={count}")

path.write_text(text.replace(bad, good, 1), encoding="utf-8")
print("Round95 UI store-review scope fix applied")
