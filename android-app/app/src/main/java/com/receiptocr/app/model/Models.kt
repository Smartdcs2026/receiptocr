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
    val reviewStatus: String = "",
    val returnReason: String = "",
    val planStatus: String = "ACTIVE",
    val originWorkDate: String = "",
    val movedToDate: String = "",
    val changeNote: String = "",
    val status: WorkStatus = WorkStatus.NOT_STARTED
)

data class PosRecord(
    val posNumber: Int,
    val customerNo: String = "",
    val billDate: String = "",
    val billTime: String = "",
    val note: String = "",
    val noReceipt: Boolean = false,
    val noReceiptReason: String = "",
    val source: String = "MANUAL",
    /**
     * path ของภาพบิลที่เป็นต้นทางของข้อมูล OCR
     * ภาพเดียวสามารถเป็น source ให้หลาย POS ได้
     */
    val ocrSourceImagePath: String = "",
    val ocrConfidence: String = "",
    val ocrTemplateName: String = "",
    val ocrWarnings: String = "",
    /** รหัสร้านที่อ่านได้จากช่อง STORE_ID ตามแม่แบบ Admin ของ POS นี้ */
    val ocrStoreId: String = "",
    /** รอบที่ใช้ตรวจเลขลูกค้าซ้ำ มาจากแม่แบบเดียวกับหน้า Admin */
    val ocrCounterCycle: String = "CONTINUOUS"
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
