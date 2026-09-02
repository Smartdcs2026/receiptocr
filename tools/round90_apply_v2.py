from pathlib import Path
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
