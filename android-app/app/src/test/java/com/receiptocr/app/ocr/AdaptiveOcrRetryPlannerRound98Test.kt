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
    fun third_attempt_preserves_round98_strong_plan_even_when_round100_adds_later_level() {
        val third = AdaptiveOcrRetryPlanner.planForAttempt(3)
        val later = AdaptiveOcrRetryPlanner.planForAttempt(99)
        assertEquals(3, third.level)
        assertTrue(third.addFineAdaptive)
        assertTrue(third.addFaintTextPass)
        assertTrue(third.addShiftedLineCrops)
        assertTrue(third.addCoarseAdaptive)
        assertTrue(third.addStrongEdgePass)
        assertTrue(third.addMicroLineCrops)

        // Round100 may add a later rescue level, but it must never remove the Round98 plan.
        assertTrue(later.level >= third.level)
        assertTrue(later.addFineAdaptive)
        assertTrue(later.addFaintTextPass)
        assertTrue(later.addShiftedLineCrops)
        assertTrue(later.addCoarseAdaptive)
        assertTrue(later.addStrongEdgePass)
        assertTrue(later.addMicroLineCrops)
    }
}
