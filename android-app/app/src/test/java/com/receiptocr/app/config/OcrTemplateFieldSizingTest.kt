package com.receiptocr.app.config

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTemplateFieldSizingTest {

    @Test
    fun numberTextUsesExampleLength() {
        val field = OcrTemplateField(
            order = 1,
            type = "NUMBER_TEXT",
            example = "10",
            minLength = 1,
            maxLength = 12
        ).alignLengthWithExample()

        assertEquals(2, field.minLength)
        assertEquals(2, field.maxLength)
    }

    @Test
    fun laterNumberTextUsesItsOwnExampleLength() {
        val field = OcrTemplateField(
            order = 1,
            type = "NUMBER_TEXT",
            example = "400040",
            minLength = 1,
            maxLength = 12
        ).alignLengthWithExample()

        assertEquals(6, field.minLength)
        assertEquals(6, field.maxLength)
    }

    @Test
    fun employeeCodeUsesExampleLength() {
        val field = OcrTemplateField(
            order = 1,
            type = "EMPLOYEE_CODE",
            example = "U400040",
            minLength = 1,
            maxLength = 12
        ).alignLengthWithExample()

        assertEquals(7, field.minLength)
        assertEquals(7, field.maxLength)
    }

    @Test
    fun customerValueKeepsConfiguredRange() {
        val field = OcrTemplateField(
            order = 1,
            type = "CUSTOMER_VALUE",
            example = "219931",
            minLength = 6,
            maxLength = 6
        ).alignLengthWithExample()

        assertEquals(6, field.minLength)
        assertEquals(6, field.maxLength)
    }
}
