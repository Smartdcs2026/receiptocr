package com.receiptocr.app.data.remote

import android.content.Context
import com.receiptocr.app.data.DemoRepository
import com.receiptocr.app.model.WorkItem
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.YearMonth

private const val WORK_API_BASE_URL = "https://receiptocr-api.somchai147258.workers.dev"
private const val WORK_CACHE_PREFS = "work_plan_cache"

enum class WorkPlanSource {
    CLOUD,
    CACHE,
    FALLBACK
}

data class LoadedWorkPlan(
    val items: List<WorkItem>,
    val source: WorkPlanSource
)

object WorkPlanRepository {

    fun loadDay(context: Context, employeeCode: String, date: LocalDate): LoadedWorkPlan {
        val cloud = runCatching { fetchDay(employeeCode, date) }.getOrNull()
        if (cloud != null) {
            save(context, dayKey(employeeCode, date), cloud)
            return LoadedWorkPlan(parseItems(cloud), WorkPlanSource.CLOUD)
        }

        val cached = read(context, dayKey(employeeCode, date))
        if (!cached.isNullOrBlank()) {
            return runCatching {
                LoadedWorkPlan(parseItems(cached), WorkPlanSource.CACHE)
            }.getOrElse { fallback(date) }
        }

        return fallback(date)
    }

    fun loadCachedDay(context: Context, employeeCode: String, date: LocalDate): LoadedWorkPlan {
        val cached = read(context, dayKey(employeeCode, date))
        if (!cached.isNullOrBlank()) {
            return runCatching {
                LoadedWorkPlan(parseItems(cached), WorkPlanSource.CACHE)
            }.getOrElse { fallback(date) }
        }
        return fallback(date)
    }

    fun loadPlannedDays(context: Context, employeeCode: String, month: YearMonth): Set<LocalDate> {
        val cloud = runCatching { fetchMonth(employeeCode, month) }.getOrNull()
        if (cloud != null) {
            save(context, monthKey(employeeCode, month), cloud)
            return parseDays(cloud)
        }
        val cached = read(context, monthKey(employeeCode, month))
        if (!cached.isNullOrBlank()) {
            return runCatching { parseDays(cached) }.getOrDefault(emptySet())
        }
        return emptySet()
    }

    fun loadCachedPlannedDays(context: Context, employeeCode: String, month: YearMonth): Set<LocalDate> {
        val cached = read(context, monthKey(employeeCode, month))
        return if (cached.isNullOrBlank()) emptySet()
        else runCatching { parseDays(cached) }.getOrDefault(emptySet())
    }

    private fun fallback(date: LocalDate): LoadedWorkPlan =
        LoadedWorkPlan(DemoRepository.getWorkItems(date), WorkPlanSource.FALLBACK)

    private fun fetchDay(employeeCode: String, date: LocalDate): String {
        val user = enc(employeeCode)
        return get("$WORK_API_BASE_URL/api/users/$user/work-plan?date=$date")
    }

    private fun fetchMonth(employeeCode: String, month: YearMonth): String {
        val user = enc(employeeCode)
        return get("$WORK_API_BASE_URL/api/users/$user/planned-days?month=$month")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json")
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

    private fun JSONObject.cleanString(name: String, fallback: String = ""): String {
        if (!has(name) || isNull(name)) return fallback
        val value = optString(name, fallback).trim()
        return if (value.equals("null", true) || value.equals("undefined", true)) fallback else value
    }

    private fun JSONObject.receiptStoreId(): String =
        cleanString("receiptStoreId").ifBlank {
            cleanString("receipt_store_id").ifBlank {
                cleanString("billStoreCode").ifBlank { cleanString("bill_store_code") }
            }
        }

    private fun parseItems(raw: String): List<WorkItem> {
        val root = JSONObject(raw)
        val a = root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                add(
                    WorkItem(
                        id = o.optInt("id", i + 1),
                        brand = o.cleanString("brand"),
                        brandAbbr = o.cleanString("brandAbbr").ifBlank {
                            o.cleanString("brand").take(3).uppercase()
                        },
                        businessType = o.cleanString("businessType"),
                        storeCode = o.cleanString("storeCode"),
                        storeName = o.cleanString("storeName"),
                        posCount = o.optInt("posCount", 1).coerceAtLeast(1),
                        openClose = o.cleanString("openClose"),
                        address = o.cleanString("address"),
                        storeFormat = o.cleanString("storeFormat"),
                        rank = o.cleanString("rank"),
                        latitude = o.cleanString("latitude"),
                        longitude = o.cleanString("longitude"),
                        storeNote = o.cleanString("storeNote"),
                        receiptStoreId = o.receiptStoreId(),
                        reviewStatus = o.cleanString("reviewStatus"),
                        returnReason = o.cleanString("returnReason"),
                        planStatus = o.cleanString("planStatus", "ACTIVE"),
                        originWorkDate = o.cleanString("originWorkDate"),
                        movedToDate = o.cleanString("movedToDate"),
                        changeNote = o.cleanString("changeNote")
                    )
                )
            }
        }
    }

    private fun parseDays(raw: String): Set<LocalDate> {
        val root = JSONObject(raw)
        val a = root.optJSONArray("days") ?: return emptySet()
        return buildSet {
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i)?.optString("date").orEmpty()
                runCatching { LocalDate.parse(s) }.getOrNull()?.let { add(it) }
            }
        }
    }

    private fun dayKey(user: String, date: LocalDate) = "day_${user}_$date"
    private fun monthKey(user: String, month: YearMonth) = "month_${user}_$month"
    private fun save(context: Context, key: String, value: String) {
        context.getSharedPreferences(WORK_CACHE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
    }
    private fun read(context: Context, key: String): String? =
        context.getSharedPreferences(WORK_CACHE_PREFS, Context.MODE_PRIVATE).getString(key, null)

    private fun enc(v: String): String =
        URLEncoder.encode(v, StandardCharsets.UTF_8.toString()).replace("+", "%20")
}
