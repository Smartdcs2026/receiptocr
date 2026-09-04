package com.receiptocr.app.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveOcrRetryPlannerRound98Test {
    @Test
    fun first_attempt_keeps_round98_behavior() {
        val plan = AdaptiveOcrRetryPlanner.planForAttempt(1)
        assertEquals(1, plan.level)
        assertTrue(plan.addFineAdaptive)
        assertTrue(plan.addFaintTextPass)
        assertFalse(plan.addShiftedLineCrops)
        assertFalse(plan.addCoarseAdaptive)
        assertFalse(plan.addStrongEdgePass)
        assertFalse(plan.addMicroLineCrops)
        assertFalse(plan.addPrecisionUpscaleCrops)
    }

    @Test
    fun second_attempt_keeps_round98_behavior() {
        val plan = AdaptiveOcrRetryPlanner.planForAttempt(2)
        assertEquals(2, plan.level)
        assertTrue(plan.addFineAdaptive)
        assertTrue(plan.addFaintTextPass)
        assertTrue(plan.addShiftedLineCrops)
        assertTrue(plan.addCoarseAdaptive)
        assertFalse(plan.addStrongEdgePass)
        assertFalse(plan.addMicroLineCrops)
        assertFalse(plan.addPrecisionUpscaleCrops)
    }

    @Test
    fun third_attempt_keeps_round98_strongest_plan() {
        val third = AdaptiveOcrRetryPlanner.planForAttempt(3)
        assertEquals(3, third.level)
        assertTrue(third.addFineAdaptive)
        assertTrue(third.addFaintTextPass)
        assertTrue(third.addShiftedLineCrops)
        assertTrue(third.addCoarseAdaptive)
        assertTrue(third.addStrongEdgePass)
        assertTrue(third.addMicroLineCrops)
        assertFalse(third.addPrecisionUpscaleCrops)
    }
}
