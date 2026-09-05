from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt"
DEMO = ROOT / "android-app/app/src/main/java/com/receiptocr/app/data/DemoRepository.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Round103 patch failed: {label} expected 1 match, found {count}")
    return text.replace(old, new, 1)


def patch_demo() -> None:
    text = DEMO.read_text(encoding="utf-8")
    if "import com.receiptocr.app.util.PhotoEvidenceManifest" not in text:
        text = replace_once(
            text,
            "import com.receiptocr.app.model.*\n",
            "import com.receiptocr.app.model.*\nimport com.receiptocr.app.util.PhotoEvidenceManifest\n",
            "DemoRepository import",
        )

    marker = "PhotoEvidenceManifest.reconcile(\n            context = context,\n            workId = workId,"
    if marker not in text:
        old = '''    fun savePhotoDraft(
        context: Context,
        workId: Int,
        date: LocalDate,
        receipt: List<String?>,
        store: List<String?>
    ) {
        val k = "${workId}_${date}"
'''
        new = '''    fun savePhotoDraft(
        context: Context,
        workId: Int,
        date: LocalDate,
        receipt: List<String?>,
        store: List<String?>
    ) {
        PhotoEvidenceManifest.reconcile(
            context = context,
            workId = workId,
            workDate = date,
            receiptPaths = receipt,
            storePaths = store
        )
        val k = "${workId}_${date}"
'''
        text = replace_once(text, old, new, "savePhotoDraft reconcile")

    DEMO.write_text(text, encoding="utf-8")


def patch_app() -> None:
    text = APP.read_text(encoding="utf-8")

    if 'source = "CAMERA"' not in text:
        old = '''            val archived = PhotoDeviceArchive.archive(
                context = context,
                sourceFile = currentFile,
                work = work,
                workDate = selectedDate,
                kind = currentTarget.first,
                slot = currentTarget.second
            )
            saveDraft()
'''
        new = '''            val archived = PhotoDeviceArchive.archive(
                context = context,
                sourceFile = currentFile,
                work = work,
                workDate = selectedDate,
                kind = currentTarget.first,
                slot = currentTarget.second
            )
            PhotoEvidenceManifest.record(
                context = context,
                workId = work.id,
                workDate = selectedDate,
                kind = currentTarget.first,
                slot = currentTarget.second,
                privatePath = currentFile.absolutePath,
                archiveUri = archived,
                source = "CAMERA"
            )
            saveDraft()
'''
        text = replace_once(text, old, new, "camera evidence record")

    if 'source = "GALLERY"' not in text:
        old = '''                val archived = PhotoDeviceArchive.archive(
                    context = context,
                    sourceFile = file,
                    work = work,
                    workDate = selectedDate,
                    kind = currentTarget.first,
                    slot = currentTarget.second
                )
                saveDraft()
'''
        new = '''                val archived = PhotoDeviceArchive.archive(
                    context = context,
                    sourceFile = file,
                    work = work,
                    workDate = selectedDate,
                    kind = currentTarget.first,
                    slot = currentTarget.second
                )
                PhotoEvidenceManifest.record(
                    context = context,
                    workId = work.id,
                    workDate = selectedDate,
                    kind = currentTarget.first,
                    slot = currentTarget.second,
                    privatePath = file.absolutePath,
                    archiveUri = archived,
                    source = "GALLERY"
                )
                saveDraft()
'''
        text = replace_once(text, old, new, "gallery evidence record")

    old_fallback = 'else -> "บันทึกภาพแล้ว"'
    new_fallback = 'else -> "บันทึกภาพในงานแล้ว • ยังเก็บลงโฟลเดอร์เครื่องไม่ได้"'
    if new_fallback not in text:
        count = text.count(old_fallback)
        if count != 2:
            raise SystemExit(f"Round103 patch failed: archive fallback expected 2 matches, found {count}")
        text = text.replace(old_fallback, new_fallback)

    if "PhotoEvidenceManifest.missingSelected(" not in text:
        old = '''        DemoRepository.savePosRecords(context, work, selectedDate, records)
        DemoRepository.savePhotoDraft(context = context, workId = work.id, date = selectedDate, receipt = receipts.toList(), store = stores.toList())
        message = "กำลังส่งข้อมูล..."
'''
        new = '''        DemoRepository.savePosRecords(context, work, selectedDate, records)
        DemoRepository.savePhotoDraft(context = context, workId = work.id, date = selectedDate, receipt = receipts.toList(), store = stores.toList())
        val missingPhotos = PhotoEvidenceManifest.missingSelected(
            receiptPaths = receipts.toList(),
            storePaths = stores.toList()
        )
        if (missingPhotos.isNotEmpty()) {
            message = "มีภาพที่เปิดไม่ได้ กรุณาเลือกภาพใหม่"
            return
        }
        message = "กำลังส่งข้อมูล..."
'''
        text = replace_once(text, old, new, "pre-submit photo integrity")

    if "PhotoEvidenceManifest.remove(\n                    context = context," not in text:
        old = '''                val file = File(preview.path)
                if (file.exists()) file.delete()

                if (preview.kind == "R") {
'''
        new = '''                val file = File(preview.path)
                if (file.exists()) file.delete()
                PhotoEvidenceManifest.remove(
                    context = context,
                    workId = work.id,
                    workDate = selectedDate,
                    kind = preview.kind,
                    slot = preview.index
                )

                if (preview.kind == "R") {
'''
        text = replace_once(text, old, new, "preview delete evidence cleanup")

    APP.write_text(text, encoding="utf-8")


patch_demo()
patch_app()
print("Round103 photo evidence integrity patch applied")
