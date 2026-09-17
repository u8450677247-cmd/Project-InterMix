package dev.anicloud.sovereign.prototype

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.SecureRandom
import java.time.Instant

enum class UpdatePhase {
    Idle,
    Discovered,
    Eligible,
    Downloading,
    Downloaded,
    Verified,
    Checkpointed,
    AwaitingConfirmation,
    Installing,
    Migrating,
    Verifying,
    Complete,
    FailedRetryable,
    FailedBlocked,
    Quarantined,
}

data class UpdateSnapshot(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val releaseId: String = "",
    val versionCode: Long = 0,
    val versionName: String = "",
    val manifestSha256: String = "",
    val apkPath: String = "",
    val apkSha256: String = "",
    val signerSha256: String = "",
    val expectedBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val checkpointPath: String = "",
    val failure: String = "",
    val retryCount: Int = 0,
    val updatedAt: String = "",
)

/** Independent durable ledger so updater recovery never depends on the Matrix schema being healthy. */
class UpdateStateStore(context: Context) :
    SQLiteOpenHelper(context, "intermix_update_v1.db", null, 1) {

    init {
        writableDatabase
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE update_state (
                singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1),
                phase TEXT NOT NULL,
                release_id TEXT NOT NULL,
                version_code INTEGER NOT NULL,
                version_name TEXT NOT NULL,
                manifest_sha256 TEXT NOT NULL,
                apk_path TEXT NOT NULL,
                apk_sha256 TEXT NOT NULL,
                signer_sha256 TEXT NOT NULL,
                expected_bytes INTEGER NOT NULL,
                downloaded_bytes INTEGER NOT NULL,
                checkpoint_path TEXT NOT NULL,
                failure TEXT NOT NULL,
                retry_count INTEGER NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE update_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phase TEXT NOT NULL,
                release_id TEXT NOT NULL,
                detail TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        writeSnapshot(db, UpdateSnapshot(updatedAt = Instant.now().toString()))
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun read(): UpdateSnapshot = readableDatabase.rawQuery(
        """
        SELECT phase,release_id,version_code,version_name,manifest_sha256,apk_path,
               apk_sha256,signer_sha256,expected_bytes,downloaded_bytes,checkpoint_path,
               failure,retry_count,updated_at
          FROM update_state WHERE singleton_id=1
        """.trimIndent(),
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use UpdateSnapshot()
        UpdateSnapshot(
            phase = runCatching { UpdatePhase.valueOf(cursor.getString(0)) }
                .getOrDefault(UpdatePhase.Quarantined),
            releaseId = cursor.getString(1),
            versionCode = cursor.getLong(2),
            versionName = cursor.getString(3),
            manifestSha256 = cursor.getString(4),
            apkPath = cursor.getString(5),
            apkSha256 = cursor.getString(6),
            signerSha256 = cursor.getString(7),
            expectedBytes = cursor.getLong(8),
            downloadedBytes = cursor.getLong(9),
            checkpointPath = cursor.getString(10),
            failure = cursor.getString(11),
            retryCount = cursor.getInt(12),
            updatedAt = cursor.getString(13),
        )
    }

    fun record(next: UpdateSnapshot, detail: String = "") {
        val now = Instant.now().toString()
        val normalized = next.copy(
            failure = next.failure.replace(Regex("\\s+"), " ").take(320),
            updatedAt = now,
        )
        val db = writableDatabase
        db.beginTransaction()
        try {
            writeSnapshot(db, normalized)
            db.insertOrThrow(
                "update_events",
                null,
                ContentValues().apply {
                    put("phase", normalized.phase.name)
                    put("release_id", normalized.releaseId)
                    put("detail", detail.replace(Regex("\\s+"), " ").take(320))
                    put("created_at", now)
                },
            )
            db.execSQL(
                "DELETE FROM update_events WHERE id NOT IN " +
                    "(SELECT id FROM update_events ORDER BY id DESC LIMIT 200)",
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun failure(retryable: Boolean, detail: String) {
        val current = read()
        record(
            current.copy(
                phase = if (retryable) UpdatePhase.FailedRetryable else UpdatePhase.FailedBlocked,
                failure = detail,
                retryCount = current.retryCount + 1,
            ),
            detail,
        )
    }

    private fun writeSnapshot(db: SQLiteDatabase, state: UpdateSnapshot) {
        db.insertWithOnConflict(
            "update_state",
            null,
            ContentValues().apply {
                put("singleton_id", 1)
                put("phase", state.phase.name)
                put("release_id", state.releaseId)
                put("version_code", state.versionCode)
                put("version_name", state.versionName)
                put("manifest_sha256", state.manifestSha256)
                put("apk_path", state.apkPath)
                put("apk_sha256", state.apkSha256)
                put("signer_sha256", state.signerSha256)
                put("expected_bytes", state.expectedBytes)
                put("downloaded_bytes", state.downloadedBytes)
                put("checkpoint_path", state.checkpointPath)
                put("failure", state.failure)
                put("retry_count", state.retryCount)
                put("updated_at", state.updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}

object UpdateCohort {
    fun basisPoints(context: Context): Int {
        val preferences = context.getSharedPreferences("intermix_update_cohort", Context.MODE_PRIVATE)
        val existing = preferences.getInt("basis_points", -1)
        if (existing in 0..9_999) return existing
        val generated = SecureRandom().nextInt(10_000)
        preferences.edit().putInt("basis_points", generated).commit()
        return preferences.getInt("basis_points", generated)
    }
}
