package com.receiptocr.app.data.remote

import android.content.Context
import com.receiptocr.app.model.PosRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val SUBMISSION_API_BASE_URL = "https://receiptocr-api.somchai147258.workers.dev"
private const val EVIDENCE_UPLOAD_PREFS = "submission_evidence_upload"
private const val EVIDENCE_PENDING_STATUS = "EVIDENCE_PENDING"

object SubmissionRepository {
    fun submit(
        context: Context,
        workPlanItemId: Int,
        records: List<PosRecord>,
        storeNote: String,
        storeLatitude: String = "",
        storeLongitude: String = "",
        receiptPaths: List<String?> = emptyList(),
        storePaths: List<String?> = emptyList()
    ): Long {
        val token = AppAuthRepository.token(context)
        if (token.isBlank()) throw IllegalStateException("กรุณาเข้าสู่ระบบใหม่")

        val pendingId = pendingSubmissionId(context, workPlanItemId)
        if (pendingId > 0L) {
            uploadAllEvidence(token, pendingId, receiptPaths, storePaths)
            finalizeSubmission(token, pendingId)
            clearPendingSubmission(context, workPlanItemId)
            return pendingId
        }

        val payload = JSONObject()
            .put("workPlanItemId", workPlanItemId)
            .put("storeNote", storeNote)
        if (storeLatitude.isNotBlank() && storeLongitude.isNotBlank()) {
            payload.put("storeLatitude", storeLatitude)
                .put("storeLongitude", storeLongitude)
                .put("storeLocationSource", "FIELD_CAPTURE")
        }
        payload.put("records", JSONArray().apply {
            records.forEach { r ->
                put(
                    JSONObject()
                        .put("posNumber", r.posNumber)
                        .put("customerNo", r.customerNo)
                        .put("billDate", r.billDate)
                        .put("billTime", r.billTime)
                        .put("note", r.note)
                        .put("noReceipt", r.noReceipt)
                        .put("noReceiptReason", r.noReceiptReason)
                        .put("source", r.source)
                        .put("ocrConfidence", r.ocrConfidence)
                        .put("ocrTemplateName", r.ocrTemplateName)
                        .put("ocrCounterCycle", r.ocrCounterCycle)
                        .put("storeReviewConfirmed", r.storeReviewConfirmed)
                        .put("storeReviewReadId", r.storeReviewReadId)
                        .put("storeReviewExpectedId", r.storeReviewExpectedId)
                        .put("storeReviewConfirmedId", r.storeReviewConfirmedId)
                        .put("storeReviewConfirmedAt", r.storeReviewConfirmedAt)
                        .put("storeReviewConfirmedBy", r.storeReviewConfirmedBy)
                )
            }
        })

        val submissionId = postSubmission(token, payload)
        if (submissionId <= 0L) throw IllegalStateException("ไม่ได้รับเลขงานจากระบบ")

        savePendingSubmission(context, workPlanItemId, submissionId)
        uploadAllEvidence(token, submissionId, receiptPaths, storePaths)
        finalizeSubmission(token, submissionId)
        clearPendingSubmission(context, workPlanItemId)
        return submissionId
    }

    fun syncEvidenceForLatestSubmission(
        context: Context,
        workPlanItemId: Int,
        receiptPaths: List<String?>,
        storePaths: List<String?>
    ): Boolean {
        val token = AppAuthRepository.token(context)
        if (token.isBlank()) return false
        val latest = latestSubmission(token, workPlanItemId) ?: return false
        if (latest.status.uppercase() !in setOf(EVIDENCE_PENDING_STATUS, "SUBMITTED", "RETURNED")) return false

        val existing = latest.evidenceSlots
        receiptPaths.take(3).forEachIndexed { slot, path ->
            if (!path.isNullOrBlank() && ("R" to slot) !in existing) {
                uploadEvidence(token, latest.submissionId, "R", slot, path)
            }
        }
        storePaths.take(10).forEachIndexed { slot, path ->
            if (!path.isNullOrBlank() && ("S" to slot) !in existing) {
                uploadEvidence(token, latest.submissionId, "S", slot, path)
            }
        }

        if (latest.status.equals(EVIDENCE_PENDING_STATUS, ignoreCase = true)) {
            finalizeSubmission(token, latest.submissionId)
            clearPendingSubmission(context, workPlanItemId)
        }
        return true
    }

    private fun postSubmission(token: String, payload: JSONObject): Long {
        val c = URL("$SUBMISSION_API_BASE_URL/api/app/submissions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 7000
        c.readTimeout = 12000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.outputStream.use { it.write(payload.toString().toByteArray()) }
        return try {
            val code = c.responseCode
            val body = responseBody(c, code)
            if (code !in 200..299) throw IllegalStateException(submissionError(body))
            JSONObject(body).optLong("submissionId", 0L)
        } finally {
            c.disconnect()
        }
    }

    private fun finalizeSubmission(token: String, submissionId: Long) {
        val c = URL("$SUBMISSION_API_BASE_URL/api/app/submissions/$submissionId/finalize")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 7000
        c.readTimeout = 12000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("{}".toByteArray()) }
        try {
            val code = c.responseCode
            val body = responseBody(c, code)
            if (code !in 200..299) throw IllegalStateException(submissionError(body))
            val root = runCatching { JSONObject(body) }.getOrNull()
            if (root != null && !root.optBoolean("ok", false)) {
                throw IllegalStateException("ระบบยังยืนยันหลักฐานไม่สำเร็จ กรุณาลองส่งอีกครั้ง")
            }
        } finally {
            c.disconnect()
        }
    }

    private data class LatestSubmission(
        val submissionId: Long,
        val status: String,
        val evidenceSlots: Set<Pair<String, Int>>
    )

    private fun latestSubmission(token: String, workPlanItemId: Int): LatestSubmission? {
        val c = URL("$SUBMISSION_API_BASE_URL/api/app/submissions/latest?workPlanItemId=$workPlanItemId")
            .openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 7000
        c.readTimeout = 10000
        c.setRequestProperty("Authorization", "Bearer $token")
        return try {
            val code = c.responseCode
            if (code == 404) return null
            val body = responseBody(c, code)
            if (code !in 200..299) return null
            val root = JSONObject(body)
            val slots = mutableSetOf<Pair<String, Int>>()
            val arr = root.optJSONArray("evidenceSlots") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                slots += item.optString("kind").uppercase() to item.optInt("slot", -1)
            }
            LatestSubmission(
                submissionId = root.optLong("submissionId", 0L),
                status = root.optString("status"),
                evidenceSlots = slots.filter { it.second >= 0 }.toSet()
            ).takeIf { it.submissionId > 0L }
        } finally {
            c.disconnect()
        }
    }

    private fun uploadAllEvidence(
        token: String,
        submissionId: Long,
        receiptPaths: List<String?>,
        storePaths: List<String?>
    ) {
        receiptPaths.take(3).forEachIndexed { slot, path ->
            if (!path.isNullOrBlank()) uploadEvidence(token, submissionId, "R", slot, path)
        }
        storePaths.take(10).forEachIndexed { slot, path ->
            if (!path.isNullOrBlank()) uploadEvidence(token, submissionId, "S", slot, path)
        }
    }

    /**
     * Round104.2: send the image body directly instead of multipart/form-data.
     * This removes multipart boundary/parser problems between Android HttpURLConnection
     * and Cloudflare Workers. Worker still accepts multipart for Admin browser uploads.
     */
    private fun uploadEvidence(token: String, submissionId: Long, kind: String, slot: Int, path: String) {
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) {
            throw IllegalStateException(
                if (kind == "R") "ภาพบิล ${slot + 1} เปิดไม่ได้ กรุณาเลือกภาพใหม่"
                else "ภาพร้าน ${slot + 1} เปิดไม่ได้ กรุณาเลือกภาพใหม่"
            )
        }
        if (file.length() > 15L * 1024L * 1024L) {
            throw IllegalStateException(
                if (kind == "R") "ภาพบิล ${slot + 1} มีขนาดเกิน 15 MB"
                else "ภาพร้าน ${slot + 1} มีขนาดเกิน 15 MB"
            )
        }

        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val c = URL("$SUBMISSION_API_BASE_URL/api/app/submissions/$submissionId/evidence")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10000
        c.readTimeout = 45000
        c.doOutput = true
        c.useCaches = false
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", mime)
        c.setRequestProperty("X-Evidence-Kind", kind)
        c.setRequestProperty("X-Evidence-Slot", slot.toString())
        c.setRequestProperty("X-Evidence-Source", "APP")
        c.setRequestProperty("X-Evidence-File-Name", file.name)
        c.setFixedLengthStreamingMode(file.length())

        try {
            c.outputStream.use { out ->
                file.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                out.flush()
            }
            val code = c.responseCode
            val body = responseBody(c, code)
            if (code !in 200..299) {
                val server = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                val label = if (kind == "R") "ภาพบิล ${slot + 1}" else "ภาพร้าน ${slot + 1}"
                throw IllegalStateException(
                    if (server.isBlank()) "ส่ง $label ไม่สำเร็จ กรุณาลองอีกครั้ง"
                    else "ส่ง $label ไม่สำเร็จ ($server)"
                )
            }
            val root = runCatching { JSONObject(body) }.getOrNull()
            val evidenceId = root?.optJSONObject("evidence")?.optLong("id", 0L) ?: 0L
            if (evidenceId <= 0L) {
                val label = if (kind == "R") "ภาพบิล ${slot + 1}" else "ภาพร้าน ${slot + 1}"
                throw IllegalStateException("Server ยังไม่ยืนยันการบันทึก $label กรุณาลองส่งอีกครั้ง")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun responseBody(c: HttpURLConnection, code: Int): String =
        (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()

    private fun submissionError(body: String): String {
        val detail = runCatching {
            val root = JSONObject(body)
            val error = root.optString("error")
            if (error == "EVIDENCE_REQUIRED") {
                val receiptCount = root.optInt("receiptCount", 0)
                val storeCount = root.optInt("storeCount", 0)
                return@runCatching when {
                    storeCount < 1 && receiptCount < 1 -> "ภาพบิลและภาพร้านยังส่งไม่ครบ กรุณาลองส่งอีกครั้ง"
                    storeCount < 1 -> "ภาพร้านยังส่งไม่ครบ กรุณาลองส่งอีกครั้ง"
                    else -> "ภาพบิลยังส่งไม่ครบ กรุณาลองส่งอีกครั้ง"
                }
            }
            val details = root.optJSONArray("details")
            if (details != null && details.length() > 0) {
                val first = details.optJSONObject(0)
                val pos = first?.optInt("posNumber", 0) ?: 0
                val message = first?.optString("message").orEmpty()
                if (pos > 0 && message.isNotBlank()) "POS $pos: $message" else message
            } else error
        }.getOrDefault("")
        return detail.ifBlank { "ส่งข้อมูลไม่สำเร็จ กรุณาตรวจข้อมูลอีกครั้ง" }
    }

    private fun pendingSubmissionId(context: Context, workPlanItemId: Int): Long =
        context.getSharedPreferences(EVIDENCE_UPLOAD_PREFS, Context.MODE_PRIVATE)
            .getLong(workPlanItemId.toString(), 0L)

    private fun savePendingSubmission(context: Context, workPlanItemId: Int, submissionId: Long) {
        context.getSharedPreferences(EVIDENCE_UPLOAD_PREFS, Context.MODE_PRIVATE)
            .edit().putLong(workPlanItemId.toString(), submissionId).commit()
    }

    private fun clearPendingSubmission(context: Context, workPlanItemId: Int) {
        context.getSharedPreferences(EVIDENCE_UPLOAD_PREFS, Context.MODE_PRIVATE)
            .edit().remove(workPlanItemId.toString()).commit()
    }
}
