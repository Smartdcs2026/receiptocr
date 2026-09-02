package com.receiptocr.app.data.remote

import android.content.Context
import com.receiptocr.app.config.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

private const val TEMPLATE_API_BASE_URL = "https://receiptocr-api.somchai147258.workers.dev"
private const val TEMPLATE_PREFS = "ocr_template_cache_v1"

object OcrTemplateRepository {

    fun load(context: Context, brand: String, brandAbbr: String = ""): LoadedOcrTemplates {
        val candidates = listOf(brand, brandAbbr).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        var receiptRuleFallback: BrandReceiptRule? = null

        candidates.forEach { candidate ->
            val cloud = runCatching { fetchCloud(candidate) }.getOrNull()
            if (!cloud.isNullOrBlank()) {
                val parsed = runCatching { parse(cloud, TemplateSource.CLOUD) }.getOrNull()
                if (parsed != null) {
                    if (receiptRuleFallback == null) receiptRuleFallback = parsed.receiptRule
                    if (parsed.templates.isNotEmpty()) {
                        // เก็บเฉพาะคำตอบที่มีรูปแบบบิลจริง ป้องกันข้อมูลกฎอย่างเดียวทับรูปแบบที่ใช้งานได้
                        saveCache(context, brand, cloud)
                        return if (parsed.receiptRule != null || receiptRuleFallback == null) parsed
                        else parsed.copy(receiptRule = receiptRuleFallback)
                    }
                }
            }
        }

        val cached = readCache(context, brand)
        if (!cached.isNullOrBlank()) {
            val parsed = runCatching { parse(cached, TemplateSource.CACHE) }.getOrNull()
            if (parsed != null && parsed.templates.isNotEmpty()) {
                return if (parsed.receiptRule != null || receiptRuleFallback == null) parsed
                else parsed.copy(receiptRule = receiptRuleFallback)
            }
        }

        val reference = candidates.asSequence().map(::referenceTemplates).firstOrNull { it.templates.isNotEmpty() }
            ?: LoadedOcrTemplates(emptyList(), TemplateSource.NONE)
        return if (reference.receiptRule != null || receiptRuleFallback == null) reference
        else reference.copy(receiptRule = receiptRuleFallback)
    }

    fun loadCached(context: Context, brand: String, brandAbbr: String = ""): LoadedOcrTemplates {
        val cached = readCache(context, brand)
        if (!cached.isNullOrBlank()) {
            val parsed = runCatching { parse(cached, TemplateSource.CACHE) }.getOrNull()
            if (parsed != null) return parsed
        }
        return listOf(brand, brandAbbr).asSequence().map { it.trim() }.filter { it.isNotBlank() }
            .map(::referenceTemplates).firstOrNull { it.templates.isNotEmpty() }
            ?: LoadedOcrTemplates(emptyList(), TemplateSource.NONE)
    }

    private fun fetchCloud(brand: String): String {
        val encoded = URLEncoder.encode(brand, StandardCharsets.UTF_8.toString()).replace("+", "%20")
        val conn = URL("$TEMPLATE_API_BASE_URL/api/brands/$encoded/ocr-templates")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/json")
        conn.useCaches = false
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(raw: String, source: TemplateSource): LoadedOcrTemplates {
        val root = JSONObject(raw)
        val items = root.optJSONArray("items") ?: JSONArray()
        val templates = buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val t = item.optJSONObject("template") ?: item
                parseTemplate(t)?.takeIf { OcrTemplateContract.validate(it).isEmpty() }?.let { add(it) }
            }
        }.filter { it.active }.sortedWith(compareByDescending<UniversalOcrTemplate> { it.priority }.thenByDescending { it.version })

        val updatedAt = if (items.length() > 0) items.optJSONObject(0)?.optString("updatedAt") else null
        return LoadedOcrTemplates(
            templates = templates,
            source = source,
            updatedAt = updatedAt?.takeIf { it.isNotBlank() },
            receiptRule = parseReceiptRule(root.optJSONObject("receiptRule"), templates.firstOrNull()?.brandId.orEmpty())
        )
    }

    private fun parseReceiptRule(root: JSONObject?, fallbackBrand: String): BrandReceiptRule? {
        if (root == null) return null
        val date = root.optJSONObject("groupDateRule") ?: return null
        return BrandReceiptRule(
            brandId = root.optString("brandId", fallbackBrand),
            dateWindowRule = ReceiptDateWindowRule(enabled = false),
            groupDateRule = ReceiptGroupDateRule(
                enabled = date.optBoolean("enabled", true),
                resetAtMonthEnd = date.optBoolean("resetAtMonthEnd", false),
                maxBeforeDays = date.optInt("maxBeforeDays", 2).coerceIn(0, 2),
                afterDaysWhenOldestIsMaxBefore = date.optInt("afterDaysWhenOldestIsMaxBefore", 0).coerceIn(0, 31),
                afterDaysWhenOldestIsOneDayBefore = date.optInt("afterDaysWhenOldestIsOneDayBefore", 2).coerceIn(0, 31),
                afterDaysWhenOldestIsWorkDay = date.optInt("afterDaysWhenOldestIsWorkDay", 2).coerceIn(0, 31),
                action = enumValueOfOrNull<RuleAction>(date.optString("action")) ?: RuleAction.BLOCK,
                warningText = date.optString("warningText", "วันที่บิลไม่อยู่ในช่วงที่ใช้ได้")
            ),
            preventDuplicateImage = root.optBoolean("preventDuplicateImage", true),
            preventDuplicateReceiptData = root.optBoolean("preventDuplicateReceiptData", true),
            customerCounterMode = enumValueOfOrNull<CustomerCounterMode>(root.optString("customerCounterMode"))
                ?: if (date.optBoolean("resetAtMonthEnd", false)) CustomerCounterMode.MONTHLY_RESET else CustomerCounterMode.CONTINUOUS
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOfOrNull(raw: String?): T? =
        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }

    private fun parseTemplate(o: JSONObject): UniversalOcrTemplate? {
        val templateId = o.optString("templateId").trim()
        val brandId = o.optString("brandId").trim()
        if (templateId.isBlank() || brandId.isBlank()) return null

        val recognition = o.optJSONObject("recognition")
        val rows = recognition?.optJSONArray("rows")
        val parsedRows = buildList {
            if (rows != null) {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val fields = row.optJSONArray("fields") ?: JSONArray()
                    val parsedFields = buildList {
                        for (j in 0 until fields.length()) {
                            val f = fields.optJSONObject(j) ?: continue
                            add(parseField(f))
                        }
                    }.sortedBy { it.order }
                    add(OcrTemplateRow(row.optInt("row", i + 1), parsedFields))
                }
            }
        }.sortedBy { it.row }

        val validation = o.optJSONObject("validation")
        val core = validation?.optJSONObject("requiredCore")
        val store = validation?.optJSONObject("store")
        val pos = validation?.optJSONObject("pos")
        val duplicate = o.optJSONObject("duplicatePolicy")

        return UniversalOcrTemplate(
            schemaVersion = o.optInt("schemaVersion", 3),
            templateId = templateId,
            brandId = brandId,
            templateName = o.optString("templateName", templateId),
            version = o.optInt("version", 1),
            priority = o.optInt("priority", 100),
            active = o.optBoolean("active", true),
            sampleText = o.optString("sampleText", ""),
            recognition = OcrTemplateRecognition(
                rowCount = recognition?.optInt("rowCount", parsedRows.size.coerceAtLeast(1)) ?: parsedRows.size.coerceAtLeast(1),
                groupAsSingleRecord = recognition?.optBoolean("groupAsSingleRecord", true) ?: true,
                deskewEnabled = recognition?.optBoolean("deskewEnabled", true) ?: true,
                layoutMode = recognition?.optString("layoutMode", "MIXED") ?: "MIXED",
                lineTolerance = (recognition?.optInt("lineTolerance", 1) ?: 1).coerceIn(0, 3),
                multiPosMode = recognition?.optString("multiPosMode", "AUTO") ?: "AUTO",
                searchScope = recognition?.optString("searchScope", "WHOLE_IMAGE") ?: "WHOLE_IMAGE",
                region = recognition?.optJSONObject("region")?.let { region ->
                    NormalizedRect(
                        left = region.optDouble("left", 0.0).toFloat(),
                        top = region.optDouble("top", 0.0).toFloat(),
                        right = region.optDouble("right", 1.0).toFloat(),
                        bottom = region.optDouble("bottom", 1.0).toFloat()
                    ).normalized()
                },
                rows = parsedRows
            ),
            validation = OcrTemplateValidation(
                requiredCore = OcrTemplateRequiredCore(
                    date = core?.optBoolean("date", true) ?: true,
                    time = core?.optBoolean("time", true) ?: true,
                    customerValue = core?.optBoolean("customerValue", true) ?: true
                ),
                store = OcrTemplateStoreValidation(
                    mustMatchWorkPlan = store?.optBoolean("mustMatchWorkPlan", true) ?: true,
                    sameStoreAcrossAllMatches = store?.optBoolean("sameStoreAcrossAllMatches", true) ?: true
                ),
                pos = OcrTemplatePosValidation(
                    mustExistInStorePlan = pos?.optBoolean("mustExistInStorePlan", true) ?: true,
                    mustBeUnique = pos?.optBoolean("mustBeUnique", true) ?: true
                )
            ),
            duplicatePolicy = OcrTemplateDuplicatePolicy(
                customerCounterCycle = duplicate?.optString("customerCounterCycle", "CONTINUOUS") ?: "CONTINUOUS",
                preventSameImageHash = duplicate?.optBoolean("preventSameImageHash", true) ?: true,
                preventSameReceiptKey = duplicate?.optBoolean("preventSameReceiptKey", true) ?: true
            )
        )
    }

    private fun parseField(f: JSONObject): OcrTemplateField {
        val c = f.optJSONObject("composite")
        val segs = c?.optJSONArray("segments")
        val composite = if (c != null) {
            OcrTemplateComposite(
                prefix = c.optString("prefix").takeIf { it.isNotBlank() && it != "null" },
                separator = c.optString("separator").takeIf { it.isNotBlank() && it != "null" },
                segments = buildList {
                    if (segs != null) {
                        for (i in 0 until segs.length()) {
                            val s = segs.optJSONObject(i) ?: continue
                            add(
                                OcrTemplateSegment(
                                    order = s.optInt("order", i + 1),
                                    type = s.optString("type"),
                                    length = s.optInt("length", 0),
                                    example = s.optString("example").takeIf { it.isNotBlank() && it != "null" }
                                )
                            )
                        }
                    }
                }.sortedBy { it.order }
            )
        } else null

        return OcrTemplateField(
            order = f.optInt("order", 1),
            type = f.optString("type"),
            example = f.optString("example").takeIf { it.isNotBlank() && it != "null" },
            required = f.optBoolean("required", true),
            minLength = f.optInt("minLength", 1),
            maxLength = f.optInt("maxLength", 12),
            format = f.optString("format", "ANY"),
            literal = f.optString("literal").takeIf { it.isNotBlank() && it != "null" },
            compareTo = f.optString("compareTo", "NONE"),
            posPrefixes = f.optString("posPrefixes").takeIf { it.isNotBlank() && it != "null" },
            posDigits = if (f.has("posDigits") && !f.isNull("posDigits")) f.optInt("posDigits", 2) else null,
            separatorValue = f.optString("separatorValue").takeIf { it.isNotBlank() && it != "null" },
            tokenGap = f.optInt("tokenGap", 0).coerceIn(0, 8),
            composite = composite
        ).alignLengthWithExample()
    }

    private fun referenceTemplates(brand: String): LoadedOcrTemplates {
        val templates = ReferenceOcrTemplates.forBrand(brand)
        return LoadedOcrTemplates(
            templates = templates,
            source = if (templates.isEmpty()) TemplateSource.NONE else TemplateSource.REFERENCE
        )
    }

    private fun cacheKey(brand: String): String =
        "brand_" + brand.trim().lowercase().replace(Regex("[^a-z0-9ก-๙]+"), "_")

    private fun saveCache(context: Context, brand: String, raw: String) {
        context.getSharedPreferences(TEMPLATE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(cacheKey(brand), raw).apply()
    }

    private fun readCache(context: Context, brand: String): String? =
        context.getSharedPreferences(TEMPLATE_PREFS, Context.MODE_PRIVATE)
            .getString(cacheKey(brand), null)
}
