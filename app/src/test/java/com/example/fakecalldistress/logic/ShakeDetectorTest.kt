package com.example.fakecalldistress.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {

    @Test
    fun testShakeThreshold() {
        var shakeDetected = false
        val detector = ShakeDetector { shakeDetected = true }

        // Test normal gravity (1g) - should not trigger
        // 9.8m/s^2 is approx 1g
        assertFalse("Should not trigger at 1g", detector.processSensorData(0f, 9.8f, 0f))

        // Test subtle movement (1.5g) - should not trigger (threshold is 2.7g)
        assertFalse("Should not trigger at 1.5g", detector.processSensorData(0f, 14.7f, 0f))

        // Test violent shake (3g) - should trigger
        assertTrue("Should trigger at 3g", detector.processSensorData(0f, 29.4f, 0f))
    }

    @Test
    fun testDebounce() {
        var shakeCount = 0
        val detector = ShakeDetector { shakeCount++ }

        // First shake triggers
        assertTrue(detector.processSensorData(0f, 30f, 0f))
        
        // Immediate second shake should be ignored due to 500ms debounce
        assertFalse("Debounce should prevent immediate second trigger", detector.processSensorData(0f, 30f, 0f))
    }
}
