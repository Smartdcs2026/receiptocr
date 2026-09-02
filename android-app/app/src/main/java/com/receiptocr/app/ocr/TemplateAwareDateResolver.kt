package com.receiptocr.app.ocr

import com.receiptocr.app.config.OcrTemplateField
import com.receiptocr.app.config.UniversalOcrTemplate
import java.time.LocalDate

/**
 * Resolves a source date against the exact template(s) that produced a POS record.
 * A brand may have multiple templates with different date conventions, so a date
 * must never be interpreted using an arbitrary first template.
 */
object TemplateAwareDateResolver {
    fun resolve(
        raw: String,
        templateName: String,
        templates: List<UniversalOcrTemplate>,
        referenceDate: LocalDate,
        allowCanonicalInput: Boolean
    ): ReceiptDateOcrNormalizer.Result {
        val value = raw.trim()
        if (value.isBlank()) {
            return ReceiptDateOcrNormalizer.Result(null, original = value, warning = "ยังอ่านวันที่จากบิลไม่ได้")
        }

        // Once a parser has already produced the internal dd/MM/yyyy representation,
        // never reinterpret it using the source template's MDY/YMD order.
        if (allowCanonicalInput && ReceiptDateOcrNormalizer.isCanonical(value)) {
            return ReceiptDateOcrNormalizer.normalize(
                raw = value,
                configuredFormat = null,
                referenceDate = referenceDate,
                allowCanonicalInput = true
            )
        }

        val active = templates.filter { it.active }
        val names = templateName
            .split('/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val preferred = active.filter { it.templateName in names }
        val candidates = if (preferred.isNotEmpty()) preferred else active
        val dateFields = candidates
            .mapNotNull(::dateField)
            .distinctBy(::fieldSignature)

        if (dateFields.isEmpty()) {
            return ReceiptDateOcrNormalizer.normalizeForField(
                raw = value,
                field = null,
                referenceDate = referenceDate,
                allowCanonicalInput = allowCanonicalInput
            )
        }

        val attempts = dateFields.map { field ->
            ReceiptDateOcrNormalizer.normalizeForField(
                raw = value,
                field = field,
                referenceDate = referenceDate,
                allowCanonicalInput = allowCanonicalInput
            )
        }
        val accepted = attempts.filter { it.value != null }
        val byValue = accepted.groupBy { it.value }
        return when {
            byValue.size == 1 -> byValue.values.first().first()
            byValue.size > 1 -> ReceiptDateOcrNormalizer.Result(
                value = null,
                original = value,
                warning = "วันที่ตรงได้มากกว่าหนึ่งรูปแบบที่กำหนด กรุณาตรวจจากภาพบิล"
            )
            else -> attempts.firstOrNull()
                ?: ReceiptDateOcrNormalizer.Result(null, original = value, warning = "วันที่ยังไม่ตรงรูปแบบที่กำหนด")
        }
    }

    private fun dateField(template: UniversalOcrTemplate): OcrTemplateField? =
        template.recognition.rows.asSequence()
            .flatMap { it.fields.asSequence() }
            .firstOrNull { it.type.equals("BILL_DATE", ignoreCase = true) }

    private fun fieldSignature(field: OcrTemplateField): String = listOf(
        field.dateOrder.uppercase(),
        field.dateCalendar.uppercase(),
        field.dateYearDigits.toString(),
        field.format.uppercase(),
        field.example.orEmpty()
    ).joinToString("|")
}
