package com.receiptocr.app.validation

import com.receiptocr.app.model.WorkItem
import com.receiptocr.app.model.WorkStatus

object SubmissionEditPolicy {
    fun isLocked(work: WorkItem, submittedThisSession: Boolean = false): Boolean {
        val review = work.reviewStatus.trim().uppercase()
        if (review == "RETURNED") return false
        if (submittedThisSession) return true
        if (review == "SUBMITTED" || review == "APPROVED") return true
        return work.status == WorkStatus.SUBMITTED || work.status == WorkStatus.APPROVED
    }
}
