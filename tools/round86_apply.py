from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

pipeline = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt"
text = pipeline.read_text(encoding="utf-8")
old = '''        // ถ้า strict ไม่ผ่าน ให้ใช้ตัวอ่านตามลำดับช่อง Admin แบบ Round84
        // ช่องที่ Admin ระบุว่าจำเป็นจะถูกอ่านตามลำดับจริง ห้ามข้ามแล้วเลื่อนข้อมูล
        val sequenceFallback = if (strictTemplateResult.detectedPos.isEmpty() && templates.isNotEmpty()) {
            TemplateSequenceFallback.apply(
                rawTexts = mlTexts.map { it.text },
                records = records,
                work = work,
                imagePath = imagePath,
                templates = templates
            )
        } else null
        val templateResult = sequenceFallback?.takeIf { it.detectedPos.isNotEmpty() } ?: strictTemplateResult
'''
new = '''        // Round86: ถ้า strict ไม่ผ่าน ให้รวมหลักฐานจากหลายรอบอ่านภาพภายใต้ POS เดียวกันก่อน
        // รอบหนึ่งอาจอ่านหัวบิล/POS/ลูกค้าได้ดี แต่อีกรอบอ่านวันที่/เวลาได้ดีกว่า
        // การรวมต้องอยู่ใน record anchor เดียวกันและไม่อนุญาตให้ข้าม POS
        val evidenceFusion = if (strictTemplateResult.detectedPos.isEmpty() && templates.isNotEmpty()) {
            PosEvidenceFusion.apply(
                rawTexts = mlTexts.map { it.text },
                records = records,
                work = work,
                imagePath = imagePath,
                templates = templates
            )
        } else null

        // ถ้าหลักฐานหลายรอบยังไม่พอ ค่อยกลับไปใช้ anchored sequence parser ของ Round84
        val sequenceFallback = if (
            strictTemplateResult.detectedPos.isEmpty() &&
            evidenceFusion?.detectedPos.orEmpty().isEmpty() &&
            templates.isNotEmpty()
        ) {
            TemplateSequenceFallback.apply(
                rawTexts = mlTexts.map { it.text },
                records = records,
                work = work,
                imagePath = imagePath,
                templates = templates
            )
        } else null
        val templateResult = evidenceFusion?.takeIf { it.detectedPos.isNotEmpty() }
            ?: sequenceFallback?.takeIf { it.detectedPos.isNotEmpty() }
            ?: strictTemplateResult
'''
if old not in text:
    raise SystemExit("RealOcrPipeline integration block not found")
pipeline.write_text(text.replace(old, new), encoding="utf-8")

build = ROOT / "android-app/app/build.gradle.kts"
build_text = build.read_text(encoding="utf-8")
build_text = build_text.replace('versionCode = 86', 'versionCode = 87')
build_text = build_text.replace('versionName = "0.85.0"', 'versionName = "0.86.0"')
if 'versionCode = 87' not in build_text or 'versionName = "0.86.0"' not in build_text:
    raise SystemExit("version bump failed")
build.write_text(build_text, encoding="utf-8")
