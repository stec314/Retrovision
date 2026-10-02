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
interface AppDao {
    @Insert
    suspend fun insertSightings(rows: List<SightingRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFix(f: FixRow)

    @Query("SELECT * FROM sightings WHERE timeMs >= :from ORDER BY timeMs")
    suspend fun sightingsSince(from: Long): List<SightingRow>

    @Query("SELECT COUNT(*) FROM sightings WHERE timeMs >= :from")
    suspend fun sightingCountSince(from: Long): Long

    /** Memory-safe sampling for retrospective review: every :stride-th row by id. */
    @Query("SELECT * FROM sightings WHERE timeMs >= :from AND (id % :stride) = 0 ORDER BY timeMs")
    suspend fun sightingsSinceSampled(from: Long, stride: Int): List<SightingRow>

    @Query("SELECT * FROM fixes WHERE timeMs >= :from ORDER BY timeMs")
    suspend fun fixesSince(from: Long): List<FixRow>

    @Query("DELETE FROM sightings WHERE timeMs < :before")
    suspend fun pruneSightings(before: Long): Int

    @Query("DELETE FROM fixes WHERE timeMs < :before")
    suspend fun pruneFixes(before: Long): Int

    @Query("SELECT COUNT(*) FROM sightings")
    fun sightingCount(): Flow<Long>

    @Query("SELECT * FROM ignores ORDER BY createdMs DESC")
    fun ignores(): Flow<List<IgnoreRow>>

    @Query("SELECT * FROM ignores")
    suspend fun ignoresNow(): List<IgnoreRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addIgnore(row: IgnoreRow)

    @Query("DELETE FROM ignores WHERE entityId = :id")
    suspend fun removeIgnore(id: String)

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
