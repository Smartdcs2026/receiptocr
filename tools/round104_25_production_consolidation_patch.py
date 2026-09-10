from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"Expected block not found: {label}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# APK UX: submission lock remains strict, but the field user only needs a short
# status. Do not explain the workflow repeatedly inside the working screen.
# -----------------------------------------------------------------------------
ui_path = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt"
ui = ui_path.read_text(encoding="utf-8")
ui = ui.replace(
    'message = "ส่งข้อมูลแล้ว • ล็อกการแก้ไขจนกว่าผู้ตรวจจะส่งกลับ"',
    'message = "ส่งข้อมูลแล้ว"'
)
ui = ui.replace(
    'message = "งานนี้ส่งแล้ว • แก้ไขได้เมื่อผู้ตรวจส่งกลับ"',
    'message = "งานนี้ส่งแล้ว"'
)
ui = ui.replace(
    'message = "งานนี้ส่งแล้ว • ไม่สามารถส่งซ้ำได้จนกว่าผู้ตรวจจะส่งกลับ"',
    'message = "งานนี้ส่งแล้ว"'
)
ui = ui.replace(
    'message = "งานนี้ส่งแล้ว • ลบภาพไม่ได้จนกว่าผู้ตรวจจะส่งกลับ"',
    'message = "งานนี้ส่งแล้ว"'
)
ui = ui.replace(
    'locationMessage = "งานนี้ส่งแล้ว • แก้ไขได้เมื่อผู้ตรวจส่งกลับ"',
    'locationMessage = "งานนี้ส่งแล้ว"'
)

for forbidden in (
    "ดูข้อมูลได้ แต่แก้ไขหรือลบไม่ได้",
    "ล็อกการแก้ไขจนกว่าผู้ตรวจจะส่งกลับ",
    "ไม่สามารถส่งซ้ำได้จนกว่าผู้ตรวจจะส่งกลับ",
    "ลบภาพไม่ได้จนกว่าผู้ตรวจจะส่งกลับ",
):
    if forbidden in ui:
        raise SystemExit(f"Verbose lock text still present: {forbidden}")

# Keep the agreed simple field navigation.
for required in ('POS("POS")', 'RECEIPTS("รูปบิล")', 'NOTES("หมายเหตุ")'):
    if required not in ui:
        raise SystemExit(f"Missing simple work tab: {required}")
if 'STORE_PHOTOS("ภาพร้าน")' in ui:
    raise SystemExit("Store photos must not return as a fourth work tab")
if "SubmissionEditPolicy.isLocked" not in ui:
    raise SystemExit("Submission lock policy disappeared from UI")

ui_path.write_text(ui, encoding="utf-8")


# -----------------------------------------------------------------------------
# APK version for this consolidated production candidate.
# -----------------------------------------------------------------------------
gradle_path = ROOT / "android-app/app/build.gradle.kts"
gradle = gradle_path.read_text(encoding="utf-8")
gradle = replace_once(gradle, "versionCode = 116", "versionCode = 117", "versionCode")
gradle = replace_once(gradle, 'versionName = "0.104.24"', 'versionName = "0.104.25"', "versionName")
gradle_path.write_text(gradle, encoding="utf-8")


# -----------------------------------------------------------------------------
# Shared terminal-POS regression guard must follow the current APK identity.
# The behavioral assertions remain unchanged.
# -----------------------------------------------------------------------------
terminal_test_path = ROOT / "tests/round10417-pos-terminal.test.js"
terminal = terminal_test_path.read_text(encoding="utf-8")
terminal = terminal.replace("versionCode = 113", "versionCode = 117")
terminal = terminal.replace('versionName = "0.104.21"', 'versionName = "0.104.25"')
terminal = terminal.replace("Android versionCode must be Round104.21", "Android versionCode must be Round104.25")
terminal = terminal.replace("Android versionName must be 0.104.19", "Android versionName must be 0.104.25")
terminal = terminal.replace("Round104.21 terminal POS behavior checks passed", "Round104.25 terminal POS behavior checks passed")
if "versionCode = 117" not in terminal or 'versionName = "0.104.25"' not in terminal:
    raise SystemExit("Terminal POS version guard was not aligned to Round104.25")
terminal_test_path.write_text(terminal, encoding="utf-8")


# -----------------------------------------------------------------------------
# Production finalize: it was still pinned to Round104.20. Bring it forward to
# the exact behavior now tested in Round104.21-104.25.
# -----------------------------------------------------------------------------
finalize_path = ROOT / ".github/workflows/round104-finalize.yml"
finalize = finalize_path.read_text(encoding="utf-8")

finalize = finalize.replace(
    "Verify Round104.20 Android version and direct evidence upload",
    "Verify Round104.25 Android version and direct evidence upload"
)
finalize = finalize.replace("grep -q 'versionCode = 112'", "grep -q 'versionCode = 117'")
finalize = finalize.replace("grep -q 'versionName = \"0.104.20\"'", "grep -q 'versionName = \"0.104.25\"'")
finalize = finalize.replace(
    "Protect Round103 OCR baseline and allow Round104.20 POS identity files",
    "Protect Round103 OCR baseline and allow Round104.25 POS identity files"
)
finalize = finalize.replace(
    "(PosIdentityResolver|RealOcrPipeline|RuleDrivenOcrEngine|PosEvidenceFusion|TemplateSequenceFallback)\\.kt$",
    "(PosIdentityResolver|RealOcrPipeline|RuleDrivenOcrEngine|PosEvidenceFusion|TemplateSequenceFallback|UniversalTemplateInterpreter)\\.kt$"
)

old_ui_block = '''      - name: Round104.20 CJ fallback and simple APK UI checks
        run: |
          node tests/round10420-cj-terminal-fallback.test.js
          grep -q 'id="fallbackUnknownToLastWorkPos"' web-admin/index.html
          grep -q 'receipt-date-rules.js?v=104200' web-admin/index.html
          grep -q 'ocr-simple.js?v=104200' web-admin/index.html
          grep -q 'POS("POS")' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          grep -q 'RECEIPTS("รูปบิล")' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          grep -q 'STORE_PHOTOS("ภาพร้าน")' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          grep -q 'NOTES("หมายเหตุ")' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          grep -q 'DatePickerDialog(' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          grep -q 'TimePickerDialog(' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt

'''
new_ui_block = '''      - name: Round104.25 CJ binding, submission lock and compact field UI checks
        run: |
          node tests/round10420-cj-terminal-fallback.test.js
          grep -q 'id="fallbackUnknownToLastWorkPos"' web-admin/index.html
          grep -q 'receipt-date-rules.js?v=104200' web-admin/index.html
          grep -q 'ocr-simple.js?v=104200' web-admin/index.html
          grep -q 'POS("POS")' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          grep -q 'RECEIPTS("รูปบิล")' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          grep -q 'NOTES("หมายเหตุ")' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          ! grep -q 'STORE_PHOTOS("ภาพร้าน")' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          grep -q 'SubmissionEditPolicy.isLocked' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          grep -q 'enabled = !workLocked' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          grep -q 'message = "ส่งข้อมูลแล้ว"' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          ! grep -q 'ดูข้อมูลได้ แต่แก้ไขหรือลบไม่ได้' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          ! grep -q 'ล็อกการแก้ไขจนกว่าผู้ตรวจจะส่งกลับ' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          grep -q 'DatePickerDialog(' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          grep -q 'TimePickerDialog(' android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt
          test -f android-app/app/src/main/java/com/receiptocr/app/validation/SubmissionEditPolicy.kt
          test -f android-app/app/src/test/java/com/receiptocr/app/validation/SubmissionEditPolicyTest.kt
          test -f android-app/app/src/test/java/com/receiptocr/app/ocr/CjFourReceiptBindingRound10421Test.kt

'''
if new_ui_block not in finalize:
    if old_ui_block not in finalize:
        raise SystemExit("Expected Round104.20 UI finalize block not found")
    finalize = finalize.replace(old_ui_block, new_ui_block, 1)

finalize = finalize.replace("Upload Round104.20 debug APK", "Upload Round104.25 debug APK")
finalize = finalize.replace("ReceiptOCR-Round104-20-debug", "ReceiptOCR-Round104-25-debug")

for required in (
    "Verify Round104.25 Android version",
    "versionCode = 117",
    'versionName = "0.104.25"',
    "UniversalTemplateInterpreter",
    "Round104.25 CJ binding, submission lock and compact field UI checks",
    "ReceiptOCR-Round104-25-debug",
):
    if required not in finalize:
        raise SystemExit(f"Finalize guard missing: {required}")

finalize_path.write_text(finalize, encoding="utf-8")

print("Round104.25 production consolidation patch applied")