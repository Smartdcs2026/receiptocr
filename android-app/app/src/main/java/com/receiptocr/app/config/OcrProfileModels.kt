package com.receiptocr.app.config

/**
 * โครงสร้าง OCR Profile นี้ออกแบบให้ Web Admin เป็นผู้กำหนด
 * แล้ว APK โหลด config ตาม brand/profile/version มาใช้อัตโนมัติ
 *
 * พิกัดเป็น normalized 0.0..1.0 เพื่อไม่ผูกกับความละเอียดภาพ
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun normalized(): NormalizedRect = NormalizedRect(
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        right = right.coerceIn(0f, 1f),
        bottom = bottom.coerceIn(0f, 1f)
    )
}

enum class OcrFieldType {
    STORE_ID,
    POS_NUMBER,
    BILL_DATE,
    BILL_TIME,
    CUSTOMER_VALUE,
    RECEIPT_UNIQUE_KEY
}

enum class OcrMatchMode {
    INSIDE_REGION,
    NEAR_LABEL,
    REGEX,
    EXACT_TOKEN,
    PREFIX,
    SUFFIX
}

enum class OcrValueType {
    TEXT,
    INTEGER,
    DATE,
    TIME
}

enum class OcrProcessingScope {
    WHOLE_IMAGE_ALL_POS
}

/**
 * กฎอ่าน field หนึ่งชนิด
 *
 * region:
 * - Admin วาด ROI บนตัวอย่างบิล
 * - ถ้าเป็น full image = fallback / template ที่ยังไม่ได้ calibrate
 *
 * labelHints:
 * - เช่น DATE, วันที่, TIME, เวลา, POS, TERMINAL, ลูกค้า
 *
 * regexPattern:
 * - Admin สามารถระบุ regex เฉพาะรูปแบบบิลของแบรนด์นั้น
 *
 * searchRadiusY:
 * - ใช้กรณี NEAR_LABEL เพื่อยอมให้ค่าจริงอยู่เหนือ/ใต้ label เล็กน้อย
 */
data class OcrRegionRule(
    val id: String,
    val fieldType: OcrFieldType,
    val region: NormalizedRect,
    val matchMode: OcrMatchMode = OcrMatchMode.INSIDE_REGION,
    val valueType: OcrValueType = OcrValueType.TEXT,
    val labelHints: List<String> = emptyList(),
    val regexPattern: String? = null,
    val required: Boolean = false,
    val priority: Int = 100,
    val searchRadiusY: Float = 0.08f,
    val allowMultiple: Boolean = false
)

data class ReceiptUniquenessRule(
    val enabled: Boolean = true,
    val fields: List<OcrFieldType> = listOf(
        OcrFieldType.STORE_ID,
        OcrFieldType.POS_NUMBER,
        OcrFieldType.BILL_DATE,
        OcrFieldType.BILL_TIME,
        OcrFieldType.CUSTOMER_VALUE
    )
)

data class AdminOcrProfile(
    val profileId: String,
    val brandId: String,
    val profileName: String,
    val version: Long,
    val active: Boolean = true,
    val processingScope: OcrProcessingScope = OcrProcessingScope.WHOLE_IMAGE_ALL_POS,
    val regions: List<OcrRegionRule> = emptyList(),
    val uniquenessRule: ReceiptUniquenessRule = ReceiptUniquenessRule()
)

/**
 * Demo profile เป็นเพียง fallback ใน APK ระหว่างที่ Web Admin/API ยังไม่พร้อม
 * Production ต้องแทนที่ provider นี้ด้วย config ที่ดาวน์โหลดจาก backend
 */
object DemoAdminOcrProfiles {
    private val full = NormalizedRect(0f, 0f, 1f, 1f)

    fun forBrand(brand: String): AdminOcrProfile = AdminOcrProfile(
        profileId = "demo-${brand.lowercase().replace(" ", "-")}",
        brandId = brand,
        profileName = "Generic full-image fallback",
        version = 1,
        regions = listOf(
            OcrRegionRule(
                id = "pos",
                fieldType = OcrFieldType.POS_NUMBER,
                region = full,
                matchMode = OcrMatchMode.NEAR_LABEL,
                valueType = OcrValueType.INTEGER,
                labelHints = listOf("POS", "P.O.S", "TERMINAL", "เครื่อง"),
                regexPattern = "(?i)(?:POS|P\\.?O\\.?S\\.?|TERMINAL|เครื่อง)\\s*(?:NO\\.?|NUMBER|#|:|-)?\\s*(\\d{1,2})",
                required = true,
                priority = 10,
                allowMultiple = true
            ),
            OcrRegionRule(
                id = "date",
                fieldType = OcrFieldType.BILL_DATE,
                region = full,
                matchMode = OcrMatchMode.NEAR_LABEL,
                valueType = OcrValueType.DATE,
                labelHints = listOf("DATE", "วันที่"),
                regexPattern = "\\b\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}\\b",
                priority = 1
            ),
            OcrRegionRule(
                id = "time",
                fieldType = OcrFieldType.BILL_TIME,
                region = full,
                matchMode = OcrMatchMode.NEAR_LABEL,
                valueType = OcrValueType.TIME,
                labelHints = listOf("TIME", "เวลา"),
                regexPattern = "\\b(?:[01]?\\d|2[0-3])[:.]([0-5]\\d)\\b",
                priority = 20
            ),
            OcrRegionRule(
                id = "customer",
                fieldType = OcrFieldType.CUSTOMER_VALUE,
                region = full,
                matchMode = OcrMatchMode.NEAR_LABEL,
                valueType = OcrValueType.INTEGER,
                labelHints = listOf(
                    "CUSTOMER", "CUSTOMERS", "CUST", "ลูกค้า", "ยอดลูกค้า",
                    "COUNT", "TRANSACTION", "TRANS"
                ),
                regexPattern = "\\b\\d{1,9}\\b",
                priority = 30
            ),
            OcrRegionRule(
                id = "store",
                fieldType = OcrFieldType.STORE_ID,
                region = full,
                matchMode = OcrMatchMode.NEAR_LABEL,
                valueType = OcrValueType.TEXT,
                labelHints = listOf("STORE", "BRANCH", "SHOP", "สาขา", "ร้าน"),
                priority = 40
            )
        )
    )
}


/**
 * ลำดับการค้นหา field เริ่มต้นเมื่อ Admin ยังไม่ได้บังคับ priority เอง
 * วันที่มาก่อนเป็นค่าเริ่มต้นตาม requirement
 */
val DEFAULT_OCR_SEARCH_ORDER = listOf(
    OcrFieldType.BILL_DATE,
    OcrFieldType.POS_NUMBER,
    OcrFieldType.STORE_ID,
    OcrFieldType.BILL_TIME,
    OcrFieldType.CUSTOMER_VALUE,
    OcrFieldType.RECEIPT_UNIQUE_KEY
)
