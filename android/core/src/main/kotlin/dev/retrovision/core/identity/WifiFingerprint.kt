package dev.retrovision.core.identity

/**
 * Device fingerprint from the Information Elements of 802.11 probe requests.
 *
 * MAC randomisation changes the address but a given device model/OS/driver
 * keeps sending the same capability elements in the same order (Vanhoef et al.,
 * "Why MAC Address Randomization is not Enough", AsiaCCS 2016). We hash the
 * elements that describe the *device*, and skip the ones that describe the
 * *request* (SSID, channel) or are per-frame random (WPS UUID-E).
 *
 * What is hashed: rates (1, 50), HT caps (45), extended caps (127), VHT caps (191),
 * extension elements (255) such as HE caps (35), HE 6 GHz caps (59) and EHT caps (108),
 * vendor elements by OUI + type (and content, except WPS), and everything else not listed below.
 * What is not, because it describes the request rather than the device:
 *  - SSID (0), DS parameter set / channel (3), Request (10), Mesh ID (114), Extended Request (255/10);
 *  - Supported Operating Classes (59): its first octet is the *current* class, which follows the
 *    band the probe is sent on; only the list of supported classes is hashed;
 *  - Interworking (107): only the access-network options octet; the optional HESSID names the target;
 *  - Multi-Link (255/107) and FILS Request Parameters (255/2): presence only; a probe-request
 *    multi-link element names the target AP MLD.
 * A truncated element list (the probe cut it to its frame budget) gives no fingerprint: the cut
 * point moves with the SSID length, so the same device would hash differently, and the shorter
 * list (it loses the HE/EHT and vendor elements at the end first) would match more strangers.
 *
 * IMPORTANT: the fingerprint is not unique. Every phone of the same model and
 * OS version shares it. It is only used to *link* MAC rotations together with
 * other evidence (time adjacency, sequence-number continuity), never as an
 * identity on its own. See [EntityResolver].
 */
object WifiFingerprint {
    private const val EID_SSID = 0
    private const val EID_DS_PARAMS = 3
    private const val EID_REQUEST = 10
    private const val EID_SUPPORTED_OP_CLASSES = 59
    private const val EID_INTERWORKING = 107
    private const val EID_MESH_ID = 114
    private const val EID_VENDOR = 221
    private const val EID_EXTENSION = 255

    // Element ID extensions (byte after the length when the element ID is 255).
    private const val EXT_FILS_REQUEST_PARAMS = 2
    private const val EXT_EXTENDED_REQUEST = 10
    private const val EXT_MULTI_LINK = 107

    // Microsoft WPS vendor element: contains UUID-E, which is per-device-random
    // on modern OSes but can also rotate; keep only its presence.
    private val WPS_OUI_TYPE = byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04)

    /**
     * Returns null when there are no usable IEs (the probe did not forward them) or when the
     * probe had to cut them ([truncated], from WifiDetail.iesTruncated).
     */
    fun of(ies: ByteArray, truncated: Boolean = false): String? {
        if (ies.isEmpty() || truncated) return null
        var h = FNV_INIT
        var pos = 0
        var used = 0
        while (pos + 2 <= ies.size) {
            val id = ies[pos].toInt() and 0xFF
            val len = ies[pos + 1].toInt() and 0xFF
            if (pos + 2 + len > ies.size) break
            val body = pos + 2
            when (id) {
                EID_SSID, EID_DS_PARAMS, EID_REQUEST, EID_MESH_ID -> Unit // request-specific
                EID_SUPPORTED_OP_CLASSES -> {
                    // Skip the current operating class (first octet): it depends on the band.
                    h = fnv(h, id)
                    if (len > 1) h = fnv(h, ies.copyOfRange(body + 1, body + len))
                    used++
                }
                EID_INTERWORKING -> {
                    // Access network options only; venue info and HESSID describe the target.
                    h = fnv(h, id)
                    if (len >= 1) h = fnv(h, ies[body].toInt())
                    used++
                }
                EID_EXTENSION -> {
                    if (len >= 1) {
                        val ext = ies[body].toInt() and 0xFF
                        when (ext) {
                            EXT_EXTENDED_REQUEST -> Unit // request-specific, like EID 10
                            EXT_MULTI_LINK, EXT_FILS_REQUEST_PARAMS -> {
                                h = fnv(h, id)
                                h = fnv(h, ext)
                                used++
                            }
                            else -> {
                                // HE caps (35), HE 6 GHz caps (59), EHT caps (108), ...
                                h = fnv(h, id)
                                h = fnv(h, len)
                                h = fnv(h, ies.copyOfRange(body, body + len))
                                used++
                            }
                        }
                    }
                }
                EID_VENDOR -> {
                    // Vendor elements: OUI + type identify the feature; content may vary.
                    h = fnv(h, id)
                    val n = minOf(len, 4)
                    val head = ies.copyOfRange(body, body + n)
                    h = fnv(h, head)
                    if (!head.contentEquals(WPS_OUI_TYPE)) {
                        // Heuristic: non-WPS vendor content is usually a stable capability
                        // blob. Revisit with real captures if it causes rotation misses.
                        h = fnv(h, ies.copyOfRange(body + n, body + len))
                    }
                    used++
                }
                else -> {
                    // Rates (1, 50), HT caps (45), ext caps (127), VHT caps (191), ...
                    h = fnv(h, id)
                    h = fnv(h, len)
                    h = fnv(h, ies.copyOfRange(body, body + len))
                    used++
                }
            }
            pos = body + len
        }
        return if (used == 0) null else "%016x".format(h)
    }

    private const val FNV_INIT = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    private fun fnv(h0: Long, b: Int): Long = (h0 xor (b.toLong() and 0xFF)) * FNV_PRIME

    private fun fnv(h0: Long, bytes: ByteArray): Long {
        var h = h0
        for (b in bytes) h = fnv(h, b.toInt())
        return h
    }
}
