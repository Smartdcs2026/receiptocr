package com.receiptocr.app.ocr

import com.receiptocr.app.model.PosRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrAccumulationNoReceiptRound94Test {
    @Test fun ocr_never_clears_user_selected_no_receipt() {
        val original=PosRecord(1,noReceipt=true,noReceiptReason="เครื่องปิด")
        val candidate=PosRecord(1,customerNo="123456",billDate="03/09/2026",billTime="10:00",source="OCR-TEMPLATE")
        val result=OcrAccumulationPolicy.merge(
            originals=listOf(original), templateRecords=listOf(candidate), profileRecords=emptyList(), currentDetectedPos=setOf(1)
        )
        assertTrue(result.records.single().noReceipt)
        assertEquals("เครื่องปิด",result.records.single().noReceiptReason)
        assertTrue(result.conflictsByPos[1].orEmpty().contains("ถูกระบุว่าไม่ได้บิล"))
    }
}
