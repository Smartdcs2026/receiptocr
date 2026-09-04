from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(rel: str, old: str, new: str) -> None:
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected one match, got {count}: {old!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


messages = "android-app/app/src/main/java/com/receiptocr/app/ui/UserFacingOcrMessages.kt"

# Keep the field-language wording already protected by Round92/94 while making
# the two integrity problems short and prominent for Round96.
replace_once(
    messages,
    'messages += "ข้อมูลบางช่องไม่ชัด • ตรวจจากภาพบิล"',
    'messages += "ข้อมูลบางช่องควรตรวจเทียบกับภาพบิล"',
)
replace_once(
    messages,
    'messages += if (pos != null) "พบบิลซ้ำในร้านเดียวกัน • POS $pos มีมากกว่า 1 ใบ" else "พบบิลซ้ำในร้านเดียวกัน • ตรวจบิล"',
    'messages += if (pos != null) "พบบิลซ้ำในร้านเดียวกัน • POS $pos ซ้ำ" else "พบบิลซ้ำในร้านเดียวกัน • ตรวจบิล"',
)
replace_once(
    messages,
    'messages += if (pos != null) "ข้อมูล POS $pos จากภาพล่าสุดไม่ตรงกับข้อมูลเดิม" else "ข้อมูลจากภาพล่าสุดไม่ตรงกับข้อมูลเดิม"',
    'messages += if (pos != null) "ข้อมูล POS $pos จากภาพล่าสุดต่างจากข้อมูลที่บันทึกไว้" else "ข้อมูลจากภาพล่าสุดต่างจากข้อมูลที่บันทึกไว้"',
)
replace_once(
    messages,
    'text.contains("พบบิลสลับร้าน") -> messages += "มีบิลจากร้านอื่นปะปน • ตรวจบิล"',
    'text.contains("พบบิลสลับร้าน") -> messages += "บิลสลับร้าน • ตรวจบิล"',
)

round96_test = "android-app/app/src/test/java/com/receiptocr/app/ui/UserFacingOcrMessagesRound96Test.kt"
replace_once(
    round96_test,
    'assertEquals("พบบิลซ้ำในร้านเดียวกัน • POS 2 มีมากกว่า 1 ใบ", UserFacingOcrMessages.warning(raw))',
    'assertEquals("พบบิลซ้ำในร้านเดียวกัน • POS 2 ซ้ำ", UserFacingOcrMessages.warning(raw))',
)

print("Round96 message regression fix applied")
