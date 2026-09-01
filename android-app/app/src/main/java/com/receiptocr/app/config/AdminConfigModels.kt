package com.receiptocr.app.config

data class ImportFieldDefinition(
    val key: String,
    val displayName: String,
    val aliases: List<String>,
    val required: Boolean
)

data class ColumnMapping(
    val systemFieldKey: String,
    val excelColumnName: String
)

data class BrandConfig(
    val brandId: String,
    val brandName: String,
    val abbreviation: String,
    val active: Boolean = true,
    val noReceiptReasons: List<String> = emptyList()
)

object DefaultImportFields {
    val fields = listOf(
        ImportFieldDefinition("brand", "Brand", listOf("Brand", "Chain", "แบรนด์"), true),
        ImportFieldDefinition("store_code", "รหัสร้าน", listOf("Store Code", "Branch Code", "รหัสสาขา", "รหัสร้าน", "Code"), true),
        ImportFieldDefinition("store_name", "ชื่อร้าน", listOf("Store Name", "Branch Name", "ชื่อสาขา", "ชื่อร้าน"), true),
        ImportFieldDefinition("business_type", "ประเภทธุรกิจ", listOf("Business Type", "ประเภทธุรกิจ"), false),
        ImportFieldDefinition("pos_count", "จำนวน POS", listOf("POS", "POS Count", "Number POS", "จำนวน POS"), true),
        ImportFieldDefinition("open_close", "เวลาเปิด-ปิด", listOf("Open Time", "Open-Close", "เวลาเปิด-ปิด"), false),
        ImportFieldDefinition("address", "ที่อยู่ร้าน", listOf("Address", "ที่อยู่", "ที่อยู่ร้าน"), false),
        ImportFieldDefinition("store_format", "รูปแบบร้าน", listOf("Store Format", "Format", "รูปแบบร้าน"), false),
        ImportFieldDefinition("rank", "Rank", listOf("Rank", "Grade"), false),
        ImportFieldDefinition("latitude", "Latitude", listOf("Latitude", "Lat", "ละติจูด"), false),
        ImportFieldDefinition("longitude", "Longitude", listOf("Longitude", "Lng", "Long", "ลองจิจูด"), false),
        ImportFieldDefinition("store_note", "หมายเหตุร้าน", listOf("Note", "Remark", "หมายเหตุ"), false)
    )
}
