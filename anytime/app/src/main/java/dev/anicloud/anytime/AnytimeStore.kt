package dev.anicloud.anytime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.anicloud.anytime.domain.AnytimeCalendar
import dev.anicloud.anytime.domain.Recurrence
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.time.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AnytimeEvent(
    val id: Long, val title: String, val description: String, val instant: Instant,
    val zone: ZoneId, val allDay: Boolean, val recurrence: Recurrence.Rule,
    val reminderMinutes: Int
)
data class AnytimeEntry(val id: Long, val instant: Instant, val text: String, val tags: String)
data class AnytimeCycle(val id: Long, val title: String, val origin: LocalDate, val period: Int)

/** All data is app-private. Journal bodies and profile birth dates use Keystore AES-GCM. */
class AnytimeStore(context: Context) : SQLiteOpenHelper(context, "anytime.db", null, 2) {
    private val prefs = context.getSharedPreferences("anytime-settings", Context.MODE_PRIVATE)
    private val cipher = PrivateCipher()

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, instant INTEGER NOT NULL, zone TEXT NOT NULL, all_day INTEGER NOT NULL, recurrence TEXT NOT NULL, remind_minutes INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE journal(id INTEGER PRIMARY KEY, instant INTEGER NOT NULL, body TEXT NOT NULL, tags TEXT NOT NULL)")
        db.execSQL("CREATE TABLE cycles(id INTEGER PRIMARY KEY, title TEXT NOT NULL, origin TEXT NOT NULL, period INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX journal_by_time ON journal(instant DESC)")
        db.execSQL("CREATE INDEX events_by_time ON events(instant)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE events ADD COLUMN remind_minutes INTEGER NOT NULL DEFAULT 0")
            return
        }
        error("Migration missing: $oldVersion → $newVersion")
    }

    fun string(key: String, fallback: String = ""): String = prefs.getString(key, fallback) ?: fallback
    fun flag(key: String, fallback: Boolean = false): Boolean = prefs.getBoolean(key, fallback)
    fun set(key: String, value: String) { check(prefs.edit().putString(key, value).commit()) }
    fun set(key: String, value: Boolean) { check(prefs.edit().putBoolean(key, value).commit()) }
    fun birthMonthDay(): MonthDay? = string("birthEncrypted").takeIf { it.isNotBlank() }
        ?.let { MonthDay.parse(cipher.decrypt(it)) }
    fun setBirthMonthDay(value: MonthDay?) {
        set("birthEncrypted", value?.let { cipher.encrypt(it.toString()) } ?: "")
    }
    fun calendar(): AnytimeCalendar {
        val zone = ZoneId.of(string("originZone", ZoneId.systemDefault().id))
        val boundary = AnytimeCalendar.Boundary.valueOf(string("boundary", "NORTHERN_SPRING_EQUINOX"))
        val fixed = string("fixedBoundary", "--03-20").let(MonthDay::parse)
        val leap = AnytimeCalendar.LeapBirthday.valueOf(string("leapBirthday", "FEBRUARY_28"))
        return AnytimeCalendar(AnytimeCalendar.Rules(zone, boundary, fixed, birthMonthDay(), leap))
    }
    fun addEvent(title: String, description: String, at: ZonedDateTime, allDay: Boolean,
                 rule: Recurrence.Rule, reminderMinutes: Int = 0): Long {
        require(title.isNotBlank() && title.length <= 140 && description.length <= 16000)
        require(reminderMinutes in listOf(0, 10, 30, 60, 1440))
        val day = calendar().fromInstant(at.toInstant())
        require(rule != Recurrence.Rule.ANYTIME_MONTHLY && rule != Recurrence.Rule.ANYTIME_YEARLY || day.counted()) {
            "Outside-time events need civil recurrence"
        }
        val values = ContentValues().apply {
            put("title", title.trim()); put("body", cipher.encrypt(description))
            put("instant", at.toInstant().toEpochMilli()); put("zone", at.zone.id)
            put("all_day", if (allDay) 1 else 0); put("recurrence", rule.name)
            put("remind_minutes", reminderMinutes)
        }
        return writableDatabase.insertOrThrow("events", null, values)
    }
    fun events(): List<AnytimeEvent> = buildList {
        readableDatabase.rawQuery("SELECT id,title,body,instant,zone,all_day,recurrence,remind_minutes FROM events ORDER BY instant", null).use { c ->
            while (c.moveToNext()) add(AnytimeEvent(c.getLong(0), c.getString(1), cipher.decrypt(c.getString(2)),
                Instant.ofEpochMilli(c.getLong(3)), ZoneId.of(c.getString(4)), c.getInt(5) != 0,
                Recurrence.Rule.valueOf(c.getString(6)), c.getInt(7)))
        }
    }
    fun removeEvent(id: Long) { writableDatabase.delete("events", "id=?", arrayOf(id.toString())) }
    fun addEntry(text: String, tags: String = ""): Long {
        require(text.isNotBlank() && text.length <= 32000 && tags.length <= 280)
        return writableDatabase.insertOrThrow("journal", null, ContentValues().apply {
            put("instant", System.currentTimeMillis()); put("body", cipher.encrypt(text.trim())); put("tags", tags.trim())
        })
    }
    fun entries(): List<AnytimeEntry> = buildList {
        readableDatabase.rawQuery("SELECT id,instant,body,tags FROM journal ORDER BY instant DESC", null).use { c ->
            while (c.moveToNext()) add(AnytimeEntry(c.getLong(0), Instant.ofEpochMilli(c.getLong(1)),
                cipher.decrypt(c.getString(2)), c.getString(3)))
        }
    }
    fun removeEntry(id: Long) { writableDatabase.delete("journal", "id=?", arrayOf(id.toString())) }
    fun addCycle(title: String, origin: LocalDate, period: Int): Long {
        require(title.isNotBlank() && title.length <= 100 && period in 2..36525)
        return writableDatabase.insertOrThrow("cycles", null, ContentValues().apply {
            put("title", title.trim()); put("origin", origin.toString()); put("period", period)
        })
    }
    fun cycles(): List<AnytimeCycle> = buildList {
        readableDatabase.rawQuery("SELECT id,title,origin,period FROM cycles ORDER BY id", null).use { c ->
            while (c.moveToNext()) add(AnytimeCycle(c.getLong(0), c.getString(1), LocalDate.parse(c.getString(2)), c.getInt(3)))
        }
    }
    fun removeCycle(id: Long) { writableDatabase.delete("cycles", "id=?", arrayOf(id.toString())) }

    /** User-initiated document export. The destination is selected by Android's file picker. */
    fun exportJson(): String = JSONObject().apply {
        put("schema", "anytime.export.v1")
        put("createdAt", Instant.now().toString())
        put("settings", JSONObject().apply {
            for (key in listOf("primary", "originZone", "boundary", "fixedBoundary", "leapBirthday",
                "motion", "originLatitude", "presentLatitude")) {
                put(key, string(key))
            }
            for (key in listOf("oled", "moon", "personal", "numerology"))
                put(key, flag(key, key != "numerology"))
            put("birthMonthDay", birthMonthDay()?.toString())
        })
        put("events", JSONArray().apply { events().forEach { e -> put(JSONObject().apply {
            put("title", e.title); put("description", e.description); put("instant", e.instant.toString())
            put("zone", e.zone.id); put("allDay", e.allDay); put("recurrence", e.recurrence.name)
            put("reminderMinutes", e.reminderMinutes)
        }) } })
        put("journal", JSONArray().apply { entries().forEach { e -> put(JSONObject().apply {
            put("instant", e.instant.toString()); put("text", e.text); put("tags", e.tags)
        }) } })
        put("cycles", JSONArray().apply { cycles().forEach { c -> put(JSONObject().apply {
            put("title", c.title); put("origin", c.origin); put("period", c.period)
        }) } })
    }.toString(2)

    data class ImportPreview(val events: Int, val reflections: Int, val cycles: Int)
    private data class ImportData(val events: List<AnytimeEvent>, val entries: List<AnytimeEntry>,
                                  val cycles: List<AnytimeCycle>) {
        fun summary() = ImportPreview(events.size, entries.size, cycles.size)
    }

    fun previewImport(json: String): ImportPreview = parseImport(json).summary()

    /** Merges records atomically, skipping identical records. Profile/settings remain private to this install. */
    fun importJson(json: String): ImportPreview {
        val incoming = parseImport(json)
        val existingEvents = events().map { listOf(it.title, it.description, it.instant, it.zone,
            it.allDay, it.recurrence, it.reminderMinutes) }.toHashSet()
        val existingEntries = entries().map { listOf(it.instant, it.text, it.tags) }.toHashSet()
        val existingCycles = cycles().map { listOf(it.title, it.origin, it.period) }.toHashSet()
        var addedEvents = 0; var addedEntries = 0; var addedCycles = 0
        val database = writableDatabase
        database.beginTransaction()
        try {
            incoming.events.forEach { e ->
                if (existingEvents.add(listOf(e.title, e.description, e.instant, e.zone,
                    e.allDay, e.recurrence, e.reminderMinutes))) {
                    database.insertOrThrow("events", null, ContentValues().apply {
                        put("title", e.title); put("body", cipher.encrypt(e.description))
                        put("instant", e.instant.toEpochMilli()); put("zone", e.zone.id)
                        put("all_day", if (e.allDay) 1 else 0); put("recurrence", e.recurrence.name)
                        put("remind_minutes", e.reminderMinutes)
                    }); addedEvents++
                }
            }
            incoming.entries.forEach { e ->
                if (existingEntries.add(listOf(e.instant, e.text, e.tags))) {
                    database.insertOrThrow("journal", null, ContentValues().apply {
                        put("instant", e.instant.toEpochMilli()); put("body", cipher.encrypt(e.text))
                        put("tags", e.tags)
                    }); addedEntries++
                }
            }
            incoming.cycles.forEach { c ->
                if (existingCycles.add(listOf(c.title, c.origin, c.period))) {
                    database.insertOrThrow("cycles", null, ContentValues().apply {
                        put("title", c.title); put("origin", c.origin.toString()); put("period", c.period)
                    }); addedCycles++
                }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        return ImportPreview(addedEvents, addedEntries, addedCycles)
    }

    private fun parseImport(json: String): ImportData {
        require(json.toByteArray(Charsets.UTF_8).size <= 5_000_000) { "Archive is larger than 5 MB" }
        val root = JSONObject(json)
        require(root.getString("schema") == "anytime.export.v1") { "Unsupported Anytime archive" }
        val eventArray = root.getJSONArray("events")
        val journalArray = root.getJSONArray("journal")
        val cycleArray = root.getJSONArray("cycles")
        require(eventArray.length() <= 5000 && journalArray.length() <= 5000 && cycleArray.length() <= 5000) {
            "Archive contains too many records"
        }
        val targetCalendar = calendar()
        val importedEvents = (0 until eventArray.length()).map { i ->
            val e = eventArray.getJSONObject(i)
            val title = e.getString("title"); val body = e.getString("description")
            val minutes = if (e.has("reminderMinutes")) e.getInt("reminderMinutes") else 0
            require(title.isNotBlank() && title.length <= 140 && body.length <= 16000 &&
                minutes in listOf(0, 10, 30, 60, 1440)) { "Invalid event in archive" }
            val instant = Instant.parse(e.getString("instant"))
            val rule = Recurrence.Rule.valueOf(e.getString("recurrence"))
            val day = targetCalendar.fromInstant(instant)
            if (rule == Recurrence.Rule.ANYTIME_MONTHLY || rule == Recurrence.Rule.ANYTIME_YEARLY) {
                require(day.counted()) {
                    "A recurring Anytime event falls outside this device's calendar year; align your origin settings first"
                }
            }
            AnytimeEvent(0, title, body, instant, ZoneId.of(e.getString("zone")),
                e.getBoolean("allDay"), rule, minutes)
        }
        val importedEntries = (0 until journalArray.length()).map { i ->
            val e = journalArray.getJSONObject(i)
            val body = e.getString("text"); val tags = e.getString("tags")
            require(body.isNotBlank() && body.length <= 32000 && tags.length <= 280) { "Invalid reflection in archive" }
            val instant = Instant.parse(e.getString("instant"))
            targetCalendar.fromInstant(instant)
            AnytimeEntry(0, instant, body, tags)
        }
        val importedCycles = (0 until cycleArray.length()).map { i ->
            val c = cycleArray.getJSONObject(i)
            val title = c.getString("title"); val period = c.getInt("period")
            require(title.isNotBlank() && title.length <= 100 && period in 2..36525) { "Invalid cycle in archive" }
            val origin = LocalDate.parse(c.getString("origin"))
            targetCalendar.fromCivil(origin)
            AnytimeCycle(0, title, origin, period)
        }
        return ImportData(importedEvents, importedEntries, importedCycles)
    }
}

private class PrivateCipher {
    private val alias = "anytime-private-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}
