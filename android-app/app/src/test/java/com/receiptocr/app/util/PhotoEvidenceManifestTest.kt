package com.receiptocr.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhotoEvidenceManifestTest {
    @Test
    fun missingSelected_ignoresEmptySlots_andFindsMissingFiles() {
        val ok = File.createTempFile("receiptocr_photo_", ".jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        try {
            val missing = PhotoEvidenceManifest.missingSelected(
                receiptPaths = listOf(ok.absolutePath, "/not-found/receipt.jpg", null),
                storePaths = listOf(null, "")
            )
            assertEquals(listOf("ภาพบิล 2"), missing)
        } finally {
            ok.delete()
        }
    }

    @Test
    fun missingSelected_returnsEmpty_whenSelectedFilesExist() {
        val receipt = File.createTempFile("receiptocr_receipt_", ".jpg").apply { writeText("receipt") }
        val store = File.createTempFile("receiptocr_store_", ".jpg").apply { writeText("store") }
        try {
            val missing = PhotoEvidenceManifest.missingSelected(
                receiptPaths = listOf(receipt.absolutePath),
                storePaths = listOf(store.absolutePath)
            )
            assertTrue(missing.isEmpty())
        } finally {
            receipt.delete()
            store.delete()
        }
    }
}
