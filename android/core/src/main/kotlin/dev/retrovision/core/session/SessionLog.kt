package dev.retrovision.core.session

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException

/** Everything the analysis needs from a recording session, nothing else. */
class RecordedSession(val sightings: List<Sighting>, val fixes: List<GeoFix>)

/**
 * A recorded session (`.rvsl`): gzip of a tiny record stream. It holds exactly what the
 * analyzer consumes: observations with their raw information elements / advertising data
 * (so fingerprinting can be re-run with new code) and your own GPS fixes. Entity ids are
 * not stored: replay runs identity resolution again, which is the point of replaying.
 *
 * Layout: "RVSL" u8 version, then records: u8 type (1 sighting, 2 fix), body.
 * Truncated files (the phone died mid-session) are read up to the last complete record.
 */
object SessionLog {
    private const val MAGIC = 0x5256534C // "RVSL"
    private const val VERSION = 1
    private const val T_SIGHTING = 1
    private const val T_FIX = 2
    private const val MAX_BLOB = 4096

    class Writer(out: OutputStream) : AutoCloseable {
        private val gz = GZIPOutputStream(out, 8192, true)
        private val d = DataOutputStream(gz)
        private var n = 0

        init {
            d.writeInt(MAGIC)
            d.writeByte(VERSION)
        }

        @Synchronized
        fun write(s: Sighting) {
            d.writeByte(T_SIGHTING)
            d.writeLong(s.timeMs)
            d.writeByte(if (s.radio == Radio.WIFI) 0 else 1)
            d.writeLong(s.address.bits)
            d.writeShort(s.rssi)
            d.writeShort(s.mergedCount)
            val w = s.wifi
            if (w != null) {
                d.writeByte(w.kind.ordinal)
                d.writeShort(w.channel)
                blob(w.ssid)
                d.writeLong(w.bssid?.bits ?: -1L)
                d.writeShort(w.seq)
                blob(w.ies)
                d.writeBoolean(w.iesTruncated)
            }
            val b = s.ble
            if (b != null) {
                d.writeByte(b.addressKind.ordinal)
                d.writeByte(b.advType)
                blob(b.advData)
                d.writeByte(b.txPowerDbm)
            }
            tick()
        }

        @Synchronized
        fun write(f: GeoFix) {
            d.writeByte(T_FIX)
            d.writeLong(f.timeMs)
            d.writeDouble(f.lat)
            d.writeDouble(f.lon)
            d.writeFloat(f.accuracyM)
            d.writeFloat(f.speedMps ?: -1f)
            tick()
        }

        private fun blob(b: ByteArray) {
            val n = minOf(b.size, MAX_BLOB)
            d.writeShort(n)
            d.write(b, 0, n)
        }

        /** Push data through gzip regularly so a crash loses seconds, not the session. */
        private fun tick() {
            if (++n % 64 == 0) d.flush()
        }

        @Synchronized
        override fun close() {
            d.flush()
            d.close()
        }
    }

    fun read(input: InputStream): RecordedSession {
        val sightings = ArrayList<Sighting>()
        val fixes = ArrayList<GeoFix>()
        try {
            val d = DataInputStream(GZIPInputStream(input, 8192).buffered())
            require(d.readInt() == MAGIC) { "not a Retrovision session file" }
            require(d.readUnsignedByte() <= VERSION) { "session file is from a newer version" }
            while (true) {
                val type = try {
                    d.readUnsignedByte()
                } catch (_: EOFException) {
                    break
                }
                when (type) {
                    T_SIGHTING -> sightings += readSighting(d)
                    T_FIX -> fixes += readFix(d)
                    else -> break // unknown record: stop rather than misparse
                }
            }
        } catch (_: EOFException) {
            // truncated tail
        } catch (e: ZipException) {
            if (e.message?.contains("Unexpected end") != true && e.message?.contains("Not in GZIP") == true) throw e
        }
        return RecordedSession(sightings, fixes)
    }

    private fun readBlob(d: DataInputStream): ByteArray = ByteArray(d.readUnsignedShort()).also { d.readFully(it) }

    private fun readSighting(d: DataInputStream): Sighting {
        val t = d.readLong()
        val radio = if (d.readUnsignedByte() == 0) Radio.WIFI else Radio.BLE
        val addr = MacAddress(d.readLong())
        val rssi = d.readShort().toInt()
        val merged = d.readShort().toInt()
        return if (radio == Radio.WIFI) {
            val kind = WifiKind.entries[d.readUnsignedByte().coerceIn(0, WifiKind.entries.size - 1)]
            val ch = d.readUnsignedShort()
            val ssid = readBlob(d)
            val bssid = d.readLong().let { if (it >= 0) MacAddress(it) else null }
            val seq = d.readUnsignedShort()
            val ies = readBlob(d)
            val trunc = d.readBoolean()
            Sighting(t, radio, addr, rssi, merged, wifi = WifiDetail(kind, ch, ssid, bssid, seq, ies, trunc))
        } else {
            val ak = BleAddressKind.entries[d.readUnsignedByte().coerceIn(0, BleAddressKind.entries.size - 1)]
            val adv = d.readUnsignedByte()
            val data = readBlob(d)
            val tx = d.readByte().toInt()
            Sighting(t, radio, addr, rssi, merged, ble = BleDetail(ak, adv, data, tx))
        }
    }

    private fun readFix(d: DataInputStream): GeoFix {
        val t = d.readLong()
        val lat = d.readDouble()
        val lon = d.readDouble()
        val acc = d.readFloat()
        val sp = d.readFloat()
        return GeoFix(t, lat, lon, acc, if (sp >= 0) sp else null)
    }
}
