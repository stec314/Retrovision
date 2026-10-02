package dev.retrovision.core.identity

/** One AD structure (Core Spec Supplement, Part A). */
class AdStructure(val type: Int, val data: ByteArray)

object AdParser {
    const val FLAGS = 0x01
    const val UUID16_INCOMPLETE = 0x02
    const val UUID16_COMPLETE = 0x03
    const val UUID128_INCOMPLETE = 0x06
    const val UUID128_COMPLETE = 0x07
    const val SHORT_NAME = 0x08
    const val COMPLETE_NAME = 0x09
    const val TX_POWER = 0x0A
    const val SERVICE_DATA_16 = 0x16
    const val APPEARANCE = 0x19
    const val MANUFACTURER = 0xFF

    /** Lenient: stops at the first malformed or zero-length structure. */
    fun parse(ad: ByteArray): List<AdStructure> {
        val out = ArrayList<AdStructure>(4)
        var pos = 0
        while (pos < ad.size) {
            val len = ad[pos].toInt() and 0xFF
            if (len == 0 || pos + 1 + len > ad.size) break
            out += AdStructure(ad[pos + 1].toInt() and 0xFF, ad.copyOfRange(pos + 2, pos + 1 + len))
            pos += 1 + len
        }
        return out
    }
}

/** Decoded view of an advertisement, the bits the rest of the app cares about. */
class AdvertisementInfo(
    val manufacturerId: Int?,
    /** Manufacturer data without the 2-byte company id. */
    val manufacturerData: ByteArray?,
    val serviceUuids16: Set<Int>,
    /** 16-bit UUID -> service data (without the UUID). */
    val serviceData16: Map<Int, ByteArray>,
    val name: String?,
    val txPower: Int?,
    /** AD Flags byte, null when absent. */
    val flags: Int?,
    /** GAP Appearance value (AD type 0x19), null when absent. */
    val appearance: Int? = null,
    /** 128-bit service UUIDs, canonical upper-case "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX". */
    val serviceUuids128: Set<String> = emptySet(),
) {
    companion object {
        fun of(ad: ByteArray): AdvertisementInfo {
            var mId: Int? = null
            var mData: ByteArray? = null
            val uuids = HashSet<Int>()
            val sdata = HashMap<Int, ByteArray>()
            var name: String? = null
            var tx: Int? = null
            var flags: Int? = null
            var appearance: Int? = null
            val uuids128 = HashSet<String>()
            for (s in AdParser.parse(ad)) {
                val d = s.data
                when (s.type) {
                    AdParser.MANUFACTURER -> if (d.size >= 2 && mId == null) {
                        mId = (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)
                        mData = d.copyOfRange(2, d.size)
                    }
                    AdParser.UUID16_COMPLETE, AdParser.UUID16_INCOMPLETE -> {
                        var i = 0
                        while (i + 1 < d.size) {
                            uuids += (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
                            i += 2
                        }
                    }
                    AdParser.UUID128_COMPLETE, AdParser.UUID128_INCOMPLETE -> {
                        var i = 0
                        while (i + 16 <= d.size) {
                            uuids128 += uuid128(d, i)
                            i += 16
                        }
                    }
                    AdParser.SERVICE_DATA_16 -> if (d.size >= 2) {
                        val u = (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)
                        sdata[u] = d.copyOfRange(2, d.size)
                    }
                    AdParser.COMPLETE_NAME, AdParser.SHORT_NAME ->
                        if (name == null) name = String(d, Charsets.UTF_8)
                    AdParser.TX_POWER -> if (d.size == 1) tx = d[0].toInt()
                    AdParser.FLAGS -> if (d.isNotEmpty()) flags = d[0].toInt() and 0xFF
                    AdParser.APPEARANCE -> if (d.size >= 2) appearance = (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)
                }
            }
            return AdvertisementInfo(mId, mData, uuids, sdata, name, tx, flags, appearance, uuids128)
        }

        /** 128-bit UUIDs are little-endian on air. */
        private fun uuid128(d: ByteArray, off: Int): String {
            val hex = (15 downTo 0).joinToString("") { "%02X".format(d[off + it].toInt() and 0xFF) }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }
    }
}

enum class TrackerKind(val label: String, val vendor: String) {
    AIRTAG("AirTag", "Apple"),
    FIND_MY_ACCESSORY("Find My accessory", "Apple network"),
    AIRPODS("AirPods (Find My)", "Apple"),
    APPLE_DEVICE_FIND_MY("Apple device (Find My)", "Apple"),
    SAMSUNG_SMARTTAG("Galaxy SmartTag", "Samsung"),
    SAMSUNG_FIND_MY_MOBILE("Samsung Find My Mobile device", "Samsung"),
    TILE("Tile", "Tile/Life360"),
    CHIPOLO("Chipolo", "Chipolo"),
    PEBBLEBEE("Pebblebee", "Pebblebee"),
    GOOGLE_FIND_MY_DEVICE("Find My Device network tag", "Google"),
    GOOGLE_FIND_MY_PHONE("Find My Device network phone", "Google"),
    /** IETF DULT (Detecting Unwanted Location Trackers) advertisement from a tag of any brand. */
    DULT_TAG("Location tag (DULT)", "IETF DULT"),
}

/**
 * @param separated true when the advertisement says the tracker is away from its
 *   owner (Apple offline-finding "separated" payload). That is the state a tracker
 *   planted on someone is in; null when the format does not tell.
 */
class TrackerMatch(val kind: TrackerKind, val separated: Boolean?)

/**
 * Tracker recognition from raw advertising data.
 *
 * Identifiers follow AirGuard (seemoo-lab, Apache-2.0), the reference Android
 * implementation of tracker detection:
 *  - Apple offline finding: company 0x004C, type 0x12; length 0x19 = separated
 *    from owner, 0x02 = near owner; status byte bits 4-5 = device class.
 *  - Samsung SmartTag: service 0xFD5A. Samsung Find My Mobile: 0xFD69.
 *  - Tile: 0xFEED. Chipolo: 0xFE33. Pebblebee: 0xFA25.
 *  - Google Find My Device network (FMDN): service data 0xFEAA with frame type
 *    0x40/0x41. 0xFEAA is shared with Eddystone (frames 0x00-0x30), hence the check.
 *    AD flags 0x02 = smartphone, 0x06 = tag.
 *  - IETF DULT (draft-ietf-dult-accessory-protocol): service data 0xFCB2; byte 0 network id,
 *    byte 1 bit 0 = mode (0 separated from owner, 1 near owner). Brands adopting it (Chipolo,
 *    Pebblebee, moto tag...) may also carry their own UUID: then the brand wins and DULT
 *    supplies the separated flag.
 */
object TrackerClassifier {
    private const val APPLE = 0x004C
    private const val APPLE_OFFLINE_FINDING = 0x12
    private const val APPLE_SEPARATED_LEN = 0x19
    private const val DULT = 0xFCB2

    fun classify(ad: ByteArray): TrackerMatch? = classify(AdvertisementInfo.of(ad))

    fun classify(info: AdvertisementInfo): TrackerMatch? {
        val md = info.manufacturerData
        if (info.manufacturerId == APPLE && md != null && md.size >= 3 &&
            (md[0].toInt() and 0xFF) == APPLE_OFFLINE_FINDING
        ) {
            val separated = (md[1].toInt() and 0xFF) >= APPLE_SEPARATED_LEN
            val kind = when ((md[2].toInt() and 0x30) shr 4) {
                1 -> TrackerKind.AIRTAG
                2 -> TrackerKind.FIND_MY_ACCESSORY
                3 -> TrackerKind.AIRPODS
                else -> TrackerKind.APPLE_DEVICE_FIND_MY
            }
            return TrackerMatch(kind, separated)
        }

        info.serviceData16[0xFEAA]?.let { sd ->
            if (sd.isNotEmpty() && (sd[0].toInt() and 0xFF) in 0x40..0x41) {
                val kind = if (info.flags == 0x02) TrackerKind.GOOGLE_FIND_MY_PHONE
                else TrackerKind.GOOGLE_FIND_MY_DEVICE
                return TrackerMatch(kind, null)
            }
        }

        val dult = info.serviceData16[DULT]
        val dultSeparated = dult?.takeIf { it.size >= 2 }?.let { (it[1].toInt() and 0x01) == 0 }
        val uuids = info.serviceUuids16 + info.serviceData16.keys
        val brand = when {
            0xFD5A in uuids -> TrackerKind.SAMSUNG_SMARTTAG
            0xFEED in uuids -> TrackerKind.TILE
            0xFE33 in uuids -> TrackerKind.CHIPOLO
            0xFA25 in uuids -> TrackerKind.PEBBLEBEE
            0xFD69 in uuids -> TrackerKind.SAMSUNG_FIND_MY_MOBILE
            else -> null
        }
        return when {
            brand != null -> TrackerMatch(brand, dultSeparated)
            dult != null -> TrackerMatch(TrackerKind.DULT_TAG, dultSeparated)
            else -> null
        }
    }

    /** Kinds that are dedicated tracking tags (vs phones/earbuds that also use the networks). */
    fun isTag(kind: TrackerKind): Boolean = when (kind) {
        TrackerKind.AIRTAG, TrackerKind.FIND_MY_ACCESSORY, TrackerKind.SAMSUNG_SMARTTAG,
        TrackerKind.TILE, TrackerKind.CHIPOLO, TrackerKind.PEBBLEBEE,
        TrackerKind.GOOGLE_FIND_MY_DEVICE, TrackerKind.DULT_TAG -> true
        TrackerKind.AIRPODS, TrackerKind.APPLE_DEVICE_FIND_MY, TrackerKind.SAMSUNG_FIND_MY_MOBILE,
        TrackerKind.GOOGLE_FIND_MY_PHONE -> false
    }
}
