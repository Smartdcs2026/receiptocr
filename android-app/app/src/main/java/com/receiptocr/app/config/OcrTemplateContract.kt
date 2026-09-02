package com.receiptocr.app.config

/** ข้อตกลงแม่แบบรุ่นเดียวกับหน้า Admin; แม่แบบที่เกินความสามารถจะไม่ถูกนำมาอ่านบิล */
object OcrTemplateContract {
    const val SCHEMA_VERSION = 4
    const val MAX_ROWS = 3
    val FIELD_TYPES = setOf(
        "BILL_DATE", "BILL_TIME", "CUSTOMER_VALUE", "STORE_ID", "POS_NUMBER",
        "YEAR_VALUE", "MONTH_VALUE", "DAY_VALUE", "EMPLOYEE_CODE", "COMPOSITE_CODE",
        "LITERAL", "SEPARATOR", "NUMBER_TEXT", "ALNUM_TEXT", "IGNORE"
    )
    val SEGMENT_TYPES = setOf(
        "LITERAL", "YEAR_VALUE", "MONTH_VALUE", "DAY_VALUE", "STORE_ID", "POS_NUMBER",
        "EMPLOYEE_CODE", "CUSTOMER_VALUE", "SEPARATOR", "NUMBER_TEXT", "ALNUM_TEXT", "IGNORE"
    )
    val COMPARE_TARGETS = setOf("NONE", "BILL_DATE", "WORK_DATE")
    val COUNTER_CYCLES = setOf("CONTINUOUS", "DAILY", "MONTHLY", "YEARLY")
    val DATE_ORDERS = setOf("DMY", "MDY", "YMD")
    val DATE_CALENDARS = setOf("AUTO", "GREGORIAN", "BUDDHIST")
    val DATE_YEAR_DIGITS = setOf(0, 2, 4)

    fun validate(template: UniversalOcrTemplate): List<String> {
        val errors = mutableListOf<String>()
        // รุ่น 3 จากระบบเดิมยังอ่านได้ ส่วนแม่แบบใหม่ต้องใช้รุ่น 4
        if (template.schemaVersion !in 3..SCHEMA_VERSION) errors += "schemaVersion"
        if (template.templateId.isBlank()) errors += "templateId"
        if (template.brandId.isBlank()) errors += "brandId"
        if (template.templateName.isBlank()) errors += "templateName"
        val rows = template.recognition.rows
        if (rows.isEmpty() || rows.size > MAX_ROWS) errors += "rows"
        var hasPos = false
        rows.forEach { row ->
            if (row.fields.isEmpty()) errors += "emptyRow"
            row.fields.forEach { field ->
                if (field.type !in FIELD_TYPES) errors += "field:${field.type}"
                if (field.minLength < 0 || field.maxLength < maxOf(1, field.minLength) || field.maxLength > 40) errors += "length:${field.type}"
                if (field.compareTo.uppercase() !in COMPARE_TARGETS) errors += "compare:${field.type}"
                if (field.type == "BILL_DATE") {
                    if (field.dateOrder.uppercase() !in DATE_ORDERS) errors += "dateOrder"
                    if (field.dateCalendar.uppercase() !in DATE_CALENDARS) errors += "dateCalendar"
                    if (field.dateYearDigits !in DATE_YEAR_DIGITS) errors += "dateYearDigits"
                }
                if (field.type == "POS_NUMBER") {
                    hasPos = true
                    if ((field.posDigits ?: 2) !in 1..6) errors += "posDigits"
                }
                if (field.type == "COMPOSITE_CODE") {
                    val segments = field.composite?.segments.orEmpty()
                    segments.forEach { segment ->
                        if (segment.type !in SEGMENT_TYPES) errors += "segment:${segment.type}"
                        if (segment.type == "POS_NUMBER") hasPos = true
                    }
                }
            }
        }
        if (!hasPos) errors += "missingPos"
        if (template.duplicatePolicy.customerCounterCycle.uppercase() !in COUNTER_CYCLES) errors += "counterCycle"
        return errors.distinct()
    }
}
