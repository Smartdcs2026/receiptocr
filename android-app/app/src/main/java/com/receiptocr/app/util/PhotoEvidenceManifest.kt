package com.receiptocr.app.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

private const val PHOTO_EVIDENCE_PREFS = "photo_evidence_manifest"

data class PhotoEvidenceEntry(
    val kind: String,
    val slot: Int,
    val privatePath: String,
    val archiveUri: String,
    val source: String,
    val sizeBytes: Long,
    val savedAt: String
)

data class PhotoEvidenceSummary(
    val receiptCount: Int,
    val storeCount: Int,
    val archivedCount: Int,
    val missingCount: Int
)

/**
 * เก็บรายการภาพของงานแต่ละร้านแยกจากหน้าจอ เพื่อให้การถ่าย/เลือกภาพหลายครั้ง
 * หรือการกลับเข้าแอปใหม่ยังตรวจสอบได้ว่าภาพใดเป็นภาพบิลและภาพใดเป็นภาพร้าน
 */
object PhotoEvidenceManifest {
    private fun key(workId: Int, workDate: LocalDate): String = "${workId}_$workDate"

    fun record(
        context: Context,
        workId: Int,
        workDate: LocalDate,
        kind: String,
        slot: Int,
        privatePath: String,
        archiveUri: String?,
        source: String
    ) {
        val file = File(privatePath)
        if (!file.exists() || file.length() <= 0L) return
        val entries = load(context, workId, workDate).toMutableList()
        entries.removeAll { it.kind == kind && it.slot == slot }
        entries += PhotoEvidenceEntry(
            kind = kind,
            slot = slot,
            privatePath = file.absolutePath,
            archiveUri = archiveUri.orEmpty(),
            source = source,
            sizeBytes = file.length(),
            savedAt = LocalDateTime.now().toString()
        )
        save(context, workId, workDate, entries)
    }

    fun remove(
        context: Context,
        workId: Int,
        workDate: LocalDate,
        kind: String,
        slot: Int
    ) {
        val entries = load(context, workId, workDate).filterNot { it.kind == kind && it.slot == slot }
        save(context, workId, workDate, entries)
    }

    /**
     * ทำให้รายการหลักฐานตรงกับภาพที่ผู้ใช้เห็นอยู่ในงานปัจจุบัน
     * ถ้าพบไฟล์หายจะไม่สร้างข้อมูลปลอมขึ้นมา และจะรายงานเป็น missing แทน
     */
    fun reconcile(
        context: Context,
        workId: Int,
        workDate: LocalDate,
        receiptPaths: List<String?>,
        storePaths: List<String?>
    ) {
        val existing = load(context, workId, workDate)
            .associateBy { it.kind to it.slot }
            .toMutableMap()

        fun sync(kind: String, paths: List<String?>) {
            val validSlots = paths.indices.toSet()
            existing.keys.filter { it.first == kind && it.second !in validSlots }
                .toList().forEach(existing::remove)

            paths.forEachIndexed { slot, path ->
                val current = existing[kind to slot]
                if (path.isNullOrBlank()) {
                    existing.remove(kind to slot)
                    return@forEachIndexed
                }
                val file = File(path)
                if (!file.exists() || file.length() <= 0L) {
                    if (current != null) {
                        existing[kind to slot] = current.copy(privatePath = path, sizeBytes = 0L)
                    } else {
                        existing[kind to slot] = PhotoEvidenceEntry(
                            kind = kind,
                            slot = slot,
                            privatePath = path,
                            archiveUri = "",
                            source = "UNKNOWN",
                            sizeBytes = 0L,
                            savedAt = LocalDateTime.now().toString()
                        )
                    }
                } else if (current == null || current.privatePath != file.absolutePath) {
                    existing[kind to slot] = PhotoEvidenceEntry(
                        kind = kind,
                        slot = slot,
                        privatePath = file.absolutePath,
                        archiveUri = current?.archiveUri.orEmpty(),
                        source = current?.source?.takeIf { it.isNotBlank() } ?: "UNKNOWN",
                        sizeBytes = file.length(),
                        savedAt = current?.savedAt?.takeIf { it.isNotBlank() } ?: LocalDateTime.now().toString()
                    )
                } else if (current.sizeBytes != file.length()) {
                    existing[kind to slot] = current.copy(sizeBytes = file.length())
                }
            }
        }

        sync("R", receiptPaths)
        sync("S", storePaths)
        save(context, workId, workDate, existing.values.sortedWith(compareBy<PhotoEvidenceEntry> { it.kind }.thenBy { it.slot }))
    }

    fun missingSelected(
        receiptPaths: List<String?>,
        storePaths: List<String?>
    ): List<String> {
        val missing = mutableListOf<String>()
        receiptPaths.forEachIndexed { index, path ->
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (!file.exists() || file.length() <= 0L) missing += "ภาพบิล ${index + 1}"
            }
        }
        storePaths.forEachIndexed { index, path ->
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (!file.exists() || file.length() <= 0L) missing += "ภาพร้าน ${index + 1}"
            }
        }
        return missing
    }

    fun summary(context: Context, workId: Int, workDate: LocalDate): PhotoEvidenceSummary {
        val entries = load(context, workId, workDate)
        return PhotoEvidenceSummary(
            receiptCount = entries.count { it.kind == "R" && File(it.privatePath).let { f -> f.exists() && f.length() > 0L } },
            storeCount = entries.count { it.kind == "S" && File(it.privatePath).let { f -> f.exists() && f.length() > 0L } },
            archivedCount = entries.count { it.archiveUri.isNotBlank() },
            missingCount = entries.count { !File(it.privatePath).let { f -> f.exists() && f.length() > 0L } }
        )
    }

    fun load(context: Context, workId: Int, workDate: LocalDate): List<PhotoEvidenceEntry> {
        val raw = context.getSharedPreferences(PHOTO_EVIDENCE_PREFS, Context.MODE_PRIVATE)
            .getString(key(workId, workDate), "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(
                        PhotoEvidenceEntry(
                            kind = o.optString("kind"),
                            slot = o.optInt("slot", -1),
                            privatePath = o.optString("privatePath"),
                            archiveUri = o.optString("archiveUri"),
                            source = o.optString("source"),
                            sizeBytes = o.optLong("sizeBytes", 0L),
                            savedAt = o.optString("savedAt")
                        )
                    )
                }
            }.filter { it.kind.isNotBlank() && it.slot >= 0 && it.privatePath.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, workId: Int, workDate: LocalDate, entries: List<PhotoEvidenceEntry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("kind", e.kind)
                    .put("slot", e.slot)
                    .put("privatePath", e.privatePath)
                    .put("archiveUri", e.archiveUri)
                    .put("source", e.source)
                    .put("sizeBytes", e.sizeBytes)
                    .put("savedAt", e.savedAt)
            )
        }
        context.getSharedPreferences(PHOTO_EVIDENCE_PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(workId, workDate), array.toString())
            .commit()
    }
}
