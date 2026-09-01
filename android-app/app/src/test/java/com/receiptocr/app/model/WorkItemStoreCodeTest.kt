package com.receiptocr.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkItemStoreCodeTest {
    private fun work(storeCode: String, receiptStoreId: String = "") = WorkItem(
        id = 1,
        brand = "CJ",
        brandAbbr = "CJ",
        businessType = "",
        storeCode = storeCode,
        storeName = "ทดสอบ",
        posCount = 1,
        openClose = "",
        address = "",
        storeFormat = "",
        rank = "",
        latitude = "",
        longitude = "",
        receiptStoreId = receiptStoreId
    )

    @Test
    fun prefixedPlanCodeFallsBackToNumericReceiptCode() {
        assertEquals("2125", work("CJ2125").expectedReceiptStoreId)
        assertEquals("3017", work("JF3017").expectedReceiptStoreId)
    }

    @Test
    fun numericPlanCodeRemainsNumeric() {
        assertEquals("2982", work("2982").expectedReceiptStoreId)
        assertEquals("0652", work("0652").expectedReceiptStoreId)
    }

    @Test
    fun explicitReceiptStoreIdAlwaysWins() {
        assertEquals("0652", work("CJ539", receiptStoreId = "0652").expectedReceiptStoreId)
    }

    @Test
    fun planCodeWithoutDigitsCannotPretendToBeReceiptStoreId() {
        assertEquals("", work("CJ").expectedReceiptStoreId)
    }
}
