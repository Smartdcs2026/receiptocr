package com.receiptocr.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.receiptocr.app.model.WorkItem
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

private const val PHOTO_CAPTURE_PREFS = "photo_capture_recovery"

data class PendingPhotoCapture(
    val workId: Int,
    val workDate: String,
    val kind: String,
    val index: Int,
    val filePath: String
)

/**
 * เก็บสถานะก่อนเปิดกล้อง/ตัวเลือกรูปไว้ในเครื่อง เพื่อให้กลับมาทำงานต่อได้
 * แม้ Android จะสร้าง Activity ใหม่ระหว่างที่แอปกล้องทำงานอยู่
 */
object PhotoCaptureRecovery {
    fun begin(
        context: Context,
        workId: Int,
        workDate: LocalDate,
        kind: String,
        index: Int,
        filePath: String = ""
    ) {
        context.getSharedPreferences(PHOTO_CAPTURE_PREFS, Context.MODE_PRIVATE).edit()
            .putInt("workId", workId)
            .putString("workDate", workDate.toString())
            .putString("kind", kind)
            .putInt("index", index)
            .putString("filePath", filePath)
            .putBoolean("active", true)
            .commit()
    }

    fun load(context: Context): PendingPhotoCapture? {
        val prefs = context.getSharedPreferences(PHOTO_CAPTURE_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("active", false)) return null
        val kind = prefs.getString("kind", "").orEmpty()
        val date = prefs.getString("workDate", "").orEmpty()
        val index = prefs.getInt("index", -1)
        val workId = prefs.getInt("workId", -1)
        if (kind.isBlank() || date.isBlank() || index < 0 || workId < 0) return null
        return PendingPhotoCapture(
            workId = workId,
            workDate = date,
            kind = kind,
            index = index,
            filePath = prefs.getString("filePath", "").orEmpty()
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PHOTO_CAPTURE_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}

/**
 * เก็บสำเนาภาพลง Pictures/ReceiptOCR แบบที่ผู้ใช้มองเห็นจากโทรศัพท์
 * โดยยังคงไฟล์ทำงานภายในแอปไว้สำหรับการอ่านบิลและงานออฟไลน์
 */
object PhotoDeviceArchive {
    fun archive(
        context: Context,
        sourceFile: File,
        work: WorkItem,
        workDate: LocalDate,
        kind: String,
        slot: Int
    ): String? {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return null
        val safeStore = sanitize(work.storeCode.ifBlank { "STORE_${work.id}" })
        val category = if (kind == "R") "บิล" else "ร้าน"
        val relative = "${Environment.DIRECTORY_PICTURES}/ReceiptOCR/$workDate/$safeStore/$category"
        val fileName = buildString {
            append(safeStore)
            append('_')
            append(if (kind == "R") "BILL" else "STORE")
            append('_')
            append(slot + 1)
            append('_')
            append(System.currentTimeMillis())
            append(".jpg")
        }

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                archiveScoped(context, sourceFile, relative, fileName)
            } else {
                archiveLegacy(sourceFile, workDate, safeStore, category, fileName)
            }
        }.getOrNull()
    }

    private fun archiveScoped(
        context: Context,
        sourceFile: File,
        relative: String,
        fileName: String
    ): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relative)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("เปิดพื้นที่เก็บภาพไม่ได้")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri.toString()
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveLegacy(
        sourceFile: File,
        workDate: LocalDate,
        safeStore: String,
        category: String,
        fileName: String
    ): String? {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = File(root, "ReceiptOCR/$workDate/$safeStore/$category").apply { mkdirs() }
        val target = File(dir, fileName)
        sourceFile.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target.absolutePath
    }

    private fun sanitize(value: String): String = value
        .trim()
        .replace(Regex("[^A-Za-z0-9ก-๙_-]+"), "_")
        .trim('_')
        .ifBlank { "STORE" }
}
