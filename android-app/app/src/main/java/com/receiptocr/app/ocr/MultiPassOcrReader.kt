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
import kotlin.math.sqrt

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
 * OCR หลายมุมมองสำหรับบิลจริง
 * - ภาพต้นฉบับ
 * - contrast หลายระดับ
 * - local contrast สำหรับบิลซีดไม่เท่ากัน
 * - adaptive threshold + ซ่อมรอยขาด 1 pixel
 * - หมุนเล็กน้อยสองทิศสำหรับแถวเอียง
 * - crop แนวนอนแบบเหลื่อมและแคบ เพื่อไม่ให้ข้อมูลคนละ POS ไหลข้ามกัน
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

        fun addPass(bitmap: Bitmap, originX: Int = 0, originY: Int = 0, owned: Boolean = true) {
            if (owned) bitmaps += bitmap
            tasks += recognizer.process(InputImage.fromBitmap(bitmap, 0))
            passOrigins += originX to originY
        }

        addPass(source, owned = false)
        val qualityWarnings = inspectImageQuality(source)

        val softContrast = enhanceForText(source, contrast = 1.12f, brightness = -5f)
        val enhanced = enhanceForText(source, contrast = 1.35f, brightness = 0f)
        val highContrast = enhanceForText(source, contrast = 1.72f, brightness = 6f)
        // ภาพมืดลงเล็กน้อยช่วยเก็บส่วนที่เกือบขาวจากแสงสะท้อน แต่ไม่สามารถกู้ pixel ที่ขาวล้วนได้
        val glareSafe = enhanceForText(source, contrast = 1.28f, brightness = -22f)
        val localContrast = localContrastNormalize(source)
        val sharpened = sharpenForText(enhanced)
        val adaptive = blockAdaptiveThreshold(localContrast)
        val repaired = bridgeBrokenStrokes(adaptive)
        val rotateLeft = rotateSameSize(localContrast, -2.5f)
        val rotateRight = rotateSameSize(localContrast, 2.5f)

        addPass(softContrast)
        addPass(enhanced)
        addPass(highContrast)
        addPass(glareSafe)
        addPass(localContrast)
        addPass(sharpened)
        addPass(adaptive)
        addPass(repaired)
        addPass(rotateLeft)
        addPass(rotateRight)

        // crop ให้แคบกว่ารอบก่อนเพื่อแยกบิล/POS ที่อยู่ใกล้กัน
        val passCount = (expectedRecords * 2 + 3).coerceIn(9, 15)
        val cropRatio = (0.72f / expectedRecords.coerceAtLeast(1)).coerceIn(0.14f, 0.26f)
        val cropHeight = (source.height * cropRatio).toInt().coerceIn(1, source.height)
        val travel = (source.height - cropHeight).coerceAtLeast(0)
        val tops = (0 until passCount).map { index ->
            if (passCount == 1) 0 else (travel.toLong() * index / (passCount - 1)).toInt()
        }.distinct()

        tops.forEachIndexed { index, top ->
            val cropSource = when (index % 4) {
                0 -> localContrast
                1 -> sharpened
                2 -> adaptive
                else -> repaired
            }
            val crop = Bitmap.createBitmap(cropSource, 0, top, cropSource.width, cropHeight)
            addPass(crop, originY = top)
        }

        return Tasks.whenAllSuccess<Text>(tasks).continueWith { completed ->
            try {
                if (!completed.isSuccessful) {
                    throw completed.exception ?: IllegalStateException("อ่านข้อความไม่สำเร็จ")
                }
                val results = completed.result.orEmpty()
                // ต้องรวม origin ใน dedup เพราะข้อความเหมือนกันอาจมาจากบิลคนละตำแหน่ง
                val unique = results.mapIndexed { index, text ->
                    val origin = passOrigins.getOrElse(index) { 0 to 0 }
                    OcrTextPass(text = text, originX = origin.first, originY = origin.second)
                }.distinctBy {
                    "${it.originX}:${it.originY}:" + normalizeForDedup(it.text.text)
                }
                MultiPassOcrResult(
                    passes = unique,
                    imageWidth = source.width,
                    imageHeight = source.height,
                    rawText = unique.mapIndexed { index, pass ->
                        "--- รอบอ่าน ${index + 1} @${pass.originY} ---\n${pass.text.text.trim()}"
                    }.joinToString("\n"),
                    qualityWarnings = qualityWarnings
                )
            } finally {
                bitmaps.distinct().forEach { if (!it.isRecycled) it.recycle() }
                if (!source.isRecycled) source.recycle()
            }
        }
    }

    private fun decodeBounded(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
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

    /** ปรับ contrast แยกเป็นพื้นที่ ช่วยบิลความร้อนที่ซีดหรือมีเงาไม่เท่ากันทั้งใบ */
    private fun localContrastNormalize(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        val output = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        val block = 64
        var top = 0
        while (top < height) {
            val bottom = (top + block).coerceAtMost(height)
            var left = 0
            while (left < width) {
                val right = (left + block).coerceAtMost(width)
                var sum = 0.0
                var sumSquares = 0.0
                var count = 0
                for (y in top until bottom) {
                    val row = y * width
                    for (x in left until right) {
                        val v = Color.red(input[row + x]).toDouble()
                        sum += v
                        sumSquares += v * v
                        count++
                    }
                }
                val mean = if (count == 0) 128.0 else sum / count
                val variance = if (count == 0) 0.0 else (sumSquares / count - mean * mean).coerceAtLeast(0.0)
                val sd = sqrt(variance).coerceAtLeast(1.0)
                val gain = (62.0 / sd).coerceIn(0.90, 2.35)
                for (y in top until bottom) {
                    val row = y * width
                    for (x in left until right) {
                        val v = Color.red(input[row + x]).toDouble()
                        val adjusted = (128.0 + (v - mean) * gain).toInt().coerceIn(0, 255)
                        output[row + x] = Color.rgb(adjusted, adjusted, adjusted)
                    }
                }
                left += block
            }
            top += block
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

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

    /** เชื่อมเฉพาะช่องว่าง 1 pixel ที่มีหมึกอยู่สองข้าง ลดอักษรขาดโดยไม่ขยายตัวอักษรทั้งก้อน */
    private fun bridgeBrokenStrokes(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        val output = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        input.copyInto(output)
        fun dark(i: Int) = Color.red(input[i]) < 80
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                if (Color.red(input[i]) < 200) continue
                val bridgeHorizontal = dark(i - 1) && dark(i + 1)
                val bridgeVertical = dark(i - width) && dark(i + width)
                if (bridgeHorizontal || bridgeVertical) output[i] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun rotateSameSize(source: Bitmap, degrees: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        canvas.rotate(degrees, source.width / 2f, source.height / 2f)
        canvas.drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return output
    }

    private fun inspectImageQuality(source: Bitmap): List<String> {
        val stepX = (source.width / 96).coerceAtLeast(1)
        val stepY = (source.height / 96).coerceAtLeast(1)
        var count = 0L
        var sum = 0.0
        var sumSquares = 0.0
        var saturated = 0L
        var veryDark = 0L
        var y = 0
        while (y < source.height) {
            var x = 0
            while (x < source.width) {
                val color = source.getPixel(x, y)
                val luminance = (
                    Color.red(color) * 0.299 +
                        Color.green(color) * 0.587 +
                        Color.blue(color) * 0.114
                    )
                sum += luminance
                sumSquares += luminance * luminance
                if (luminance >= 250.0) saturated++
                if (luminance <= 28.0) veryDark++
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0L) return emptyList()
        val mean = sum / count
        val variance = (sumSquares / count - mean * mean).coerceAtLeast(0.0)
        val saturatedRatio = saturated.toDouble() / count.toDouble()
        val darkRatio = veryDark.toDouble() / count.toDouble()
        val warnings = mutableListOf<String>()
        if (mean < 55.0 || darkRatio > 0.35) warnings += "ภาพค่อนข้างมืด • ระบบปรับภาพให้อัตโนมัติแล้ว กรุณาตรวจผล"
        if (mean > 235.0) warnings += "ภาพสว่างมาก • ระบบพยายามดึงข้อความจางให้อัตโนมัติแล้ว"
        if (variance < 180.0) warnings += "ตัวอักษรกับพื้นกระดาษต่างกันน้อย • ระบบเพิ่ม contrast เฉพาะพื้นที่แล้ว"
        if (saturatedRatio > 0.18) warnings += "พบพื้นที่ขาวจัด/แสงสะท้อนมาก • หากข้อมูลตรงนั้นหายจริงควรถ่ายเพิ่มอีกมุม"
        return warnings.distinct()
    }

    private fun normalizeForDedup(value: String): String = value
        .uppercase()
        .replace(Regex("\\s+"), "")
}
