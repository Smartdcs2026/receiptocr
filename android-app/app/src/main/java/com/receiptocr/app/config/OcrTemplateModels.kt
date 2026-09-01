package com.receiptocr.app.config

data class OcrTemplateField(
    val order: Int,
    val type: String,
    val example: String? = null,
    val required: Boolean = true,
    val minLength: Int = 1,
    val maxLength: Int = 12,
    val format: String = "ANY",
    val literal: String? = null,
    val compareTo: String = "NONE",
    val posPrefixes: String? = null,
    val posDigits: Int? = null,
    val separatorValue: String? = null,
    val tokenGap: Int = 0,
    val composite: OcrTemplateComposite? = null
)

data class OcrTemplateComposite(
    val prefix: String? = null,
    val separator: String? = null,
    val segments: List<OcrTemplateSegment> = emptyList()
)

data class OcrTemplateSegment(
    val order: Int,
    val type: String,
    val length: Int = 0,
    val example: String? = null
)

data class OcrTemplateRow(
    val row: Int,
    val fields: List<OcrTemplateField>
)

data class OcrTemplateRecognition(
    val rowCount: Int = 1,
    val groupAsSingleRecord: Boolean = true,
    val deskewEnabled: Boolean = true,
    val layoutMode: String = "MIXED",
    val lineTolerance: Int = 1,
    val multiPosMode: String = "AUTO",
    /**
     * WHOLE_IMAGE = อ่านทั้งภาพ
     * OPTIONAL_REGION_WITH_WHOLE_IMAGE_FALLBACK = ให้คะแนนพื้นที่ที่ Admin กำหนดก่อน
     * แล้วอ่านทั้งภาพซ้ำเพื่อไม่ให้ POS อื่นในภาพเดียวกันตกหล่น
     * REGION_ONLY = ใช้เฉพาะพื้นที่ที่กำหนด
     */
    val searchScope: String = "WHOLE_IMAGE",
    val region: NormalizedRect? = null,
    val rows: List<OcrTemplateRow> = emptyList()
)

data class OcrTemplateRequiredCore(
    val date: Boolean = true,
    val time: Boolean = true,
    val customerValue: Boolean = true
)

data class OcrTemplateStoreValidation(
    val mustMatchWorkPlan: Boolean = true,
    val sameStoreAcrossAllMatches: Boolean = true
)

data class OcrTemplatePosValidation(
    val mustExistInStorePlan: Boolean = true,
    val mustBeUnique: Boolean = true
)

data class OcrTemplateValidation(
    val requiredCore: OcrTemplateRequiredCore = OcrTemplateRequiredCore(),
    val store: OcrTemplateStoreValidation = OcrTemplateStoreValidation(),
    val pos: OcrTemplatePosValidation = OcrTemplatePosValidation()
)

data class OcrTemplateDuplicatePolicy(
    val customerCounterCycle: String = "CONTINUOUS",
    val preventSameImageHash: Boolean = true,
    val preventSameReceiptKey: Boolean = true
)

data class UniversalOcrTemplate(
    val schemaVersion: Int = 4,
    val templateId: String,
    val brandId: String,
    val templateName: String,
    val version: Int = 1,
    val priority: Int = 100,
    val active: Boolean = true,
    val sampleText: String = "",
    val recognition: OcrTemplateRecognition = OcrTemplateRecognition(),
    val validation: OcrTemplateValidation = OcrTemplateValidation(),
    val duplicatePolicy: OcrTemplateDuplicatePolicy = OcrTemplateDuplicatePolicy()
)

enum class TemplateSource {
    CLOUD,
    CACHE,
    REFERENCE,
    NONE
}

data class LoadedOcrTemplates(
    val templates: List<UniversalOcrTemplate>,
    val source: TemplateSource,
    val updatedAt: String? = null,
    val receiptRule: BrandReceiptRule? = null
)
