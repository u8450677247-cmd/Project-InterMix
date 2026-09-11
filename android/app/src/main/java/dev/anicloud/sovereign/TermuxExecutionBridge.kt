package dev.anicloud.sovereign.prototype

import android.app.IntentService
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow

const val TermuxRunCommandPermission = "com.termux.permission.RUN_COMMAND"
private const val TermuxPackage = "com.termux"
private const val TermuxRunCommandService = "com.termux.app.RunCommandService"
private const val TermuxRunCommandAction = "com.termux.RUN_COMMAND"
private const val TermuxCommandPath = "com.termux.RUN_COMMAND_PATH"
private const val TermuxCommandArguments = "com.termux.RUN_COMMAND_ARGUMENTS"
private const val TermuxCommandWorkdir = "com.termux.RUN_COMMAND_WORKDIR"
private const val TermuxCommandBackground = "com.termux.RUN_COMMAND_BACKGROUND"
private const val TermuxCommandLabel = "com.termux.RUN_COMMAND_COMMAND_LABEL"
private const val TermuxCommandDescription = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
private const val TermuxPendingIntent = "com.termux.RUN_COMMAND_PENDING_INTENT"
private const val TermuxResultBundle = "result"
private const val TermuxStdout = "stdout"
private const val TermuxStdoutOriginalLength = "stdout_original_length"
private const val TermuxStderr = "stderr"
private const val TermuxStderrOriginalLength = "stderr_original_length"
private const val TermuxExitCode = "exitCode"
private const val TermuxErrorCode = "err"
private const val TermuxErrorMessage = "errmsg"
private const val TermuxBash = "/data/data/com.termux/files/usr/bin/bash"
private const val TermuxHome = "/data/data/com.termux/files/home"
private const val BridgePreferences = "anicloud_termux_bridge"
private const val ResultJobId = "dev.anicloud.sovereign.EXECUTION_JOB_ID"

enum class ExecutionKind(val wireName: String, val label: String) {
    InspectEnvironment("inspect_environment", "INSPECT ENVIRONMENT"),
    Run("run", "RUN SCRIPT"),
    Test("test", "RUN TESTS"),
    Build("build", "BUILD PROJECT"),
    InstallDependencies("install_dependencies", "INSTALL DEPENDENCIES"),
    ;

    companion object {
        fun fromWireName(raw: String): ExecutionKind? = entries.firstOrNull {
            it.wireName == raw.trim().lowercase()
        }
    }
}

data class ExecutionProposal(
    val kind: ExecutionKind,
    val command: String,
    val workdir: String = ".",
    val networkRequired: Boolean = false,
    val dependencies: List<String> = emptyList(),
    val reason: String = "",
    val timeoutSeconds: Int = 600,
)

data class PendingExecutionAction(
    val id: Long,
    val sessionId: String,
    val missionId: String,
    val kind: ExecutionKind,
    val command: String,
    val workdir: String,
    val networkRequired: Boolean,
    val dependencies: List<String>,
    val reason: String,
    val timeoutSeconds: Int,
    val status: String,
    val createdAt: String,
)

data class TermuxBridgeStatus(
    val enabled: Boolean = false,
    val installed: Boolean = false,
    val serviceAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val workdir: String = "",
) {
    val ready: Boolean
        get() = enabled && installed && serviceAvailable && permissionGranted && workdir.isNotBlank()

    val detail: String
        get() = when {
            !installed -> "The official com.termux app is not visible to AniCloudAI"
            !serviceAvailable -> "Termux is installed, but RunCommandService is unavailable"
            !permissionGranted -> "Android permission to run commands in Termux is not granted"
            !enabled -> "disabled; use /exec on after completing bridge setup"
            workdir.isBlank() -> "project path missing; use /exec workdir <Termux path>"
            else -> "ready at $workdir"
        }
}

class TermuxBridgeConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(BridgePreferences, Context.MODE_PRIVATE)

    fun enabled(): Boolean = preferences.getBoolean("enabled", false)

    fun workdir(): String = preferences.getString("workdir", "").orEmpty()

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("enabled", enabled).apply()
    }

    fun setWorkdir(raw: String): String {
        val normalized = normalizeTermuxRoot(raw)
        preferences.edit().putString("workdir", normalized).apply()
        return normalized
    }
}

object TermuxExecutionEvents {
    val completed = MutableSharedFlow<Long>(extraBufferCapacity = 8)
}

class TermuxExecutionBridge(private val context: Context) {
    private val config = TermuxBridgeConfigStore(context)

    @Suppress("DEPRECATION")
    fun status(): TermuxBridgeStatus {
        val packageManager = context.packageManager
        val installed = runCatching { packageManager.getApplicationInfo(TermuxPackage, 0) }.isSuccess
        // Query the explicit component rather than resolve an implicit action.
        // This remains reliable under Android package-visibility filtering.
        val serviceAvailable = installed && runCatching {
            packageManager.getServiceInfo(
                ComponentName(TermuxPackage, TermuxRunCommandService),
                0,
            )
        }.isSuccess
        val permissionGranted =
            context.checkSelfPermission(TermuxRunCommandPermission) == PackageManager.PERMISSION_GRANTED
        return TermuxBridgeStatus(
            enabled = config.enabled(),
            installed = installed,
            serviceAvailable = serviceAvailable,
            permissionGranted = permissionGranted,
            workdir = config.workdir(),
        )
    }

    fun execute(action: PendingExecutionAction) {
        val bridge = status()
        require(bridge.ready) { bridge.detail }
        val proposal = validateExecutionProposal(
            ExecutionProposal(
                kind = action.kind,
                command = action.command,
                workdir = action.workdir,
                networkRequired = action.networkRequired,
                dependencies = action.dependencies,
                reason = action.reason,
                timeoutSeconds = action.timeoutSeconds,
            ),
        )
        val workdir = resolveExecutionWorkdir(bridge.workdir, proposal.workdir)
        val resultIntent = Intent(context, TermuxExecutionResultService::class.java)
            .putExtra(ResultJobId, action.id)
        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingResult = PendingIntent.getService(
            context,
            action.id.hashCode(),
            resultIntent,
            flags,
        )
        context.startService(
            baseIntent(
                label = "AniCloudAI #${action.id} · ${action.kind.label}",
                description = proposal.reason.ifBlank { "User-approved AniCloudAI project command." },
            ).apply {
                putExtra(TermuxCommandPath, TermuxBash)
                putExtra(
                    TermuxCommandArguments,
                    arrayOf(
                        "-lc",
                        executionWrapper(action.id, proposal.timeoutSeconds),
                        "anicloud-exec",
                        proposal.command,
                    ),
                )
                putExtra(TermuxCommandWorkdir, workdir)
                putExtra(TermuxPendingIntent, pendingResult)
            },
        ) ?: error("Termux refused the command-service request.")
    }

    fun stop(action: PendingExecutionAction) {
        val bridge = status()
        require(bridge.ready) { bridge.detail }
        val pidFile = "\$HOME/.anicloud/run/${action.id}.pid"
        val stopCommand = "if test -s \"$pidFile\"; then pid=\$(cat \"$pidFile\"); " +
            "pkill -TERM -P \"\$pid\" 2>/dev/null || true; " +
            "kill -TERM \"\$pid\" 2>/dev/null || true; fi"
        context.startService(
            baseIntent("AniCloudAI STOP #${action.id}", "Stop the approved AniCloudAI command.").apply {
                putExtra(TermuxCommandPath, TermuxBash)
                putExtra(TermuxCommandArguments, arrayOf("-lc", stopCommand))
                putExtra(TermuxCommandWorkdir, TermuxHome)
            },
        ) ?: error("Termux refused the STOP request.")
    }

    private fun baseIntent(label: String, description: String): Intent =
        Intent(TermuxRunCommandAction).setClassName(TermuxPackage, TermuxRunCommandService).apply {
            putExtra(TermuxCommandBackground, true)
            putExtra(TermuxCommandLabel, label.take(80))
            putExtra(TermuxCommandDescription, description.take(280))
        }

    private fun executionWrapper(jobId: Long, timeoutSeconds: Int): String = """
        set -u
        run_dir="${'$'}HOME/.anicloud/run"
        pid_file="${'$'}run_dir/$jobId.pid"
        mkdir -p "${'$'}run_dir"
        cleanup() { rm -f -- "${'$'}pid_file"; }
        trap 'if test -s "${'$'}pid_file"; then kill -TERM "${'$'}(cat "${'$'}pid_file")" 2>/dev/null || true; fi; cleanup' TERM INT HUP
        "${'$'}PREFIX/bin/timeout" -k 5s ${timeoutSeconds}s "${'$'}PREFIX/bin/bash" -lc "${'$'}1" &
        child="${'$'}!"
        printf '%s\n' "${'$'}child" > "${'$'}pid_file"
        wait "${'$'}child"
        status="${'$'}?"
        cleanup
        exit "${'$'}status"
    """.trimIndent()
}

@Suppress("DEPRECATION")
class TermuxExecutionResultService : IntentService("AniCloudAI-Termux-Result") {
    override fun onHandleIntent(intent: Intent?) {
        val jobId = intent?.getLongExtra(ResultJobId, -1L) ?: -1L
        if (jobId <= 0L) return
        val result = intent?.getBundleExtra(TermuxResultBundle)
        val stdout = result?.getString(TermuxStdout).orEmpty()
        val stderr = result?.getString(TermuxStderr).orEmpty()
        val stdoutLength = result?.get(TermuxStdoutOriginalLength)?.toString()?.toLongOrNull()
            ?: stdout.length.toLong()
        val stderrLength = result?.get(TermuxStderrOriginalLength)?.toString()?.toLongOrNull()
            ?: stderr.length.toLong()
        val exitCode = result?.getInt(TermuxExitCode, -1) ?: -1
        val errorCode = result?.getInt(TermuxErrorCode, -1) ?: -1
        val errorMessage = result?.getString(TermuxErrorMessage).orEmpty()
        runCatching {
            MemoryMatrixRepository(applicationContext).use { repository ->
                repository.completeExecutionAction(
                    id = jobId,
                    stdout = stdout,
                    stderr = stderr,
                    stdoutOriginalLength = stdoutLength,
                    stderrOriginalLength = stderrLength,
                    exitCode = exitCode,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                )
            }
        }
        TermuxExecutionEvents.completed.tryEmit(jobId)
    }
}

fun validateExecutionProposal(proposal: ExecutionProposal): ExecutionProposal {
    val command = proposal.command.replace("\u0000", "").trim()
    require(command.isNotBlank() && command.length <= 4_096) {
        "Execution commands must contain 1 to 4096 characters."
    }
    require(command.none { it == '\n' || it == '\r' || (it.code < 0x20 && it != '\t') }) {
        "Execution commands must be one inspectable line."
    }
    val blocked = Regex(
        "(?:^|[^A-Za-z0-9_-])(?:su|sudo|rm|rmdir|shred|dd|truncate|kill|pkill|" +
            "chmod|chown|mkfs(?:\\.[a-z0-9]+)?|mount|umount|reboot|shutdown|halt|" +
            "poweroff|am|pm|settings)\\b",
        RegexOption.IGNORE_CASE,
    )
    require(!blocked.containsMatchIn(command)) {
        "The command contains a destructive, privileged, or Android-control operation."
    }
    val absolutePath = Regex("""(?:^|[\s'"=])/(?!/)""")
    require(
        "../" !in command &&
            !command.contains("/data/") &&
            !command.contains("/storage/") &&
            !absolutePath.containsMatchIn(command)
    ) {
        "Commands may not name parent traversal or absolute filesystem paths."
    }
    val dependencyMutation = Regex(
        "\\b(?:pip3?|npm|pnpm|yarn|pkg|apt|apt-get|gem)\\s+(?:install|add)|" +
            "\\bpython3?\\s+-m\\s+pip\\s+install|\\bcargo\\s+add|\\bgo\\s+get",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(command)
    require(!dependencyMutation || proposal.kind == ExecutionKind.InstallDependencies) {
        "Package changes require the install_dependencies action kind."
    }
    val networkUse = dependencyMutation || Regex(
        "\\b(?:curl|wget)\\b|\\bgit\\s+(?:clone|fetch|pull)\\b",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(command)
    require(!networkUse || proposal.networkRequired) {
        "Network-capable commands must declare network use in their approval preview."
    }
    val workdir = proposal.workdir.trim().ifBlank { "." }
    require(
        workdir == "." ||
            (Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}").matches(workdir) &&
                workdir.split('/').none { it == ".." }),
    ) { "Execution workdirs must stay relative to the configured Termux project root." }
    val dependencies = proposal.dependencies.map { it.trim() }
        .filter(String::isNotBlank)
        .distinct()
        .take(40)
    if (proposal.kind == ExecutionKind.InstallDependencies) {
        require(proposal.networkRequired) { "Dependency installation must declare network use." }
        require(dependencies.isNotEmpty()) { "Dependency installation must name the proposed packages." }
    }
    return proposal.copy(
        command = command,
        workdir = workdir,
        dependencies = dependencies,
        reason = proposal.reason.replace("\u0000", "").trim().take(280),
        timeoutSeconds = proposal.timeoutSeconds.coerceIn(5, 1_800),
    )
}

fun normalizeTermuxRoot(raw: String): String {
    val normalized = raw.replace("\u0000", "").trim().removeSuffix("/")
    require(normalized == TermuxHome || normalized.startsWith("$TermuxHome/")) {
        "The bridge root must be an absolute project path inside the Termux home directory."
    }
    require(normalized.split('/').none { it == ".." }) { "The bridge root cannot traverse parents." }
    return normalized
}

fun resolveExecutionWorkdir(root: String, relative: String): String =
    if (relative == ".") normalizeTermuxRoot(root) else normalizeTermuxRoot("$root/$relative")
