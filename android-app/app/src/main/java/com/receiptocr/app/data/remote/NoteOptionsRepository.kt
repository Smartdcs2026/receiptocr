package com.receiptocr.app.data.remote

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val NOTE_OPTIONS_API = "https://receiptocr-api.somchai147258.workers.dev/api/note-options"
private const val NOTE_OPTIONS_PREFS = "note_options_cache_v1"
private const val NOTE_OPTIONS_KEY = "items"

enum class NoteOptionCategory {
    POS_NOTE,
    STORE_NOTE,
    NO_RECEIPT_REASON
}

data class NoteOption(
    val id: String,
    val category: NoteOptionCategory,
    val label: String,
    val sortOrder: Int = 100
)

data class LoadedNoteOptions(val items: List<NoteOption>) {
    fun labels(category: NoteOptionCategory): List<String> = items
        .filter { it.category == category }
        .sortedWith(compareBy<NoteOption> { it.sortOrder }.thenBy { it.label })
        .map { it.label }
        .distinct()
}

object NoteOptionsRepository {
    fun load(context: Context): LoadedNoteOptions {
        val cloud = runCatching { fetch() }.getOrNull()
        if (!cloud.isNullOrBlank()) {
            context.getSharedPreferences(NOTE_OPTIONS_PREFS, Context.MODE_PRIVATE)
                .edit().putString(NOTE_OPTIONS_KEY, cloud).apply()
            return runCatching { parse(cloud) }.getOrElse { fallback() }
        }
        return loadCached(context)
    }

    fun loadCached(context: Context): LoadedNoteOptions {
        val raw = context.getSharedPreferences(NOTE_OPTIONS_PREFS, Context.MODE_PRIVATE)
            .getString(NOTE_OPTIONS_KEY, null)
        return if (raw.isNullOrBlank()) fallback()
        else runCatching { parse(raw) }.getOrElse { fallback() }
    }

    private fun fetch(): String {
        val c = URL(NOTE_OPTIONS_API).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json")
        c.useCaches = false
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            body
        } finally {
            c.disconnect()
        }
    }

    private fun parse(raw: String): LoadedNoteOptions {
        val rows = JSONObject(raw).optJSONArray("items")
        val items = buildList {
            if (rows != null) for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val category = runCatching {
                    NoteOptionCategory.valueOf(row.optString("category").uppercase())
                }.getOrNull() ?: continue
                val label = row.optString("label").trim()
                if (label.isBlank() || label == "อื่น ๆ") continue
                add(NoteOption(row.optString("id", "item-$i"), category, label, row.optInt("sortOrder", 100)))
            }
        }
        return LoadedNoteOptions(items)
    }

    private fun fallback(): LoadedNoteOptions = LoadedNoteOptions(
        listOf(
            "เครื่อง POS ไม่เปิดใช้งาน", "ไม่มีลูกค้า", "ไม่มีการขาย",
            "พนักงานไม่อนุญาต", "บิลอ่านไม่ได้", "เครื่องขัดข้อง"
        ).mapIndexed { index, label ->
            NoteOption("fallback-$index", NoteOptionCategory.NO_RECEIPT_REASON, label, (index + 1) * 10)
        }
    )
}
