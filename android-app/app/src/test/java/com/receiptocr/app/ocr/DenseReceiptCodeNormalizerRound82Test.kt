package com.receiptocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class DenseReceiptCodeNormalizerRound82Test {

    @Test
    fun keepsMb02RealCodeUnchangedWhenAlreadyCorrect() {
        assertEquals(
            "R201051846U110030 20/08/69 17:51",
            OcrTextNormalizer.normalizeLine("R201051846U110030 20/08/69 17:51")
        )
    }

    @Test
    fun restoresUWhenDotMatrixPrintIsReadAsVBetweenLongNumberGroups() {
        assertEquals(
            "R201051846U110030 20/08/69 17:51",
            OcrTextNormalizer.normalizeLine("R201051846V110030 20/08/69 17:51")
        )
    }

    @Test
    fun restoresCommonDigitLikeCharactersOnlyInsideNumberRuns() {
        assertEquals(
            "R202039530U400072 20/08/69 17:18",
            OcrTextNormalizer.normalizeLine("R202039S30U400072 20/08/69 17:18")
        )
        assertEquals(
            "R202038930U400072 20/08/69 17:18",
            OcrTextNormalizer.normalizeLine("R20203B930U400072 20/08/69 17:18")
        )
    }

    @Test
    fun doesNotChangeOrdinaryWordsContainingV() {
        assertEquals(
            "VAT INCLUDED",
            OcrTextNormalizer.normalizeLine("VAT INCLUDED")
        )
    }
}
