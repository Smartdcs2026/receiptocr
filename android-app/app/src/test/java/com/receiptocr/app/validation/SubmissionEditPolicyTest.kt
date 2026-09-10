package com.receiptocr.app.validation

import com.receiptocr.app.model.WorkItem
import com.receiptocr.app.model.WorkStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionEditPolicyTest {
    private fun work(status: WorkStatus, review: String = "") = WorkItem(
        id = 1, brand = "CJ More", brandAbbr = "CJ", businessType = "",
        storeCode = "0690", storeName = "Test", posCount = 4, openClose = "",
        address = "", storeFormat = "", rank = "", latitude = "", longitude = "",
        reviewStatus = review, status = status
    )

    @Test fun submittedIsLocked() {
        assertTrue(SubmissionEditPolicy.isLocked(work(WorkStatus.SUBMITTED)))
        assertTrue(SubmissionEditPolicy.isLocked(work(WorkStatus.DRAFT, "SUBMITTED")))
    }

    @Test fun approvedIsLocked() {
        assertTrue(SubmissionEditPolicy.isLocked(work(WorkStatus.APPROVED)))
        assertTrue(SubmissionEditPolicy.isLocked(work(WorkStatus.DRAFT, "APPROVED")))
    }

    @Test fun returnedUnlocksEvenIfOldLocalStatusWasSubmitted() {
        assertFalse(SubmissionEditPolicy.isLocked(work(WorkStatus.SUBMITTED, "RETURNED")))
    }

    @Test fun draftAndFailedRemainEditable() {
        assertFalse(SubmissionEditPolicy.isLocked(work(WorkStatus.DRAFT)))
        assertFalse(SubmissionEditPolicy.isLocked(work(WorkStatus.FAILED)))
    }

    @Test fun successfulSendLocksImmediatelyBeforeRefresh() {
        assertTrue(SubmissionEditPolicy.isLocked(work(WorkStatus.DRAFT), submittedThisSession = true))
    }
}
