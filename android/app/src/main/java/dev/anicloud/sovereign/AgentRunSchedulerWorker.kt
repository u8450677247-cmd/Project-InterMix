package dev.anicloud.sovereign.prototype

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class AgentRunWake(val missionId: String, val state: AgentRunState)

object AgentRunSchedulerEvents {
    val wake = MutableSharedFlow<AgentRunWake>(replay = 1, extraBufferCapacity = 8)
}

class AgentRunSchedulerWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            MemoryMatrixRepository(applicationContext).use { repository ->
                val mission = repository.activeAgentMission() ?: return@use
                recoverGrantedExecution(repository, mission)
                val modelRepository = ModelRepository(applicationContext)
                val runtimeAvailable = AdaptiveRuntimePolicy.select(
                    mode = mission.mode,
                    prompt = mission.objective,
                    conversationModel = modelRepository.installedModel(ModelRole.Conversation),
                    reasoningModel = modelRepository.installedModel(ModelRole.Reasoning),
                    facts = deviceRuntimeFacts(),
                ) != null
                val powerManager = applicationContext.getSystemService(PowerManager::class.java)
                val schedule = repository.reconcileAgentRunSchedule(
                    runtimeAvailable = runtimeAvailable,
                    powerAvailable = !powerManager.isPowerSaveMode && !powerManager.isDeviceIdleMode,
                    thermalAvailable = powerManager.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE,
                ) ?: return@use
                AgentRunSchedulerEvents.wake.tryEmit(AgentRunWake(schedule.missionId, schedule.state))
                AgentRunScheduler.scheduleNext(applicationContext, schedule)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private fun deviceRuntimeFacts(): DeviceRuntimeFacts {
        val activityManager = applicationContext.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val dispatcher = File(
            applicationContext.applicationInfo.nativeLibraryDir,
            AdaptiveRuntimePolicy.GoogleTensorDispatcher,
        )
        return DeviceRuntimeFacts(
            socModel = Build.SOC_MODEL.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            dispatcherAvailable = dispatcher.isFile && dispatcher.length() > 0L,
            availableMemoryBytes = memory.availMem,
        )
    }

    private fun recoverGrantedExecution(
        repository: MemoryMatrixRepository,
        mission: AgentMissionCheckpoint,
    ) {
        val actions = repository.pendingExecutionActions(limit = 100).filter { it.missionId == mission.id }
        if (actions.any { it.status in setOf("running", "cancel_requested") }) return
        val pending = actions.firstOrNull { it.status == "pending" && it.grantId.isNotBlank() } ?: return
        val grant = repository.activeExecutionGrant(mission.id)
        if (grant?.id != pending.grantId) {
            repository.setExecutionActionStatus(
                pending.id,
                "pending",
                "denied",
                "The linked execution grant is no longer active.",
            )
            return
        }
        val bridge = TermuxExecutionBridge(applicationContext)
        if (!bridge.status().ready) return
        if (!repository.setExecutionActionStatus(pending.id, "pending", "running")) return
        runCatching { bridge.execute(pending) }.onFailure { failure ->
            repository.completeExecutionAction(
                id = pending.id,
                stdout = "",
                stderr = "",
                stdoutOriginalLength = 0L,
                stderrOriginalLength = 0L,
                exitCode = -1,
                errorCode = 1,
                errorMessage = "Scheduler dispatch failed: " +
                    (failure.message ?: failure::class.java.simpleName),
            )
            TermuxExecutionEvents.completed.tryEmit(pending.id)
        }
    }
}

object AgentRunScheduler {
    private const val PeriodicWork = "anicloud-agent-reconcile-v1"
    private const val ImmediateWork = "anicloud-agent-kick-v1"
    private const val DelayedWork = "anicloud-agent-delayed-v1"

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AgentRunSchedulerWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PeriodicWork,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        kick(context)
    }

    fun kick(context: Context) {
        val request = OneTimeWorkRequestBuilder<AgentRunSchedulerWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ImmediateWork,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun scheduleNext(context: Context, schedule: AgentRunSchedule) {
        val delayMillis = schedule.nextRunAtEpochMillis - System.currentTimeMillis()
        if (delayMillis <= 0L || schedule.state in setOf(
                AgentRunState.Runnable,
                AgentRunState.Reconciling,
                AgentRunState.WaitingAuthority,
                AgentRunState.Paused,
                AgentRunState.Completed,
                AgentRunState.Failed,
            )
        ) {
            WorkManager.getInstance(context).cancelUniqueWork(DelayedWork)
            return
        }
        val request = OneTimeWorkRequestBuilder<AgentRunSchedulerWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DelayedWork,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
