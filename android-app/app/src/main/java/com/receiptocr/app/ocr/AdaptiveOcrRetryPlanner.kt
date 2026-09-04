package com.receiptocr.app.ocr

import java.io.File

/**
 * วางแผนการอ่านซ้ำแบบเพิ่มความพยายามทีละระดับ โดยไม่ตัดวิธีอ่านเดิมออก
 *
 * ระดับ 1 = เก็บวิธี Round97 ทั้งหมด + เพิ่มวิธีเสริมที่ปลอดภัยสำหรับตัวอักษรจาง/ขาด
 * ระดับ 2 = เพิ่มช่วงครอปที่เลื่อนตำแหน่งและ threshold อีกแบบ เพื่อหาอักขระที่รอบแรกตกหล่น
 * ระดับ 3 = เพิ่มวิธีเข้มขึ้นอีกสำหรับภาพยาก และคงใช้ระดับนี้เมื่อกดอ่านซ้ำต่อไป
 *
 * ตัว planner ไม่ตีความตัวเลขและไม่แก้ค่าบิล จึงไม่เกี่ยวกับการเดาข้อมูล
 */
data class AdaptiveOcrRetryPlan(
    val level: Int,
    val addFineAdaptive: Boolean,
    val addFaintTextPass: Boolean,
    val addShiftedLineCrops: Boolean,
    val addCoarseAdaptive: Boolean,
    val addStrongEdgePass: Boolean,
    val addMicroLineCrops: Boolean
)

object AdaptiveOcrRetryPlanner {
    private data class AttemptState(
        var count: Int,
        val touchedAt: Long
    )

    private const val MAX_LEVEL = 3
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
            addMicroLineCrops = level >= 3
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
