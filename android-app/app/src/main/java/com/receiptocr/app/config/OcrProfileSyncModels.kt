package com.receiptocr.app.config

/**
 * โครง transport สำหรับ Web Admin -> Backend -> APK
 * รอบนี้ยังใช้ local/demo provider อยู่
 * รอบเชื่อม API สามารถ deserialize JSON ลงโครงนี้แล้ว map ไป AdminOcrProfile/BrandReceiptRule
 */
data class OcrProfilePackage(
    val ocrProfile: AdminOcrProfile,
    val receiptRule: BrandReceiptRule,
    val etag: String? = null,
    val updatedAt: String? = null
)

data class OcrProfileSyncState(
    val brandId: String,
    val profileId: String,
    val version: Long,
    val loadedFrom: String,
    val lastUpdatedAt: String? = null
)
