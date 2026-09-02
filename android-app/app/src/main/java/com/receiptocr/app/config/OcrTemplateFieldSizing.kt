package com.receiptocr.app.config

/**
 * ทำให้จำนวนตัวอักษรที่ APK ใช้ตรงกับตัวอย่างที่ผู้ดูแลกำหนดไว้ในรูปแบบบิล
 * สำหรับช่องที่ตัวอย่างมีหน้าที่บอกความยาว เช่น 10 = 2 ตัว, 400040 = 6 ตัว
 * ส่วนวันที่ เวลา ข้อความคงที่ ตัวคั่น และยอด/เลขลูกค้า ใช้กติกาเฉพาะของตัวเอง
 */
internal fun OcrTemplateField.alignLengthWithExample(): OcrTemplateField {
    val sample = example?.trim().orEmpty()
    if (sample.isBlank()) return this

    val exactLengthTypes = setOf(
        "YEAR_VALUE", "YEAR",
        "MONTH_VALUE", "MONTH",
        "DAY_VALUE", "DAY",
        "STORE_ID",
        "POS_NUMBER",
        "EMPLOYEE_CODE",
        "NUMBER_TEXT",
        "ALNUM_TEXT"
    )
    if (type.uppercase() !in exactLengthTypes) return this

    val length = sample.length.coerceAtLeast(1)
    return copy(minLength = length, maxLength = length)
}
