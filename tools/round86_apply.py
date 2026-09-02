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
if old in text:
    text = text.replace(old, new)
elif new not in text:
    raise SystemExit("RealOcrPipeline integration block not found")
pipeline.write_text(text, encoding="utf-8")

fusion = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt"
fusion_text = fusion.read_text(encoding="utf-8")
old_boundary = '''        val results = mutableListOf<Evidence>()
        candidates.forEach { candidate ->
            compiled.forEach { prefix ->
'''
new_boundary = '''        // ใช้ prefix ถึง POS เป็นขอบเขตของ record ถัดไป เพื่อห้ามวันที่/เวลาไหลข้าม POS
        val recordBoundary = compilePrefix(ordered, posIndex + 1)

        val results = mutableListOf<Evidence>()
        candidates.forEach { candidate ->
            compiled.forEach { prefix ->
'''
if old_boundary in fusion_text:
    fusion_text = fusion_text.replace(old_boundary, new_boundary)
elif new_boundary not in fusion_text:
    raise SystemExit("record boundary insertion point not found")

old_window = '''                    val anchorStart = match.range.first.coerceAtLeast(0)
                    val anchorEndExclusive = (match.range.last + 1).coerceAtMost(candidate.text.length)
                    val localEnd = (anchorEndExclusive + LOCAL_AFTER_ANCHOR).coerceAtMost(candidate.text.length)
                    val localText = candidate.text.substring(anchorStart, localEnd)
'''
new_window = '''                    val anchorStart = match.range.first.coerceAtLeast(0)
                    val anchorEndExclusive = (match.range.last + 1).coerceAtMost(candidate.text.length)
                    val distanceEnd = (anchorEndExclusive + LOCAL_AFTER_ANCHOR).coerceAtMost(candidate.text.length)
                    val nextRecordStart = recordBoundary?.regex
                        ?.find(candidate.text, anchorEndExclusive)
                        ?.range
                        ?.first
                        ?.takeIf { it > anchorStart }
                    val localEnd = nextRecordStart?.coerceAtMost(distanceEnd) ?: distanceEnd
                    val localText = candidate.text.substring(anchorStart, localEnd)
'''
if old_window in fusion_text:
    fusion_text = fusion_text.replace(old_window, new_window)
elif new_window not in fusion_text:
    raise SystemExit("local evidence window block not found")
fusion.write_text(fusion_text, encoding="utf-8")

build = ROOT / "android-app/app/build.gradle.kts"
build_text = build.read_text(encoding="utf-8")
build_text = build_text.replace('versionCode = 86', 'versionCode = 87')
build_text = build_text.replace('versionName = "0.85.0"', 'versionName = "0.86.0"')
if 'versionCode = 87' not in build_text or 'versionName = "0.86.0"' not in build_text:
    raise SystemExit("version bump failed")
build.write_text(build_text, encoding="utf-8")
