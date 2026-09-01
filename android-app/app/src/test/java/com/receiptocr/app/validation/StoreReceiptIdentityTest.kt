package com.receiptocr.app.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreReceiptIdentityTest {
    @Test
    fun alphanumericWorkCodeDoesNotFalseMatchAgainstReceiptStoreId() {
        val result = StoreReceiptIdentity.evaluate(
            workStoreCode = "CJ375",
            storeIdsByPos = mapOf(1 to "1695", 2 to "1695", 3 to "1695")
        )

        assertEquals(StoreReceiptStatus.OK, result.status)
        assertTrue(result.warningsByPos.isEmpty())
        assertTrue(result.summaryWarnings.isEmpty())
    }

    @Test
    fun numericWorkCodeWithNoMatchingPosIsWrongStore() {
        val result = StoreReceiptIdentity.evaluate(
            workStoreCode = "1695",
            storeIdsByPos = mapOf(1 to "1700", 2 to "1700", 3 to "1700")
        )

        assertEquals(StoreReceiptStatus.WRONG_STORE, result.status)
        assertTrue(result.summaryWarnings.single().contains("บิลผิดร้าน"))
    }

    @Test
    fun numericWorkCodeWithOneDifferentPosIsSwappedReceipt() {
        val result = StoreReceiptIdentity.evaluate(
            workStoreCode = "1695",
            storeIdsByPos = mapOf(1 to "1695", 2 to "1700", 3 to "1695")
        )

        assertEquals(StoreReceiptStatus.BILL_SWAPPED_STORE, result.status)
        assertEquals(setOf(2), result.warningsByPos.keys)
        assertTrue(result.warningsByPos.getValue(2).contains("พบบิลสลับร้าน"))
    }

    @Test
    fun alphanumericWorkCodeUsesClearMajorityForSwappedReceipt() {
        val result = StoreReceiptIdentity.evaluate(
            workStoreCode = "CJ375",
            storeIdsByPos = mapOf(1 to "1695", 2 to "1700", 3 to "1695")
        )

        assertEquals(StoreReceiptStatus.BILL_SWAPPED_STORE, result.status)
        assertEquals(setOf(2), result.warningsByPos.keys)
    }

    @Test
    fun twoDifferentStoresWithoutComparablePlanNeedsReview() {
        val result = StoreReceiptIdentity.evaluate(
            workStoreCode = "CJ375",
            storeIdsByPos = mapOf(1 to "1695", 2 to "1700")
        )

        assertEquals(StoreReceiptStatus.MIXED_STORE, result.status)
        assertTrue(result.summaryWarnings.single().contains("ยังระบุไม่ได้"))
    }

    @Test
    fun leadingZerosDoNotCreateFalseStoreMismatch() {
        val result = StoreReceiptIdentity.evaluate(
            workStoreCode = "0652",
            storeIdsByPos = mapOf(1 to "0652", 2 to "652")
        )

        assertEquals(StoreReceiptStatus.OK, result.status)
    }
}
