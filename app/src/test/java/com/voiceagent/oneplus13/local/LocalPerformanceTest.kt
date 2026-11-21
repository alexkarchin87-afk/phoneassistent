package com.voiceagent.oneplus13.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPerformanceTest {
    @Test fun profilesLimitThreadsToAvailableCpus() {
        assertEquals(LocalWorkLimits(2, 0), localWorkLimits(LocalPerformance.COOL, 0, 8))
        assertEquals(LocalWorkLimits(3, 0), localWorkLimits(LocalPerformance.BALANCED, 0, 8))
        assertEquals(LocalWorkLimits(4, 0), localWorkLimits(LocalPerformance.FAST, 0, 8))
        assertEquals(LocalWorkLimits(1, 0), localWorkLimits(LocalPerformance.FAST, 0, 1))
    }

    @Test fun thermalEventsReduceWorkAndRecoveryRestoresProfile() {
        assertEquals(LocalWorkLimits(2, 20), localWorkLimits(LocalPerformance.FAST, 2, 8))
        assertEquals(LocalWorkLimits(1, 100), localWorkLimits(LocalPerformance.FAST, 3, 8))
        assertEquals(LocalWorkLimits(1, 250), localWorkLimits(LocalPerformance.FAST, 4, 8))
        assertEquals(LocalWorkLimits(1, 250), localWorkLimits(LocalPerformance.COOL, 6, 8))
        assertEquals(LocalWorkLimits(4, 0), localWorkLimits(LocalPerformance.FAST, 1, 8))
    }
}
