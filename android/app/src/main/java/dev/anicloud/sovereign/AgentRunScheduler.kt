package dev.anicloud.sovereign.prototype

import org.json.JSONObject

enum class AgentRunState {
    Runnable,
    WaitingRuntime,
    WaitingPower,
    WaitingThermal,
    WaitingAuthority,
    WaitingExecution,
    Reconciling,
    Paused,
    Completed,
    Failed,
}

data class AgentRunSchedule(
    val missionId: String,
    val state: AgentRunState,
    val reason: String,
    val revision: Long,
    val nextRunAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(missionId.isNotBlank())
        require(reason.isNotBlank() && reason.length <= 500)
        require(revision >= 1L)
        require(nextRunAtEpochMillis >= 0L)
        require(updatedAtEpochMillis > 0L)
    }
}

data class AgentRunSchedulerInput(
    val missionId: String,
    val missionStatus: AgentMissionStatus,
    val runtimeAvailable: Boolean,
    val powerAvailable: Boolean,
    val thermalAvailable: Boolean,
    val authorityRequired: Boolean,
    val pendingGrantedExecution: Boolean,
    val runningExecution: Boolean,
    val terminalExecutionReady: Boolean,
    val nowEpochMillis: Long,
)

/** Pure persisted state reducer. Generated text cannot mark a run runnable. */
object AgentRunSchedulerPolicy {
    fun reduce(
        input: AgentRunSchedulerInput,
        previous: AgentRunSchedule? = null,
    ): AgentRunSchedule {
        require(input.missionId.isNotBlank())
        require(input.nowEpochMillis > 0L)
        require(previous == null || previous.missionId == input.missionId)
        val decision = decide(input)
        val changed = previous == null || previous.state != decision.first || previous.reason != decision.second
        val revision = when {
            previous == null -> 1L
            changed -> previous.revision + 1L
            else -> previous.revision
        }
        val nextRunAt = when (decision.first) {
            AgentRunState.Runnable,
            AgentRunState.Reconciling,
            -> input.nowEpochMillis
            AgentRunState.WaitingExecution -> input.nowEpochMillis + 60_000L
            AgentRunState.WaitingRuntime,
            AgentRunState.WaitingPower,
            AgentRunState.WaitingThermal,
            -> input.nowEpochMillis + (15L * 60L * 1_000L)
            AgentRunState.WaitingAuthority,
            AgentRunState.Paused,
            AgentRunState.Completed,
            AgentRunState.Failed,
            -> 0L
        }
        return AgentRunSchedule(
            missionId = input.missionId,
            state = decision.first,
            reason = decision.second,
            revision = revision,
            nextRunAtEpochMillis = nextRunAt,
            updatedAtEpochMillis = input.nowEpochMillis,
        )
    }

    private fun decide(input: AgentRunSchedulerInput): Pair<AgentRunState, String> = when {
        input.missionStatus == AgentMissionStatus.Completed ->
            AgentRunState.Completed to "Mission completed at a verified controller checkpoint."
        input.missionStatus in setOf(AgentMissionStatus.Failed, AgentMissionStatus.Cancelled) ->
            AgentRunState.Failed to "Mission is ${input.missionStatus.name.lowercase()}."
        input.terminalExecutionReady ->
            AgentRunState.Reconciling to "Terminal execution evidence is ready for controller reconciliation."
        input.runningExecution || input.pendingGrantedExecution ->
            AgentRunState.WaitingExecution to "A granted execution is pending or running."
        input.authorityRequired ->
            AgentRunState.WaitingAuthority to "The next execution requires an explicit authority transition."
        input.missionStatus == AgentMissionStatus.Paused ->
            AgentRunState.Paused to "Mission is durably paused."
        !input.powerAvailable ->
            AgentRunState.WaitingPower to "Android power policy is deferring autonomous work."
        !input.thermalAvailable ->
            AgentRunState.WaitingThermal to "Android thermal pressure is deferring autonomous work."
        !input.runtimeAvailable ->
            AgentRunState.WaitingRuntime to "No compatible installed cognition runtime is available."
        else -> AgentRunState.Runnable to "All durable run prerequisites are satisfied."
    }
}

object AgentRunScheduleCodec {
    fun encode(schedule: AgentRunSchedule): JSONObject = JSONObject()
        .put("mission_id", schedule.missionId)
        .put("state", schedule.state.name)
        .put("reason", schedule.reason)
        .put("revision", schedule.revision)
        .put("next_run_at_epoch_millis", schedule.nextRunAtEpochMillis)
        .put("updated_at_epoch_millis", schedule.updatedAtEpochMillis)

    fun decode(payload: JSONObject): AgentRunSchedule = AgentRunSchedule(
        missionId = payload.getString("mission_id"),
        state = AgentRunState.valueOf(payload.getString("state")),
        reason = payload.getString("reason"),
        revision = payload.getLong("revision"),
        nextRunAtEpochMillis = payload.getLong("next_run_at_epoch_millis"),
        updatedAtEpochMillis = payload.getLong("updated_at_epoch_millis"),
    )
}
