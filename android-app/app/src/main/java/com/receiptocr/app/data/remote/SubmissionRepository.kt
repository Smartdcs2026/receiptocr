package com.receiptocr.app.data.remote

import android.content.Context
import com.receiptocr.app.model.PosRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val SUBMISSION_API_BASE_URL = "https://receiptocr-api.somchai147258.workers.dev"
private const val EVIDENCE_UPLOAD_PREFS = "submission_evidence_upload"

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
        if (latest.status.uppercase() !in setOf("SUBMITTED", "RETURNED")) return false

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

    private fun uploadEvidence(token: String, submissionId: Long, kind: String, slot: Int, path: String) {
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) {
            throw IllegalStateException(
                if (kind == "R") "ภาพบิล ${slot + 1} เปิดไม่ได้ กรุณาเลือกภาพใหม่"
                else "ภาพร้าน ${slot + 1} เปิดไม่ได้ กรุณาเลือกภาพใหม่"
            )
        }

        val boundary = "ReceiptOCR-${UUID.randomUUID()}"
        val c = URL("$SUBMISSION_API_BASE_URL/api/app/submissions/$submissionId/evidence")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10000
        c.readTimeout = 45000
        c.doOutput = true
        c.useCaches = false
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setChunkedStreamingMode(64 * 1024)

        try {
            DataOutputStream(BufferedOutputStream(c.outputStream)).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }
                field("kind", kind)
                field("slot", slot.toString())
                field("source", "APP")

                val mime = when (file.extension.lowercase()) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> "image/jpeg"
                }
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name.replace("\"", "")}\"\r\n")
                out.writeBytes("Content-Type: $mime\r\n\r\n")
                file.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                out.writeBytes("\r\n--$boundary--\r\n")
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
            val details = root.optJSONArray("details")
            if (details != null && details.length() > 0) {
                val first = details.optJSONObject(0)
                val pos = first?.optInt("posNumber", 0) ?: 0
                val message = first?.optString("message").orEmpty()
                if (pos > 0 && message.isNotBlank()) "POS $pos: $message" else message
            } else root.optString("error")
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
