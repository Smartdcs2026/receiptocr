package com.receiptocr.app.data.remote

import android.content.Context
import com.receiptocr.app.config.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

private const val API_BASE_URL = "https://receiptocr-api.somchai147258.workers.dev"
private const val PREFS_NAME = "ocr_profile_cache"

enum class ProfileSource {
    CLOUD,
    CACHE,
    FALLBACK
}

data class LoadedOcrConfig(
    val profile: AdminOcrProfile,
    val receiptRule: BrandReceiptRule,
    val source: ProfileSource,
    val updatedAt: String? = null
)

object OcrProfileRepository {

    /**
     * Online-first + offline cache:
     * 1) พยายามโหลด profile ล่าสุดจาก Cloudflare
     * 2) ถ้าสำเร็จ cache JSON ลงเครื่อง
     * 3) ถ้า network/API ล้ม ใช้ cache ล่าสุด
     * 4) ถ้าไม่มี cache ใช้ fallback ภายใน APK
     */
    fun load(context: Context, brand: String, brandAbbr: String = ""): LoadedOcrConfig {
        val candidates = listOf(brand, brandAbbr).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        candidates.forEach { candidate ->
            val cloud = runCatching { fetchCloud(candidate) }.getOrNull()
            if (cloud != null) {
                val parsed = runCatching { parsePackage(cloud.first, ProfileSource.CLOUD) }.getOrNull()
                if (parsed != null) {
                    saveCache(context, brand, cloud.first)
                    return parsed
                }
            }
        }

        val cached = readCache(context, brand)
        if (!cached.isNullOrBlank()) {
            return runCatching { parsePackage(cached, ProfileSource.CACHE) }
                .getOrElse { fallback(brand) }
        }

        return fallback(brand)
    }

    fun loadCachedOrFallback(context: Context, brand: String, brandAbbr: String = ""): LoadedOcrConfig {
        val cached = readCache(context, brand)
        if (!cached.isNullOrBlank()) {
            return runCatching { parsePackage(cached, ProfileSource.CACHE) }
                .getOrElse { fallback(brand) }
        }
        return fallback(brand.ifBlank { brandAbbr })
    }

    private fun fetchCloud(brand: String): Pair<String, Int> {
        val encoded = URLEncoder.encode(brand, StandardCharsets.UTF_8.toString())
            .replace("+", "%20")
        val connection = (URL("$API_BASE_URL/api/brands/$encoded/ocr-profile").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            useCaches = false
        }

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            body to code
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheKey(brand: String): String =
        "brand_" + brand.trim().lowercase().replace(Regex("[^a-z0-9ก-๙]+"), "_")

    private fun saveCache(context: Context, brand: String, json: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(cacheKey(brand), json)
            .apply()
    }

    private fun readCache(context: Context, brand: String): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(cacheKey(brand), null)

    private fun fallback(brand: String): LoadedOcrConfig =
        LoadedOcrConfig(
            profile = DemoAdminOcrProfiles.forBrand(brand),
            receiptRule = DemoReceiptRules.forBrand(brand),
            source = ProfileSource.FALLBACK
        )

    private fun parsePackage(raw: String, source: ProfileSource): LoadedOcrConfig {
        val root = JSONObject(raw)
        val p = root.getJSONObject("ocrProfile")
        val profile = AdminOcrProfile(
            profileId = p.getString("profileId"),
            brandId = p.getString("brandId"),
            profileName = p.optString("profileName", p.getString("profileId")),
            version = p.optLong("version", 1),
            active = p.optBoolean("active", true),
            processingScope = enumOrDefault(
                p.optString("processingScope"),
                OcrProcessingScope.WHOLE_IMAGE_ALL_POS
            ),
            regions = parseRegions(p.optJSONArray("regions")),
            uniquenessRule = parseUniqueness(p.optJSONObject("uniquenessRule"))
        )

        val rr = root.optJSONObject("receiptRule")
        val date = rr?.optJSONObject("dateWindowRule")
        val brandRule = BrandReceiptRule(
            brandId = profile.brandId,
            dateWindowRule = ReceiptDateWindowRule(
                enabled = date?.optBoolean("enabled", true) ?: true,
                beforeDays = date?.optInt("beforeDays", 2) ?: 2,
                afterDays = date?.optInt("afterDays", 2) ?: 2,
                action = enumOrDefault(
                    date?.optString("action").takeUnless { it.isNullOrBlank() }
                        ?: date?.optString("severity"),
                    RuleAction.WARNING
                ),
                warningText = date?.optString("warningText").takeUnless { it.isNullOrBlank() }
                    ?: date?.optString("message").takeUnless { it.isNullOrBlank() }
                    ?: "วันที่ไม่ตรงเงื่อนไข"
            ),
            preventDuplicateImage = rr?.optBoolean("preventDuplicateImage", true) ?: true,
            preventDuplicateReceiptData = rr?.optBoolean("preventDuplicateReceiptData", true) ?: true,
            storeIdentityRule = parseStoreIdentity(rr?.optJSONObject("storeIdentityRule")),
            customerCounterMode = enumOrDefault(
                rr?.optString("customerCounterMode"),
                CustomerCounterMode.UNSPECIFIED
            )
        )

        val serverMeta = root.optJSONObject("serverMeta")
        return LoadedOcrConfig(
            profile = profile,
            receiptRule = brandRule,
            source = source,
            updatedAt = serverMeta?.optString("updatedAt")?.takeIf { it.isNotBlank() }
        )
    }

    private fun parseRegions(array: JSONArray?): List<OcrRegionRule> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val r = o.optJSONObject("region") ?: continue
                add(
                    OcrRegionRule(
                        id = o.optString("id", "rule-$i"),
                        fieldType = enumOrDefault(o.optString("fieldType"), OcrFieldType.BILL_DATE),
                        region = NormalizedRect(
                            left = r.optDouble("left", 0.0).toFloat(),
                            top = r.optDouble("top", 0.0).toFloat(),
                            right = r.optDouble("right", 1.0).toFloat(),
                            bottom = r.optDouble("bottom", 1.0).toFloat()
                        ).normalized(),
                        matchMode = enumOrDefault(o.optString("matchMode"), OcrMatchMode.INSIDE_REGION),
                        valueType = enumOrDefault(o.optString("valueType"), OcrValueType.TEXT),
                        labelHints = jsonStrings(o.optJSONArray("labelHints")),
                        regexPattern = o.optString("regexPattern").takeIf { it.isNotBlank() && it != "null" },
                        required = o.optBoolean("required", false),
                        priority = o.optInt("priority", 100),
                        searchRadiusY = o.optDouble("searchRadiusY", 0.08).toFloat(),
                        allowMultiple = o.optBoolean("allowMultiple", false)
                    )
                )
            }
        }
    }

    private fun parseUniqueness(o: JSONObject?): ReceiptUniquenessRule {
        if (o == null) return ReceiptUniquenessRule()
        val fields = jsonStrings(o.optJSONArray("fields"))
            .mapNotNull { enumOrNull<OcrFieldType>(it) }
        return ReceiptUniquenessRule(
            enabled = o.optBoolean("enabled", true),
            fields = if (fields.isEmpty()) ReceiptUniquenessRule().fields else fields
        )
    }

    private fun parseStoreIdentity(o: JSONObject?): StoreIdentityRule {
        if (o == null) return StoreIdentityRule()
        return StoreIdentityRule(
            enabled = o.optBoolean("enabled", false),
            requiredTokens = jsonStrings(o.optJSONArray("requiredTokens")),
            requireAll = o.optBoolean("requireAll", false)
        )
    }

    private fun jsonStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(raw: String?): T? {
        if (raw.isNullOrBlank()) return null
        return enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        enumOrNull<T>(raw) ?: fallback
}
