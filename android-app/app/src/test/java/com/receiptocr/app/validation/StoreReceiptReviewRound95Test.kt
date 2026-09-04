package com.receiptocr.app.validation

import com.receiptocr.app.model.PosRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreReceiptReviewRound95Test {
    @Test fun exactHumanConfirmationAllowsCurrentReadAndExpectedStore() {
        val record = PosRecord(
            posNumber = 3, ocrStoreId = "7600", ocrStoreIdExpected = true,
            storeReviewConfirmed = true, storeReviewReadId = "7600",
            storeReviewExpectedId = "1600", storeReviewConfirmedId = "1600"
        )
        assertTrue(StoreReceiptReview.isValid(record, "1600"))
        assertTrue(StoreReceiptReview.isMismatch(record, "1600"))
    }

    @Test fun changedOcrReadInvalidatesOldConfirmation() {
        val record = PosRecord(
            posNumber = 3, ocrStoreId = "7601", ocrStoreIdExpected = true,
            storeReviewConfirmed = true, storeReviewReadId = "7600",
            storeReviewExpectedId = "1600", storeReviewConfirmedId = "1600"
        )
        assertFalse(StoreReceiptReview.isValid(record, "1600"))
    }

    @Test fun changedExpectedStoreInvalidatesOldConfirmation() {
        val record = PosRecord(
            posNumber = 3, ocrStoreId = "7600", ocrStoreIdExpected = true,
            storeReviewConfirmed = true, storeReviewReadId = "7600",
            storeReviewExpectedId = "1600", storeReviewConfirmedId = "1600"
        )
        assertFalse(StoreReceiptReview.isValid(record, "1700"))
    }
}
