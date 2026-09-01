package com.receiptocr.app.validation

/**
 * ตรวจรหัสร้านบนบิลกับรหัสร้านที่แผนงานกำหนดไว้เสมอ
 *
 * ถ้าองค์กรมีรหัสแผนงานและรหัสบนบิลคนละชุด ให้ WorkItem ส่ง receiptStoreId
 * ที่ map ไว้แล้วมาเป็น expectedStoreId แทนการข้าม validation
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
        expectedStoreId: String,
        storeIdsByPos: Map<Int, String>
    ): StoreReceiptAssessment {
        val expected = normalizeStoreId(expectedStoreId)
        if (expected.isBlank()) {
            return StoreReceiptAssessment(
                status = StoreReceiptStatus.UNKNOWN,
                summaryWarnings = listOf(
                    "ยังไม่มีรหัสร้านในแผนงานสำหรับตรวจบิล • กรุณาให้ผู้ดูแลกำหนดรหัสร้านก่อนใช้งาน"
                )
            )
        }

        val cleaned = storeIdsByPos.mapValues { (_, raw) -> normalizeStoreId(raw) }
            .filterValues { it.isNotBlank() }
        if (cleaned.isEmpty()) {
            return StoreReceiptAssessment(
                status = StoreReceiptStatus.UNKNOWN,
                expectedReceiptStoreId = expected
            )
        }

        val matches = cleaned.filterValues { sameStoreId(it, expected) }
        val mismatches = cleaned.filterValues { !sameStoreId(it, expected) }

        if (mismatches.isEmpty()) {
            return StoreReceiptAssessment(
                status = StoreReceiptStatus.OK,
                expectedReceiptStoreId = expected
            )
        }

        if (matches.isEmpty()) {
            val actualValues = mismatches.values.distinct().sorted().joinToString(", ")
            return StoreReceiptAssessment(
                status = StoreReceiptStatus.WRONG_STORE,
                warningsByPos = mismatches.mapValues { (_, actual) ->
                    "บิลผิดร้าน • รหัสร้านที่อ่านได้ ($actual) ไม่ตรงกับรหัสร้านของงาน ($expected)"
                },
                summaryWarnings = listOf(
                    "บิลผิดร้าน • รหัสร้านที่อ่านได้ $actualValues ไม่ตรงกับรหัสร้านของงาน $expected"
                ),
                expectedReceiptStoreId = expected
            )
        }

        return StoreReceiptAssessment(
            status = StoreReceiptStatus.BILL_SWAPPED_STORE,
            warningsByPos = mismatches.mapValues { (_, actual) ->
                "พบบิลสลับร้าน • รหัสร้านที่อ่านได้ ($actual) ควรเป็น $expected"
            },
            summaryWarnings = listOf(
                "พบบิลสลับร้าน ${mismatches.size} POS • กรุณาเปลี่ยนเป็นบิลของร้าน $expected ก่อนใช้งาน"
            ),
            expectedReceiptStoreId = expected
        )
    }

    fun normalizeStoreId(raw: String): String =
        raw.trim()
            .uppercase()
            .replace(Regex("[\\s._/-]+"), "")
            .filter { it.isLetterOrDigit() }

    fun sameStoreId(first: String, second: String): Boolean {
        val a = normalizeStoreId(first)
        val b = normalizeStoreId(second)
        if (a.isBlank() || b.isBlank()) return false

        // รหัสตัวเลขล้วนยอมรับ leading zero ต่างกัน เช่น 0652 = 652
        if (a.all(Char::isDigit) && b.all(Char::isDigit)) {
            return a.trimStart('0').ifBlank { "0" } == b.trimStart('0').ifBlank { "0" }
        }
        return a == b
    }
}
