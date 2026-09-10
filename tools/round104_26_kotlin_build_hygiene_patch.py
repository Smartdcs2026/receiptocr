from pathlib import Path

BUILD = Path("android-app/app/build.gradle.kts")
UI = Path("android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt")
TERMINAL_TEST = Path("tests/round10417-pos-terminal.test.js")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old in text:
        if text.count(old) != 1:
            raise SystemExit(f"{label}: expected exactly one old occurrence, found {text.count(old)}")
        return text.replace(old, new, 1)
    if new not in text:
        raise SystemExit(f"{label}: neither old nor new form found")
    return text


build = BUILD.read_text(encoding="utf-8")
build = replace_once(build, "versionCode = 117", "versionCode = 118", "versionCode")
build = replace_once(build, 'versionName = "0.104.25"', 'versionName = "0.104.26"', "versionName")
BUILD.write_text(build, encoding="utf-8")

ui = UI.read_text(encoding="utf-8")

import_replacements = {
    "import androidx.compose.material.icons.outlined.ArrowBack":
        "import androidx.compose.material.icons.automirrored.outlined.ArrowBack",
    "import androidx.compose.material.icons.outlined.Send":
        "import androidx.compose.material.icons.automirrored.outlined.Send",
    "import androidx.compose.material.icons.outlined.ReceiptLong":
        "import androidx.compose.material.icons.automirrored.outlined.ReceiptLong",
}
for old, new in import_replacements.items():
    ui = replace_once(ui, old, new, old)

usage_replacements = {
    "Icons.Outlined.ArrowBack": "Icons.AutoMirrored.Outlined.ArrowBack",
    "Icons.Outlined.Send": "Icons.AutoMirrored.Outlined.Send",
    "Icons.Outlined.ReceiptLong": "Icons.AutoMirrored.Outlined.ReceiptLong",
}
for old, new in usage_replacements.items():
    if old in ui:
        ui = ui.replace(old, new)
    elif new not in ui:
        raise SystemExit(f"missing both deprecated and AutoMirrored usage for {old}")

old_warning_block = '''            if (mixedBoundaryConflict && earliestProposalDate != null && latestProposalDate != null) {
                add(
                    "${earliestProposalDate.format(DateTimeFormatter.ofPattern(\"dd/MM/yyyy\"))} กับ " +
                        "${latestProposalDate.format(DateTimeFormatter.ofPattern(\"dd/MM/yyyy\"))} ใช้ร่วมกันไม่ได้"
                )
            }'''
new_warning_block = '''            if (mixedBoundaryConflict) {
                val earliest = requireNotNull(earliestProposalDate)
                val latest = requireNotNull(latestProposalDate)
                add(
                    "${earliest.format(DateTimeFormatter.ofPattern(\"dd/MM/yyyy\"))} กับ " +
                        "${latest.format(DateTimeFormatter.ofPattern(\"dd/MM/yyyy\"))} ใช้ร่วมกันไม่ได้"
                )
            }'''
ui = replace_once(ui, old_warning_block, new_warning_block, "mixed boundary warning block")

for deprecated in usage_replacements:
    if deprecated in ui:
        raise SystemExit(f"deprecated icon usage still present: {deprecated}")

if "mixedBoundaryConflict && earliestProposalDate != null && latestProposalDate != null" in ui:
    raise SystemExit("redundant mixed-boundary null condition still present")

UI.write_text(ui, encoding="utf-8")

test = TERMINAL_TEST.read_text(encoding="utf-8")
test = replace_once(test, "versionCode = 117", "versionCode = 118", "terminal test versionCode")
test = replace_once(test, "Android versionCode must be Round104.25", "Android versionCode must be Round104.26", "terminal test versionCode label")
test = replace_once(test, 'versionName = \\\"0.104.25\\\"', 'versionName = \\\"0.104.26\\\"', "terminal test versionName")
test = replace_once(test, "Android versionName must be 0.104.25", "Android versionName must be 0.104.26", "terminal test versionName label")
test = replace_once(test, "Round104.25 terminal POS behavior checks passed", "Round104.26 terminal POS behavior checks passed", "terminal test success label")
TERMINAL_TEST.write_text(test, encoding="utf-8")

print("Round104.26 Kotlin build hygiene patch applied")
