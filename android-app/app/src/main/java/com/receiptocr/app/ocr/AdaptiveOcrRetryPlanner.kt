package com.receiptocr.app.ocr

import java.io.File

/**
 * Round101: ย้อนแกนการอ่านกลับมายึดพฤติกรรม Round98 เป็นฐานที่ผ่านการทดลองจริงดีกว่า Round100
 * แล้วเพิ่มรอบช่วยอ่านแบบระมัดระวังหลังจากกดอ่านภาพเดิมหลายครั้งเท่านั้น
 *
 * ระดับ 1-3 = ต้องรักษาพฤติกรรม Round98 เดิม
 * ระดับ 4   = เพิ่มเฉพาะการขยาย crop ข้อความเล็กแบบจำกัด ไม่แตะการอ่านครั้งแรก
 *
 * ตัว planner ไม่ตีความตัวเลข ไม่เติมข้อมูล และไม่เดาค่าบิล
 */
data class AdaptiveOcrRetryPlan(
    val level: Int,
    val addFineAdaptive: Boolean,
    val addFaintTextPass: Boolean,
    val addShiftedLineCrops: Boolean,
    val addCoarseAdaptive: Boolean,
    val addStrongEdgePass: Boolean,
    val addMicroLineCrops: Boolean,
    val addPrecisionUpscaleCrops: Boolean
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
            // ระดับ 1-3 ด้านล่างต้องคงค่าเดิมของ Round98
            addFineAdaptive = true,
            addFaintTextPass = true,
            addShiftedLineCrops = level >= 2,
            addCoarseAdaptive = level >= 2,
            addStrongEdgePass = level >= 3,
            addMicroLineCrops = level >= 3,
            // ความสามารถใหม่ของ Round101 เริ่มเฉพาะครั้งที่ 4
            addPrecisionUpscaleCrops = level >= 4
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
