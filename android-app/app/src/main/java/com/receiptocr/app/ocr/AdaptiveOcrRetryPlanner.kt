package com.receiptocr.app.ocr

import java.io.File

/**
 * วางแผนการอ่านซ้ำแบบเพิ่มความพยายามทีละระดับ โดยไม่ตัดวิธีอ่านเดิมออก
 *
 * ระดับ 1 = เก็บวิธี Round97/98 ทั้งหมด + วิธีเสริมที่ปลอดภัยสำหรับตัวอักษรจาง/ขาด/ตัวเล็ก
 * ระดับ 2 = เพิ่มช่วงครอปที่เลื่อนตำแหน่งและ threshold อีกแบบ
 * ระดับ 3 = เพิ่มขอบตัวอักษรที่เข้มขึ้นและช่วงบรรทัดขนาดเล็กมาก
 * ระดับ 4 = รอบช่วยภาพยาก เพิ่มการอ่านภาพเอียงเล็กน้อยและครอปพื้นที่ย่อยที่ขยายใหญ่
 *
 * ทุกระดับเป็นการเพิ่มหลักฐาน ไม่แก้ตัวเลข ไม่เติมข้อมูล และไม่เดาค่าบิล
 */
data class AdaptiveOcrRetryPlan(
    val level: Int,
    val addFineAdaptive: Boolean,
    val addFaintTextPass: Boolean,
    val addShiftedLineCrops: Boolean,
    val addCoarseAdaptive: Boolean,
    val addStrongEdgePass: Boolean,
    val addMicroLineCrops: Boolean,
    val addUpscaledLineCrops: Boolean,
    val addSkewRescuePasses: Boolean,
    val addUpscaledGridCrops: Boolean
)

object AdaptiveOcrRetryPlanner {
    private data class AttemptState(
        var count: Int,
        val touchedAt: Long
    )

    private const val MAX_LEVEL = 4
    private const val MAX_TRACKED_IMAGES = 64
    private val attempts = linkedMapOf<String, AttemptState>()

    @Synchronized
    fun next(file: File): AdaptiveOcrRetryPlan {
        val key = imageKey(file)
        val previous = attempts[key]?.count ?: 0
        val next = (previous + 1).coerceAtMost(MAX_LEVEL)
        attempts[key] = AttemptState(next, System.currentTimeMillis())
        trimOldEntries()
        return planForAttempt(next)
    }

    fun planForAttempt(attempt: Int): AdaptiveOcrRetryPlan {
        val level = attempt.coerceIn(1, MAX_LEVEL)
        return AdaptiveOcrRetryPlan(
            level = level,
            addFineAdaptive = true,
            addFaintTextPass = true,
            addShiftedLineCrops = level >= 2,
            addCoarseAdaptive = level >= 2,
            addStrongEdgePass = level >= 3,
            addMicroLineCrops = level >= 3,
            // การขยายเพียงบางแถบเป็นวิธีเสริมที่ปลอดภัย จึงใช้ตั้งแต่รอบแรก
            addUpscaledLineCrops = true,
            addSkewRescuePasses = level >= 4,
            addUpscaledGridCrops = level >= 4
        )
    }

    @Synchronized
    fun reset(file: File) {
        attempts.remove(imageKey(file))
    }

    @Synchronized
    fun clear() {
        attempts.clear()
    }

    private fun imageKey(file: File): String = buildString {
        append(runCatching { file.canonicalPath }.getOrElse { file.absolutePath })
        append('|')
        append(file.length())
        append('|')
        append(file.lastModified())
    }

    private fun trimOldEntries() {
        if (attempts.size <= MAX_TRACKED_IMAGES) return
        val removeCount = attempts.size - MAX_TRACKED_IMAGES
        attempts.entries
            .sortedBy { it.value.touchedAt }
            .take(removeCount)
            .forEach { attempts.remove(it.key) }
    }
}
