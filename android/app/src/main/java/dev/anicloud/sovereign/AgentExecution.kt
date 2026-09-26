package dev.anicloud.sovereign.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

private const val MaximumExecutionReceipts = 1_600
private val TaskIdentityPattern = Regex("^TASK-[0-9]{3}$")
private val StepIdentityPattern = Regex("^STEP-[0-9]{6}$")
private val RunIdentityPattern = Regex("^RUN-[0-9]{8}$")
private val TransactionIdentityPattern = Regex("^TX-[0-9]{8}$")
private val OperationIdentityPattern = Regex("^OP-[0-9]{8}$")
private val ToolCallIdentityPattern = Regex("^TC-[0-9]{8}$")

enum class AgentOperationState {
    Pending,
    Succeeded,
    Failed,
    Stale,
    Duplicate,
    Compensated,
    Uncompensated,
}

data class AgentRunIdentity(
    val missionId: String,
    val taskId: String,
    val logicalStepId: String,
    val runId: String,
) {
    init {
        require(missionId.isNotBlank()) { "A run identity requires its mission." }
        require(TaskIdentityPattern.matches(taskId)) { "A run identity has an invalid task id." }
        require(StepIdentityPattern.matches(logicalStepId)) { "A run identity has an invalid logical step id." }
        require(RunIdentityPattern.matches(runId)) { "A run identity has an invalid run id." }
    }
}

data class AgentOperationIdentity(
    val missionId: String,
    val taskId: String,
    val logicalStepId: String,
    val runId: String,
    val transactionId: String,
    val operationId: String,
    val toolCallId: String,
) {
    init {
        AgentRunIdentity(missionId, taskId, logicalStepId, runId)
        require(TransactionIdentityPattern.matches(transactionId)) {
            "An operation identity has an invalid transaction id."
        }
        require(OperationIdentityPattern.matches(operationId)) {
            "An operation identity has an invalid operation id."
        }
        require(ToolCallIdentityPattern.matches(toolCallId)) {
            "An operation identity has an invalid tool-call id."
        }
    }

    val runIdentity: AgentRunIdentity
        get() = AgentRunIdentity(missionId, taskId, logicalStepId, runId)
}

data class AgentOperationReceipt(
    val identity: AgentOperationIdentity,
    val state: AgentOperationState,
    val evidenceSha256: String = "",
    val detail: String = "",
    val updatedAt: String = "",
) {
    init {
        require(evidenceSha256.isBlank() || Regex("^[0-9a-f]{64}$").matches(evidenceSha256)) {
            "Operation evidence hash is invalid."
        }
        require(detail.length <= 2_000) { "Operation evidence detail is too large." }
    }
}

/**
 * Durable controller truth for asynchronous execution. Sequence numbers are mission-local and the
 * complete identity tuple is required at admission, so a callback from another run can be retained
 * as evidence without ever advancing the current mission.
 */
data class AgentExecutionLedger(
    val missionId: String,
    val currentTaskId: String,
    val logicalStepOrdinal: Int,
    val runSequence: Int = 0,
    val transactionSequence: Int = 0,
    val operationSequence: Int = 0,
    val currentRunId: String = "",
    val currentTransactionId: String = "",
    val admittedRunIds: List<String> = emptyList(),
    val processedToolCallIds: List<String> = emptyList(),
    val receipts: List<AgentOperationReceipt> = emptyList(),
    val staleResultCount: Int = 0,
    val duplicateResultCount: Int = 0,
) {
    init {
        require(missionId.isNotBlank()) { "An execution ledger requires its mission." }
        require(TaskIdentityPattern.matches(currentTaskId)) { "Execution ledger task id is invalid." }
        require(logicalStepOrdinal in 1..999_999) { "Execution ledger logical step is out of range." }
        require(runSequence >= 0 && transactionSequence >= 0 && operationSequence >= 0) {
            "Execution ledger sequences cannot be negative."
        }
        require(currentRunId.isBlank() || RunIdentityPattern.matches(currentRunId)) {
            "Execution ledger current run id is invalid."
        }
        require(currentTransactionId.isBlank() || TransactionIdentityPattern.matches(currentTransactionId)) {
            "Execution ledger current transaction id is invalid."
        }
        require(admittedRunIds.distinct().size == admittedRunIds.size) {
            "Admitted run ids must be unique."
        }
        require(processedToolCallIds.distinct().size == processedToolCallIds.size) {
            "Processed tool-call ids must be unique."
        }
        require(receipts.size <= MaximumExecutionReceipts) { "Execution receipt ledger is over capacity." }
        require(staleResultCount >= 0 && duplicateResultCount >= 0) {
            "Execution rejection counters cannot be negative."
        }
    }

    val currentLogicalStepId: String
        get() = canonicalExecutionId("STEP", logicalStepOrdinal, 6)

    val currentRunIdentity: AgentRunIdentity?
        get() = currentRunId.takeIf(String::isNotBlank)?.let {
            AgentRunIdentity(missionId, currentTaskId, currentLogicalStepId, it)
        }
}

data class AgentTransactionLease(
    val ledger: AgentExecutionLedger,
    val transactionId: String,
    val operations: List<AgentOperationIdentity>,
)

data class AgentResultAdmission(
    val ledger: AgentExecutionLedger,
    val accepted: Boolean,
    val stale: Boolean = false,
    val duplicate: Boolean = false,
)

data class AgentMissionRunAdmission(
    val mission: AgentMissionCheckpoint,
    val accepted: Boolean,
    val stale: Boolean = false,
    val duplicate: Boolean = false,
)

data class AgentMissionTransactionLease(
    val mission: AgentMissionCheckpoint,
    val transactionId: String,
    val operations: List<AgentOperationIdentity>,
)

object AgentExecutionController {
    fun initial(
        missionId: String,
        taskId: String,
        logicalStepsCompleted: Int = 0,
    ): AgentExecutionLedger = AgentExecutionLedger(
        missionId = missionId,
        currentTaskId = canonicalTaskId(taskId, logicalStepsCompleted),
        logicalStepOrdinal = (logicalStepsCompleted + 1).coerceIn(1, 999_999),
    )

    fun beginRun(
        ledger: AgentExecutionLedger,
        taskId: String,
        logicalStepsCompleted: Int,
    ): AgentExecutionLedger {
        val nextSequence = ledger.runSequence + 1
        return ledger.copy(
            currentTaskId = canonicalTaskId(taskId, logicalStepsCompleted),
            logicalStepOrdinal = (logicalStepsCompleted + 1).coerceIn(1, 999_999),
            runSequence = nextSequence,
            currentRunId = canonicalExecutionId("RUN", nextSequence, 8),
            currentTransactionId = "",
        )
    }

    fun admitRunResult(
        ledger: AgentExecutionLedger,
        identity: AgentRunIdentity,
    ): AgentResultAdmission {
        if (identity.runId in ledger.admittedRunIds) {
            return AgentResultAdmission(
                ledger = ledger.copy(duplicateResultCount = ledger.duplicateResultCount + 1),
                accepted = false,
                duplicate = true,
            )
        }
        val current = ledger.currentRunIdentity
        if (current == null || current != identity) {
            return AgentResultAdmission(
                ledger = ledger.copy(staleResultCount = ledger.staleResultCount + 1),
                accepted = false,
                stale = true,
            )
        }
        return AgentResultAdmission(
            ledger = ledger.copy(admittedRunIds = (ledger.admittedRunIds + identity.runId).takeLast(1_440)),
            accepted = true,
        )
    }

    fun beginTransaction(
        ledger: AgentExecutionLedger,
        operationCount: Int,
        explicitBundle: Boolean,
        updatedAt: String,
    ): AgentTransactionLease {
        require(ledger.currentRunId.isNotBlank()) { "A transaction requires an active run." }
        require(ledger.currentRunId in ledger.admittedRunIds) {
            "A transaction requires a controller-admitted run result."
        }
        if (explicitBundle) {
            require(operationCount in 2..MaximumMissionTransactionOperations) {
                "An explicit transaction requires 2..$MaximumMissionTransactionOperations operations."
            }
        } else {
            require(operationCount == 1) { "An implicit controller transaction contains one operation." }
        }
        val nextTransactionSequence = ledger.transactionSequence + 1
        val transactionId = canonicalExecutionId("TX", nextTransactionSequence, 8)
        var nextOperationSequence = ledger.operationSequence
        val operations = buildList {
            repeat(operationCount) {
                nextOperationSequence++
                add(AgentOperationIdentity(
                    missionId = ledger.missionId,
                    taskId = ledger.currentTaskId,
                    logicalStepId = ledger.currentLogicalStepId,
                    runId = ledger.currentRunId,
                    transactionId = transactionId,
                    operationId = canonicalExecutionId("OP", nextOperationSequence, 8),
                    toolCallId = canonicalExecutionId("TC", nextOperationSequence, 8),
                ))
            }
        }
        val pendingReceipts = operations.map { identity ->
            AgentOperationReceipt(identity, AgentOperationState.Pending, updatedAt = updatedAt)
        }
        return AgentTransactionLease(
            ledger = ledger.copy(
                transactionSequence = nextTransactionSequence,
                operationSequence = nextOperationSequence,
                currentTransactionId = transactionId,
                receipts = boundedReceipts(ledger.receipts + pendingReceipts),
            ),
            transactionId = transactionId,
            operations = operations,
        )
    }

    fun admitOperationResult(
        ledger: AgentExecutionLedger,
        identity: AgentOperationIdentity,
        succeeded: Boolean,
        evidence: String,
        updatedAt: String,
    ): AgentResultAdmission {
        if (identity.toolCallId in ledger.processedToolCallIds) {
            val duplicateReceipt = AgentOperationReceipt(
                identity = identity,
                state = AgentOperationState.Duplicate,
                evidenceSha256 = evidenceSha256(evidence),
                detail = evidence.take(2_000),
                updatedAt = updatedAt,
            )
            return AgentResultAdmission(
                ledger = ledger.copy(
                    receipts = boundedReceipts(ledger.receipts + duplicateReceipt),
                    duplicateResultCount = ledger.duplicateResultCount + 1,
                ),
                accepted = false,
                duplicate = true,
            )
        }
        val currentRun = ledger.currentRunIdentity
        val pending = ledger.receipts.any {
            it.identity == identity && it.state == AgentOperationState.Pending
        }
        val currentIdentity = currentRun == identity.runIdentity &&
            ledger.currentTransactionId == identity.transactionId
        if (!currentIdentity || !pending) {
            val staleReceipt = AgentOperationReceipt(
                identity = identity,
                state = AgentOperationState.Stale,
                evidenceSha256 = evidenceSha256(evidence),
                detail = evidence.take(2_000),
                updatedAt = updatedAt,
            )
            return AgentResultAdmission(
                ledger = ledger.copy(
                    processedToolCallIds = boundedToolCallIds(
                        ledger.processedToolCallIds + identity.toolCallId,
                    ),
                    receipts = boundedReceipts(ledger.receipts + staleReceipt),
                    staleResultCount = ledger.staleResultCount + 1,
                ),
                accepted = false,
                stale = true,
            )
        }
        val completedReceipt = AgentOperationReceipt(
            identity = identity,
            state = if (succeeded) AgentOperationState.Succeeded else AgentOperationState.Failed,
            evidenceSha256 = evidenceSha256(evidence),
            detail = evidence.take(2_000),
            updatedAt = updatedAt,
        )
        return AgentResultAdmission(
            ledger = ledger.copy(
                processedToolCallIds = boundedToolCallIds(
                    ledger.processedToolCallIds + identity.toolCallId,
                ),
                receipts = boundedReceipts(
                    ledger.receipts.map { receipt ->
                        if (receipt.identity == identity && receipt.state == AgentOperationState.Pending) {
                            completedReceipt
                        } else {
                            receipt
                        }
                    },
                ),
            ),
            accepted = true,
        )
    }

    fun recordCompensation(
        ledger: AgentExecutionLedger,
        identity: AgentOperationIdentity,
        compensated: Boolean,
        detail: String,
        updatedAt: String,
    ): AgentExecutionLedger {
        require(ledger.receipts.any {
            it.identity == identity && it.state == AgentOperationState.Succeeded
        }) { "Only a verified successful operation can receive compensation evidence." }
        val receipt = AgentOperationReceipt(
            identity = identity,
            state = if (compensated) {
                AgentOperationState.Compensated
            } else {
                AgentOperationState.Uncompensated
            },
            evidenceSha256 = evidenceSha256(detail),
            detail = detail.take(2_000),
            updatedAt = updatedAt,
        )
        return ledger.copy(receipts = boundedReceipts(ledger.receipts + receipt))
    }

    fun afterCheckpoint(
        ledger: AgentExecutionLedger,
        nextTaskId: String,
        logicalStepsCompleted: Int,
    ): AgentExecutionLedger = ledger.copy(
        currentTaskId = canonicalTaskId(nextTaskId, logicalStepsCompleted),
        logicalStepOrdinal = (logicalStepsCompleted + 1).coerceIn(1, 999_999),
        currentRunId = "",
        currentTransactionId = "",
    )

    private fun canonicalTaskId(taskId: String, completedLogicalSteps: Int): String =
        taskId.takeIf(TaskIdentityPattern::matches)
            ?: "TASK-${(completedLogicalSteps + 1).coerceIn(1, MaximumMissionTasks).toString().padStart(3, '0')}"

    private fun boundedReceipts(receipts: List<AgentOperationReceipt>): List<AgentOperationReceipt> =
        receipts.takeLast(MaximumExecutionReceipts)

    private fun boundedToolCallIds(ids: List<String>): List<String> =
        ids.distinct().takeLast(DefaultMissionToolOperationBudget * 2)
}

object AgentExecutionLedgerCodec {
    fun encode(ledger: AgentExecutionLedger): JSONObject = JSONObject()
        .put("mission_id", ledger.missionId)
        .put("current_task_id", ledger.currentTaskId)
        .put("logical_step_ordinal", ledger.logicalStepOrdinal)
        .put("run_sequence", ledger.runSequence)
        .put("transaction_sequence", ledger.transactionSequence)
        .put("operation_sequence", ledger.operationSequence)
        .put("current_run_id", ledger.currentRunId)
        .put("current_transaction_id", ledger.currentTransactionId)
        .put("admitted_run_ids", JSONArray(ledger.admittedRunIds))
        .put("processed_tool_call_ids", JSONArray(ledger.processedToolCallIds))
        .put("stale_result_count", ledger.staleResultCount)
        .put("duplicate_result_count", ledger.duplicateResultCount)
        .put("receipts", JSONArray().apply {
            ledger.receipts.forEach { receipt ->
                put(JSONObject()
                    .put("mission_id", receipt.identity.missionId)
                    .put("task_id", receipt.identity.taskId)
                    .put("logical_step_id", receipt.identity.logicalStepId)
                    .put("run_id", receipt.identity.runId)
                    .put("transaction_id", receipt.identity.transactionId)
                    .put("operation_id", receipt.identity.operationId)
                    .put("tool_call_id", receipt.identity.toolCallId)
                    .put("state", receipt.state.name)
                    .put("evidence_sha256", receipt.evidenceSha256)
                    .put("detail", receipt.detail)
                    .put("updated_at", receipt.updatedAt))
            }
        })

    fun decode(payload: JSONObject): AgentExecutionLedger {
        val receiptsPayload = payload.optJSONArray("receipts") ?: JSONArray()
        val receipts = buildList {
            for (index in 0 until receiptsPayload.length().coerceAtMost(MaximumExecutionReceipts)) {
                val receipt = receiptsPayload.getJSONObject(index)
                add(AgentOperationReceipt(
                    identity = AgentOperationIdentity(
                        missionId = receipt.getString("mission_id"),
                        taskId = receipt.getString("task_id"),
                        logicalStepId = receipt.getString("logical_step_id"),
                        runId = receipt.getString("run_id"),
                        transactionId = receipt.getString("transaction_id"),
                        operationId = receipt.getString("operation_id"),
                        toolCallId = receipt.getString("tool_call_id"),
                    ),
                    state = AgentOperationState.valueOf(receipt.getString("state")),
                    evidenceSha256 = receipt.optString("evidence_sha256"),
                    detail = receipt.optString("detail").take(2_000),
                    updatedAt = receipt.optString("updated_at"),
                ))
            }
        }
        return AgentExecutionLedger(
            missionId = payload.getString("mission_id"),
            currentTaskId = payload.getString("current_task_id"),
            logicalStepOrdinal = payload.getInt("logical_step_ordinal"),
            runSequence = payload.optInt("run_sequence", 0),
            transactionSequence = payload.optInt("transaction_sequence", 0),
            operationSequence = payload.optInt("operation_sequence", 0),
            currentRunId = payload.optString("current_run_id"),
            currentTransactionId = payload.optString("current_transaction_id"),
            admittedRunIds = payload.stringList("admitted_run_ids", 1_440),
            processedToolCallIds = payload.stringList(
                "processed_tool_call_ids",
                DefaultMissionToolOperationBudget * 2,
            ),
            receipts = receipts,
            staleResultCount = payload.optInt("stale_result_count", 0).coerceAtLeast(0),
            duplicateResultCount = payload.optInt("duplicate_result_count", 0).coerceAtLeast(0),
        )
    }

    private fun JSONObject.stringList(key: String, limit: Int): List<String> {
        val values = optJSONArray(key) ?: JSONArray()
        return buildList {
            for (index in 0 until values.length()) {
                values.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct().takeLast(limit)
    }
}

private fun canonicalExecutionId(prefix: String, ordinal: Int, width: Int): String {
    require(ordinal > 0) { "Execution identity ordinals are one-based." }
    return "$prefix-${ordinal.toString().padStart(width, '0')}"
}

private fun evidenceSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
