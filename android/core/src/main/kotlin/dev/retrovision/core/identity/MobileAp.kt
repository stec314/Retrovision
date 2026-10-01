package dev.retrovision.core.identity

import dev.retrovision.core.model.MacAddress

/**
 * Heuristics for access points that move with a person or vehicle: phone
 * hotspots, car Wi-Fi, dashcams, action cams. A *moving* AP seen at several of
 * your places is a much stronger signal than a phone probe, because it is
 * usually a vehicle.
 *
 * Heuristic only: patterns are default names, which people rename.
 */
object MobileAp {
    enum class Kind { PHONE_HOTSPOT, VEHICLE, CAMERA, WIFI_DIRECT }

    private val patterns: List<Pair<Regex, Kind>> = listOf(
        Regex("^iPhone( di| de| von| van|’s|'s|$| )", RegexOption.IGNORE_CASE) to Kind.PHONE_HOTSPOT,
        Regex("^(AndroidAP|AndroidShare|Galaxy|Redmi|POCO|Xiaomi|HUAWEI|HONOR|OnePlus|OPPO|vivo|Pixel|moto|Nokia|realme)", RegexOption.IGNORE_CASE) to Kind.PHONE_HOTSPOT,
        Regex("(Hotspot|Personal Hotspot|Mobile ?Hotspot|MiFi|Jetpack)", RegexOption.IGNORE_CASE) to Kind.PHONE_HOTSPOT,
        Regex("^(VW[ _-]?WLAN|Audi|BMW|MINI|Mercedes|MBUX|Skoda|SEAT|CUPRA|Porsche|Tesla|Ford|FordPass|Opel|Peugeot|Citroen|Renault|Toyota|Hyundai|Kia|Volvo|Jeep|Fiat|Alfa|myChevrolet|OnStar)", RegexOption.IGNORE_CASE) to Kind.VEHICLE,
        Regex("(Dashcam|DashCam|70mai|VIOFO|BlackVue|Thinkware|Garmin ?Dash|Nextbase|GoPro|DJI|Insta360)", RegexOption.IGNORE_CASE) to Kind.CAMERA,
        Regex("^DIRECT-") to Kind.WIFI_DIRECT,
    )

    fun classify(ssid: String, bssid: MacAddress?): Kind? {
        patterns.firstOrNull { it.first.containsMatchIn(ssid) }?.let { return it.second }
        // Android and iOS hotspots use a locally administered BSSID; fixed APs
        // almost never do. Weak signal on its own, so only for non-empty SSIDs.
        if (bssid != null && bssid.isLocallyAdministered && ssid.isNotEmpty()) return Kind.PHONE_HOTSPOT
        return null
    }
}
