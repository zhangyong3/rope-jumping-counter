package com.yzrun.ropecounter.counter

import com.yzrun.ropecounter.data.WorkoutConfig
import com.yzrun.ropecounter.data.WorkoutMode
import com.yzrun.ropecounter.data.WorkoutPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutEngineTest {
    @Test
    fun fixedCount_runsGroupsAndRest() {
        val engine = WorkoutEngine(
            config = WorkoutConfig(
                mode = WorkoutMode.FIXED_COUNT,
                targetCountPerGroup = 2,
                groups = 2,
                restSec = 10,
            ),
            startedAtMs = 0,
        )

        assertEquals(WorkoutPhase.PREPARING, engine.tick(3_000).phase)
        engine.markPoseReady(3_000)
        assertEquals(WorkoutPhase.ACTIVE, engine.tick(6_000).phase)
        engine.recordJump(6_200)
        val resting = engine.recordJump(6_500)
        assertEquals(WorkoutPhase.REST, resting.phase)
        assertEquals(2, resting.currentGroup)
        assertEquals(2, resting.totalCount)

        assertEquals(WorkoutPhase.COUNTDOWN, engine.tick(16_500).phase)
        assertEquals(WorkoutPhase.ACTIVE, engine.tick(19_500).phase)
        engine.recordJump(19_800)
        val complete = engine.recordJump(20_100)
        assertEquals(WorkoutPhase.COMPLETE, complete.phase)
        assertEquals(4, complete.totalCount)
    }

    @Test
    fun pause_doesNotConsumeTimedGroup() {
        val engine = WorkoutEngine(
            WorkoutConfig(mode = WorkoutMode.FIXED_TIME, durationPerGroupSec = 10, groups = 1),
            startedAtMs = 0,
        )

        engine.markPoseReady(0)
        engine.tick(3_000)
        engine.togglePause(8_000)
        assertEquals(WorkoutPhase.PAUSED, engine.tick(30_000).phase)
        engine.togglePause(30_000)
        assertEquals(WorkoutPhase.ACTIVE, engine.tick(34_999).phase)
        assertEquals(WorkoutPhase.COMPLETE, engine.tick(35_000).phase)
    }

    @Test
    fun countdownWaitsUntilPoseIsReady() {
        val engine = WorkoutEngine(WorkoutConfig(mode = WorkoutMode.FREE), startedAtMs = 0)

        assertEquals(WorkoutPhase.PREPARING, engine.tick(20_000).phase)
        assertEquals(WorkoutPhase.COUNTDOWN, engine.markPoseReady(20_000).phase)
        assertEquals(WorkoutPhase.ACTIVE, engine.tick(23_000).phase)
    }

    @Test
    fun losingPoseDuringCountdown_returnsToPreparation() {
        val engine = WorkoutEngine(WorkoutConfig(mode = WorkoutMode.FREE), startedAtMs = 0)

        engine.markPoseReady(1_000)
        assertEquals(WorkoutPhase.COUNTDOWN, engine.tick(2_000).phase)
        assertEquals(WorkoutPhase.PREPARING, engine.requirePosePreparation(2_000).phase)
        assertEquals(WorkoutPhase.PREPARING, engine.tick(20_000).phase)
    }
}
