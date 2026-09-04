package com.receiptocr.app.model

import java.time.LocalDate

data class UserProfile(
    val employeeCode: String,
    val fullName: String,
    val username: String = ""
)

enum class WorkStatus {
    NOT_STARTED,
    DRAFT,
    SUBMITTED,
    RETURNED,
    APPROVED,
    FAILED
}

data class WorkItem(
    val id: Int,
    val brand: String,
    val brandAbbr: String,
    val businessType: String,
    val storeCode: String,
    val storeName: String,
    val posCount: Int,
    val openClose: String,
    val address: String,
    val storeFormat: String,
    val rank: String,
    val latitude: String,
    val longitude: String,
    val storeNote: String = "",
    val receiptStoreId: String = "",
    val receiptStoreIdPending: Boolean = false,
    val reviewStatus: String = "",
    val returnReason: String = "",
    val planStatus: String = "ACTIVE",
    val originWorkDate: String = "",
    val movedToDate: String = "",
    val changeNote: String = "",
    val status: WorkStatus = WorkStatus.NOT_STARTED
) {
    val expectedReceiptStoreId: String
        get() = if (receiptStoreIdPending) "" else receiptStoreId.trim().ifBlank { storeCode.filter(Char::isDigit) }
}

data class PosRecord(
    val posNumber: Int,
    val customerNo: String = "",
    /** ค่าที่ผ่านกติกา Admin แล้วเท่านั้น และเก็บเป็น dd/MM/yyyy */
    val billDate: String = "",
    /** ค่าที่ผ่านการตรวจแล้วเท่านั้น และเก็บเป็น HH:mm */
    val billTime: String = "",
    val note: String = "",
    val noReceipt: Boolean = false,
    val noReceiptReason: String = "",
    val source: String = "MANUAL",
    val ocrSourceImagePath: String = "",
    val ocrConfidence: String = "",
    val ocrTemplateName: String = "",
    val ocrWarnings: String = "",
    /** ค่าวันที่ดิบที่ OCR เห็นจากภาพ ใช้แสดงเตือน/ตรวจสอบ ไม่ใช้เป็นวันที่ส่งงาน */
    val ocrRawBillDate: String = "",
    /** ค่าเวลาดิบที่ OCR เห็นจากภาพ ใช้แสดงเตือน/ตรวจสอบ ไม่ใช้เป็นเวลาส่งงาน */
    val ocrRawBillTime: String = "",
    /** รหัสร้านที่อ่านได้จากช่อง STORE_ID ตามแม่แบบ Admin ของ POS นี้ */
    val ocrStoreId: String = "",
    /** true เฉพาะรูปแบบบิลที่ Admin กำหนดว่ามี STORE_ID ให้ตรวจ */
    val ocrStoreIdExpected: Boolean = false,
    /** รอบที่ใช้ตรวจเลขลูกค้าซ้ำ มาจากแม่แบบเดียวกับหน้า Admin */
    val ocrCounterCycle: String = "CONTINUOUS",
    /** รหัส POS ที่เห็นบนบิลก่อนจับคู่กับช่องงาน เช่น N01 / B01 */
    val ocrRawPosIdentity: String = "",
    /** ผู้ใช้ตรวจจากภาพแล้วและยืนยันรหัสร้าน โดยยังเก็บค่าที่ระบบอ่านเดิมไว้ */
    val storeReviewConfirmed: Boolean = false,
    val storeReviewReadId: String = "",
    val storeReviewExpectedId: String = "",
    val storeReviewConfirmedId: String = "",
    val storeReviewConfirmedAt: String = "",
    val storeReviewConfirmedBy: String = ""
)

enum class AppScreen {
    LOGIN,
    HOME,
    STORE_INFO,
    STORE_WORK
}

data class PhotoDraft(
    val receiptPaths: List<String?>,
    val storePaths: List<String?>
)
