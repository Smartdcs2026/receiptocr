package com.receiptocr.app.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import java.io.File

data class OcrTextPass(
    val text: Text,
    val originX: Int = 0,
    val originY: Int = 0
)

data class MultiPassOcrResult(
    val passes: List<OcrTextPass>,
    val imageWidth: Int,
    val imageHeight: Int,
    val rawText: String,
    val qualityWarnings: List<String> = emptyList()
) {
    val texts: List<Text> get() = passes.map { it.text }
}

/**
 * อ่านทั้งภาพและอ่านซ้ำเป็นช่วงแนวนอนที่เหลื่อมกัน
 * ช่วยภาพที่วางบิลหลายใบซ้อนกัน โดยไม่ผูกกับชื่อแบรนด์หรือรูปแบบบิลใด
 */
object MultiPassOcrReader {
    fun process(
        recognizer: TextRecognizer,
        file: File,
        expectedRecords: Int
    ): Task<MultiPassOcrResult> {
        val source = decodeBounded(file.absolutePath)
            ?: return Tasks.forException(IllegalArgumentException("ไม่สามารถเปิดภาพบิลได้"))

        val bitmaps = mutableListOf<Bitmap>()
        val tasks = mutableListOf<Task<Text>>()
        val passOrigins = mutableListOf<Pair<Int, Int>>()
        tasks += recognizer.process(InputImage.fromBitmap(source, 0))
        passOrigins += 0 to 0

        val qualityWarnings = inspectImageQuality(source)

        // อ่านภาพเต็มหลายระดับ ภาพสีรักษารายละเอียดเดิม ภาพขาวดำช่วยบิลซีด
        // และภาพขาวดำเข้มช่วยกรณีตัวอักษรความร้อนมีสีใกล้กับพื้นกระดาษ
        val softContrast = enhanceForText(source, contrast = 1.15f, brightness = -4f)
        val enhanced = enhanceForText(source, contrast = 1.35f, brightness = 0f)
        val highContrast = enhanceForText(source, contrast = 1.75f, brightness = 8f)
        val sharpened = sharpenForText(enhanced)
        val adaptive = blockAdaptiveThreshold(enhanced)
        bitmaps += softContrast
        bitmaps += enhanced
        bitmaps += highContrast
        bitmaps += sharpened
        bitmaps += adaptive
        tasks += recognizer.process(InputImage.fromBitmap(softContrast, 0))
        passOrigins += 0 to 0
        tasks += recognizer.process(InputImage.fromBitmap(enhanced, 0))
        passOrigins += 0 to 0
        tasks += recognizer.process(InputImage.fromBitmap(highContrast, 0))
        passOrigins += 0 to 0
        tasks += recognizer.process(InputImage.fromBitmap(sharpened, 0))
        passOrigins += 0 to 0
        tasks += recognizer.process(InputImage.fromBitmap(adaptive, 0))
        passOrigins += 0 to 0

        // แถบที่แคบและเหลื่อมกันช่วยแยกบิลซ้อนหลายใบออกจากกัน
        // จำนวนช่วงอิงจำนวน POS ในแผนงาน แต่ไม่ผูกกับชื่อแบรนด์
        val passCount = (expectedRecords * 2 + 1).coerceIn(7, 11)
        // ช่วงเดิมสูงเกินไปและมักครอบบิลซ้อนพร้อมกัน 2 ใบ ทำให้ ML Kit
        // รวมบรรทัดคนละ POS เข้าด้วยกัน จึงใช้ช่วงแคบลงแต่คงการเหลื่อมไว้
        val cropRatio = (0.86f / expectedRecords.coerceAtLeast(1)).coerceIn(0.18f, 0.30f)
        val cropHeight = (source.height * cropRatio).toInt().coerceIn(1, source.height)
        val travel = (source.height - cropHeight).coerceAtLeast(0)
        val tops = (0 until passCount).map { index ->
            if (passCount == 1) 0 else (travel.toLong() * index / (passCount - 1)).toInt()
        }.distinct()

        tops.forEachIndexed { index, top ->
            val cropSource = when (index % 3) {
                0 -> highContrast
                1 -> sharpened
                else -> adaptive
            }
            val crop = Bitmap.createBitmap(cropSource, 0, top, cropSource.width, cropHeight)
            bitmaps += crop
            tasks += recognizer.process(InputImage.fromBitmap(crop, 0))
            passOrigins += 0 to top
        }

        return Tasks.whenAllSuccess<Text>(tasks).continueWith { completed ->
            try {
                if (!completed.isSuccessful) {
                    throw completed.exception ?: IllegalStateException("อ่านข้อความไม่สำเร็จ")
                }
                val results = completed.result.orEmpty()
                val unique = results.mapIndexed { index, text ->
                    val origin = passOrigins.getOrElse(index) { 0 to 0 }
                    OcrTextPass(text = text, originX = origin.first, originY = origin.second)
                }.distinctBy { normalizeForDedup(it.text.text) }
                MultiPassOcrResult(
                    passes = unique,
                    imageWidth = source.width,
                    imageHeight = source.height,
                    rawText = unique.mapIndexed { index, pass ->
                        "--- รอบอ่าน ${index + 1} ---\n${pass.text.text.trim()}"
                    }.joinToString("\n"),
                    qualityWarnings = qualityWarnings
                )
            } finally {
                bitmaps.forEach { if (!it.isRecycled) it.recycle() }
                if (!source.isRecycled) source.recycle()
            }
        }
    }

    private fun decodeBounded(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        // ภาพจากกล้อง 12MP เคยถูกลดจากราว 4,000px เหลือประมาณ 1,000px
        // ทำให้ตัวเลขบนบิลหลายใบเล็กเกินกว่าที่ ML Kit จะรักษาไว้ได้
        // 2,800px ทำให้ภาพ 4,032px ใช้ inSampleSize=2 (ประมาณ 2,016px)
        // โดยยังควบคุมหน่วยความจำด้วยการอ่านแบบ power-of-two sampling
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 2800) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun enhanceForText(source: Bitmap, contrast: Float, brightness: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        val offset = (1f - contrast) * 128f + brightness
        grayscale.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, offset,
            0f, contrast, 0f, 0f, offset,
            0f, 0f, contrast, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )))
        Canvas(output).drawBitmap(
            source,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(grayscale)
            }
        )
        return output
    }

    /** เพิ่มขอบตัวอักษรโดยคงขนาดและตำแหน่งเดิม เพื่อให้ข้อความที่ขาดเล็กน้อยต่อเนื่องขึ้น */
    private fun sharpenForText(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        val output = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        input.copyInto(output)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val center = Color.red(input[i])
                val value = (center * 5 - Color.red(input[i - 1]) - Color.red(input[i + 1]) -
                    Color.red(input[i - width]) - Color.red(input[i + width])).coerceIn(0, 255)
                output[i] = Color.rgb(value, value, value)
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    /** แยกตัวอักษรจากพื้นกระดาษทีละพื้นที่ ช่วยบิลที่ซีดไม่เท่ากันหรือมีเงาบางส่วน */
    private fun blockAdaptiveThreshold(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        val output = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        val block = 48
        var top = 0
        while (top < height) {
            var left = 0
            val bottom = (top + block).coerceAtMost(height)
            while (left < width) {
                val right = (left + block).coerceAtMost(width)
                var sum = 0L
                var count = 0
                for (y in top until bottom) {
                    val row = y * width
                    for (x in left until right) {
                        sum += Color.red(input[row + x])
                        count++
                    }
                }
                val threshold = ((if (count == 0) 128 else sum / count).toInt() - 10).coerceIn(55, 225)
                for (y in top until bottom) {
                    val row = y * width
                    for (x in left until right) {
                        val value = if (Color.red(input[row + x]) < threshold) 0 else 255
                        output[row + x] = Color.rgb(value, value, value)
                    }
                }
                left += block
            }
            top += block
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    /** ตรวจคุณภาพแบบเบา ๆ จากจุดตัวอย่าง เพื่อเตือนโดยไม่ปฏิเสธภาพแทนผู้ใช้ */
    private fun inspectImageQuality(source: Bitmap): List<String> {
        val stepX = (source.width / 96).coerceAtLeast(1)
        val stepY = (source.height / 96).coerceAtLeast(1)
        var count = 0L
        var sum = 0.0
        var sumSquares = 0.0
        var y = 0
        while (y < source.height) {
            var x = 0
            while (x < source.width) {
                val color = source.getPixel(x, y)
                val luminance = (
                    android.graphics.Color.red(color) * 0.299 +
                        android.graphics.Color.green(color) * 0.587 +
                        android.graphics.Color.blue(color) * 0.114
                    )
                sum += luminance
                sumSquares += luminance * luminance
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0L) return emptyList()
        val mean = sum / count
        val variance = (sumSquares / count - mean * mean).coerceAtLeast(0.0)
        val warnings = mutableListOf<String>()
        if (mean < 55.0) warnings += "ภาพค่อนข้างมืด กรุณาตรวจข้อมูลกับภาพ"
        if (mean > 235.0) warnings += "ภาพสว่างมาก ตัวอักษรอาจจาง กรุณาตรวจข้อมูลกับภาพ"
        if (variance < 180.0) warnings += "ภาพมีความต่างสีต่ำ ตัวอักษรอาจไม่ชัด"
        return warnings
    }

    private fun normalizeForDedup(value: String): String = value
        .uppercase()
        .replace(Regex("\\s+"), "")
}
