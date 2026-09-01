package com.receiptocr.app.ocr

import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class RebuiltOcrLine(
    val index: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class RebuiltOcrDocument(
    val lines: List<RebuiltOcrLine>,
    val estimatedSkewDegrees: Float
)

/**
 * จัดคำใหม่จาก bounding boxes เพื่อไม่ยึดลำดับ block ที่ ML Kit ส่งกลับมา
 * Round78 แยกกลุ่มแนวนอนที่ห่างกันมาก เพื่อไม่รวมข้อความของบิล/POS คนละใบเป็นแถวเดียวกัน
 */
object SpatialTextLayout {
    private data class Token(
        val text: String,
        val rect: Rect,
        val centerX: Float,
        val centerY: Float,
        val height: Float,
        val angle: Float
    )

    fun rebuild(text: Text, deskew: Boolean = true): RebuiltOcrDocument {
        val tokens = buildList {
            text.textBlocks.forEach { block ->
                block.lines.forEach { line ->
                    line.elements.forEach elementLoop@ { element ->
                        val rect = element.boundingBox ?: return@elementLoop
                        val value = element.text.trim()
                        if (value.isBlank()) return@elementLoop
                        add(Token(
                            text = value,
                            rect = rect,
                            centerX = rect.exactCenterX(),
                            centerY = rect.exactCenterY(),
                            height = rect.height().coerceAtLeast(1).toFloat(),
                            angle = angleOf(element.cornerPoints)
                        ))
                    }
                }
            }
        }

        if (tokens.isEmpty()) {
            val fallback = text.textBlocks.flatMap { it.lines }.mapIndexedNotNull { index, line ->
                val value = line.text.trim().replace(Regex("\\s+"), " ")
                val rect = line.boundingBox
                if (value.isBlank()) null else RebuiltOcrLine(
                    index, value,
                    rect?.left?.toFloat() ?: 0f,
                    rect?.top?.toFloat() ?: index.toFloat(),
                    rect?.right?.toFloat() ?: 0f,
                    rect?.bottom?.toFloat() ?: (index + 1).toFloat()
                )
            }
            return RebuiltOcrDocument(fallback, 0f)
        }

        val skew = if (deskew) median(tokens.map { it.angle }.filter { it in -15f..15f }) else 0f
        val radians = Math.toRadians((-skew).toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val rotated = tokens.map { token ->
            token to Pair(token.centerX * c - token.centerY * s, token.centerX * s + token.centerY * c)
        }.sortedWith(compareBy({ it.second.second }, { it.second.first }))

        val medianHeight = median(tokens.map { it.height }).coerceAtLeast(1f)
        val tolerance = medianHeight * 0.62f
        val yGroups = mutableListOf<MutableList<Pair<Token, Pair<Float, Float>>>>()
        rotated.forEach { item ->
            val y = item.second.second
            val target = yGroups.minByOrNull { group -> kotlin.math.abs(group.map { it.second.second }.average().toFloat() - y) }
            val targetY = target?.map { it.second.second }?.average()?.toFloat()
            if (target != null && targetY != null && kotlin.math.abs(targetY - y) <= tolerance) target += item
            else yGroups += mutableListOf(item)
        }

        // ML Kit อาจวางข้อความของบิลสองใบที่อยู่ระดับ Y ใกล้กันไว้ในบรรทัดเดียว
        // แยกเมื่อช่องว่างแนวนอนกว้างผิดปกติเมื่อเทียบกับความสูงตัวอักษร
        val groups = yGroups.flatMap { splitHorizontal(it, medianHeight) }

        val lines = groups
            .sortedWith(compareBy({ group -> group.map { it.second.second }.average() }, { group -> group.minOf { it.second.first } }))
            .mapIndexed { index, group ->
                val sorted = group.sortedBy { it.second.first }
                RebuiltOcrLine(
                    index = index,
                    text = sorted.joinToString(" ") { it.first.text }.replace(Regex("\\s+"), " ").trim(),
                    left = sorted.minOf { it.first.rect.left }.toFloat(),
                    top = sorted.minOf { it.first.rect.top }.toFloat(),
                    right = sorted.maxOf { it.first.rect.right }.toFloat(),
                    bottom = sorted.maxOf { it.first.rect.bottom }.toFloat()
                )
            }
            .filter { it.text.isNotBlank() }

        return RebuiltOcrDocument(lines, skew)
    }

    private fun splitHorizontal(
        group: List<Pair<Token, Pair<Float, Float>>>,
        medianHeight: Float
    ): List<List<Pair<Token, Pair<Float, Float>>>> {
        if (group.size <= 1) return listOf(group)
        val sorted = group.sortedBy { it.second.first }
        val gapLimit = medianHeight * 10f
        val result = mutableListOf<MutableList<Pair<Token, Pair<Float, Float>>>>()
        var current = mutableListOf(sorted.first())
        for (index in 1 until sorted.size) {
            val previous = sorted[index - 1].first.rect
            val next = sorted[index].first.rect
            val gap = (next.left - previous.right).toFloat()
            if (gap > gapLimit) {
                result += current
                current = mutableListOf()
            }
            current += sorted[index]
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun angleOf(points: Array<Point>?): Float {
        if (points == null || points.size < 2) return 0f
        val a = points[0]
        val b = points[1]
        return Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }
}
