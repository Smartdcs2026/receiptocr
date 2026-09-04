from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "android-app/app/src/main/java/com/receiptocr/app/validation/ReceiptValidationEngine.kt"
text = path.read_text(encoding="utf-8")

replacements = [
    (
        '"POS ${record.posNumber}: พบหลักฐานบิลมากกว่าหนึ่งชุดสำหรับเครื่องเดียวกัน • กรุณาตรวจบิลก่อนส่ง"',
        '"พบบิล POS ${record.posNumber} ซ้ำ • กรุณาตรวจภาพบิลก่อนส่ง"'
    ),
    (
        '"วันที่ยังไม่ผ่านเงื่อนไขที่ Admin กำหนดและยังแปลงเป็นมาตรฐานไม่ได้"',
        '"วันที่ยังไม่ผ่านรูปแบบวันที่ของร้านและยังไม่สามารถนำไปใช้ได้"'
    )
]

for old, new in replacements:
    if old not in text:
        raise SystemExit(f"Round94 field-language anchor not found: {old}")
    text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")

# Round94 deliberately removes technical/Admin wording from user-facing validation.
# Keep the regression intent (wrong-shaped date must still be rejected) while
# asserting the new field-work wording instead of requiring the old technical text.
test_path = ROOT / "android-app/app/src/test/java/com/receiptocr/app/validation/ReceiptValidationEngineTest.kt"
test_text = test_path.read_text(encoding="utf-8")
old_test = '''        assertTrue(issue.orEmpty().contains("Admin"))\n        assertTrue(issue.orEmpty().contains("มาตรฐาน"))'''
new_test = '''        assertTrue(issue.orEmpty().contains("รูปแบบวันที่ของร้าน"))\n        assertTrue(issue.orEmpty().contains("ไม่สามารถนำไปใช้ได้"))'''
if old_test not in test_text:
    raise SystemExit("Round94 field-language test anchor not found")
test_path.write_text(test_text.replace(old_test, new_test, 1), encoding="utf-8")

print("Round94 field-work language fix applied")
