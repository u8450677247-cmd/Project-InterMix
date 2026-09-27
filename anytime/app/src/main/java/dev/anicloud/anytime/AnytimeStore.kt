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
    val zone: ZoneId, val allDay: Boolean, val recurrence: Recurrence.Rule
)
data class AnytimeEntry(val id: Long, val instant: Instant, val text: String, val tags: String)
data class AnytimeCycle(val id: Long, val title: String, val origin: LocalDate, val period: Int)

/** All data is app-private. Journal bodies and profile birth dates use Keystore AES-GCM. */
class AnytimeStore(context: Context) : SQLiteOpenHelper(context, "anytime.db", null, 1) {
    private val prefs = context.getSharedPreferences("anytime-settings", Context.MODE_PRIVATE)
    private val cipher = PrivateCipher()

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, instant INTEGER NOT NULL, zone TEXT NOT NULL, all_day INTEGER NOT NULL, recurrence TEXT NOT NULL)")
        db.execSQL("CREATE TABLE journal(id INTEGER PRIMARY KEY, instant INTEGER NOT NULL, body TEXT NOT NULL, tags TEXT NOT NULL)")
        db.execSQL("CREATE TABLE cycles(id INTEGER PRIMARY KEY, title TEXT NOT NULL, origin TEXT NOT NULL, period INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX journal_by_time ON journal(instant DESC)")
        db.execSQL("CREATE INDEX events_by_time ON events(instant)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versioned, additive migrations will be introduced with later schemas.
        check(oldVersion == newVersion) { "Migration missing: $oldVersion → $newVersion" }
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
                 rule: Recurrence.Rule): Long {
        require(title.isNotBlank() && title.length <= 140 && description.length <= 16000)
        val day = calendar().fromInstant(at.toInstant())
        require(rule != Recurrence.Rule.ANYTIME_MONTHLY && rule != Recurrence.Rule.ANYTIME_YEARLY || day.counted()) {
            "Outside-time events need civil recurrence"
        }
        val values = ContentValues().apply {
            put("title", title.trim()); put("body", cipher.encrypt(description))
            put("instant", at.toInstant().toEpochMilli()); put("zone", at.zone.id)
            put("all_day", if (allDay) 1 else 0); put("recurrence", rule.name)
        }
        return writableDatabase.insertOrThrow("events", null, values)
    }
    fun events(): List<AnytimeEvent> = buildList {
        readableDatabase.rawQuery("SELECT id,title,body,instant,zone,all_day,recurrence FROM events ORDER BY instant", null).use { c ->
            while (c.moveToNext()) add(AnytimeEvent(c.getLong(0), c.getString(1), cipher.decrypt(c.getString(2)),
                Instant.ofEpochMilli(c.getLong(3)), ZoneId.of(c.getString(4)), c.getInt(5) != 0,
                Recurrence.Rule.valueOf(c.getString(6))))
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
                "motion", "oled", "moon", "personal", "numerology", "originLatitude", "presentLatitude")) {
                put(key, string(key))
            }
            put("birthMonthDay", birthMonthDay()?.toString())
        })
        put("events", JSONArray().apply { events().forEach { e -> put(JSONObject().apply {
            put("title", e.title); put("description", e.description); put("instant", e.instant)
            put("zone", e.zone.id); put("allDay", e.allDay); put("recurrence", e.recurrence.name)
        }) } })
        put("journal", JSONArray().apply { entries().forEach { e -> put(JSONObject().apply {
            put("instant", e.instant); put("text", e.text); put("tags", e.tags)
        }) } })
        put("cycles", JSONArray().apply { cycles().forEach { c -> put(JSONObject().apply {
            put("title", c.title); put("origin", c.origin); put("period", c.period)
        }) } })
    }.toString(2)
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
