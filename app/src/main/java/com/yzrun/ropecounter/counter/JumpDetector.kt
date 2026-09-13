package com.yzrun.ropecounter.counter

import com.yzrun.ropecounter.vision.JumpDetection
import com.yzrun.ropecounter.vision.MotionState
import com.yzrun.ropecounter.vision.PoseFrame
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Detects a two-foot jump from normalized pose landmarks.
 *
 * The detector intentionally does not try to see the rope. It combines ankle and hip lift,
 * normalized by torso length, then applies hysteresis and timing constraints. Keeping this class
 * free of Android/MediaPipe types makes it deterministic and unit-testable.
 */
class JumpDetector {
    private var state = MotionState.CALIBRATING
    private var calibrationSamples = 0
    private var baselineLeftAnkleY = 0f
    private var baselineRightAnkleY = 0f
    private var baselineAnkleY = 0f
    private var baselineHipY = 0f
    private var baselineTorsoLength = 0f
    private var previousCalibrationAnkleY = 0f
    private var previousCalibrationHipY = 0f
    private var previousCalibrationTorsoLength = 0f
    private var smoothedLift = 0f
    private var previousLift = 0f
    private var previousLeftAnkleLift = 0f
    private var previousRightAnkleLift = 0f
    private var cycleFloorLift = 0f
    private var landingMinimumLift = Float.POSITIVE_INFINITY
    private var previousTimestampMs = 0L
    private var takeoffTimestampMs = 0L
    private var takeoffLift = 0f
    private var lastJumpTimestampMs = Long.MIN_VALUE / 2
    private var peakLift = 0f
    private var lostSinceMs = 0L

    fun reset() {
        state = MotionState.CALIBRATING
        calibrationSamples = 0
        baselineLeftAnkleY = 0f
        baselineRightAnkleY = 0f
        baselineAnkleY = 0f
        baselineHipY = 0f
        baselineTorsoLength = 0f
        previousCalibrationAnkleY = 0f
        previousCalibrationHipY = 0f
        previousCalibrationTorsoLength = 0f
        smoothedLift = 0f
        previousLift = 0f
        previousLeftAnkleLift = 0f
        previousRightAnkleLift = 0f
        cycleFloorLift = 0f
        landingMinimumLift = Float.POSITIVE_INFINITY
        previousTimestampMs = 0L
        takeoffTimestampMs = 0L
        takeoffLift = 0f
        lastJumpTimestampMs = Long.MIN_VALUE / 2
        peakLift = 0f
        lostSinceMs = 0L
    }

    fun process(frame: PoseFrame): JumpDetection {
        val measurements = measurements(frame)
        if (measurements == null) {
            if (lostSinceMs == 0L) lostSinceMs = frame.timestampMs
            if (frame.timestampMs - lostSinceMs > LOST_RESET_MS) {
                state = MotionState.LOST
            }
            return result(false, 0f, "请保持全身入镜", smoothedLift)
        }
        lostSinceMs = 0L

        if (state == MotionState.LOST) {
            state = MotionState.CALIBRATING
            calibrationSamples = 0
        }

        if (state == MotionState.CALIBRATING) {
            addCalibrationSample(measurements)
            previousTimestampMs = frame.timestampMs
            if (calibrationSamples >= REQUIRED_CALIBRATION_SAMPLES) {
                state = MotionState.READY
            }
            return result(
                jumpDetected = false,
                quality = measurements.quality,
                guidance = if (state == MotionState.READY) "可以开始" else "请站稳，正在校准",
                lift = 0f,
            )
        }
        // Do not compare the current position with the initial framing here. A jumper naturally
        // drifts sideways and toward/away from the camera. The per-cycle floor below absorbs that
        // accumulated translation, while the current torso length keeps jump amplitude normalized.
        val torso = measurements.torsoLength.coerceAtLeast(MIN_TORSO_LENGTH)
        val leftAnkleLift = (baselineLeftAnkleY - measurements.leftAnkleY) / torso
        val rightAnkleLift = (baselineRightAnkleY - measurements.rightAnkleY) / torso
        val rawLift = ANKLE_WEIGHT * (leftAnkleLift + rightAnkleLift) / 2f +
            HIP_WEIGHT * (baselineHipY - measurements.hipY) / torso
        previousLift = smoothedLift
        smoothedLift += SMOOTHING_ALPHA * (rawLift - smoothedLift)

        val deltaMs = (frame.timestampMs - previousTimestampMs).coerceIn(1L, 100L)
        val velocity = (smoothedLift - previousLift) * 1_000f / deltaMs
        val leftAnkleVelocity = (leftAnkleLift - previousLeftAnkleLift) * 1_000f / deltaMs
        val rightAnkleVelocity = (rightAnkleLift - previousRightAnkleLift) * 1_000f / deltaMs
        val feetRisingTogether = leftAnkleVelocity > MIN_SINGLE_FOOT_RISING_VELOCITY &&
            rightAnkleVelocity > MIN_SINGLE_FOOT_RISING_VELOCITY &&
            (leftAnkleVelocity + rightAnkleVelocity) / 2f > MIN_FEET_RISING_VELOCITY &&
            abs(leftAnkleVelocity - rightAnkleVelocity) < MAX_FEET_VELOCITY_DIFFERENCE
        previousLeftAnkleLift = leftAnkleLift
        previousRightAnkleLift = rightAnkleLift
        previousTimestampMs = frame.timestampMs
        var detected = false

        when (state) {
            MotionState.READY -> {
                updateGroundBaseline(measurements)
                if (velocity <= RISING_VELOCITY) {
                    cycleFloorLift += CYCLE_FLOOR_ALPHA * (smoothedLift - cycleFloorLift)
                }
                if (
                    feetRisingTogether &&
                    smoothedLift - cycleFloorLift > RISING_LIFT &&
                    velocity > RISING_VELOCITY
                ) {
                    state = MotionState.RISING
                    takeoffTimestampMs = frame.timestampMs
                    takeoffLift = cycleFloorLift
                    peakLift = smoothedLift
                }
            }

            MotionState.RISING -> {
                peakLift = maxOf(peakLift, smoothedLift)
                when {
                    peakLift - takeoffLift >= AIRBORNE_LIFT -> state = MotionState.AIRBORNE
                    velocity < LANDING_VELOCITY -> {
                        state = MotionState.READY
                        cycleFloorLift = smoothedLift
                    }
                    frame.timestampMs - takeoffTimestampMs > MAX_JUMP_MS -> {
                        state = MotionState.READY
                        cycleFloorLift = smoothedLift
                    }
                }
            }

            MotionState.AIRBORNE -> {
                peakLift = maxOf(peakLift, smoothedLift)
                if (velocity < LANDING_VELOCITY) {
                    val risingDuration = frame.timestampMs - takeoffTimestampMs
                    detected = risingDuration in MIN_RISING_MS..MAX_RISING_MS &&
                        peakLift - takeoffLift >= MIN_RISE_AMPLITUDE &&
                        frame.timestampMs - lastJumpTimestampMs >= REFRACTORY_MS
                    if (detected) lastJumpTimestampMs = frame.timestampMs
                    state = MotionState.LANDING
                    landingMinimumLift = smoothedLift
                }
            }

            MotionState.LANDING -> {
                landingMinimumLift = minOf(landingMinimumLift, smoothedLift)
                val jumpDuration = frame.timestampMs - takeoffTimestampMs
                if (
                    feetRisingTogether &&
                    smoothedLift - landingMinimumLift > RISING_LIFT &&
                    velocity > RISING_VELOCITY
                ) {
                    cycleFloorLift = landingMinimumLift
                    state = MotionState.RISING
                    takeoffTimestampMs = frame.timestampMs
                    takeoffLift = cycleFloorLift
                    peakLift = smoothedLift
                } else if (
                    jumpDuration >= MIN_LANDING_SETTLE_MS &&
                    abs(velocity) <= MAX_SETTLED_VELOCITY
                ) {
                    state = MotionState.READY
                    cycleFloorLift = smoothedLift
                    updateGroundBaseline(measurements)
                } else if (jumpDuration > MAX_JUMP_MS) {
                    state = MotionState.READY
                    cycleFloorLift = landingMinimumLift
                }
            }

            else -> Unit
        }

        return result(
            jumpDetected = detected,
            quality = measurements.quality,
            guidance = when (state) {
                MotionState.READY -> "可以开始"
                MotionState.RISING,
                MotionState.AIRBORNE,
                MotionState.LANDING,
                -> "计数中"
                else -> "请站稳，正在校准"
            },
            lift = smoothedLift,
        )
    }

    private fun addCalibrationSample(measurements: Measurements) {
        if (calibrationSamples > 0) {
            val movement = (
                abs(measurements.ankleY - previousCalibrationAnkleY) +
                    abs(measurements.hipY - previousCalibrationHipY)
                ) / measurements.torsoLength.coerceAtLeast(MIN_TORSO_LENGTH)
            val scaleMovement = abs(measurements.torsoLength - previousCalibrationTorsoLength) /
                previousCalibrationTorsoLength.coerceAtLeast(MIN_TORSO_LENGTH)
            if (movement > MAX_CALIBRATION_MOVEMENT || scaleMovement > MAX_CALIBRATION_SCALE_MOVEMENT) {
                restartCalibration(measurements)
                return
            }
        }

        calibrationSamples += 1
        val alpha = 1f / calibrationSamples
        baselineLeftAnkleY += alpha * (measurements.leftAnkleY - baselineLeftAnkleY)
        baselineRightAnkleY += alpha * (measurements.rightAnkleY - baselineRightAnkleY)
        baselineAnkleY += alpha * (measurements.ankleY - baselineAnkleY)
        baselineHipY += alpha * (measurements.hipY - baselineHipY)
        baselineTorsoLength += alpha * (measurements.torsoLength - baselineTorsoLength)
        previousCalibrationAnkleY = measurements.ankleY
        previousCalibrationHipY = measurements.hipY
        previousCalibrationTorsoLength = measurements.torsoLength
    }

    private fun restartCalibration(measurements: Measurements) {
        state = MotionState.CALIBRATING
        calibrationSamples = 0
        baselineLeftAnkleY = 0f
        baselineRightAnkleY = 0f
        baselineAnkleY = 0f
        baselineHipY = 0f
        baselineTorsoLength = 0f
        smoothedLift = 0f
        previousLift = 0f
        previousLeftAnkleLift = 0f
        previousRightAnkleLift = 0f
        cycleFloorLift = 0f
        landingMinimumLift = Float.POSITIVE_INFINITY
        addCalibrationSample(measurements)
    }

    private fun updateGroundBaseline(measurements: Measurements) {
        if (abs(smoothedLift - cycleFloorLift) > GROUNDED_LIFT) return
        baselineLeftAnkleY += BASELINE_ALPHA * (measurements.leftAnkleY - baselineLeftAnkleY)
        baselineRightAnkleY += BASELINE_ALPHA * (measurements.rightAnkleY - baselineRightAnkleY)
        baselineAnkleY += BASELINE_ALPHA * (measurements.ankleY - baselineAnkleY)
        baselineHipY += BASELINE_ALPHA * (measurements.hipY - baselineHipY)
        baselineTorsoLength += BASELINE_ALPHA * (measurements.torsoLength - baselineTorsoLength)
    }

    private fun measurements(frame: PoseFrame): Measurements? {
        val leftShoulder = frame[LEFT_SHOULDER] ?: return null
        val rightShoulder = frame[RIGHT_SHOULDER] ?: return null
        val leftHip = frame[LEFT_HIP] ?: return null
        val rightHip = frame[RIGHT_HIP] ?: return null
        val leftKnee = frame[LEFT_KNEE] ?: return null
        val rightKnee = frame[RIGHT_KNEE] ?: return null
        val leftAnkle = frame[LEFT_ANKLE] ?: return null
        val rightAnkle = frame[RIGHT_ANKLE] ?: return null

        val points = listOf(
            leftShoulder,
            rightShoulder,
            leftHip,
            rightHip,
            leftKnee,
            rightKnee,
            leftAnkle,
            rightAnkle,
        )
        val quality = points.map { minOf(it.visibility, it.presence) }.average().toFloat()
        if (quality < MIN_AVERAGE_QUALITY || points.any { it.visibility < MIN_POINT_VISIBILITY }) {
            return null
        }

        val shoulderX = (leftShoulder.x + rightShoulder.x) / 2f
        val shoulderY = (leftShoulder.y + rightShoulder.y) / 2f
        val hipX = (leftHip.x + rightHip.x) / 2f
        val hipY = (leftHip.y + rightHip.y) / 2f
        val torsoLength = hypot(shoulderX - hipX, shoulderY - hipY)
        if (torsoLength < MIN_TORSO_LENGTH) return null

        return Measurements(
            leftAnkleY = leftAnkle.y,
            rightAnkleY = rightAnkle.y,
            ankleY = (leftAnkle.y + rightAnkle.y) / 2f,
            hipY = hipY,
            torsoLength = torsoLength,
            quality = quality,
        )
    }

    private fun result(
        jumpDetected: Boolean,
        quality: Float,
        guidance: String,
        lift: Float,
    ) = JumpDetection(jumpDetected, state, quality, guidance, lift)

    private data class Measurements(
        val leftAnkleY: Float,
        val rightAnkleY: Float,
        val ankleY: Float,
        val hipY: Float,
        val torsoLength: Float,
        val quality: Float,
    )

    private companion object {
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25
        const val RIGHT_KNEE = 26
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28

        const val REQUIRED_CALIBRATION_SAMPLES = 20
        const val MIN_POINT_VISIBILITY = 0.25f
        const val MIN_AVERAGE_QUALITY = 0.48f
        const val MIN_TORSO_LENGTH = 0.08f
        const val ANKLE_WEIGHT = 0.72f
        const val HIP_WEIGHT = 0.28f
        const val SMOOTHING_ALPHA = 0.52f
        const val BASELINE_ALPHA = 0.025f
        const val MAX_CALIBRATION_MOVEMENT = 0.050f
        const val MAX_CALIBRATION_SCALE_MOVEMENT = 0.050f
        const val CYCLE_FLOOR_ALPHA = 0.18f
        const val MIN_SINGLE_FOOT_RISING_VELOCITY = -0.25f
        const val MIN_FEET_RISING_VELOCITY = 0.06f
        const val MAX_FEET_VELOCITY_DIFFERENCE = 1.20f

        const val RISING_LIFT = 0.022f
        const val RISING_VELOCITY = 0.08f
        const val AIRBORNE_LIFT = 0.050f
        const val LANDING_VELOCITY = -0.06f
        const val GROUNDED_LIFT = 0.018f

        const val MIN_RISING_MS = 45L
        const val MAX_RISING_MS = 500L
        const val MIN_RISE_AMPLITUDE = 0.018f
        const val MIN_LANDING_SETTLE_MS = 250L
        const val MAX_SETTLED_VELOCITY = 0.08f
        const val MAX_JUMP_MS = 850L
        const val REFRACTORY_MS = 180L
        const val LOST_RESET_MS = 900L
    }
}
