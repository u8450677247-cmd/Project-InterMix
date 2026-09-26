package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRunSchedulerTest {
    private val now = 1_800_000_000_000L

    private fun input(
        status: AgentMissionStatus = AgentMissionStatus.Running,
        runtime: Boolean = true,
        power: Boolean = true,
        thermal: Boolean = true,
        authority: Boolean = false,
        pendingExecution: Boolean = false,
        runningExecution: Boolean = false,
        terminalExecution: Boolean = false,
    ) = AgentRunSchedulerInput(
        missionId = "LF-SCHEDULE",
        missionStatus = status,
        runtimeAvailable = runtime,
        powerAvailable = power,
        thermalAvailable = thermal,
        authorityRequired = authority,
        pendingGrantedExecution = pendingExecution,
        runningExecution = runningExecution,
        terminalExecutionReady = terminalExecution,
        nowEpochMillis = now,
    )

    @Test
    fun prerequisitesProduceRunnableState() {
        val schedule = AgentRunSchedulerPolicy.reduce(input())

        assertEquals(AgentRunState.Runnable, schedule.state)
        assertEquals(now, schedule.nextRunAtEpochMillis)
    }

    @Test
    fun terminalEvidenceReconcilesBeforePausedOrAuthorityState() {
        val schedule = AgentRunSchedulerPolicy.reduce(
            input(
                status = AgentMissionStatus.Paused,
                authority = true,
                terminalExecution = true,
            ),
        )

        assertEquals(AgentRunState.Reconciling, schedule.state)
    }

    @Test
    fun executionAndResourceWaitsRemainTyped() {
        assertEquals(
            AgentRunState.WaitingExecution,
            AgentRunSchedulerPolicy.reduce(input(pendingExecution = true)).state,
        )
        assertEquals(
            AgentRunState.WaitingPower,
            AgentRunSchedulerPolicy.reduce(input(power = false)).state,
        )
        assertEquals(
            AgentRunState.WaitingThermal,
            AgentRunSchedulerPolicy.reduce(input(thermal = false)).state,
        )
        assertEquals(
            AgentRunState.WaitingRuntime,
            AgentRunSchedulerPolicy.reduce(input(runtime = false)).state,
        )
    }

    @Test
    fun codecAndUnchangedRevisionAreStable() {
        val first = AgentRunSchedulerPolicy.reduce(input())
        val second = AgentRunSchedulerPolicy.reduce(input(), first)
        val restored = AgentRunScheduleCodec.decode(AgentRunScheduleCodec.encode(second))

        assertEquals(first.revision, second.revision)
        assertEquals(second, restored)
    }
}
