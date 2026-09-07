package dev.anicloud.sovereign.prototype

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val HistoryFileName = "conversation_history_v1.json"
private const val MaxHistoryBytes = 2L * 1024L * 1024L
private const val MaxHistoryMessages = 200
private const val MaxMessageCharacters = 8 * 1024

/** App-private, atomic persistence for committed conversation turns only. */
class ConversationRepository(context: Context) {
    private val historyFile = File(context.filesDir, HistoryFileName)

    fun load(): List<ChatMessage> {
        if (!historyFile.isFile || historyFile.length() !in 1..MaxHistoryBytes) return emptyList()
        return runCatching {
            val payload = JSONArray(historyFile.readText(Charsets.UTF_8))
            buildList {
                val first = (payload.length() - MaxHistoryMessages).coerceAtLeast(0)
                for (index in first until payload.length()) {
                    val item = payload.getJSONObject(index)
                    val text = item.getString("text").take(MaxMessageCharacters)
                    if (text.isBlank()) continue
                    add(
                        ChatMessage(
                            id = item.getLong("id"),
                            speaker = ChatSpeaker.valueOf(item.getString("speaker")),
                            text = text,
                        ),
                    )
                }
            }
        }.getOrElse {
            quarantineCorruptHistory()
            emptyList()
        }
    }

    fun save(messages: List<ChatMessage>) {
        val retained = messages.takeLast(MaxHistoryMessages)
        val payload = JSONArray()
        retained.forEach { message ->
            payload.put(
                JSONObject()
                    .put("id", message.id)
                    .put("speaker", message.speaker.name)
                    .put("text", message.text.take(MaxMessageCharacters)),
            )
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MaxHistoryBytes) { "Conversation history exceeds its 2 MiB safety cap." }

        val temporary = File(historyFile.parentFile, "$HistoryFileName.pending")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                historyFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                historyFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun quarantineCorruptHistory() {
        if (!historyFile.isFile) return
        val quarantine = File(historyFile.parentFile, "$HistoryFileName.corrupt-${System.currentTimeMillis()}")
        runCatching { Files.move(historyFile.toPath(), quarantine.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}
