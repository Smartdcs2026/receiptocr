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
 * Round98: การกดอ่านซ้ำภาพเดิมเพิ่มวิธีอ่านทีละระดับ โดยไม่ตัดวิธีเดิมออก
 * Round100: เพิ่มการขยายข้อความเล็ก และรอบช่วยภาพเอียง/พื้นที่ย่อยสำหรับภาพยาก
 * ทุกวิธีเพิ่มเฉพาะหลักฐานจากภาพ ไม่แก้หรือเดาตัวเลขแทนกฎของ Admin
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

        fun queue(bitmap: Bitmap, originX: Int = 0, originY: Int = 0) {
            bitmaps += bitmap
            tasks += recognizer.process(InputImage.fromBitmap(bitmap, 0))
            passOrigins += originX to originY
        }

        tasks += recognizer.process(InputImage.fromBitmap(source, 0))
        passOrigins += 0 to 0

        val qualityWarnings = inspectImageQuality(source)

        // Baseline เดิม: อ่านภาพเต็มหลายระดับทุกครั้ง ห้ามตัดออกเมื่อเพิ่มรอบใหม่
        val softContrast = enhanceForText(source, contrast = 1.15f, brightness = -4f)
        val enhanced = enhanceForText(source, contrast = 1.35f, brightness = 0f)
        val highContrast = enhanceForText(source, contrast = 1.75f, brightness = 8f)
        val sharpened = sharpenForText(enhanced)
        val adaptive = blockAdaptiveThreshold(enhanced)
        queue(softContrast)
        queue(enhanced)
        queue(highContrast)
        queue(sharpened)
        queue(adaptive)

        // แถบกว้างที่เหลื่อมกัน ช่วยแยกบิลหลายใบที่วางต่อกันในภาพเดียว
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
            queue(Bitmap.createBitmap(cropSource, 0, top, cropSource.width, cropHeight), originY = top)
        }

        // แถบบางสำหรับตัวเลขชิดกัน เช่นเลขชุดรหัส POS/ยอดลูกค้าในบิลซ้อน
        val linePassCount = (expectedRecords * 2 + 5).coerceIn(9, 13)
        val lineRatio = (0.42f / expectedRecords.coerceAtLeast(1)).coerceIn(0.09f, 0.15f)
        val lineHeight = (source.height * lineRatio).toInt().coerceIn(1, source.height)
        val lineTravel = (source.height - lineHeight).coerceAtLeast(0)
        val lineTops = (0 until linePassCount).map { index ->
            if (linePassCount == 1) 0 else (lineTravel.toLong() * index / (linePassCount - 1)).toInt()
        }.distinct()
        lineTops.forEachIndexed { index, top ->
            val cropSource = if (index % 2 == 0) sharpened else highContrast
            queue(Bitmap.createBitmap(cropSource, 0, top, cropSource.width, lineHeight), originY = top)
        }

        // Round98/100 ระดับ 1-2: ช่วยตัวอักษรจางและอ่านข้อความที่ตกตรงรอยต่อของแถบเดิม
        if (retryPlan.addFineAdaptive || retryPlan.addFaintTextPass) {
            val fineAdaptive = blockAdaptiveThreshold(
                source = enhanced,
                block = 32,
                thresholdOffset = -4
            )
            val faintText = enhanceForText(source, contrast = 1.48f, brightness = -12f)
            if (retryPlan.addFineAdaptive) queue(fineAdaptive) else bitmaps += fineAdaptive
            if (retryPlan.addFaintTextPass) queue(faintText) else bitmaps += faintText

            if (retryPlan.addShiftedLineCrops && lineTops.size >= 2) {
                val shiftedTops = lineTops.zipWithNext { a, b -> (a + b) / 2 }.distinct()
                shiftedTops.forEachIndexed { index, top ->
                    val cropSource = if (index % 2 == 0) fineAdaptive else faintText
                    val crop = Bitmap.createBitmap(cropSource, 0, top, cropSource.width, lineHeight)
                    queue(crop, originY = top)

                    // Round100: ขยายเฉพาะบางแถบ ไม่ขยายทั้งภาพ เพื่อลดหน่วยความจำ
                    // และช่วยกรณีเลขเล็ก/ตัวพิมพ์ความร้อนบางที่ ML Kit มองข้ามในขนาดเดิม
                    if (retryPlan.addUpscaledLineCrops && index % 3 == 1) {
                        queue(scaleForText(crop, requestedScale = 1.35f), originY = top)
                    }
                }
            }
        }

        // ระดับ 2-3: threshold คนละขนาด + ขอบตัวอักษรเข้ม + micro line crops
        if (retryPlan.addCoarseAdaptive || retryPlan.addStrongEdgePass) {
            val coarseAdaptive = blockAdaptiveThreshold(
                source = softContrast,
                block = 72,
                thresholdOffset = -14
            )
            val strongEdge = sharpenForText(highContrast)
            if (retryPlan.addCoarseAdaptive) queue(coarseAdaptive) else bitmaps += coarseAdaptive
            if (retryPlan.addStrongEdgePass) queue(strongEdge) else bitmaps += strongEdge

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
                    queue(crop, originY = top)
                    if (retryPlan.addUpscaledLineCrops && index % 4 == 2) {
                        queue(scaleForText(crop, requestedScale = 1.45f), originY = top)
                    }
                }
            }
        }

        // Round100 ระดับ 4: ใช้เฉพาะเมื่อผู้ใช้กดอ่านภาพเดิมหลายครั้งแล้ว
        // 1) มองภาพที่เอียงเล็กน้อยจากอีกสองมุม
        // 2) แบ่งภาพเป็นพื้นที่ย่อยแล้วขยาย เพื่อให้ข้อความเล็กมีขนาดใหญ่ขึ้นก่อนส่งเข้า ML Kit
        if (retryPlan.addSkewRescuePasses) {
            queue(rotateForText(enhanced, -1.35f))
            queue(rotateForText(enhanced, 1.35f))
        }

        if (retryPlan.addUpscaledGridCrops) {
            val tileWidth = (source.width * 0.64f).toInt().coerceIn(1, source.width)
            val tileHeight = (source.height * 0.52f).toInt().coerceIn(1, source.height)
            val xPositions = listOf(0, (source.width - tileWidth).coerceAtLeast(0)).distinct()
            val yPositions = listOf(0, (source.height - tileHeight).coerceAtLeast(0)).distinct()
            var tileIndex = 0
            yPositions.forEach { top ->
                xPositions.forEach { left ->
                    val tileSource = if (tileIndex++ % 2 == 0) sharpened else highContrast
                    val tile = Bitmap.createBitmap(tileSource, left, top, tileWidth, tileHeight)
                    bitmaps += tile
                    queue(scaleForText(tile, requestedScale = 1.25f), originX = left, originY = top)
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
                bitmaps.distinctBy { System.identityHashCode(it) }.forEach { if (!it.isRecycled) it.recycle() }
                if (!source.isRecycled) source.recycle()
            }
        }
    }

    private fun decodeBounded(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        // รักษารายละเอียดภาพกล้องไว้มากกว่ารุ่นเก่า แต่ยังคุมหน่วยความจำด้วย power-of-two sampling
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

    /** ขยายเฉพาะ crop เล็ก เพื่อช่วยข้อความเล็ก โดยจำกัดด้านยาวไม่เกิน 3200px */
    private fun scaleForText(source: Bitmap, requestedScale: Float): Bitmap {
        val longest = maxOf(source.width, source.height).coerceAtLeast(1)
        val safeScale = minOf(requestedScale, 3200f / longest.toFloat()).coerceAtLeast(1f)
        val width = (source.width * safeScale).toInt().coerceAtLeast(1)
        val height = (source.height * safeScale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    /** หมุนเพียงเล็กน้อยบนพื้นขาว ช่วยเส้นบิลที่เอียงโดยไม่เปลี่ยนข้อมูลในภาพ */
    private fun rotateForText(source: Bitmap, degrees: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        canvas.rotate(degrees, source.width / 2f, source.height / 2f)
        canvas.drawBitmap(
            source,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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
