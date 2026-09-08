package dev.anicloud.sovereign.prototype

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

private const val MatrixDatabaseName = "sovereign_memory_v1.db"
private const val MatrixSchemaVersion = 1
private const val LegacyHistoryName = "conversation_history_v1.json"
private const val MaxStoredMessageCharacters = 32 * 1024
private const val MaxMemoryValueCharacters = 4 * 1024
private const val MaxActionContentCharacters = 64 * 1024

data class MatrixMemory(
    val id: Long,
    val kind: String,
    val key: String,
    val value: String,
    val confidence: Double,
    val salience: Double,
    val pinned: Boolean,
    val updatedAt: String,
)

data class MemoryMatrixSnapshot(
    val messageCount: Int = 0,
    val memoryCount: Int = 0,
    val pendingActionCount: Int = 0,
    val databaseBytes: Long = 0,
    val ftsAvailable: Boolean = false,
    val recentMemories: List<MatrixMemory> = emptyList(),
)

data class PendingWorkspaceAction(
    val id: Long,
    val kind: WorkspaceActionKind,
    val path: String,
    val content: String,
    val reason: String,
    val createdAt: String,
)

data class MemoryProposalResult(
    val stored: Int = 0,
    val skipped: Int = 0,
)

/**
 * App-private continuity store. Model text can propose memories, but only this
 * deterministic controller validates and commits them.
 */
class MemoryMatrixRepository(private val context: Context) :
    SQLiteOpenHelper(context, MatrixDatabaseName, null, MatrixSchemaVersion) {

    init {
        setWriteAheadLoggingEnabled(true)
        writableDatabase
        migrateLegacyHistory()
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sessions (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                summary TEXT NOT NULL DEFAULT '',
                current_task TEXT NOT NULL DEFAULT '',
                open_loops_json TEXT NOT NULL DEFAULT '[]',
                decisions_json TEXT NOT NULL DEFAULT '[]',
                active_project TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                role TEXT NOT NULL,
                speaker TEXT NOT NULL,
                content TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'chat',
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_session ON messages(session_id, id)")
        db.execSQL(
            """
            CREATE TABLE memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                memory_key TEXT NOT NULL,
                value TEXT NOT NULL,
                normalized_value TEXT NOT NULL,
                source_message_id INTEGER REFERENCES messages(id) ON DELETE SET NULL,
                confidence REAL NOT NULL,
                salience REAL NOT NULL,
                explicitly_stated INTEGER NOT NULL DEFAULT 0,
                sensitive INTEGER NOT NULL DEFAULT 0,
                pinned INTEGER NOT NULL DEFAULT 0,
                active INTEGER NOT NULL DEFAULT 1,
                supersedes_id INTEGER REFERENCES memories(id) ON DELETE SET NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_memories_active_key ON memories(active, kind, memory_key)")
        db.execSQL(
            """
            CREATE TABLE memory_revisions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                memory_id INTEGER NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
                old_value TEXT NOT NULL,
                new_value TEXT NOT NULL,
                reason TEXT NOT NULL,
                source_message_id INTEGER REFERENCES messages(id) ON DELETE SET NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE project_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT REFERENCES sessions(id) ON DELETE SET NULL,
                action TEXT NOT NULL,
                path TEXT NOT NULL,
                result TEXT NOT NULL,
                before_hash TEXT,
                after_hash TEXT,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE agent_actions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT REFERENCES sessions(id) ON DELETE SET NULL,
                kind TEXT NOT NULL,
                path TEXT NOT NULL,
                content TEXT NOT NULL DEFAULT '',
                reason TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'pending',
                result TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_agent_actions_status ON agent_actions(status, id DESC)")
        installFts(db)
        ensureSession(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun loadMessages(limit: Int = 200): List<ChatMessage> {
        val sessionId = activeSessionId(writableDatabase)
        val rows = readableDatabase.rawQuery(
            "SELECT id, speaker, content FROM messages WHERE session_id=? ORDER BY id DESC LIMIT ?",
            arrayOf(sessionId, limit.coerceIn(1, 500).toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val speaker = runCatching { ChatSpeaker.valueOf(cursor.getString(1)) }
                        .getOrDefault(ChatSpeaker.System)
                    add(ChatMessage(cursor.getLong(0), speaker, cursor.getString(2)))
                }
            }
        }
        return rows.asReversed()
    }

    @Synchronized
    fun appendMessage(
        speaker: ChatSpeaker,
        text: String,
        source: String = "chat",
    ): Long {
        val clean = text.replace("\u0000", "").trim().take(MaxStoredMessageCharacters)
        require(clean.isNotBlank()) { "Cannot store an empty message." }
        val db = writableDatabase
        val sessionId = activeSessionId(db)
        val now = now()
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("role", roleFor(speaker))
            put("speaker", speaker.name)
            put("content", clean)
            put("source", source.take(40))
            put("created_at", now)
        }
        val id = db.insertOrThrow("messages", null, values)
        db.execSQL("UPDATE sessions SET updated_at=? WHERE id=?", arrayOf(now, sessionId))
        return id
    }

    @Synchronized
    fun recallContext(query: String, beforeMessageId: Long, characterBudget: Int = 10_000): String {
        val budget = characterBudget.coerceIn(2_000, 16_000)
        val memories = searchMemories(query, 8)
        val recent = recentMessages(beforeMessageId, 10)
        val archived = searchArchivedMessages(query, beforeMessageId, 4)
        val session = activeSession(writableDatabase)

        val blocks = mutableListOf<String>()
        if (session != null) {
            val sessionLines = buildList {
                session.getString("summary").takeIf(String::isNotBlank)?.let { add("Summary: $it") }
                session.getString("current_task").takeIf(String::isNotBlank)?.let { add("Task: $it") }
                session.getString("active_project").takeIf(String::isNotBlank)?.let { add("Project: $it") }
            }
            if (sessionLines.isNotEmpty()) {
                blocks += "[ACTIVE SESSION CHECKPOINT]\n${sessionLines.joinToString("\n")}"
            }
        }
        if (memories.isNotEmpty()) {
            blocks += "[RELEVANT DURABLE MEMORY]\n" + memories.joinToString("\n") {
                "- [${it.kind}] ${it.value} (memory ${it.id})"
            }
        }
        if (archived.isNotEmpty()) {
            blocks += "[RECALLED CONVERSATION]\n" + archived.joinToString("\n") { it }
        }
        if (recent.isNotEmpty()) {
            blocks += "[RECENT COMMITTED CONVERSATION]\n" + recent.joinToString("\n") { it }
        }
        return fitBlocks(blocks, budget)
    }

    @Synchronized
    fun rememberExplicit(value: String, sourceMessageId: Long): Long {
        val clean = value.replace("\u0000", "").trim().take(MaxMemoryValueCharacters)
        require(clean.isNotBlank()) { "Usage: /remember <durable fact or preference>" }
        require(!containsCredentialShape(clean)) { "Credentials and secrets cannot enter Memory Matrix." }
        val key = "explicit_${normalized(clean).take(80).replace(' ', '_')}"
        return upsertMemory(
            kind = "user_fact",
            key = key,
            value = clean,
            sourceMessageId = sourceMessageId,
            confidence = 1.0,
            salience = 0.85,
        )
    }

    @Synchronized
    fun applyMemoryProposal(
        payload: JSONObject?,
        userPrompt: String,
        sourceMessageId: Long,
    ): MemoryProposalResult {
        val proposals = payload?.optJSONArray("memories") ?: return MemoryProposalResult()
        var stored = 0
        var skipped = 0
        val normalizedPrompt = normalized(userPrompt)
        for (index in 0 until minOf(proposals.length(), 8)) {
            val item = proposals.optJSONObject(index)
            if (item == null) {
                skipped++
                continue
            }
            val kind = sanitizeKind(item.optString("kind", "user_fact"))
            val key = sanitizeKey(item.optString("key"))
            val proposedValue = item.optString("value").replace("\u0000", "").trim()
            val quote = item.optString("explicit_quote").replace("\u0000", "").trim()
                .take(MaxMemoryValueCharacters)
            val normalizedQuote = normalized(quote)
            val quoteVerified = normalizedQuote.isNotBlank() && normalizedPrompt.contains(normalizedQuote)
            val allowedKinds = setOf(
                "user_fact", "user_preference", "user_goal", "project_fact", "decision",
            )
            if (key.isBlank() || proposedValue.isBlank() || !quoteVerified || kind !in allowedKinds ||
                containsCredentialShape(quote)
            ) {
                skipped++
                continue
            }
            upsertMemory(
                kind = kind,
                key = key,
                // The quote is the authoritative value. The model may classify
                // it, but cannot paraphrase an unstated fact into durable memory.
                value = quote,
                sourceMessageId = sourceMessageId,
                confidence = item.optDouble("confidence", 0.78).coerceIn(0.0, 1.0),
                salience = item.optDouble("salience", 0.55).coerceIn(0.0, 1.0),
            )
            stored++
        }
        return MemoryProposalResult(stored, skipped)
    }

    @Synchronized
    fun listMemories(limit: Int = 40): List<MatrixMemory> = readableDatabase.rawQuery(
        """
        SELECT id, kind, memory_key, value, confidence, salience, pinned, updated_at
        FROM memories WHERE active=1
        ORDER BY pinned DESC, salience DESC, updated_at DESC LIMIT ?
        """.trimIndent(),
        arrayOf(limit.coerceIn(1, 200).toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toMatrixMemory())
        }
    }

    @Synchronized
    fun forgetMemory(id: Long): Boolean {
        val values = ContentValues().apply {
            put("active", 0)
            put("updated_at", now())
        }
        return writableDatabase.update("memories", values, "id=?", arrayOf(id.toString())) > 0
    }

    @Synchronized
    fun setMemoryPinned(id: Long, pinned: Boolean): Boolean {
        val values = ContentValues().apply {
            put("pinned", if (pinned) 1 else 0)
            put("updated_at", now())
        }
        return writableDatabase.update("memories", values, "id=? AND active=1", arrayOf(id.toString())) > 0
    }

    @Synchronized
    fun snapshot(): MemoryMatrixSnapshot {
        val db = readableDatabase
        return MemoryMatrixSnapshot(
            messageCount = scalarInt(db, "SELECT COUNT(*) FROM messages"),
            memoryCount = scalarInt(db, "SELECT COUNT(*) FROM memories WHERE active=1"),
            pendingActionCount = scalarInt(db, "SELECT COUNT(*) FROM agent_actions WHERE status='pending'"),
            databaseBytes = databaseFootprint(),
            ftsAvailable = ftsAvailable(db),
            recentMemories = listMemories(12),
        )
    }

    @Synchronized
    fun queueWorkspaceAction(proposal: WorkspaceActionProposal): Long {
        require(proposal.kind.requiresApproval) { "Read-only actions do not enter the approval queue." }
        val now = now()
        val values = ContentValues().apply {
            put("session_id", activeSessionId(writableDatabase))
            put("kind", proposal.kind.wireName)
            put("path", proposal.path.take(512))
            put("content", proposal.content.take(MaxActionContentCharacters))
            put("reason", proposal.reason.take(280))
            put("status", "pending")
            put("created_at", now)
            put("updated_at", now)
        }
        return writableDatabase.insertOrThrow("agent_actions", null, values)
    }

    @Synchronized
    fun pendingWorkspaceActions(limit: Int = 20): List<PendingWorkspaceAction> = readableDatabase.rawQuery(
        """
        SELECT id, kind, path, content, reason, created_at
        FROM agent_actions WHERE status='pending' ORDER BY id ASC LIMIT ?
        """.trimIndent(),
        arrayOf(limit.coerceIn(1, 100).toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val kind = WorkspaceActionKind.fromWireName(cursor.getString(1)) ?: continue
                add(
                    PendingWorkspaceAction(
                        id = cursor.getLong(0),
                        kind = kind,
                        path = cursor.getString(2),
                        content = cursor.getString(3),
                        reason = cursor.getString(4),
                        createdAt = cursor.getString(5),
                    ),
                )
            }
        }
    }

    @Synchronized
    fun resolveWorkspaceAction(id: Long, status: String, result: String): Boolean {
        require(status in setOf("approved", "denied", "failed"))
        val values = ContentValues().apply {
            put("status", status)
            put("result", result.take(2_000))
            put("updated_at", now())
        }
        return writableDatabase.update(
            "agent_actions",
            values,
            "id=? AND status='pending'",
            arrayOf(id.toString()),
        ) > 0
    }

    @Synchronized
    fun recordProjectEvent(action: String, path: String, result: WorkspaceActionResult) {
        val values = ContentValues().apply {
            put("session_id", activeSessionId(writableDatabase))
            put("action", action.take(60))
            put("path", path.take(512))
            put("result", result.detail.take(2_000))
            result.beforeSha256?.let { put("before_hash", it) }
            result.afterSha256?.let { put("after_hash", it) }
            put("created_at", now())
        }
        writableDatabase.insertOrThrow("project_events", null, values)
    }

    private fun installFts(db: SQLiteDatabase) {
        runCatching {
            db.execSQL(
                "CREATE VIRTUAL TABLE messages_fts USING fts5(content, speaker, content='messages', content_rowid='id')",
            )
            db.execSQL(
                "CREATE VIRTUAL TABLE memories_fts USING fts5(memory_key, value, kind, content='memories', content_rowid='id')",
            )
            db.execSQL(
                "CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN " +
                    "INSERT INTO messages_fts(rowid,content,speaker) VALUES(new.id,new.content,new.speaker); END",
            )
            db.execSQL(
                "CREATE TRIGGER messages_ad AFTER DELETE ON messages BEGIN " +
                    "INSERT INTO messages_fts(messages_fts,rowid,content,speaker) " +
                    "VALUES('delete',old.id,old.content,old.speaker); END",
            )
            db.execSQL(
                "CREATE TRIGGER memories_ai AFTER INSERT ON memories BEGIN " +
                    "INSERT INTO memories_fts(rowid,memory_key,value,kind) " +
                    "VALUES(new.id,new.memory_key,new.value,new.kind); END",
            )
            db.execSQL(
                "CREATE TRIGGER memories_ad AFTER DELETE ON memories BEGIN " +
                    "INSERT INTO memories_fts(memories_fts,rowid,memory_key,value,kind) " +
                    "VALUES('delete',old.id,old.memory_key,old.value,old.kind); END",
            )
        }
    }

    private fun ensureSession(db: SQLiteDatabase): String {
        val existing = db.rawQuery(
            "SELECT value FROM settings WHERE key='active_session_id' LIMIT 1",
            null,
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        if (existing != null) {
            val valid = db.rawQuery("SELECT 1 FROM sessions WHERE id=?", arrayOf(existing))
                .use { it.moveToFirst() }
            if (valid) return existing
        }
        val sessionId = UUID.randomUUID().toString()
        val now = now()
        db.execSQL(
            "INSERT INTO sessions(id,title,created_at,updated_at) VALUES(?,?,?,?)",
            arrayOf(sessionId, "AniCloudAI Session", now, now),
        )
        db.execSQL(
            "INSERT OR REPLACE INTO settings(key,value,updated_at) VALUES('active_session_id',?,?)",
            arrayOf(sessionId, now),
        )
        return sessionId
    }

    private fun activeSessionId(db: SQLiteDatabase): String = ensureSession(db)

    private fun activeSession(db: SQLiteDatabase): ActiveSession? {
        val id = activeSessionId(db)
        return db.rawQuery(
            "SELECT summary,current_task,active_project FROM sessions WHERE id=?",
            arrayOf(id),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ActiveSession(
                summary = cursor.getString(0),
                currentTask = cursor.getString(1),
                activeProject = cursor.getString(2),
            )
        }
    }

    private fun recentMessages(beforeMessageId: Long, limit: Int): List<String> {
        val sessionId = activeSessionId(writableDatabase)
        return readableDatabase.rawQuery(
            """
            SELECT speaker,content FROM messages
            WHERE session_id=? AND id<? ORDER BY id DESC LIMIT ?
            """.trimIndent(),
            arrayOf(sessionId, beforeMessageId.toString(), limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add("${speakerLabel(cursor.getString(0))}: ${compact(cursor.getString(1), 900)}")
                }
            }.asReversed()
        }
    }

    private fun searchArchivedMessages(query: String, beforeMessageId: Long, limit: Int): List<String> {
        val match = ftsQuery(query)
        if (match.isBlank() || !ftsAvailable(readableDatabase)) return emptyList()
        return runCatching {
            readableDatabase.rawQuery(
                """
                SELECT m.speaker,m.content,m.created_at FROM messages_fts
                JOIN messages m ON m.id=messages_fts.rowid
                WHERE messages_fts MATCH ? AND m.id<?
                ORDER BY bm25(messages_fts) ASC LIMIT ?
                """.trimIndent(),
                arrayOf(match, beforeMessageId.toString(), (limit + 10).toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        val line = "[${cursor.getString(2)}] ${speakerLabel(cursor.getString(0))}: " +
                            compact(cursor.getString(1), 700)
                        if (line !in this) add(line)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun searchMemories(query: String, limit: Int): List<MatrixMemory> {
        val match = ftsQuery(query)
        if (match.isNotBlank() && ftsAvailable(readableDatabase)) {
            val found = runCatching {
                readableDatabase.rawQuery(
                    """
                    SELECT m.id,m.kind,m.memory_key,m.value,m.confidence,m.salience,m.pinned,m.updated_at
                    FROM memories_fts JOIN memories m ON m.id=memories_fts.rowid
                    WHERE memories_fts MATCH ? AND m.active=1 AND m.sensitive=0
                    ORDER BY m.pinned DESC,bm25(memories_fts) ASC,m.salience DESC LIMIT ?
                    """.trimIndent(),
                    arrayOf(match, limit.toString()),
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.toMatrixMemory()) }
                }
            }.getOrDefault(emptyList())
            if (found.isNotEmpty()) return found
        }
        return readableDatabase.rawQuery(
            """
            SELECT id,kind,memory_key,value,confidence,salience,pinned,updated_at
            FROM memories WHERE active=1 AND sensitive=0
            ORDER BY pinned DESC,salience DESC,updated_at DESC LIMIT ?
            """.trimIndent(),
            arrayOf(minOf(limit, 4).toString()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toMatrixMemory()) }
        }
    }

    private fun upsertMemory(
        kind: String,
        key: String,
        value: String,
        sourceMessageId: Long,
        confidence: Double,
        salience: Double,
    ): Long {
        val db = writableDatabase
        val normalizedValue = normalized(value)
        val now = now()
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                """
                SELECT id,value,normalized_value,confidence FROM memories
                WHERE active=1 AND kind=? AND memory_key=? ORDER BY id DESC LIMIT 1
                """.trimIndent(),
                arrayOf(kind, key),
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else ExistingMemory(
                    id = cursor.getLong(0),
                    value = cursor.getString(1),
                    normalizedValue = cursor.getString(2),
                    confidence = cursor.getDouble(3),
                )
            }
            if (existing?.normalizedValue == normalizedValue) {
                db.execSQL(
                    """
                    UPDATE memories SET confidence=?,salience=max(salience,?),
                    explicitly_stated=1,updated_at=?,source_message_id=? WHERE id=?
                    """.trimIndent(),
                    arrayOf<Any?>(
                        maxOf(existing.confidence, confidence),
                        salience,
                        now,
                        sourceMessageId,
                        existing.id,
                    ),
                )
                db.setTransactionSuccessful()
                return existing.id
            }
            existing?.let {
                db.execSQL(
                    "UPDATE memories SET active=0,updated_at=? WHERE id=?",
                    arrayOf<Any?>(now, it.id),
                )
            }
            val values = ContentValues().apply {
                put("kind", kind)
                put("memory_key", key)
                put("value", value)
                put("normalized_value", normalizedValue)
                put("source_message_id", sourceMessageId)
                put("confidence", confidence)
                put("salience", salience)
                put("explicitly_stated", 1)
                put("sensitive", 0)
                put("active", 1)
                existing?.let { put("supersedes_id", it.id) }
                put("created_at", now)
                put("updated_at", now)
            }
            val newId = db.insertOrThrow("memories", null, values)
            existing?.let {
                db.execSQL(
                    """
                    INSERT INTO memory_revisions(memory_id,old_value,new_value,reason,source_message_id,created_at)
                    VALUES(?,?,?,?,?,?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        newId,
                        it.value,
                        value,
                        "validated memory proposal",
                        sourceMessageId,
                        now,
                    ),
                )
            }
            db.setTransactionSuccessful()
            return newId
        } finally {
            db.endTransaction()
        }
    }

    private fun migrateLegacyHistory() {
        val legacy = File(context.filesDir, LegacyHistoryName)
        if (!legacy.isFile || legacy.length() !in 1..(2L * 1024L * 1024L)) return
        if (scalarInt(readableDatabase, "SELECT COUNT(*) FROM messages") > 0) return
        val payload = runCatching { JSONArray(legacy.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val sessionId = activeSessionId(db)
            for (index in 0 until minOf(payload.length(), 500)) {
                val item = payload.optJSONObject(index) ?: continue
                val text = item.optString("text").replace("\u0000", "").trim()
                    .take(MaxStoredMessageCharacters)
                val speaker = runCatching { ChatSpeaker.valueOf(item.optString("speaker")) }.getOrNull()
                    ?: continue
                if (text.isBlank()) continue
                val values = ContentValues().apply {
                    put("session_id", sessionId)
                    put("role", roleFor(speaker))
                    put("speaker", speaker.name)
                    put("content", text)
                    put("source", "legacy-json")
                    put("created_at", now())
                }
                db.insertOrThrow("messages", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val migrated = File(context.filesDir, "$LegacyHistoryName.migrated")
        runCatching { Files.move(legacy.toPath(), migrated.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun databaseFootprint(): Long {
        val database = context.getDatabasePath(MatrixDatabaseName)
        return listOf(database, File("${database.path}-wal"), File("${database.path}-shm"))
            .filter(File::isFile)
            .sumOf(File::length)
    }

    private fun ftsAvailable(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='memories_fts'",
        null,
    ).use { it.moveToFirst() }

    private fun scalarInt(db: SQLiteDatabase, sql: String): Int = db.rawQuery(sql, null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    private fun android.database.Cursor.toMatrixMemory() = MatrixMemory(
        id = getLong(0),
        kind = getString(1),
        key = getString(2),
        value = getString(3),
        confidence = getDouble(4),
        salience = getDouble(5),
        pinned = getInt(6) != 0,
        updatedAt = getString(7),
    )

    private fun roleFor(speaker: ChatSpeaker): String = when (speaker) {
        ChatSpeaker.User -> "user"
        ChatSpeaker.Core -> "assistant"
        ChatSpeaker.System -> "system"
    }

    private fun speakerLabel(raw: String): String = when (raw) {
        ChatSpeaker.User.name -> "User"
        ChatSpeaker.Core.name -> "Sovereign Core"
        else -> "System Lens"
    }

    private fun fitBlocks(blocks: List<String>, budget: Int): String {
        val output = StringBuilder()
        for (block in blocks) {
            val separator = if (output.isEmpty()) "" else "\n\n"
            val remaining = budget - output.length - separator.length
            if (remaining <= 0) break
            output.append(separator)
            output.append(block.take(remaining))
        }
        return output.toString()
    }

    private fun compact(raw: String, limit: Int): String = raw.replace(Regex("\\s+"), " ").trim()
        .let { if (it.length <= limit) it else it.take(limit - 1) + "…" }

    private fun ftsQuery(raw: String): String = Regex("[\\p{L}\\p{N}_-]{2,}")
        .findAll(raw.lowercase())
        .map { it.value.replace("\"", "") }
        .distinct()
        .take(10)
        .joinToString(" OR ") { "\"$it\"*" }

    private fun normalized(raw: String): String = raw.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun sanitizeKind(raw: String): String = raw.lowercase()
        .replace(Regex("[^a-z0-9_-]+"), "_")
        .trim('_')
        .take(50)
        .ifBlank { "user_fact" }

    private fun sanitizeKey(raw: String): String = raw.lowercase()
        .replace(Regex("[^a-z0-9_-]+"), "_")
        .trim('_')
        .take(120)

    private fun containsCredentialShape(raw: String): Boolean = Regex(
        "(?i)(api[_ -]?key|access[_ -]?token|password|private[_ -]?key|secret)" +
            "\\s*(?:[:=]|\\bis\\b)|\\b(?:sk|gh[pousr])_[A-Za-z0-9_-]{8,}|" +
            "\\bAIza[A-Za-z0-9_-]{12,}|-----BEGIN [A-Z ]+PRIVATE KEY-----",
    ).containsMatchIn(raw)

    private fun now(): String = Instant.now().toString()

    private data class ExistingMemory(
        val id: Long,
        val value: String,
        val normalizedValue: String,
        val confidence: Double,
    )

    private data class ActiveSession(
        val summary: String,
        val currentTask: String,
        val activeProject: String,
    ) {
        fun getString(column: String): String = when (column) {
            "summary" -> summary
            "current_task" -> currentTask
            "active_project" -> activeProject
            else -> ""
        }
    }
}
