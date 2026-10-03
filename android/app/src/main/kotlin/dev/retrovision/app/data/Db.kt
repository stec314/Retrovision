// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "sightings", indices = [Index("timeMs"), Index("entityId")])
class SightingRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    /** 0 = Wi-Fi, 1 = BLE */
    val radio: Int,
    val address: Long,
    val entityId: String,
    val rssi: Int,
    val merged: Int,
    val wifiKind: Int,
    val channel: Int,
    val ssid: ByteArray,
    /** -1 = none */
    val bssid: Long,
    val seq: Int,
    val ies: ByteArray,
    val bleAddrKind: Int,
    val advType: Int,
    val advData: ByteArray,
    val txPower: Int,
    /** Which receiver heard it: probe hardware id, or "phone". */
    @androidx.room.ColumnInfo(defaultValue = "''") val source: String = "",
    /** Beacon timestamp (AP uptime, µs), -1 when not a beacon or not forwarded. */
    @androidx.room.ColumnInfo(defaultValue = "-1") val tsf: Long = -1,
)

@Entity(tableName = "fixes")
class FixRow(
    @PrimaryKey val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    /** negative = unknown */
    val speedMps: Float,
)

@Entity(tableName = "ignores")
class IgnoreRow(
    @PrimaryKey val entityId: String,
    val label: String,
    val createdMs: Long,
)

@Entity(tableName = "enrichments")
class EnrichRow(
    @PrimaryKey val key: String,
    val source: String,
    val json: String,
    val fetchedMs: Long,
)

@Entity(tableName = "baseline")
class BaselineRow(
    @PrimaryKey val entityId: String,
    /** Distinct local days this device was seen only at your routine places. */
    val days: Int,
    val lastDay: Long,
    val lastMs: Long,
)

/** Devices that seem to be with you everywhere: candidates for "is this yours?". */
@Entity(tableName = "companions")
class CompanionRow(
    @PrimaryKey val entityId: String,
    /** Distinct local days it travelled with you. */
    val days: Int,
    val lastDay: Long,
    /** 0 counting, 1 suggested, 2 confirmed yours, 3 confirmed NOT yours. */
    val state: Int,
    val label: String,
    val updatedMs: Long,
)

/** What you said about an alert or device: ground truth for tuning thresholds. */
@Entity(tableName = "feedback")
class FeedbackRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityId: String,
    /** 0 false alarm, 1 mine, 2 really suspicious. */
    val label: Int,
    val score: Double,
    /** Reason class names, comma separated (no device data beyond the entity id). */
    val reasons: String,
    val timeMs: Long,
)

@Entity(tableName = "familiar_places")
class FamiliarRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
    val label: String,
    /** FamiliarPlace.State ordinal */
    val state: Int,
    /** FamiliarPlace.Kind ordinal */
    val kind: Int,
    val createdMs: Long,
)

@Dao
/** One device found by a search over everything stored (not just the analysis window). */
class DbHit(
    val entityId: String,
    val radio: Int,
    val firstMs: Long,
    val lastMs: Long,
    val n: Int,
    val maxRssi: Int,
    val days: Int,
    /** A network name that matched, and an advertisement that matched (for the BLE name), when any. */
    val hitSsid: ByteArray?,
    val hitAdv: ByteArray?,
)

class TimeRssi(val timeMs: Long, val rssi: Int)

interface AppDao {
    @Insert
    suspend fun insertSightings(rows: List<SightingRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFix(f: FixRow)

    @Query("SELECT * FROM sightings WHERE timeMs >= :from ORDER BY timeMs")
    suspend fun sightingsSince(from: Long): List<SightingRow>

    @Query("SELECT COUNT(*) FROM sightings WHERE timeMs >= :from")
    suspend fun sightingCountSince(from: Long): Long

    /**
     * The analysis window, thinned: one row per (device, frame kind, advert type, SSID) per
     * [bucketMs]. A device that advertises every second says the same thing 60 times a minute;
     * loading every copy of a 2-12 h window is what ran the app out of memory. The newest row of
     * each bucket is kept (SQLite takes bare columns from the MAX() row), and `merged` is summed so
     * frame counts stay right. Probe requests for different SSIDs stay separate rows.
     * Newest first, so a [limit] cut drops the oldest part of the window, never the present.
     */
    @Query(
        "SELECT MAX(id) AS id, timeMs, radio, address, entityId, rssi, SUM(merged) AS merged, wifiKind, channel, " +
            "ssid, bssid, seq, ies, bleAddrKind, advType, advData, txPower, source, tsf FROM sightings " +
            "WHERE timeMs >= :from AND timeMs < :to GROUP BY entityId, radio, wifiKind, advType, ssid, timeMs / :bucketMs ORDER BY timeMs DESC LIMIT :limit",
    )
    suspend fun sightingsThinned(from: Long, to: Long, bucketMs: Long, limit: Int): List<SightingRow>

    /** Cheap row estimate (two index lookups instead of counting millions of encrypted rows). */
    @Query("SELECT IFNULL(MAX(id) - MIN(id) + 1, 0) FROM sightings")
    suspend fun sightingEstimate(): Long

    /** Memory-safe sampling for retrospective review: every :stride-th row by id. */
    @Query("SELECT * FROM sightings WHERE timeMs >= :from AND (id % :stride) = 0 ORDER BY timeMs")
    suspend fun sightingsSinceSampled(from: Long, stride: Int): List<SightingRow>

    @Query("SELECT * FROM fixes WHERE timeMs >= :from ORDER BY timeMs")
    suspend fun fixesSince(from: Long): List<FixRow>

    @Query("DELETE FROM sightings WHERE timeMs < :before")
    suspend fun pruneSightings(before: Long): Int

    @Query("DELETE FROM fixes WHERE timeMs < :before")
    suspend fun pruneFixes(before: Long): Int

    @Query("SELECT * FROM ignores ORDER BY createdMs DESC")
    fun ignores(): Flow<List<IgnoreRow>>

    @Query("SELECT * FROM ignores")
    suspend fun ignoresNow(): List<IgnoreRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addIgnore(row: IgnoreRow)

    @Query("DELETE FROM ignores WHERE entityId = :id")
    suspend fun removeIgnore(id: String)

    @Query("DELETE FROM ignores")
    suspend fun wipeIgnores()

    /**
     * Full scan of every stored sighting: address (entity id), network names (beacons and probe
     * requests) and Bluetooth advertisements (names). Byte search because names are stored raw;
     * [a], [b], [c] are case variants of the query. Slow on large databases: run on demand only.
     */
    @Query(
        "SELECT entityId, MIN(radio) AS radio, MIN(timeMs) AS firstMs, MAX(timeMs) AS lastMs, COUNT(*) AS n, MAX(rssi) AS maxRssi, " +
            "COUNT(DISTINCT timeMs / 86400000) AS days, " +
            "MAX(CASE WHEN instr(ssid, :a) > 0 OR instr(ssid, :b) > 0 OR instr(ssid, :c) > 0 THEN ssid END) AS hitSsid, " +
            "MAX(CASE WHEN instr(advData, :a) > 0 OR instr(advData, :b) > 0 OR instr(advData, :c) > 0 THEN advData END) AS hitAdv " +
            "FROM sightings WHERE entityId LIKE :like " +
            "OR instr(ssid, :a) > 0 OR instr(ssid, :b) > 0 OR instr(ssid, :c) > 0 " +
            "OR instr(advData, :a) > 0 OR instr(advData, :b) > 0 OR instr(advData, :c) > 0 " +
            "GROUP BY entityId ORDER BY lastMs DESC LIMIT :limit",
    )
    suspend fun searchAll(like: String, a: ByteArray, b: ByteArray, c: ByteArray, limit: Int): List<DbHit>

    @Query("SELECT timeMs, rssi FROM sightings WHERE entityId = :id ORDER BY timeMs LIMIT :limit")
    suspend fun timesFor(id: String, limit: Int): List<TimeRssi>

    @Query("SELECT * FROM fixes WHERE timeMs BETWEEN :from AND :to ORDER BY ABS(timeMs - :at) LIMIT 1")
    suspend fun fixNear(from: Long, to: Long, at: Long): FixRow?

    @Query("SELECT * FROM enrichments WHERE `key` = :key")
    suspend fun enrichment(key: String): EnrichRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEnrichment(row: EnrichRow)

    /** Roughly one fix every 30 s, for learning routine places over weeks without loading every row. */
    @Query("SELECT * FROM fixes WHERE timeMs >= :from AND (timeMs / 5000) % 6 = 0 ORDER BY timeMs")
    suspend fun fixesSampled(from: Long): List<FixRow>

    @Query("SELECT * FROM familiar_places ORDER BY createdMs DESC")
    fun familiarPlaces(): Flow<List<FamiliarRow>>

    @Query("SELECT * FROM familiar_places")
    suspend fun familiarNow(): List<FamiliarRow>

    @Insert
    suspend fun addFamiliar(row: FamiliarRow): Long

    @Query("UPDATE familiar_places SET state = :state WHERE id = :id")
    suspend fun setFamiliarState(id: Long, state: Int)

    @Query("UPDATE familiar_places SET label = :label WHERE id = :id")
    suspend fun setFamiliarLabel(id: Long, label: String)

    /** Moves/resizes a place and confirms it (editing a suggestion accepts it). */
    @Query("UPDATE familiar_places SET lat = :lat, lon = :lon, radiusM = :radiusM, label = :label, state = :state WHERE id = :id")
    suspend fun updateFamiliarArea(id: Long, lat: Double, lon: Double, radiusM: Double, label: String, state: Int)

    @Query("DELETE FROM familiar_places WHERE id = :id")
    suspend fun deleteFamiliar(id: Long)

    @Query("SELECT * FROM baseline WHERE entityId = :id")
    suspend fun baseline(id: String): BaselineRow?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun putBaseline(row: BaselineRow)

    @Query("SELECT entityId FROM baseline WHERE days >= :minDays")
    suspend fun residents(minDays: Int): List<String>

    @Query("DELETE FROM baseline")
    suspend fun wipeBaseline()

    @Query("SELECT * FROM companions WHERE entityId = :id")
    suspend fun companion(id: String): CompanionRow?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun putCompanion(row: CompanionRow)

    @Query("SELECT * FROM companions WHERE state = 1 ORDER BY updatedMs DESC")
    fun companionSuggestions(): kotlinx.coroutines.flow.Flow<List<CompanionRow>>

    @Insert
    suspend fun addFeedback(row: FeedbackRow)

    /** Unanswered or rejected suggestions not refreshed for a month are dropped (confirmed ones are kept). */
    @Query("DELETE FROM companions WHERE updatedMs < :before AND state != 2")
    suspend fun pruneCompanions(before: Long): Int

    @Query("DELETE FROM feedback WHERE timeMs < :before")
    suspend fun pruneFeedback(before: Long): Int

    @Query("DELETE FROM companions")
    suspend fun wipeCompanions()

    @Query("DELETE FROM feedback")
    suspend fun wipeFeedback()

    @Query("SELECT * FROM feedback ORDER BY timeMs DESC")
    fun feedback(): kotlinx.coroutines.flow.Flow<List<FeedbackRow>>

    @Query("SELECT entityId FROM feedback WHERE label = 0 AND timeMs >= :since")
    suspend fun falseAlarmsSince(since: Long): List<String>

    @Query("DELETE FROM familiar_places")
    suspend fun wipeFamiliar()

    @Query("DELETE FROM sightings")
    suspend fun wipeSightings()

    @Query("DELETE FROM fixes")
    suspend fun wipeFixes()

    @Query("DELETE FROM enrichments")
    suspend fun wipeEnrichments()
}

@Database(
    entities = [
        SightingRow::class, FixRow::class, IgnoreRow::class, EnrichRow::class, FamiliarRow::class, BaselineRow::class,
        CompanionRow::class, FeedbackRow::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class Db : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        private const val NAME = "retrovision.db"

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `familiar_places` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lat` REAL NOT NULL, " +
                        "`lon` REAL NOT NULL, `radiusM` REAL NOT NULL, `label` TEXT NOT NULL, " +
                        "`state` INTEGER NOT NULL, `kind` INTEGER NOT NULL, `createdMs` INTEGER NOT NULL)",
                )
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `baseline` (" +
                        "`entityId` TEXT PRIMARY KEY NOT NULL, `days` INTEGER NOT NULL, " +
                        "`lastDay` INTEGER NOT NULL, `lastMs` INTEGER NOT NULL)",
                )
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sightings` ADD COLUMN `source` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `sightings` ADD COLUMN `tsf` INTEGER NOT NULL DEFAULT -1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `companions` (" +
                        "`entityId` TEXT PRIMARY KEY NOT NULL, `days` INTEGER NOT NULL, `lastDay` INTEGER NOT NULL, " +
                        "`state` INTEGER NOT NULL, `label` TEXT NOT NULL, `updatedMs` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `feedback` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `entityId` TEXT NOT NULL, `label` INTEGER NOT NULL, " +
                        "`score` REAL NOT NULL, `reasons` TEXT NOT NULL, `timeMs` INTEGER NOT NULL)",
                )
            }
        }

        /** Opens the SQLCipher-encrypted database; if the key is lost the old file is discarded. */
        fun open(ctx: Context): Db {
            System.loadLibrary("sqlcipher")
            var pass = KeyVault.passphrase(ctx)
            if (pass == null) {
                ctx.deleteDatabase(NAME)
                KeyVault.reset(ctx)
                pass = KeyVault.passphrase(ctx)
                    ?: error("Android Keystore is not available; refusing to store data unencrypted")
            }
            return Room.databaseBuilder(ctx.applicationContext, Db::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(pass))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}
