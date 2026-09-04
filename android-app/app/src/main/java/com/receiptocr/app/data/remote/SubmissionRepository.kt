package com.receiptocr.app.data.remote

import android.content.Context
import com.receiptocr.app.model.PosRecord
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val SUBMISSION_API_BASE_URL = "https://receiptocr-api.somchai147258.workers.dev"

object SubmissionRepository {
    fun submit(context: Context, workPlanItemId: Int, records: List<PosRecord>, storeNote: String): Long {
        val token = AppAuthRepository.token(context)
        if (token.isBlank()) throw IllegalStateException("กรุณาเข้าสู่ระบบใหม่")
        val payload = JSONObject().put("workPlanItemId", workPlanItemId).put("storeNote", storeNote).put("records", JSONArray().apply {
            records.forEach { r ->
                put(JSONObject().put("posNumber", r.posNumber).put("customerNo", r.customerNo)
                    .put("billDate", r.billDate).put("billTime", r.billTime).put("note", r.note)
                    .put("noReceipt", r.noReceipt).put("noReceiptReason", r.noReceiptReason).put("source", r.source)
                    .put("ocrConfidence", r.ocrConfidence).put("ocrTemplateName", r.ocrTemplateName)
                    .put("ocrCounterCycle", r.ocrCounterCycle)
                    .put("storeReviewConfirmed", r.storeReviewConfirmed)
                    .put("storeReviewReadId", r.storeReviewReadId)
                    .put("storeReviewExpectedId", r.storeReviewExpectedId)
                    .put("storeReviewConfirmedId", r.storeReviewConfirmedId)
                    .put("storeReviewConfirmedAt", r.storeReviewConfirmedAt)
                    .put("storeReviewConfirmedBy", r.storeReviewConfirmedBy))
            }
        })
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
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val error = runCatching {
                    val root = JSONObject(body)
                    val details = root.optJSONArray("details")
                    if (details != null && details.length() > 0) {
                        val first = details.optJSONObject(0)
                        val pos = first?.optInt("posNumber", 0) ?: 0
                        val message = first?.optString("message").orEmpty()
                        if (pos > 0 && message.isNotBlank()) "POS $pos: $message" else message
                    } else ""
                }.getOrDefault("")
                throw IllegalStateException(error.ifBlank { "ส่งข้อมูลไม่สำเร็จ กรุณาตรวจข้อมูลอีกครั้ง" })
            }
            JSONObject(body).optLong("submissionId", 0L)
        } finally { c.disconnect() }
    }
}
