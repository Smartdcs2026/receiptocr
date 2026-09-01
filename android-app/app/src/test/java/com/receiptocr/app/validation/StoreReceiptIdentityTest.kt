package com.receiptocr.app.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreReceiptIdentityTest {
    @Test
    fun alphanumericWorkCodeMustNotBeSkippedWhenReceiptStoreIsDifferent() {
        val result = StoreReceiptIdentity.evaluate(
            expectedStoreId = "CJ375",
            storeIdsByPos = mapOf(1 to "1695", 2 to "1695", 3 to "1695")
        )

        assertEquals(StoreReceiptStatus.WRONG_STORE, result.status)
        assertEquals(setOf(1, 2, 3), result.warningsByPos.keys)
        assertTrue(result.summaryWarnings.single().contains("บิลผิดร้าน"))
    }

    @Test
    fun noMatchingPosIsWrongStore() {
        val result = StoreReceiptIdentity.evaluate(
            expectedStoreId = "1695",
            storeIdsByPos = mapOf(1 to "1700", 2 to "1700", 3 to "1700")
        )

        assertEquals(StoreReceiptStatus.WRONG_STORE, result.status)
        assertTrue(result.summaryWarnings.single().contains("บิลผิดร้าน"))
    }

    @Test
    fun oneDifferentPosIsSwappedReceipt() {
        val result = StoreReceiptIdentity.evaluate(
            expectedStoreId = "1695",
            storeIdsByPos = mapOf(1 to "1695", 2 to "1700", 3 to "1695")
        )

        assertEquals(StoreReceiptStatus.BILL_SWAPPED_STORE, result.status)
        assertEquals(setOf(2), result.warningsByPos.keys)
        assertTrue(result.warningsByPos.getValue(2).contains("พบบิลสลับร้าน"))
    }

    @Test
    fun alphanumericExpectedCodeCanMatchExactly() {
        val result = StoreReceiptIdentity.evaluate(
            expectedStoreId = "CJ375",
            storeIdsByPos = mapOf(1 to "CJ375", 2 to "cj375")
        )

        assertEquals(StoreReceiptStatus.OK, result.status)
        assertTrue(result.warningsByPos.isEmpty())
    }

    @Test
    fun multipleDifferentWrongStoresRemainWrongStoreNotMajorityGuess() {
        val result = StoreReceiptIdentity.evaluate(
            expectedStoreId = "CJ375",
            storeIdsByPos = mapOf(1 to "1695", 2 to "1700", 3 to "1695")
        )

        assertEquals(StoreReceiptStatus.WRONG_STORE, result.status)
        assertEquals(setOf(1, 2, 3), result.warningsByPos.keys)
    }

    @Test
    fun leadingZerosDoNotCreateFalseNumericStoreMismatch() {
        val result = StoreReceiptIdentity.evaluate(
            expectedStoreId = "0652",
            storeIdsByPos = mapOf(1 to "0652", 2 to "652")
        )

        assertEquals(StoreReceiptStatus.OK, result.status)
    }
}
