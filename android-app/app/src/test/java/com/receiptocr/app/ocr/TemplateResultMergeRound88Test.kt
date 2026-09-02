package com.receiptocr.app.ocr

import com.receiptocr.app.model.PosRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateResultMergeRound88Test {
    private val originals = listOf(PosRecord(1), PosRecord(2), PosRecord(3))

    @Test
    fun partialReaderMustNotBlockAnotherReaderFromAddingPos2() {
        val primary = UniversalTemplateResult(
            records = listOf(
                PosRecord(1, customerNo = "051846", billDate = "20/08/2026", billTime = "17:51", source = "OCR-TEMPLATE"),
                PosRecord(2), PosRecord(3)
            ),
            message = "primary",
            detectedPos = listOf(1),
            usedUniversalTemplate = true
        )
        val supplement = UniversalTemplateResult(
            records = listOf(
                PosRecord(1),
                PosRecord(2, customerNo = "039030", billDate = "20/08/69", billTime = "17:18", source = "OCR-EVIDENCE"),
                PosRecord(3)
            ),
            message = "supplement",
            detectedPos = listOf(2),
            usedUniversalTemplate = true
        )

        val merged = RealOcrPipeline.mergeUniversalTemplateResults(originals, primary, supplement)
        assertEquals(listOf(1, 2), merged.detectedPos)
        assertEquals("051846", merged.records.first { it.posNumber == 1 }.customerNo)
        assertEquals("039030", merged.records.first { it.posNumber == 2 }.customerNo)
        assertTrue(RealOcrPipeline.needsTemplateHelp(merged, setOf(1, 2, 3)))
    }

    @Test
    fun noMoreHelpNeededWhenEveryExpectedPosIsComplete() {
        val complete = UniversalTemplateResult(
            records = listOf(
                PosRecord(1, "1", "20/08/2026", "10:00"),
                PosRecord(2, "2", "20/08/2026", "10:01"),
                PosRecord(3, "3", "20/08/2026", "10:02")
            ),
            message = "complete",
            detectedPos = listOf(1, 2, 3),
            usedUniversalTemplate = true
        )
        assertFalse(RealOcrPipeline.needsTemplateHelp(complete, setOf(1, 2, 3)))
    }
}
