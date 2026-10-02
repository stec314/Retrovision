package dev.retrovision.core.identity

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiKind

/**
 * "Notable" devices: radios whose *kind* is worth knowing about — pentest gadgets, roadside
 * plate readers, body cams, camera glasses, recording pendants, drones.
 *
 * These are PATTERN matches on what a radio says about itself (default names, vendor prefixes,
 * service UUIDs), so:
 *  - a match is not proof: the same chips and names appear in harmless gear;
 *  - a miss is not a clean bill: a renamed or silent device does not match.
 * The app shows them as information, never as an alert on their own. They matter when they
 * come with behaviour (an attack pattern) or persistence (seen at several of your places).
 *
 * The signature data is a subset of the Fieldwatch catalog (MIT, see the resource header).
 */
enum class NotableKind { HACKING, SURVEILLANCE, LAW_ENFORCEMENT, GLASSES, RECORDER, DRONE }

class NotableSignature(
    val id: String,
    val name: String,
    val kind: NotableKind,
    val note: String,
    internal val rules: List<Rule>,
) {
    class Rule internal constructor(val kind: String, val radio: Radio?, val text: String, val companyId: Int, val dataHex: String) {
        val glob: Regex? = if (kind == "NAME_GLOB") globRegex(text) else null
        val prefix: String = text.filter { it.isLetterOrDigit() }.uppercase()
    }

    override fun toString() = name
}

/** What a sighting says about itself, in the shape the matcher needs. */
class RadioFacts(
    val radio: Radio,
    val address: MacAddress,
    /** BLE: public / static / private. Vendor prefixes only mean something on non-random addresses. */
    val bleKind: BleAddressKind? = null,
    /** BLE local name, or the SSID of an access point. */
    val name: String? = null,
    val companyId: Int? = null,
    val manufacturerDataHex: String = "",
    val uuids16: Set<Int> = emptySet(),
    val uuids128: Set<String> = emptySet(),
    val serviceData16: Map<Int, String> = emptyMap(),
    /** Wi-Fi vendor-specific IE OUIs, hex "AABBCC". */
    val vendorIeOuis: Set<String> = emptySet(),
    /** True for frames sent by an access point (beacon / probe response). */
    val isAp: Boolean = false,
) {
    companion object {
        private val PROTOCOL_IE_OUIS = setOf("0050F2", "000FAC", "506F9A") // WPA/WMM/WPS, RSN, Wi-Fi Alliance

        fun of(s: Sighting): RadioFacts {
            s.ble?.let { b ->
                val info = AdvertisementInfo.of(b.advData)
                return RadioFacts(
                    Radio.BLE, s.address, b.addressKind, info.name, info.manufacturerId,
                    info.manufacturerData?.hex().orEmpty(), info.serviceUuids16, info.serviceUuids128,
                    info.serviceData16.mapValues { it.value.hex() },
                )
            }
            val w = s.wifi!!
            val ap = w.kind == WifiKind.BEACON || w.kind == WifiKind.PROBE_RESP
            return RadioFacts(
                Radio.WIFI, if (ap) (w.bssid ?: s.address) else s.address,
                name = if (ap) w.ssidText.ifEmpty { null } else null,
                vendorIeOuis = vendorOuis(w.ies) - PROTOCOL_IE_OUIS,
                isAp = ap,
            )
        }

        fun vendorOuis(ies: ByteArray): Set<String> {
            val out = HashSet<String>()
            var i = 0
            while (i + 2 <= ies.size) {
                val id = ies[i].toInt() and 0xFF
                val len = ies[i + 1].toInt() and 0xFF
                if (i + 2 + len > ies.size) break
                if (id == 221 && len >= 3) out += ies.copyOfRange(i + 2, i + 5).hex()
                i += 2 + len
            }
            return out
        }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

internal fun globRegex(p: String): Regex = Regex(
    "^" + p.map { c -> when (c) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(c.toString()) } }.joinToString("") + "$",
    RegexOption.IGNORE_CASE,
)

object NotableCatalog {
    private const val RESOURCE = "/dev/retrovision/core/identity/notable-signatures.tsv"

    val signatures: List<NotableSignature> by lazy {
        val text = NotableCatalog::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.readText() ?: ""
        parse(text)
    }

    fun parse(text: String): List<NotableSignature> {
        val out = ArrayList<NotableSignature>()
        var head: List<String>? = null
        var rules = ArrayList<NotableSignature.Rule>()
        fun flush() {
            val h = head ?: return
            val kind = runCatching { NotableKind.valueOf(h[3]) }.getOrNull() ?: return
            out += NotableSignature(h[1], h[2], kind, h.getOrElse(4) { "" }, rules)
        }
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split('\t')
            when (f[0]) {
                "F" -> { flush(); head = f; rules = ArrayList() }
                "R" -> if (f.size >= 6) rules += NotableSignature.Rule(
                    f[1],
                    when (f[2]) { "WIFI" -> Radio.WIFI; "BLE" -> Radio.BLE; else -> null },
                    f[3], f[4].toIntOrNull() ?: 0, f[5].uppercase(),
                )
            }
        }
        flush()
        return out
    }

    fun match(s: Sighting): List<NotableSignature> = match(RadioFacts.of(s))

    fun match(f: RadioFacts, catalog: List<NotableSignature> = signatures): List<NotableSignature> =
        catalog.filter { sig -> sig.rules.any { hits(f, it) } }

    private fun hits(f: RadioFacts, r: NotableSignature.Rule): Boolean {
        if (r.radio != null && r.radio != f.radio) return false
        return when (r.kind) {
            "NAME_CONTAINS" -> f.name != null && r.text.isNotBlank() && f.name.contains(r.text, ignoreCase = true)
            "NAME_GLOB" -> f.name != null && r.glob!!.matches(f.name)
            "OUI" -> ouiHits(f, r.prefix)
            "MAC_PREFIX" -> f.address.toString().filter { it.isLetterOrDigit() }.uppercase().startsWith(r.prefix)
            "VENDOR_IE_OUI" -> f.vendorIeOuis.any { it.startsWith(r.prefix) }
            "SERVICE_UUID" -> when (r.prefix.length) {
                4 -> r.prefix.toInt(16).let { it in f.uuids16 || it in f.serviceData16.keys }
                32 -> f.uuids128.any { u -> u.replace("-", "") == r.prefix }
                else -> false
            }
            "MANUFACTURER_ID" -> f.companyId != null && f.companyId == r.companyId
            "MANUFACTURER_DATA" -> r.dataHex.isNotEmpty() && (r.companyId == 0 || f.companyId == r.companyId) &&
                f.manufacturerDataHex.startsWith(r.dataHex)
            "SERVICE_DATA" -> serviceDataHits(f, r)
            else -> false
        }
    }

    private fun ouiHits(f: RadioFacts, want: String): Boolean {
        if (want.length != 6) return false
        if (f.radio == Radio.BLE && f.bleKind != BleAddressKind.PUBLIC && f.bleKind != BleAddressKind.UNKNOWN) return false
        val oui = "%06X".format(f.address.oui)
        if (oui == want) return true
        // Guest/mesh radios set the local bit on a burned-in vendor prefix: recover it for APs.
        if (f.radio == Radio.WIFI && f.isAp && f.address.isLocallyAdministered) {
            return "%06X".format(f.address.oui and 0xFDFFFF) == want
        }
        return false
    }

    private fun serviceDataHits(f: RadioFacts, r: NotableSignature.Rule): Boolean {
        val uuid = r.prefix.takeIf { it.length == 4 }?.toInt(16)
        if (uuid == null && r.dataHex.isEmpty()) return false
        return f.serviceData16.any { (u, data) ->
            (uuid == null || u == uuid) && when {
                r.dataHex.isEmpty() -> true
                uuid == null -> data.contains(r.dataHex) // no UUID given: payload anywhere
                else -> data.startsWith(r.dataHex)
            }
        }
    }
}
