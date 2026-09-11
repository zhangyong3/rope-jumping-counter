package com.yzrun.ropecounter.counter

import com.yzrun.ropecounter.vision.PoseFrame
import com.yzrun.ropecounter.vision.PosePoint
import com.yzrun.ropecounter.vision.MotionState
import org.junit.Assert.assertEquals
import org.junit.Test

class JumpDetectorTest {
    @Test
    fun oneTakeoffAndLanding_countsExactlyOnce() {
        val detector = JumpDetector()
        var timestamp = 0L
        repeat(28) {
            detector.process(frame(timestamp, 0f))
            timestamp += 33
        }

        var jumps = 0
        val verticalOffsets = listOf(
            0.005f,
            0.015f,
            0.030f,
            0.045f,
            0.050f,
            0.045f,
            0.030f,
            0.015f,
            0.004f,
            0f,
            0f,
            0f,
            0f,
            0f,
        )
        verticalOffsets.forEach { offset ->
            if (detector.process(frame(timestamp, offset)).jumpDetected) jumps += 1
            timestamp += 33
        }

        assertEquals(1, jumps)
    }

    @Test
    fun smallBodySway_isIgnored() {
        val detector = JumpDetector()
        var timestamp = 0L
        repeat(28) {
            detector.process(frame(timestamp, 0f))
            timestamp += 33
        }

        var jumps = 0
        repeat(60) { index ->
            val offset = if (index % 2 == 0) 0.003f else -0.003f
            if (detector.process(frame(timestamp, offset)).jumpDetected) jumps += 1
            timestamp += 33
        }

        assertEquals(0, jumps)
    }

    @Test
    fun missingPose_clearsTrackingAndRequestsRecalibration() {
        val detector = JumpDetector()
        var timestamp = 0L
        repeat(28) {
            detector.process(frame(timestamp, 0f))
            timestamp += 33
        }

        var result = detector.process(PoseFrame(timestamp, emptyList(), 540, 960))
        repeat(32) {
            timestamp += 33
            result = detector.process(PoseFrame(timestamp, emptyList(), 540, 960))
        }

        assertEquals(MotionState.LOST, result.state)
    }

    @Test
    fun walkingTowardCamera_recalibratesWithoutCounting() {
        val detector = JumpDetector()
        var timestamp = 0L
        repeat(24) {
            detector.process(frame(timestamp, 0f))
            timestamp += 33
        }

        var jumps = 0
        listOf(1.05f, 1.10f, 1.16f, 1.22f, 1.28f).forEach { scale ->
            if (detector.process(frame(timestamp, 0f, scale)).jumpDetected) jumps += 1
            timestamp += 33
        }

        assertEquals(0, jumps)
    }

    @Test
    fun repeatedFastJumps_countWithoutReturningToOriginalGroundLine() {
        val detector = JumpDetector()
        var timestamp = 0L
        repeat(24) {
            detector.process(frame(timestamp, 0f))
            timestamp += 33
        }

        var jumps = 0
        repeat(5) {
            listOf(
                0.015f,
                0.025f,
                0.042f,
                0.055f,
                0.048f,
                0.032f,
                0.020f,
                0.016f,
                0.015f,
            ).forEach { offset ->
                if (detector.process(frame(timestamp, offset)).jumpDetected) jumps += 1
                timestamp += 33
            }
        }

        assertEquals(5, jumps)
    }

    @Test
    fun repeatedJumps_withAProgressivelyShiftedGroundLine_keepCounting() {
        val detector = JumpDetector()
        var timestamp = 0L
        repeat(24) {
            detector.process(frame(timestamp, 0f))
            timestamp += 33
        }

        var jumps = 0
        repeat(7) { jumpIndex ->
            val floor = jumpIndex * 0.009f
            listOf(
                floor,
                floor + 0.008f,
                floor + 0.020f,
                floor + 0.036f,
                floor + 0.050f,
                floor + 0.042f,
                floor + 0.026f,
                floor + 0.010f,
                floor,
            ).forEach { offset ->
                if (detector.process(frame(timestamp, offset)).jumpDetected) jumps += 1
                timestamp += 33
            }
        }

        assertEquals(7, jumps)
    }

    private fun frame(timestampMs: Long, shiftUp: Float, scale: Float = 1f): PoseFrame {
        val points = MutableList(33) { PosePoint(0.5f, 0.5f, 0f, 1f, 1f) }
        points[11] = PosePoint(0.42f, 0.55f - 0.25f * scale - shiftUp, 0f, 1f, 1f)
        points[12] = PosePoint(0.58f, 0.55f - 0.25f * scale - shiftUp, 0f, 1f, 1f)
        points[23] = PosePoint(0.45f, 0.55f - shiftUp, 0f, 1f, 1f)
        points[24] = PosePoint(0.55f, 0.55f - shiftUp, 0f, 1f, 1f)
        points[25] = PosePoint(0.46f, 0.55f + 0.17f * scale - shiftUp, 0f, 1f, 1f)
        points[26] = PosePoint(0.54f, 0.55f + 0.17f * scale - shiftUp, 0f, 1f, 1f)
        points[27] = PosePoint(0.46f, 0.55f + 0.35f * scale - shiftUp, 0f, 1f, 1f)
        points[28] = PosePoint(0.54f, 0.55f + 0.35f * scale - shiftUp, 0f, 1f, 1f)
        return PoseFrame(timestampMs, points, 540, 960)
    }
}
