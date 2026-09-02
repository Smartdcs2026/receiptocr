from pathlib import Path
import re
import runpy

path = Path(__file__).with_name("round90_apply.py")
text = path.read_text(encoding="utf-8")
start_marker = "rep(\n    simple,\n    'if(f.type===\"BILL_DATE\")return"
end_marker = "rep(\n    simple,\n    '''      checks.push"
start = text.find(start_marker)
end = text.find(end_marker, start + 1)
if start < 0 or end < 0:
    raise SystemExit("Round90 simple-fieldRegex wrapper markers not found")

# ocr-simple.js มี fieldRegex เก่าที่ไม่ได้ถูกใช้โดยหน้าทดสอบปัจจุบัน
# (หน้าทดสอบใช้ ReceiptOcrPatternEngine) จึงไม่แก้บล็อกซ้ำนี้ใน Round90
text = text[:start] + text[end:]
path.write_text(text, encoding="utf-8")
runpy.run_path(str(path), run_name="__main__")

# Python re.sub ในสคริปต์หลักตีความ backslash ของ replacement หนึ่งชั้น
# แก้เฉพาะ single \d / \s ที่หลงเหลือใน Kotlin Regex ให้เป็น \\d / \\s
# โดยไม่แตะ escape ที่ถูกต้องอยู่แล้ว
pos_path = path.parents[1] / "android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt"
pos_text = pos_path.read_text(encoding="utf-8")
pos_text = re.sub(r"(?<!\\)\\([ds])", r"\\\\\1", pos_text)
pos_path.write_text(pos_text, encoding="utf-8")
