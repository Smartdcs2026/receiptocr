from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "tests/round10417-pos-terminal.test.js"
text = path.read_text(encoding="utf-8")
replacements = {
    "assert(gradle.includes('versionCode = 118'), 'Android versionCode must be Round104.26');": "assert(gradle.includes('versionCode = 119'), 'Android versionCode must be Round104.27');",
    "assert(gradle.includes('versionName = \"0.104.26\"'), 'Android versionName must be 0.104.26');": "assert(gradle.includes('versionName = \"0.104.27\"'), 'Android versionName must be 0.104.27');",
    "console.log('Round104.26 terminal POS behavior checks passed');": "console.log('Round104.27 terminal POS behavior checks passed');",
}
for old, new in replacements.items():
    if old not in text:
        raise SystemExit(f"Expected terminal guard text not found: {old}")
    text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("Round104.27 terminal version guard aligned")
