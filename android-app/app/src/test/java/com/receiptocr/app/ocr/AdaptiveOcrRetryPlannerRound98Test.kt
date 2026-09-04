package com.receiptocr.app.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveOcrRetryPlannerRound98Test {
    @Test
    fun first_attempt_keeps_round97_baseline_and_adds_safe_supplement() {
        val plan = AdaptiveOcrRetryPlanner.planForAttempt(1)
        assertEquals(1, plan.level)
        assertTrue(plan.addFineAdaptive)
        assertTrue(plan.addFaintTextPass)
        assertFalse(plan.addShiftedLineCrops)
        assertFalse(plan.addCoarseAdaptive)
        assertFalse(plan.addStrongEdgePass)
        assertFalse(plan.addMicroLineCrops)
    }

    @Test
    fun second_attempt_adds_new_reading_methods_without_dropping_baseline() {
        val plan = AdaptiveOcrRetryPlanner.planForAttempt(2)
        assertEquals(2, plan.level)
        assertTrue(plan.addFineAdaptive)
        assertTrue(plan.addFaintTextPass)
        assertTrue(plan.addShiftedLineCrops)
        assertTrue(plan.addCoarseAdaptive)
        assertFalse(plan.addStrongEdgePass)
        assertFalse(plan.addMicroLineCrops)
    }

    @Test
    fun third_and_later_attempts_use_strongest_safe_plan() {
        val third = AdaptiveOcrRetryPlanner.planForAttempt(3)
        val later = AdaptiveOcrRetryPlanner.planForAttempt(99)
        assertEquals(3, third.level)
        assertEquals(third, later)
        assertTrue(third.addFineAdaptive)
        assertTrue(third.addFaintTextPass)
        assertTrue(third.addShiftedLineCrops)
        assertTrue(third.addCoarseAdaptive)
        assertTrue(third.addStrongEdgePass)
        assertTrue(third.addMicroLineCrops)
    }
}
