package com.receiptocr.app.config

/**
 * แม่แบบอ้างอิงที่มากับ APK ใช้เมื่อเครื่องยังไม่เคยดาวน์โหลดแม่แบบจาก Admin เท่านั้น
 * แม่แบบ Cloud/Cache มีสิทธิ์สูงกว่าเสมอ เพื่อให้ Admin เปลี่ยนรูปแบบได้โดยไม่ต้องออก APK ใหม่
 */
object ReferenceOcrTemplates {
    fun forBrand(brand: String): List<UniversalOcrTemplate> {
        val key = brand.trim().lowercase().replace(Regex("[^a-z0-9ก-๙]+"), "")
        return when {
            key.contains("cj") -> listOf(cj(brand))
            key.contains("lgo") || key.contains("lgofresh") -> listOf(lgo(brand))
            else -> emptyList()
        }
    }

    private fun cj(brand: String) = UniversalOcrTemplate(
        schemaVersion = 3,
        templateId = "reference-cj-bno-s-v1",
        brandId = brand,
        templateName = "CJ วันที่ เวลา และ BNO",
        version = 1,
        priority = 80,
        recognition = OcrTemplateRecognition(
            deskewEnabled = true,
            layoutMode = "MIXED",
            lineTolerance = 2,
            rows = listOf(
                OcrTemplateRow(1, listOf(
                    OcrTemplateField(1, "BILL_DATE", minLength = 8, maxLength = 10),
                    OcrTemplateField(2, "BILL_TIME", minLength = 4, maxLength = 8),
                    OcrTemplateField(
                        order = 3,
                        type = "COMPOSITE_CODE",
                        minLength = 14,
                        maxLength = 30,
                        composite = OcrTemplateComposite(
                            prefix = "BNO:S",
                            separator = "-",
                            segments = listOf(
                                OcrTemplateSegment(1, "YEAR_VALUE", 2, "26"),
                                OcrTemplateSegment(2, "MONTH_VALUE", 2, "08"),
                                OcrTemplateSegment(3, "STORE_ID", 4, "0652"),
                                OcrTemplateSegment(4, "POS_NUMBER", 3, "N02"),
                                OcrTemplateSegment(5, "CUSTOMER_VALUE", 6, "004184")
                            )
                        )
                    )
                ))
            )
        ),
        duplicatePolicy = OcrTemplateDuplicatePolicy(customerCounterCycle = "MONTHLY")
    )

    private fun lgo(brand: String) = UniversalOcrTemplate(
        schemaVersion = 3,
        templateId = "reference-lgo-fresh-row-v1",
        brandId = brand,
        templateName = "L-go fresh ข้อมูลเรียงในแถว",
        version = 1,
        priority = 80,
        recognition = OcrTemplateRecognition(
            deskewEnabled = true,
            layoutMode = "MIXED",
            lineTolerance = 2,
            rows = listOf(
                OcrTemplateRow(1, listOf(
                    OcrTemplateField(1, "BILL_DATE", minLength = 8, maxLength = 10),
                    OcrTemplateField(2, "BILL_TIME", minLength = 4, maxLength = 8),
                    OcrTemplateField(3, "STORE_ID", minLength = 4, maxLength = 4),
                    OcrTemplateField(4, "POS_NUMBER", minLength = 3, maxLength = 3, posDigits = 3),
                    OcrTemplateField(5, "COMPOSITE_CODE", required = false, minLength = 8, maxLength = 8),
                    OcrTemplateField(6, "CUSTOMER_VALUE", minLength = 1, maxLength = 9)
                ))
            )
        )
    )
}
