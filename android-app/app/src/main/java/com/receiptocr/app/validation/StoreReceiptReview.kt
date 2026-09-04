package com.receiptocr.app.validation

import com.receiptocr.app.model.PosRecord

/**
 * การยืนยันจากคนใช้ได้เฉพาะ OCR read + expected store เดิมเท่านั้น
 * ถ้าภาพใหม่อ่านต่าง หรือแผนงานเปลี่ยน รหัสยืนยันเก่าจะใช้ไม่ได้ทันที
 */
object StoreReceiptReview {
    fun isValid(record: PosRecord, expectedStoreId: String): Boolean {
        if (!record.storeReviewConfirmed) return false
        if (record.ocrStoreId.isBlank() || expectedStoreId.isBlank()) return false
        return StoreReceiptIdentity.sameStoreId(record.storeReviewReadId, record.ocrStoreId) &&
            StoreReceiptIdentity.sameStoreId(record.storeReviewExpectedId, expectedStoreId) &&
            StoreReceiptIdentity.sameStoreId(record.storeReviewConfirmedId, expectedStoreId)
    }

    fun isMismatch(record: PosRecord, expectedStoreId: String): Boolean {
        if (!record.ocrStoreIdExpected || record.ocrStoreId.isBlank() || expectedStoreId.isBlank()) return false
        return !StoreReceiptIdentity.sameStoreId(record.ocrStoreId, expectedStoreId)
    }
}
