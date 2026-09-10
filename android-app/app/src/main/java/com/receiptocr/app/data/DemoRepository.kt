package com.receiptocr.app.data

import android.content.Context
import android.net.Uri
import com.receiptocr.app.model.*
import com.receiptocr.app.util.PhotoEvidenceManifest
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

object DemoRepository {

    val noReceiptReasons = listOf(
        "เครื่อง POS ไม่เปิดใช้งาน",
        "ไม่มีลูกค้า",
        "ไม่มีการขาย",
        "พนักงานไม่อนุญาต",
        "บิลอ่านไม่ได้",
        "เครื่องขัดข้อง",
        "อื่น ๆ"
    )

    fun getWorkItems(date: LocalDate): List<WorkItem> {
        date
        return listOf(
            WorkItem(
                1, "CJ MORE", "CJ", "C-Store", "CJ1078",
                "ถนนเอราวัณ คลอง 2, ปทุมธานี", 3, "06:00-23:00",
                "ซีเจ มอร์ สาขาถนนเอราวัณคลอง 2 เลขที่ 1/20 หมู่ที่ 12 ตำบลคลองสอง อำเภอคลองหลวง จังหวัดปทุมธานี 12120",
                "Stand Alone", "B+", "14.10400577", "100.64198135"
            ),
            WorkItem(
                2, "CJ MORE", "CJ", "C-Store", "CJ1157",
                "เลียบคลอง 3 คลองหลวง", 2, "06:00-23:00",
                "คลองสาม อำเภอคลองหลวง จังหวัดปทุมธานี",
                "Stand Alone", "A", "14.07500000", "100.65000000"
            ),
            WorkItem(
                3, "CJ MORE", "CJ", "C-Store", "CJ1234",
                "รังสิต-คลองสอง", 4, "06:00-23:00",
                "รังสิต ปทุมธานี",
                "Community", "A", "13.99000000", "100.62000000"
            ),
            WorkItem(
                4, "CJ MORE", "CJ", "C-Store", "CJ1289",
                "ตลาดคลองหลวง", 1, "06:00-23:00",
                "คลองหลวง ปทุมธานี",
                "Stand Alone", "B", "14.02000000", "100.64000000"
            )
        )
    }

    fun plannedDays(month: YearMonth): Set<LocalDate> =
        listOf(2,3,4,5,6,10,11,12,14,20,21,22,23,24)
            .filter { it <= month.lengthOfMonth() }
            .map { month.atDay(it) }.toSet()

    fun loadStatus(context: Context, workId: Int, date: LocalDate): WorkStatus {
        val raw = context.getSharedPreferences("store_state", Context.MODE_PRIVATE)
            .getString("${workId}_${date}", "") ?: ""
        return when (raw) {
            "DRAFT" -> WorkStatus.DRAFT
            "SUBMITTED" -> WorkStatus.SUBMITTED
            "FAILED" -> WorkStatus.FAILED
            else -> WorkStatus.NOT_STARTED
        }
    }

    fun saveStatus(context: Context, workId: Int, date: LocalDate, status: WorkStatus) {
        context.getSharedPreferences("store_state", Context.MODE_PRIVATE)
            .edit()
            .putString("${workId}_${date}", status.name)
            .apply()
    }

    fun loadPosRecords(context: Context, work: WorkItem, date: LocalDate): List<PosRecord> {
        val prefs = context.getSharedPreferences("pos_records", Context.MODE_PRIVATE)
        val savedPosNumbers = prefs.getString("${work.id}_${date}.posNumbers", "")
            .orEmpty().split(',').mapNotNull { it.toIntOrNull() }
        val posNumbers = if (savedPosNumbers.size == work.posCount) savedPosNumbers else (1..work.posCount).toList()
        return posNumbers.map { n ->
            val k = "${work.id}_${date}_$n"
            val customer = prefs.getString("$k.customer", "") ?: ""
            val note = prefs.getString("$k.note", "") ?: ""
            val noReceipt = prefs.getBoolean("$k.noReceipt", false)
            val source = prefs.getString("$k.source", "MANUAL") ?: "MANUAL"
            val sourceImage = prefs.getString("$k.ocrSourceImagePath", "") ?: ""
            val unusedRecord = customer.isBlank() && note.isBlank() && !noReceipt &&
                source == "MANUAL" && sourceImage.isBlank()
            PosRecord(
                posNumber = n,
                customerNo = customer,
                billDate = if (unusedRecord) "" else prefs.getString("$k.date", "") ?: "",
                billTime = if (unusedRecord) "" else prefs.getString("$k.time", "") ?: "",
                note = note,
                noReceipt = noReceipt,
                noReceiptReason = prefs.getString("$k.reason", "") ?: "",
                source = source,
                ocrSourceImagePath = sourceImage,
                ocrConfidence = prefs.getString("$k.ocrConfidence", "") ?: "",
                ocrTemplateName = prefs.getString("$k.ocrTemplateName", "") ?: "",
                ocrWarnings = prefs.getString("$k.ocrWarnings", "") ?: "",
                ocrStoreId = prefs.getString("$k.ocrStoreId", "") ?: "",
                ocrStoreIdExpected = prefs.getBoolean("$k.ocrStoreIdExpected", false),
                ocrCounterCycle = prefs.getString("$k.ocrCounterCycle", "CONTINUOUS") ?: "CONTINUOUS",
                ocrRawPosIdentity = prefs.getString("$k.ocrRawPosIdentity", "") ?: "",
                storeReviewConfirmed = prefs.getBoolean("$k.storeReviewConfirmed", false),
                storeReviewReadId = prefs.getString("$k.storeReviewReadId", "") ?: "",
                storeReviewExpectedId = prefs.getString("$k.storeReviewExpectedId", "") ?: "",
                storeReviewConfirmedId = prefs.getString("$k.storeReviewConfirmedId", "") ?: "",
                storeReviewConfirmedAt = prefs.getString("$k.storeReviewConfirmedAt", "") ?: "",
                storeReviewConfirmedBy = prefs.getString("$k.storeReviewConfirmedBy", "") ?: ""
            )
        }
    }

    fun savePosRecords(context: Context, work: WorkItem, date: LocalDate, records: List<PosRecord>) {
        val editor = context.getSharedPreferences("pos_records", Context.MODE_PRIVATE).edit()
        editor.putString(
            "${work.id}_${date}.posNumbers",
            records.take(work.posCount).joinToString(",") { it.posNumber.toString() }
        )
        records.forEach { r ->
            val k = "${work.id}_${date}_${r.posNumber}"
            editor.putString("$k.customer", r.customerNo)
                .putString("$k.date", r.billDate)
                .putString("$k.time", r.billTime)
                .putString("$k.note", r.note)
                .putBoolean("$k.noReceipt", r.noReceipt)
                .putString("$k.reason", r.noReceiptReason)
                .putString("$k.source", r.source)
                .putString("$k.ocrSourceImagePath", r.ocrSourceImagePath)
                .putString("$k.ocrConfidence", r.ocrConfidence)
                .putString("$k.ocrTemplateName", r.ocrTemplateName)
                .putString("$k.ocrWarnings", r.ocrWarnings)
                .putString("$k.ocrStoreId", r.ocrStoreId)
                .putBoolean("$k.ocrStoreIdExpected", r.ocrStoreIdExpected)
                .putString("$k.ocrCounterCycle", r.ocrCounterCycle)
                .putString("$k.ocrRawPosIdentity", r.ocrRawPosIdentity)
                .putBoolean("$k.storeReviewConfirmed", r.storeReviewConfirmed)
                .putString("$k.storeReviewReadId", r.storeReviewReadId)
                .putString("$k.storeReviewExpectedId", r.storeReviewExpectedId)
                .putString("$k.storeReviewConfirmedId", r.storeReviewConfirmedId)
                .putString("$k.storeReviewConfirmedAt", r.storeReviewConfirmedAt)
                .putString("$k.storeReviewConfirmedBy", r.storeReviewConfirmedBy)
        }
        editor.apply()
    }

    fun savePhotoDraft(
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
        context.getSharedPreferences("photo_drafts", Context.MODE_PRIVATE).edit()
            .putString("$k.receipts", receipt.take(3).joinToString("|") { it.orEmpty() })
            .putString("$k.stores", store.take(10).joinToString("|") { it.orEmpty() })
            .apply()
    }

    private fun retainedPhotoDir(context: Context, workId: Int, date: LocalDate): File =
        File(context.filesDir, "submitted_photos/$date/$workId").apply { mkdirs() }

    private fun retainedSlotFile(
        context: Context,
        workId: Int,
        date: LocalDate,
        kind: String,
        slot: Int
    ): File? {
        val prefix = "${kind}_${slot}."
        return retainedPhotoDir(context, workId, date).listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.length() > 0L }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun copyToRetainedSlot(
        context: Context,
        workId: Int,
        date: LocalDate,
        kind: String,
        slot: Int,
        source: File
    ): String? {
        if (!source.exists() || source.length() <= 0L) return null
        val dir = retainedPhotoDir(context, workId, date)
        val ext = source.extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
        val target = File(dir, "${kind}_${slot}.$ext")
        if (source.absolutePath != target.absolutePath) {
            source.copyTo(target, overwrite = true)
        }
        dir.listFiles()?.filter { it.isFile && it.name.startsWith("${kind}_${slot}.") && it.absolutePath != target.absolutePath }
            ?.forEach { it.delete() }
        return target.takeIf { it.exists() && it.length() > 0L }?.absolutePath
    }

    private fun recoverArchivedSlot(
        context: Context,
        workId: Int,
        date: LocalDate,
        kind: String,
        slot: Int
    ): String? {
        retainedSlotFile(context, workId, date, kind, slot)?.let { return it.absolutePath }
        val entry = PhotoEvidenceManifest.load(context, workId, date)
            .firstOrNull { it.kind == kind && it.slot == slot } ?: return null
        val privateFile = File(entry.privatePath)
        if (privateFile.exists() && privateFile.length() > 0L) {
            return copyToRetainedSlot(context, workId, date, kind, slot, privateFile)
        }
        if (entry.archiveUri.isBlank()) return null
        return runCatching {
            val temp = File.createTempFile("restore_${kind}_${slot}_", ".jpg", context.cacheDir)
            try {
                val uri = Uri.parse(entry.archiveUri)
                val input = if (uri.scheme.equals("content", true)) {
                    context.contentResolver.openInputStream(uri)
                } else {
                    File(entry.archiveUri).takeIf { it.exists() }?.inputStream()
                } ?: return@runCatching null
                input.use { source -> temp.outputStream().use { out -> source.copyTo(out) } }
                copyToRetainedSlot(context, workId, date, kind, slot, temp)
            } finally {
                temp.delete()
            }
        }.getOrNull()
    }

    fun retainSubmittedPhotoDraft(
        context: Context,
        workId: Int,
        date: LocalDate,
        receipt: List<String?>,
        store: List<String?>
    ): PhotoDraft {
        fun retain(kind: String, paths: List<String?>, max: Int): List<String?> {
            val result = MutableList<String?>(max) { null }
            for (slot in 0 until max) {
                val path = paths.getOrNull(slot)
                if (path.isNullOrBlank()) {
                    val dir = retainedPhotoDir(context, workId, date)
                    dir.listFiles()?.filter { it.isFile && it.name.startsWith("${kind}_${slot}.") }?.forEach { it.delete() }
                    continue
                }
                result[slot] = copyToRetainedSlot(context, workId, date, kind, slot, File(path))
                    ?: throw IllegalStateException(if (kind == "R") "ภาพบิล ${slot + 1} เปิดไม่ได้" else "ภาพร้าน ${slot + 1} เปิดไม่ได้")
            }
            val last = result.indexOfLast { !it.isNullOrBlank() }
            return if (last < 0) emptyList() else result.take(last + 1)
        }

        val retained = PhotoDraft(
            receiptPaths = retain("R", receipt, 3),
            storePaths = retain("S", store, 10)
        )
        savePhotoDraft(context, workId, date, retained.receiptPaths, retained.storePaths)
        return retained
    }

    fun loadPhotoDraft(context: Context, workId: Int, date: LocalDate): PhotoDraft {
        val k = "${workId}_${date}"
        val prefs = context.getSharedPreferences("photo_drafts", Context.MODE_PRIVATE)

        fun decodeSlots(raw: String, max: Int): List<String?> {
            if (raw.isBlank()) return emptyList()
            return raw.split("|", limit = max)
                .take(max)
                .map { path -> path.takeIf { it.isNotBlank() && File(it).exists() } }
        }

        fun merge(kind: String, saved: List<String?>, max: Int): List<String?> {
            val result = MutableList<String?>(max) { null }
            for (slot in 0 until max) {
                result[slot] = saved.getOrNull(slot) ?: recoverArchivedSlot(context, workId, date, kind, slot)
            }
            val last = result.indexOfLast { !it.isNullOrBlank() }
            return if (last < 0) emptyList() else result.take(last + 1)
        }

        val r = merge("R", decodeSlots(prefs.getString("$k.receipts", "") ?: "", 3), 3)
        val s = merge("S", decodeSlots(prefs.getString("$k.stores", "") ?: "", 10), 10)
        return PhotoDraft(r, s)
    }

    fun saveStoreWorkNote(context: Context, workId: Int, date: LocalDate, note: String) {
        context.getSharedPreferences("store_work_notes", Context.MODE_PRIVATE)
            .edit().putString("${workId}_${date}", note).apply()
    }

    fun loadStoreWorkNote(context: Context, workId: Int, date: LocalDate): String =
        context.getSharedPreferences("store_work_notes", Context.MODE_PRIVATE)
            .getString("${workId}_${date}", "") ?: ""

    fun saveOcrRawText(context: Context, workId: Int, date: LocalDate, imagePath: String, rawText: String) {
        val key = "${workId}_${date}_${imagePath.hashCode()}"
        context.getSharedPreferences("ocr_raw_text", Context.MODE_PRIVATE)
            .edit()
            .putString(key, rawText)
            .apply()
    }

    fun loadOcrRawText(context: Context, workId: Int, date: LocalDate, imagePath: String): String {
        val key = "${workId}_${date}_${imagePath.hashCode()}"
        return context.getSharedPreferences("ocr_raw_text", Context.MODE_PRIVATE)
            .getString(key, "") ?: ""
    }

    fun isSubmittedReceiptFingerprintUsed(context: Context, fingerprint: String): Boolean =
        context.getSharedPreferences("submitted_receipt_fingerprints", Context.MODE_PRIVATE)
            .getBoolean(fingerprint, false)

    fun markSubmittedReceiptFingerprint(context: Context, fingerprint: String) {
        context.getSharedPreferences("submitted_receipt_fingerprints", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(fingerprint, true)
            .apply()
    }

    fun isSubmittedImageHashUsed(context: Context, hash: String): Boolean =
        context.getSharedPreferences("submitted_image_hashes", Context.MODE_PRIVATE)
            .getBoolean(hash, false)

    fun markSubmittedImageHash(context: Context, hash: String) {
        context.getSharedPreferences("submitted_image_hashes", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(hash, true)
            .apply()
    }
}
