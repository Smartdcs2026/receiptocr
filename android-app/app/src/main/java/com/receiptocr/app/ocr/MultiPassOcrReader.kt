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
 *
 * Round101:
 * - การอ่านครั้งที่ 1-3 รักษาแกน Round98 ซึ่งทดสอบจริงแล้วดีกว่า Round100
 * - ไม่ใช้การขยาย/หมุนภาพแบบ Round100 ในการอ่านครั้งแรก
 * - ครั้งที่ 4 จึงเพิ่ม precision upscale เฉพาะ crop บางช่วง เพื่อช่วยข้อความเล็ก
 * - ทุกวิธีเป็นเพียงหลักฐานจากภาพ ไม่เติม ไม่แก้ และไม่เดาตัวเลข
 */
object MultiPassOcrReader {
    fun process(
        recognizer: TextRecognizer,
        file: File,
        expectedRecords: Int
    ): Task<MultiPassOcrResult> {
        val retryPlan = AdaptiveOcrRetryPlanner.next(file)
        val source = decodeBounded(file.absolutePath)
            ?: return Tasks.forException(IllegalArgumentException("ไม่สามารถเปิดภาพบิลได้"))

        val bitmaps = mutableListOf<Bitmap>()
        val tasks = mutableListOf<Task<Text>>()
        val passOrigins = mutableListOf<Pair<Int, Int>>()
        tasks += recognizer.process(InputImage.fromBitmap(source, 0))
        passOrigins += 0 to 0

        val qualityWarnings = inspectImageQuality(source)

        // Baseline Round98: อ่านภาพเต็มหลายระดับทุกครั้ง ห้ามตัดออก
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

        // แถบกว้างที่เหลื่อมกันสำหรับหลายบิลในภาพเดียว — คงค่า Round98
        val passCount = (expectedRecords * 2 + 1).coerceIn(7, 11)
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

        // แถบบางสำหรับเลขชิดกัน — คงค่า Round98
        val linePassCount = (expectedRecords * 2 + 5).coerceIn(9, 13)
        val lineRatio = (0.42f / expectedRecords.coerceAtLeast(1)).coerceIn(0.09f, 0.15f)
        val lineHeight = (source.height * lineRatio).toInt().coerceIn(1, source.height)
        val lineTravel = (source.height - lineHeight).coerceAtLeast(0)
        val lineTops = (0 until linePassCount).map { index ->
            if (linePassCount == 1) 0 else (lineTravel.toLong() * index / (linePassCount - 1)).toInt()
        }.distinct()
        lineTops.forEachIndexed { index, top ->
            val cropSource = if (index % 2 == 0) sharpened else highContrast
            val crop = Bitmap.createBitmap(cropSource, 0, top, cropSource.width, lineHeight)
            bitmaps += crop
            tasks += recognizer.process(InputImage.fromBitmap(crop, 0))
            passOrigins += 0 to top
        }

        // Round98 ระดับ 1-2: ตัวอักษรจาง/เส้นขาด และช่วงกึ่งกลางระหว่างแถบเดิม
        if (retryPlan.addFineAdaptive || retryPlan.addFaintTextPass) {
            val fineAdaptive = blockAdaptiveThreshold(
                source = enhanced,
                block = 32,
                thresholdOffset = -4
            )
            val faintText = enhanceForText(source, contrast = 1.48f, brightness = -12f)
            bitmaps += fineAdaptive
            bitmaps += faintText

            if (retryPlan.addFineAdaptive) {
                tasks += recognizer.process(InputImage.fromBitmap(fineAdaptive, 0))
                passOrigins += 0 to 0
            }
            if (retryPlan.addFaintTextPass) {
                tasks += recognizer.process(InputImage.fromBitmap(faintText, 0))
                passOrigins += 0 to 0
            }

            if (retryPlan.addShiftedLineCrops && lineTops.size >= 2) {
                val shiftedTops = lineTops.zipWithNext { a, b -> (a + b) / 2 }.distinct()
                shiftedTops.forEachIndexed { index, top ->
                    val cropSource = if (index % 2 == 0) fineAdaptive else faintText
                    val crop = Bitmap.createBitmap(cropSource, 0, top, cropSource.width, lineHeight)
                    bitmaps += crop
                    tasks += recognizer.process(InputImage.fromBitmap(crop, 0))
                    passOrigins += 0 to top
                }
            }
        }

        // Round98 ระดับ 2-3: threshold อีกขนาด + ขอบเข้ม + micro crops
        if (retryPlan.addCoarseAdaptive || retryPlan.addStrongEdgePass) {
            val coarseAdaptive = blockAdaptiveThreshold(
                source = softContrast,
                block = 72,
                thresholdOffset = -14
            )
            val strongEdge = sharpenForText(highContrast)
            bitmaps += coarseAdaptive
            bitmaps += strongEdge

            if (retryPlan.addCoarseAdaptive) {
                tasks += recognizer.process(InputImage.fromBitmap(coarseAdaptive, 0))
                passOrigins += 0 to 0
            }
            if (retryPlan.addStrongEdgePass) {
                tasks += recognizer.process(InputImage.fromBitmap(strongEdge, 0))
                passOrigins += 0 to 0
            }

            if (retryPlan.addMicroLineCrops) {
                val microPassCount = (expectedRecords * 3 + 5).coerceIn(11, 17)
                val microRatio = (0.30f / expectedRecords.coerceAtLeast(1)).coerceIn(0.07f, 0.11f)
                val microHeight = (source.height * microRatio).toInt().coerceIn(1, source.height)
                val microTravel = (source.height - microHeight).coerceAtLeast(0)
                val microTops = (0 until microPassCount).map { index ->
                    if (microPassCount == 1) 0 else (microTravel.toLong() * index / (microPassCount - 1)).toInt()
                }.distinct()

                microTops.forEachIndexed { index, top ->
                    val cropSource = if (index % 2 == 0) strongEdge else coarseAdaptive
                    val crop = Bitmap.createBitmap(cropSource, 0, top, cropSource.width, microHeight)
                    bitmaps += crop
                    tasks += recognizer.process(InputImage.fromBitmap(crop, 0))
                    passOrigins += 0 to top
                }
            }
        }

        // Round101 ระดับ 4: เพิ่มเพียงวิธีเดียวหลัง Round98 ล้มเหลวครบสามรอบแล้ว
        // ขยายเฉพาะแถบกึ่งกลางบางช่วง ไม่ขยายทั้งภาพและไม่หมุนภาพแบบ Round100
        if (retryPlan.addPrecisionUpscaleCrops && lineTops.size >= 2) {
            val rescueTops = lineTops.zipWithNext { a, b -> (a + b) / 2 }.distinct()
            rescueTops.forEachIndexed { index, top ->
                if (index % 2 == 0) {
                    val crop = Bitmap.createBitmap(sharpened, 0, top, sharpened.width, lineHeight)
                    bitmaps += crop
                    val scaled = scaleForText(crop, requestedScale = 1.25f)
                    bitmaps += scaled
                    tasks += recognizer.process(InputImage.fromBitmap(scaled, 0))
                    passOrigins += 0 to top
                }
            }
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
                bitmaps.distinctBy { System.identityHashCode(it) }
                    .forEach { if (!it.isRecycled) it.recycle() }
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

    /** ขยายเฉพาะ crop เล็กและจำกัดด้านยาว เพื่อไม่สร้างภาพขนาดใหญ่เกินจำเป็น */
    private fun scaleForText(source: Bitmap, requestedScale: Float): Bitmap {
        val longest = maxOf(source.width, source.height).coerceAtLeast(1)
        val safeScale = minOf(requestedScale, 3000f / longest.toFloat()).coerceAtLeast(1f)
        val width = (source.width * safeScale).toInt().coerceAtLeast(1)
        val height = (source.height * safeScale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    /** เพิ่มขอบตัวอักษรโดยคงขนาดและตำแหน่งเดิม */
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

    /** แยกตัวอักษรจากพื้นกระดาษทีละพื้นที่ */
    private fun blockAdaptiveThreshold(
        source: Bitmap,
        block: Int = 48,
        thresholdOffset: Int = -10
    ): Bitmap {
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        val output = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        val safeBlock = block.coerceIn(16, 128)
        var top = 0
        while (top < height) {
            var left = 0
            val bottom = (top + safeBlock).coerceAtMost(height)
            while (left < width) {
                val right = (left + safeBlock).coerceAtMost(width)
                var sum = 0L
                var count = 0
                for (y in top until bottom) {
                    val row = y * width
                    for (x in left until right) {
                        sum += Color.red(input[row + x])
                        count++
                    }
                }
                val average = if (count == 0) 128 else (sum / count).toInt()
                val threshold = (average + thresholdOffset).coerceIn(55, 225)
                for (y in top until bottom) {
                    val row = y * width
                    for (x in left until right) {
                        val value = if (Color.red(input[row + x]) < threshold) 0 else 255
                        output[row + x] = Color.rgb(value, value, value)
                    }
                }
                left += safeBlock
            }
            top += safeBlock
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    /** ตรวจคุณภาพแบบเบา ๆ เพื่อเตือนโดยไม่ปฏิเสธภาพแทนผู้ใช้ */
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
                    Color.red(color) * 0.299 +
                        Color.green(color) * 0.587 +
                        Color.blue(color) * 0.114
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
