package com.dajiraj.steps_count

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure anti-spike primitives of [StepCountManager].
 *
 * These run on the plain JVM (no device) because [StepCountManager.plausibleMax] and
 * [StepCountManager.isAcceptableSensorValue] are pure functions. They pin down the two mechanisms
 * that stop the phantom single-entry spikes (1k / 2k / 40k / 50k) reported from the field:
 *  - the rate gate (EC-3): a delta faster than a human can physically walk is impossible,
 *  - the garbage gate (EC-1 / EC-48): a non-finite or absurd cumulative value is never booked.
 */
internal class StepCountManagerTest {

    // ---- Rate gate: plausibleMax(dtSec) --------------------------------------------------------

    @Test
    fun plausibleMax_knownPoints() {
        assertEquals(60L, StepCountManager.plausibleMax(0))       // base slack only
        assertEquals(65L, StepCountManager.plausibleMax(1))       // 60 + 5*1
        assertEquals(360L, StepCountManager.plausibleMax(60))     // 60 + 5*60
        assertEquals(18_060L, StepCountManager.plausibleMax(3600)) // 60 + 5*3600
    }

    @Test
    fun plausibleMax_negativeDtTreatedAsZero() {
        assertEquals(60L, StepCountManager.plausibleMax(-5))
    }

    @Test
    fun plausibleMax_rejectsTheReportedSpikes() {
        // The field signature: thousands to tens of thousands of steps in a single close-spaced event.
        assertTrue(2_000 > StepCountManager.plausibleMax(2), "2k in 2s must be rejected")
        assertTrue(40_000 > StepCountManager.plausibleMax(2), "40k in 2s must be rejected")
        assertTrue(50_000 > StepCountManager.plausibleMax(5), "50k in 5s must be rejected")
        // Even over a full minute of silence, a 50k jump is impossible.
        assertTrue(50_000 > StepCountManager.plausibleMax(60), "50k in 60s must be rejected")
    }

    @Test
    fun plausibleMax_acceptsRealWalking() {
        // Brisk walking ~2 steps/sec: 120 steps in 60s is well within the cap.
        assertTrue(120 <= StepCountManager.plausibleMax(60), "real walking must pass")
        // A hard run, 3 steps/sec for 10 minutes = 1800 steps.
        assertTrue(1_800 <= StepCountManager.plausibleMax(600), "running must pass")
    }

    @Test
    fun plausibleMax_acceptsGenuineMultiDayCatchUp() {
        // Service dead for 10 real days on an OEM device, user walked 42k steps in that window.
        // This MUST pass: it is real data delivered late, not a glitch.
        val tenDaysSec = 10L * 24 * 3600
        assertTrue(42_000 <= StepCountManager.plausibleMax(tenDaysSec), "10-day real catch-up must pass")
    }

    @Test
    fun plausibleMax_isMonotonicNonDecreasing() {
        var prev = Long.MIN_VALUE
        for (dt in longArrayOf(0, 1, 10, 60, 600, 3600, 7200, 86_400, 864_000)) {
            val cap = StepCountManager.plausibleMax(dt)
            assertTrue(cap >= prev, "cap must not decrease as dt grows (dt=$dt)")
            prev = cap
        }
    }

    // ---- Garbage gate: isAcceptableSensorValue(v) ----------------------------------------------

    @Test
    fun garbageGate_acceptsRealValues() {
        assertTrue(StepCountManager.isAcceptableSensorValue(0f))
        assertTrue(StepCountManager.isAcceptableSensorValue(12_345f))
        assertTrue(StepCountManager.isAcceptableSensorValue(1.0e9f)) // inclusive upper bound
    }

    @Test
    fun garbageGate_rejectsNonFinite() {
        assertFalse(StepCountManager.isAcceptableSensorValue(Float.NaN))
        assertFalse(StepCountManager.isAcceptableSensorValue(Float.POSITIVE_INFINITY))
        assertFalse(StepCountManager.isAcceptableSensorValue(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun garbageGate_rejectsNegative() {
        assertFalse(StepCountManager.isAcceptableSensorValue(-1f))
    }

    @Test
    fun garbageGate_rejectsHubGlitchMagnitudes() {
        // uint32 0xFFFFFFFF reinterpreted as float, and Float.MAX_VALUE: classic sensor-hub garbage.
        assertFalse(StepCountManager.isAcceptableSensorValue(4.294_967_3e9f))
        assertFalse(StepCountManager.isAcceptableSensorValue(Float.MAX_VALUE))
    }

    // ---- Anchor model: computeCredit(last, anchor) (Phase 2) -----------------------------------

    @Test
    fun computeCredit_basic() {
        assertEquals(5L, StepCountManager.computeCredit(1005.0, 1000.0))
        assertEquals(0L, StepCountManager.computeCredit(1000.0, 1000.0))
    }

    @Test
    fun computeCredit_flooringAndNegative() {
        assertEquals(5L, StepCountManager.computeCredit(1005.9, 1000.0)) // floor, never over-credit
        assertEquals(0L, StepCountManager.computeCredit(1000.7, 1000.0)) // sub-1-step fraction not booked
        assertEquals(0L, StepCountManager.computeCredit(999.0, 1000.0))  // counter behind anchor -> 0
    }

    @Test
    fun computeCredit_nanMeansNoAnchor() {
        assertEquals(0L, StepCountManager.computeCredit(Double.NaN, 1000.0))
        assertEquals(0L, StepCountManager.computeCredit(1000.0, Double.NaN))
    }

    // ---- Anchor model: splitIntoRowChunks(credit, maxPerRow) -----------------------------------

    @Test
    fun splitIntoRowChunks_splitsAndPreservesSum() {
        assertEquals(listOf(30), StepCountManager.splitIntoRowChunks(30, 50_000))
        assertEquals(listOf(50_000), StepCountManager.splitIntoRowChunks(50_000, 50_000))
        assertEquals(listOf(50_000, 1), StepCountManager.splitIntoRowChunks(50_001, 50_000))
        assertEquals(listOf(50_000, 50_000, 20_000), StepCountManager.splitIntoRowChunks(120_000, 50_000))
        assertEquals(emptyList(), StepCountManager.splitIntoRowChunks(0, 50_000))
    }

    @Test
    fun splitIntoRowChunks_sumAlwaysEqualsCredit() {
        for (credit in longArrayOf(1, 49, 50_000, 50_001, 123_456, 1_000_000)) {
            val sum = StepCountManager.splitIntoRowChunks(credit, 50_000).sumOf { it.toLong() }
            assertEquals(credit, sum, "chunk sum must equal credit ($credit)")
        }
    }
}
