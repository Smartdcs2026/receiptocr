package com.receiptocr.app.ocr

import com.receiptocr.app.config.*
import com.receiptocr.app.model.PosRecord
import com.receiptocr.app.model.WorkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MultiTemplateSequenceCollectorRound94Test {
    private fun field(order:Int,type:String,example:String,min:Int=1,max:Int=20, literal:String?=null, dateOrder:String="DMY", calendar:String="AUTO", yearDigits:Int=0, prefixes:String?=null, posDigits:Int?=null)=
        OcrTemplateField(order,type,example,true,min,max,when(type){"BILL_DATE"->"DATE";"BILL_TIME"->"TIME";"CUSTOMER_VALUE","NUMBER_TEXT"->"DIGITS";else->"ALNUM"},dateOrder,calendar,yearDigits,literal,"NONE",prefixes,posDigits)

    private fun template(id:String,name:String,fields:List<OcrTemplateField>)=UniversalOcrTemplate(
        templateId=id,brandId="Mini",templateName=name,priority=100,active=true,
        recognition=OcrTemplateRecognition(rows=listOf(OcrTemplateRow(1,fields)))
    )

    private val work=WorkItem(1,"Mini","Mb","","001","ร้านทดสอบ",3,"","","","","","","",receiptStoreId="")

    @Test fun one_image_can_fill_multiple_pos_from_different_templates() {
        val mb02=template("mb02","Mb_02",listOf(
            field(1,"LITERAL","R",1,1,"R"), field(2,"NUMBER_TEXT","20",2,2),
            field(3,"POS_NUMBER","2",1,1,posDigits=1), field(4,"CUSTOMER_VALUE","039030",6,6),
            field(5,"LITERAL","U",1,1,"U"), field(6,"NUMBER_TEXT","400072",6,6),
            field(7,"BILL_DATE","20/08/69",8,8,dateOrder="DMY",calendar="BUDDHIST",yearDigits=2),
            field(8,"BILL_TIME","17:18",5,5)
        ))
        val mb03=template("mb03","Mb_03",listOf(
            field(1,"LITERAL","Date:",5,5,"Date:"),
            field(2,"BILL_DATE","14-08-26",8,8,dateOrder="DMY",calendar="GREGORIAN",yearDigits=2),
            field(3,"BILL_TIME","22:05",5,5), field(4,"NUMBER_TEXT","20",2,2),
            field(5,"POS_NUMBER","1",1,1,posDigits=1), field(6,"CUSTOMER_VALUE","157464",6,6)
        ))
        val result=MultiTemplateSequenceCollector.apply(
            rawTexts=listOf("Date : 14-08-26 22:05 201157464\nR202039030U400072 20/08/69 17:18"),
            records=listOf(PosRecord(1),PosRecord(2),PosRecord(3)), work=work,
            workDate=LocalDate.of(2026,8,20), imagePath="x.jpg", templates=listOf(mb02,mb03),
            receiptRule=BrandReceiptRule("Mini")
        )
        assertTrue(result.detectedPos.containsAll(listOf(1,2)))
        assertEquals("157464", result.records.first{it.posNumber==1}.customerNo)
        assertEquals("039030", result.records.first{it.posNumber==2}.customerNo)
        assertTrue(result.templateName.orEmpty().contains("Mb_02"))
        assertTrue(result.templateName.orEmpty().contains("Mb_03"))
    }

    @Test fun prefix_mapping_sends_n01_and_b01_to_different_work_pos() {
        val t=template("prefix","Prefix",listOf(
            field(1,"POS_NUMBER","N01",3,3,prefixes="N,B",posDigits=2),
            field(2,"CUSTOMER_VALUE","123456",6,6),
            field(3,"BILL_DATE","03/09/26",8,8,dateOrder="DMY",calendar="GREGORIAN",yearDigits=2),
            field(4,"BILL_TIME","10:00",5,5)
        ))
        val rule=BrandReceiptRule("Brand",posIdentityRule=PosIdentityRule(
            enabled=true,allowedPrefixes=listOf("N","B"),mappings=listOf(
                PosIdentityMapping("N01",1),PosIdentityMapping("B01",2)
            )
        ))
        val result=MultiTemplateSequenceCollector.apply(
            listOf("N01 123456 03/09/26 10:00\nB01 654321 03/09/26 10:05"),
            listOf(PosRecord(1),PosRecord(2)),work,LocalDate.of(2026,9,3),"p.jpg",listOf(t),rule
        )
        assertEquals(listOf(1,2),result.detectedPos)
        assertEquals("N01",result.records.first{it.posNumber==1}.ocrRawPosIdentity)
        assertEquals("B01",result.records.first{it.posNumber==2}.ocrRawPosIdentity)
    }
}
