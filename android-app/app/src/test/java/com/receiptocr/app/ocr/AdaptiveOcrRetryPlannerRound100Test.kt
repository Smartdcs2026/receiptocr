package com.receiptocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveOcrRetryPlannerRound100Test {
    @Test
    fun first_attempt_does_not_use_heavy_rescue_passes() {
        val plan = AdaptiveOcrRetryPlanner.planForAttempt(1)
        assertEquals(1, plan.level)
        assertFalse(plan.addUpscaledLineCrops)
        assertFalse(plan.addSkewRescuePasses)
        assertFalse(plan.addUpscaledGridCrops)
    }

    @Test
    fun second_attempt_adds_small_text_enlargement() {
        val plan = AdaptiveOcrRetryPlanner.planForAttempt(2)
        assertEquals(2, plan.level)
        assertTrue(plan.addUpscaledLineCrops)
        assertTrue(plan.addShiftedLineCrops)
        assertFalse(plan.addSkewRescuePasses)
        assertFalse(plan.addUpscaledGridCrops)
    }

    @Test
    fun fourth_attempt_adds_skew_and_grid_rescue_without_dropping_previous_methods() {
        val plan = AdaptiveOcrRetryPlanner.planForAttempt(4)
        assertEquals(4, plan.level)
        assertTrue(plan.addFineAdaptive)
        assertTrue(plan.addFaintTextPass)
        assertTrue(plan.addShiftedLineCrops)
        assertTrue(plan.addCoarseAdaptive)
        assertTrue(plan.addStrongEdgePass)
        assertTrue(plan.addMicroLineCrops)
        assertTrue(plan.addUpscaledLineCrops)
        assertTrue(plan.addSkewRescuePasses)
        assertTrue(plan.addUpscaledGridCrops)
    }

    @Test
    fun later_attempts_are_capped_at_round100_rescue_level() {
        val fourth = AdaptiveOcrRetryPlanner.planForAttempt(4)
        val later = AdaptiveOcrRetryPlanner.planForAttempt(999)
        assertEquals(4, later.level)
        assertEquals(fourth, later)
    }
}
