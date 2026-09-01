package com.receiptocr.app.config

/**
 * กฎทั้งหมดนี้ออกแบบให้มาจาก Web Admin/API ในระยะ production
 * APK เป็น consumer ของ config และไม่ควร hard-code กฎของแต่ละแบรนด์
 */
enum class CustomerCounterMode {
    UNSPECIFIED,
    MONTHLY_RESET,
    CONTINUOUS
}

enum class RuleAction {
    WARNING,
    BLOCK
}

data class StoreIdentityRule(
    val enabled: Boolean = false,
    /** ข้อความ/รหัสจำเพาะที่ OCR ต้องพบเพื่อยืนยันว่าเป็นร้านที่ถูกต้อง */
    val requiredTokens: List<String> = emptyList(),
    /** true = ต้องพบครบทุก token, false = พบอย่างน้อยหนึ่ง token */
    val requireAll: Boolean = false
)

/**
 * กำหนดช่วงวันที่บิลเทียบกับวันงาน
 * ตัวอย่าง beforeDays=2, afterDays=2 หมายถึง วันงาน-2 ถึง วันงาน+2
 * สามารถปิด rule หรือเปลี่ยนเป็น BLOCK/WARNING จาก Admin ได้
 */
data class ReceiptDateWindowRule(
    val enabled: Boolean = true,
    val beforeDays: Int = 2,
    val afterDays: Int = 2,
    val action: RuleAction = RuleAction.WARNING,
    val warningText: String = "วันที่ไม่ตรงเงื่อนไข"
)

/**
 * ตรวจวันที่บิลของ POS ทุกเครื่องในร้านเป็นกลุ่มเดียวกัน
 * วันที่เก่าสุดเป็นตัวกำหนดวันสุดท้ายที่อนุญาต และวันที่ใหม่สุดใช้ตรวจขอบบน
 */
data class ReceiptGroupDateRule(
    val enabled: Boolean = true,
    /** true = วันที่บิลทุกใบต้องอยู่เดือน/ปีเดียวกับวันทำงาน */
    val resetAtMonthEnd: Boolean = false,
    val maxBeforeDays: Int = 2,
    val afterDaysWhenOldestIsMaxBefore: Int = 0,
    val afterDaysWhenOldestIsOneDayBefore: Int = 2,
    val afterDaysWhenOldestIsWorkDay: Int = 2,
    val action: RuleAction = RuleAction.BLOCK,
    val warningText: String = "วันที่บิลไม่อยู่ในช่วงที่ใช้ได้"
)

data class BrandReceiptRule(
    val brandId: String,
    val dateWindowRule: ReceiptDateWindowRule = ReceiptDateWindowRule(),
    val groupDateRule: ReceiptGroupDateRule = ReceiptGroupDateRule(),
    val preventDuplicateImage: Boolean = true,
    val preventDuplicateReceiptData: Boolean = true,
    val storeIdentityRule: StoreIdentityRule = StoreIdentityRule(),
    val customerCounterMode: CustomerCounterMode = CustomerCounterMode.UNSPECIFIED
)

/**
 * ค่า Demo ชั่วคราวเท่านั้น
 * Production จะโหลดค่าจาก Web Admin/API แยกตาม Brand/Profile
 */
object DemoReceiptRules {
    fun forBrand(brand: String): BrandReceiptRule = BrandReceiptRule(
        brandId = brand,
        dateWindowRule = ReceiptDateWindowRule(
            enabled = true,
            beforeDays = 2,
            afterDays = 2,
            action = RuleAction.BLOCK,
            warningText = "วันที่ไม่ตรงเงื่อนไข"
        ),
        groupDateRule = ReceiptGroupDateRule(),
        preventDuplicateImage = true,
        preventDuplicateReceiptData = true,
        storeIdentityRule = StoreIdentityRule(enabled = false),
        customerCounterMode = CustomerCounterMode.UNSPECIFIED
    )
}
