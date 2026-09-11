package com.receiptocr.app.data.remote

import android.content.Context
import com.receiptocr.app.model.WorkItem

private const val STORE_HOURS_PREFS = "store_hours_overrides"

data class SavedStoreHours(
    val value: String,
    val updatedAt: Long,
    val source: String = "FIELD_CONFIRMED"
)

object StoreHoursRepository {
    private fun key(work: WorkItem): String = "${work.brand.trim()}|${work.storeCode.trim()}"

    fun normalize(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val compact = value.lowercase().replace(" ", "")
        if (
            compact in setOf("24ชั่วโมง", "24ชม", "24ชม.", "24h", "24hours", "24hour", "เปิด24ชั่วโมง", "ตลอด24ชั่วโมง", "00.00-00.00", "00:00-00:00")
        ) return "เปิด 24 ชั่วโมง"

        val match = Regex("""^(\d{1,2})[.:](\d{2})\s*[-–—]\s*(\d{1,2})[.:](\d{2})$""").matchEntire(value)
            ?: return null
        val openHour = match.groupValues[1].toIntOrNull() ?: return null
        val openMinute = match.groupValues[2].toIntOrNull() ?: return null
        val closeHour = match.groupValues[3].toIntOrNull() ?: return null
        val closeMinute = match.groupValues[4].toIntOrNull() ?: return null
        if (openHour !in 0..23 || closeHour !in 0..23 || openMinute !in 0..59 || closeMinute !in 0..59) return null
        if (openHour == closeHour && openMinute == closeMinute) return "เปิด 24 ชั่วโมง"
        return "%02d.%02d-%02d.%02d".format(openHour, openMinute, closeHour, closeMinute)
    }

    fun load(context: Context, work: WorkItem): SavedStoreHours? {
        val p = context.getSharedPreferences(STORE_HOURS_PREFS, Context.MODE_PRIVATE)
        val prefix = key(work)
        val value = p.getString("$prefix.value", "").orEmpty()
        if (value.isBlank()) return null
        return SavedStoreHours(
            value = value,
            updatedAt = p.getLong("$prefix.at", 0L),
            source = p.getString("$prefix.source", "FIELD_CONFIRMED").orEmpty().ifBlank { "FIELD_CONFIRMED" }
        )
    }

    fun save(context: Context, work: WorkItem, raw: String): SavedStoreHours {
        val normalized = normalize(raw) ?: throw IllegalArgumentException("เวลาเปิด-ปิดไม่ถูกต้อง")
        val saved = SavedStoreHours(normalized, System.currentTimeMillis())
        val prefix = key(work)
        context.getSharedPreferences(STORE_HOURS_PREFS, Context.MODE_PRIVATE).edit()
            .putString("$prefix.value", saved.value)
            .putLong("$prefix.at", saved.updatedAt)
            .putString("$prefix.source", saved.source)
            .apply()
        return saved
    }

    fun applySaved(context: Context, work: WorkItem): WorkItem {
        val saved = load(context, work) ?: return work
        return work.copy(openClose = saved.value)
    }

    fun displayValue(raw: String): String = normalize(raw) ?: raw.trim().ifBlank { "ยังไม่มีเวลาเปิด-ปิด" }
}
