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

@Dao
interface AppDao {
    @Insert
    suspend fun insertSightings(rows: List<SightingRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFix(f: FixRow)

    @Query("SELECT * FROM sightings WHERE timeMs >= :from ORDER BY timeMs")
    suspend fun sightingsSince(from: Long): List<SightingRow>

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

    @Query("DELETE FROM sightings")
    suspend fun wipeSightings()

    @Query("DELETE FROM fixes")
    suspend fun wipeFixes()

    @Query("DELETE FROM enrichments")
    suspend fun wipeEnrichments()
}

@Database(
    entities = [SightingRow::class, FixRow::class, IgnoreRow::class, EnrichRow::class],
    version = 1,
    exportSchema = false,
)
abstract class Db : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        private const val NAME = "retrovision.db"

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
                .build()
        }
    }
}
