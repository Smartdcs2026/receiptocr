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

    /**
     * โหลดรูปแบบบิลของแบรนด์ โดยต้องได้ "รูปแบบบิลจริง" ก่อนจึงหยุดค้นหา
     * การพบเฉพาะกฎวันที่ไม่ถือว่าพบรูปแบบบิลแล้ว เพราะชื่อแบรนด์/ชื่อย่ออาจอยู่คนละคีย์
     */
    fun load(context: Context, brand: String, brandAbbr: String = ""): LoadedOcrTemplates {
        val directAliases = aliasesOf(brand, brandAbbr)
        val resolvedAliases = runCatching { resolveAliasesFromBrandList(directAliases) }.getOrDefault(emptyList())
        val candidates = distinctAliases(directAliases + resolvedAliases)

        var fallbackRule: BrandReceiptRule? = null
        var fallbackRuleJson: JSONObject? = null

        candidates.forEach { candidate ->
            val cloud = runCatching { fetchCloud(candidate) }.getOrNull()
            if (cloud.isNullOrBlank()) return@forEach

            val root = runCatching { JSONObject(cloud) }.getOrNull()
            val parsed = runCatching { parse(cloud, TemplateSource.CLOUD) }.getOrNull()
            if (parsed?.receiptRule != null && fallbackRule == null) {
                fallbackRule = parsed.receiptRule
                fallbackRuleJson = root?.optJSONObject("receiptRule")?.let { JSONObject(it.toString()) }
            }

            if (parsed != null && parsed.templates.isNotEmpty()) {
                val cacheRaw = mergeReceiptRule(cloud, fallbackRuleJson)
                saveCache(context, candidates, cacheRaw)
                return runCatching { parse(cacheRaw, TemplateSource.CLOUD) }
                    .getOrDefault(parsed.copy(receiptRule = parsed.receiptRule ?: fallbackRule))
            }
        }

        // ไม่มีรูปแบบใหม่จากระบบ จึงค่อยใช้รูปแบบที่บันทึกไว้ในเครื่อง
        val cached = loadCachedFromAliases(context, candidates)
        if (cached != null && cached.templates.isNotEmpty()) {
            return cached.copy(receiptRule = cached.receiptRule ?: fallbackRule)
        }

        val reference = candidates.asSequence()
            .map(::referenceTemplates)
            .firstOrNull { it.templates.isNotEmpty() }
        if (reference != null) return reference.copy(receiptRule = fallbackRule)

        // อาจมีเพียงกฎวันที่ แต่ยังไม่มีรูปแบบบิล
        return LoadedOcrTemplates(
            templates = emptyList(),
            source = if (fallbackRule != null) TemplateSource.CLOUD else TemplateSource.NONE,
            receiptRule = fallbackRule
        )
    }

    fun loadCached(context: Context, brand: String, brandAbbr: String = ""): LoadedOcrTemplates {
        val directAliases = aliasesOf(brand, brandAbbr)
        val cached = loadCachedFromAliases(context, directAliases)
        if (cached != null && cached.templates.isNotEmpty()) return cached

        return directAliases.asSequence()
            .map(::referenceTemplates)
            .firstOrNull { it.templates.isNotEmpty() }
            ?: cached
            ?: LoadedOcrTemplates(emptyList(), TemplateSource.NONE)
    }

    private fun loadCachedFromAliases(context: Context, aliases: List<String>): LoadedOcrTemplates? {
        var ruleOnly: LoadedOcrTemplates? = null
        aliases.forEach { alias ->
            val cached = readCache(context, alias)
            if (cached.isNullOrBlank()) return@forEach
            val parsed = runCatching { parse(cached, TemplateSource.CACHE) }.getOrNull() ?: return@forEach
            if (parsed.templates.isNotEmpty()) return parsed
            if (ruleOnly == null && parsed.receiptRule != null) ruleOnly = parsed
        }
        return ruleOnly
    }

    private fun fetchCloud(brand: String): String {
        val encoded = URLEncoder.encode(brand, StandardCharsets.UTF_8.toString()).replace("+", "%20")
        return get("$TEMPLATE_API_BASE_URL/api/brands/$encoded/ocr-templates")
    }

    /**
     * ถ้าแผนงานใช้ชื่อย่อ แต่หน้าจัดการบิลบันทึกด้วยชื่อเต็ม (หรือกลับกัน)
     * ให้ค้นจากรายการแบรนด์แล้วลองทั้งสองชื่อ โดยไม่ให้ผู้ใช้ต้องตั้งค่าซ้ำ
     */
    private fun resolveAliasesFromBrandList(inputAliases: List<String>): List<String> {
        if (inputAliases.isEmpty()) return emptyList()
        val raw = get("$TEMPLATE_API_BASE_URL/api/brands")
        val root = JSONObject(raw)
        val items = root.optJSONArray("items") ?: JSONArray()
        val wanted = inputAliases.map(::identityKey).filter { it.isNotBlank() }.toSet()
        val found = mutableListOf<String>()

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = item.optString("brand_name").trim().ifBlank { item.optString("brandName").trim() }
            val abbr = item.optString("brand_abbr").trim().ifBlank { item.optString("brandAbbr").trim() }
            val keys = listOf(name, abbr).map(::identityKey).filter { it.isNotBlank() }
            if (keys.any { it in wanted }) {
                if (name.isNotBlank()) found += name
                if (abbr.isNotBlank()) found += abbr
            }
        }
        return found
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
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
                parseTemplate(t)
                    ?.takeIf { OcrTemplateContract.validate(it).isEmpty() }
                    ?.let { add(it) }
            }
        }
            .filter { it.active }
            .sortedWith(
                compareByDescending<UniversalOcrTemplate> { it.priority }
                    .thenByDescending { it.version }
            )

        val updatedAt = if (items.length() > 0) items.optJSONObject(0)?.optString("updatedAt") else null
        return LoadedOcrTemplates(
            templates = templates,
            source = source,
            updatedAt = updatedAt?.takeIf { it.isNotBlank() },
            receiptRule = parseReceiptRule(
                root.optJSONObject("receiptRule"),
                templates.firstOrNull()?.brandId.orEmpty()
            )
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
                rowCount = recognition?.optInt("rowCount", parsedRows.size.coerceAtLeast(1))
                    ?: parsedRows.size.coerceAtLeast(1),
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
        )
    }

    private fun referenceTemplates(brand: String): LoadedOcrTemplates {
        val templates = ReferenceOcrTemplates.forBrand(brand)
        return LoadedOcrTemplates(
            templates = templates,
            source = if (templates.isEmpty()) TemplateSource.NONE else TemplateSource.REFERENCE
        )
    }

    private fun mergeReceiptRule(raw: String, fallbackRule: JSONObject?): String {
        if (fallbackRule == null) return raw
        return runCatching {
            val root = JSONObject(raw)
            if (root.optJSONObject("receiptRule") == null) {
                root.put("receiptRule", JSONObject(fallbackRule.toString()))
            }
            root.toString()
        }.getOrDefault(raw)
    }

    private fun aliasesOf(brand: String, brandAbbr: String): List<String> =
        distinctAliases(listOf(brand, brandAbbr))

    private fun distinctAliases(values: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<String>()
        values.forEach { raw ->
            val value = raw.trim()
            if (value.isBlank()) return@forEach
            val key = identityKey(value)
            if (seen.add(key)) result += value
        }
        return result
    }

    private fun identityKey(value: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9ก-๙]+"), "")

    private fun cacheKey(brand: String): String =
        "brand_" + brand.trim().lowercase().replace(Regex("[^a-z0-9ก-๙]+"), "_")

    private fun saveCache(context: Context, aliases: List<String>, raw: String) {
        val editor = context.getSharedPreferences(TEMPLATE_PREFS, Context.MODE_PRIVATE).edit()
        distinctAliases(aliases).forEach { alias -> editor.putString(cacheKey(alias), raw) }
        editor.apply()
    }

    private fun readCache(context: Context, brand: String): String? =
        context.getSharedPreferences(TEMPLATE_PREFS, Context.MODE_PRIVATE)
            .getString(cacheKey(brand), null)
}
