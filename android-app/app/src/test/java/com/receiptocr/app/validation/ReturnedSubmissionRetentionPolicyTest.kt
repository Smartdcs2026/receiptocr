package com.receiptocr.app.validation

import com.receiptocr.app.model.WorkItem
import com.receiptocr.app.model.WorkStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnedSubmissionRetentionPolicyTest {
    private fun work(review: String, status: WorkStatus = WorkStatus.SUBMITTED) = WorkItem(
        id = 27,
        brand = "CJ More",
        brandAbbr = "CJ",
        businessType = "",
        storeCode = "0690",
        storeName = "Returned Test",
        posCount = 4,
        openClose = "",
        address = "",
        storeFormat = "",
        rank = "",
        latitude = "",
        longitude = "",
        reviewStatus = review,
        status = status
    )

    @Test
    fun returnedWorkCanReuseItsSubmittedEvidence() {
        assertTrue(SubmissionEditPolicy.canReuseSubmittedEvidence(work("RETURNED")))
        assertTrue(SubmissionEditPolicy.canReuseSubmittedEvidence(work(" returned ")))
    }

    @Test
    fun nonReturnedWorkCannotReuseSubmittedEvidence() {
        assertFalse(SubmissionEditPolicy.canReuseSubmittedEvidence(work("SUBMITTED")))
        assertFalse(SubmissionEditPolicy.canReuseSubmittedEvidence(work("APPROVED")))
        assertFalse(SubmissionEditPolicy.canReuseSubmittedEvidence(work("", WorkStatus.DRAFT)))
    }

    @Test
    fun returnedStillUnlocksOldLocalSubmittedState() {
        assertFalse(SubmissionEditPolicy.isLocked(work("RETURNED", WorkStatus.SUBMITTED)))
    }
}
