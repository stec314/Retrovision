package dev.retrovision.core.identity

/** What kind of thing a transmitter probably is. Best effort, from what it broadcasts about itself. */
enum class DeviceCategory {
    ROUTER, HOTSPOT, VEHICLE, CAMERA, WIFI_DIRECT,
    WIFI_CLIENT,
    PHONE, COMPUTER, WATCH, AUDIO, TV, INPUT, HEALTH, HOME, TRACKER, BEACON,
    BLE_OTHER,
}

/** How far a MAC/BLE address identifies the same device over time. */
enum class MacTrust {
    /** Factory (globally administered) MAC, or BLE public address: stable. */
    STABLE,
    /** BLE random static address: stable until the device reboots or resets. */
    UNTIL_REBOOT,
    /**
     * Randomised Wi-Fi MAC that the device used to join a network (auth / (re)association request).
     * Android 10+, iOS 14+ and Windows keep one random address per network and reuse it every time
     * they connect, so it identifies the device for as long as it uses that network, often for days.
     * Another network gets a different address. Not forever: iOS "rotating" private addresses and
     * Android's non-persistent mode (e.g. open networks) still renew it every day or two.
     */
    PER_NETWORK,
    /** Randomised Wi-Fi MAC or BLE private address: rotates, so the same device shows up under new addresses. */
    ROTATING,
}

/** Evidence gathered across one entity's advertisements, classified at the end. */
class CategoryHints {
    var appearance: Int? = null
    var appleType: Int? = null
    var companyId: Int? = null
    var name: String? = null
    val uuids16 = HashSet<Int>()

    fun add(info: AdvertisementInfo) {
        if (appearance == null && info.appearance != null && info.appearance != 0) appearance = info.appearance
        if (companyId == null) companyId = info.manufacturerId
        if (name == null && !info.name.isNullOrBlank()) name = info.name
        uuids16 += info.serviceUuids16
        uuids16 += info.serviceData16.keys
        if (info.manufacturerId == APPLE) {
            val t = info.manufacturerData?.firstOrNull()?.toInt()?.and(0xFF)
            // Keep the most telling Apple message type seen (Nearby Info and Proximity Pairing over the rest).
            if (t != null && (appleType == null || t == 0x07 || t == 0x10)) appleType = t
        }
    }

    companion object {
        const val APPLE = 0x004C
        const val MICROSOFT = 0x0006
        const val GARMIN = 0x0087
        const val FAST_PAIR = 0xFE2C
        const val IBEACON_TYPE = 0x02
        const val EDDYSTONE = 0xFEAA
    }
}

object DeviceCategories {
    private val audioName = Regex("(AirPods|Buds|Beats|JBL|Bose|WH-|WF-|Sony|Jabra|Sennheiser|Headset|Headphone|Earbud|Soundcore|Marshall|Bang ?& ?Olufsen|B&O|Speaker|Echo)", RegexOption.IGNORE_CASE)
    private val watchName = Regex("(Watch|Band|Fitbit|Garmin|Amazfit|Mi Smart|Huami|Forerunner|Fenix|Venu|Polar|Suunto|Whoop|Oura)", RegexOption.IGNORE_CASE)
    private val tvName = Regex("(\\bTV\\b|Bravia|webOS|Chromecast|Fire ?TV|Roku|Android TV|\\[TV\\])", RegexOption.IGNORE_CASE)
    private val inputName = Regex("(Keyboard|Mouse|MX Master|Trackpad|Controller|Gamepad|Xbox|DualSense|Pencil)", RegexOption.IGNORE_CASE)
    private val carName = Regex("^(BMW|Audi|VW|MINI|Mercedes|MB |Tesla|Toyota|Ford|Opel|Peugeot|Citroen|Renault|Fiat|Jeep|Volvo|Hyundai|Kia|Skoda|SEAT|CUPRA|Uconnect|My ?Car|CAR )", RegexOption.IGNORE_CASE)
    private val phoneName = Regex("(iPhone|Galaxy S|Galaxy A|Pixel|Redmi|Xiaomi|OnePlus|moto g|HUAWEI|HONOR|OPPO|vivo)", RegexOption.IGNORE_CASE)
    private val computerName = Regex("(MacBook|iMac|\\bPC\\b|DESKTOP-|LAPTOP-|ThinkPad|Surface)", RegexOption.IGNORE_CASE)

    fun forWifiAp(mobileAp: MobileAp.Kind?): DeviceCategory = when (mobileAp) {
        MobileAp.Kind.PHONE_HOTSPOT -> DeviceCategory.HOTSPOT
        MobileAp.Kind.VEHICLE -> DeviceCategory.VEHICLE
        MobileAp.Kind.CAMERA -> DeviceCategory.CAMERA
        MobileAp.Kind.WIFI_DIRECT -> DeviceCategory.WIFI_DIRECT
        null -> DeviceCategory.ROUTER
    }

    fun forBle(tracker: TrackerKind?, isTag: Boolean, h: CategoryHints): DeviceCategory {
        if (tracker != null && isTag) return DeviceCategory.TRACKER
        h.appearance?.let { fromAppearance(it) }?.let { return it }
        val name = h.name.orEmpty()
        if (name.isNotEmpty()) {
            when {
                carName.containsMatchIn(name) -> return DeviceCategory.VEHICLE
                audioName.containsMatchIn(name) -> return DeviceCategory.AUDIO
                watchName.containsMatchIn(name) -> return DeviceCategory.WATCH
                tvName.containsMatchIn(name) -> return DeviceCategory.TV
                inputName.containsMatchIn(name) -> return DeviceCategory.INPUT
                computerName.containsMatchIn(name) -> return DeviceCategory.COMPUTER
                phoneName.containsMatchIn(name) -> return DeviceCategory.PHONE
            }
        }
        when (h.appleType) {
            0x07 -> return DeviceCategory.AUDIO // Proximity Pairing: AirPods / Beats
            0x10, 0x0C -> return DeviceCategory.PHONE // Nearby Info / Handoff: iPhone, iPad, Mac
            0x09 -> return DeviceCategory.TV // AirPlay target
            IBEACON -> return DeviceCategory.BEACON
        }
        if (tracker == TrackerKind.AIRPODS) return DeviceCategory.AUDIO
        if (tracker == TrackerKind.APPLE_DEVICE_FIND_MY || tracker == TrackerKind.GOOGLE_FIND_MY_PHONE || tracker == TrackerKind.SAMSUNG_FIND_MY_MOBILE) {
            return DeviceCategory.PHONE
        }
        if (CategoryHints.FAST_PAIR in h.uuids16) return DeviceCategory.AUDIO
        if (CategoryHints.EDDYSTONE in h.uuids16) return DeviceCategory.BEACON
        if (h.companyId == CategoryHints.MICROSOFT) return DeviceCategory.COMPUTER
        if (h.companyId == CategoryHints.GARMIN) return DeviceCategory.WATCH
        return DeviceCategory.BLE_OTHER
    }

    private const val IBEACON = 0x02

    /** Bluetooth SIG Appearance: category = value >> 6. */
    fun fromAppearance(a: Int): DeviceCategory? = when (a ushr 6) {
        0x001 -> DeviceCategory.PHONE
        0x002 -> DeviceCategory.COMPUTER
        0x003, 0x007, 0x011, 0x012 -> DeviceCategory.WATCH
        0x005, 0x027, 0x028 -> DeviceCategory.TV
        0x006, 0x00F, 0x02A -> DeviceCategory.INPUT
        0x008, 0x009 -> DeviceCategory.TRACKER
        0x00A, 0x021, 0x022, 0x025, 0x029 -> DeviceCategory.AUDIO
        0x00C, 0x00D, 0x00E, 0x010, 0x031 -> DeviceCategory.HEALTH
        in 0x013..0x020, 0x024 -> DeviceCategory.HOME
        0x023 -> DeviceCategory.VEHICLE
        else -> null
    }
}
