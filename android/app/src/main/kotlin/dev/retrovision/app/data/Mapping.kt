package dev.retrovision.app.data

import dev.retrovision.core.model.BleAddressKind
import dev.retrovision.core.model.BleDetail
import dev.retrovision.core.model.GeoFix
import dev.retrovision.core.model.MacAddress
import dev.retrovision.core.model.Radio
import dev.retrovision.core.model.Sighting
import dev.retrovision.core.model.WifiDetail
import dev.retrovision.core.model.WifiKind

private val EMPTY = ByteArray(0)

fun Sighting.toRow(entityId: String): SightingRow = SightingRow(
    timeMs = timeMs,
    radio = if (radio == Radio.WIFI) 0 else 1,
    address = address.bits,
    entityId = entityId,
    rssi = rssi,
    merged = mergedCount,
    wifiKind = wifi?.kind?.ordinal ?: 0,
    channel = wifi?.channel ?: 0,
    ssid = wifi?.ssid ?: EMPTY,
    bssid = wifi?.bssid?.bits ?: -1L,
    seq = wifi?.seq ?: 0,
    ies = wifi?.ies ?: EMPTY,
    bleAddrKind = ble?.addressKind?.ordinal ?: 0,
    advType = ble?.advType ?: 0,
    advData = ble?.advData ?: EMPTY,
    txPower = ble?.txPowerDbm ?: 0,
)

fun SightingRow.toSighting(): Sighting {
    val mac = MacAddress(address)
    return if (radio == 0) {
        Sighting(
            timeMs, Radio.WIFI, mac, rssi, merged,
            wifi = WifiDetail(
                WifiKind.entries[wifiKind.coerceIn(0, WifiKind.entries.size - 1)], channel, ssid,
                if (bssid >= 0) MacAddress(bssid) else null, seq, ies,
            ),
        )
    } else {
        Sighting(
            timeMs, Radio.BLE, mac, rssi, merged,
            ble = BleDetail(
                BleAddressKind.entries[bleAddrKind.coerceIn(0, BleAddressKind.entries.size - 1)],
                advType, advData, txPower,
            ),
        )
    }
}

fun FixRow.toFix() = GeoFix(timeMs, lat, lon, accuracyM, if (speedMps >= 0) speedMps else null)
