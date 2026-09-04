package com.receiptocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveOcrRetryPlannerRound101Test {
    @Test
    fun fourth_attempt_adds_only_precision_upscale_beyond_round98() {
        val third = AdaptiveOcrRetryPlanner.planForAttempt(3)
        val fourth = AdaptiveOcrRetryPlanner.planForAttempt(4)

        assertEquals(3, third.level)
        assertEquals(4, fourth.level)
        assertFalse(third.addPrecisionUpscaleCrops)
        assertTrue(fourth.addPrecisionUpscaleCrops)

        assertEquals(third.addFineAdaptive, fourth.addFineAdaptive)
        assertEquals(third.addFaintTextPass, fourth.addFaintTextPass)
        assertEquals(third.addShiftedLineCrops, fourth.addShiftedLineCrops)
        assertEquals(third.addCoarseAdaptive, fourth.addCoarseAdaptive)
        assertEquals(third.addStrongEdgePass, fourth.addStrongEdgePass)
        assertEquals(third.addMicroLineCrops, fourth.addMicroLineCrops)
    }

    @Test
    fun attempts_after_four_are_capped_to_same_rescue_plan() {
        val fourth = AdaptiveOcrRetryPlanner.planForAttempt(4)
        val later = AdaptiveOcrRetryPlanner.planForAttempt(99)
        assertEquals(fourth, later)
    }
}
