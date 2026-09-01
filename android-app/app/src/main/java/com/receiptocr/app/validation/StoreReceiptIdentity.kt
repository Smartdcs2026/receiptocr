package com.receiptocr.app.validation

/**
 * แยกการตรวจ "รหัสร้านบนบิล" ออกจากรหัสงานภายใน เช่น CJ375
 * เพราะสองค่านี้อาจเป็นคนละระบบรหัสกัน
 */
enum class StoreReceiptStatus {
    OK,
    BILL_SWAPPED_STORE,
    WRONG_STORE,
    MIXED_STORE,
    UNKNOWN
}

data class StoreReceiptAssessment(
    val status: StoreReceiptStatus,
    val warningsByPos: Map<Int, String> = emptyMap(),
    val summaryWarnings: List<String> = emptyList(),
    val expectedReceiptStoreId: String? = null
)

object StoreReceiptIdentity {
    fun evaluate(
        workStoreCode: String,
        storeIdsByPos: Map<Int, String>
    ): StoreReceiptAssessment {
        val cleaned = storeIdsByPos.mapValues { (_, raw) -> normalizeReceiptStoreId(raw) }
            .filterValues { it.isNotBlank() }
        if (cleaned.isEmpty()) return StoreReceiptAssessment(StoreReceiptStatus.UNKNOWN)

        // เทียบกับ Work Plan โดยตรงได้เฉพาะกรณีรหัสงานเป็นตัวเลขล้วน
        // เช่น 1695 <-> 1695 เท่านั้น ไม่เอา CJ375 ไปเทียบกับ 1695
        val expected = comparableWorkStoreId(workStoreCode)
        if (expected != null) {
            val mismatches = cleaned.filterValues { it != expected }
            val matches = cleaned.filterValues { it == expected }
            if (mismatches.isEmpty()) {
                return StoreReceiptAssessment(
                    status = StoreReceiptStatus.OK,
                    expectedReceiptStoreId = expected
                )
            }
            if (matches.isEmpty()) {
                val values = mismatches.values.distinct().sorted().joinToString(", ")
                return StoreReceiptAssessment(
                    status = StoreReceiptStatus.WRONG_STORE,
                    summaryWarnings = listOf(
                        "บิลผิดร้าน • รหัสร้านที่อ่านได้ $values ไม่ตรงกับรหัสร้านของงาน $expected"
                    ),
                    expectedReceiptStoreId = expected
                )
            }
            return StoreReceiptAssessment(
                status = StoreReceiptStatus.BILL_SWAPPED_STORE,
                warningsByPos = mismatches.mapValues { (_, actual) ->
                    "พบบิลสลับร้าน • รหัสร้านที่อ่านได้ ($actual) ควรเป็น $expected"
                },
                expectedReceiptStoreId = expected
            )
        }

        val groups = cleaned.entries.groupBy { it.value }
        if (groups.size <= 1) {
            return StoreReceiptAssessment(StoreReceiptStatus.OK)
        }

        val ranked = groups.entries.sortedByDescending { it.value.size }
        val top = ranked.first()
        val secondSize = ranked.getOrNull(1)?.value?.size ?: 0
        return if (top.value.size > secondSize) {
            val expectedFromMajority = top.key
            val outliers = cleaned.filterValues { it != expectedFromMajority }
            StoreReceiptAssessment(
                status = StoreReceiptStatus.BILL_SWAPPED_STORE,
                warningsByPos = outliers.mapValues { (_, actual) ->
                    "พบบิลสลับร้าน • รหัสร้านที่อ่านได้ ($actual) ต่างจากบิลส่วนใหญ่ ($expectedFromMajority)"
                },
                expectedReceiptStoreId = expectedFromMajority
            )
        } else {
            StoreReceiptAssessment(
                status = StoreReceiptStatus.MIXED_STORE,
                summaryWarnings = listOf(
                    "พบรหัสร้านหลายค่าในภาพ (${cleaned.values.distinct().sorted().joinToString(", ")}) • ยังระบุไม่ได้ว่าบิลใดสลับร้าน กรุณาตรวจภาพ"
                )
            )
        }
    }

    fun normalizeReceiptStoreId(raw: String): String =
        raw.trim().filter(Char::isDigit).trimStart('0').ifBlank {
            if (raw.any(Char::isDigit)) "0" else ""
        }

    private fun comparableWorkStoreId(raw: String): String? {
        val value = raw.trim()
        if (!value.matches(Regex("^\\d+$"))) return null
        return value.trimStart('0').ifBlank { "0" }
    }
}
