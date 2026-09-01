package com.receiptocr.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.receiptocr.app.model.WorkItem
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.UUID

fun formatDate(date: LocalDate): String =
    String.format(Locale("th"), "%02d/%02d/%04d", date.dayOfMonth, date.monthValue, date.year)

fun thaiMonthName(month: YearMonth): String {
    val months = listOf("มกราคม","กุมภาพันธ์","มีนาคม","เมษายน","พฤษภาคม","มิถุนายน",
        "กรกฎาคม","สิงหาคม","กันยายน","ตุลาคม","พฤศจิกายน","ธันวาคม")
    return "${months[month.monthValue-1]} ${month.year}"
}

fun openMap(context: Context, item: WorkItem) {
    val label = Uri.encode("${item.storeCode} ${item.storeName}")
    val uri = Uri.parse("geo:${item.latitude},${item.longitude}?q=${item.latitude},${item.longitude}($label)")
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}

fun launchCameraFile(context: Context): Pair<File, Uri> {
    val dir = File(context.filesDir, "photos").apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return file to uri
}

fun copyUriToPrivateFile(context: Context, sourceUri: Uri): File? {
    return try {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val target = File(dir, "gallery_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")

        val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return null
        inputStream.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }

        target
    } catch (_: Exception) {
        null
    }
}

fun shareLocalImage(context: Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "แชร์ภาพ"))
}
