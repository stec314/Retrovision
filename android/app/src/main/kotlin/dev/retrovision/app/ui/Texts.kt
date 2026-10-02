package dev.retrovision.app.ui

import dev.retrovision.app.enrich.Vendors
import dev.retrovision.core.analysis.EntityKind
import dev.retrovision.core.analysis.EntityReport
import dev.retrovision.core.analysis.Reason
import dev.retrovision.core.identity.MobileAp
import java.util.Locale

/** Tiny bilingual (it/en) string helper; the app has no other localisation needs yet. */
object Texts {
    private val it: Boolean get() = Locale.getDefault().language == "it"
    fun tr(en: String, itText: String) = if (it) itText else en

    fun notifRunning() = tr("Listening for the probe", "In ascolto della sonda")
    fun channelOngoing() = tr("Collection running", "Raccolta in corso")
    fun channelAlerts() = tr("Possible following", "Possibile pedinamento")
    fun channelAlertsSilent() = tr("Possible following (silent)", "Possibile pedinamento (silenzioso)")
    fun stop() = tr("Stop", "Ferma")
    fun cannotOpenPort() = tr("Cannot open the serial port", "Impossibile aprire la porta seriale")
    fun alertTitle(label: String) = tr("Seen with you again: $label", "Ti segue? $label")

    fun entityLabel(r: EntityReport): String {
        val addr = r.addresses.first()
        val vendor = (r.bleCompanyId?.let { Vendors.forCompany(it) } ?: Vendors.forMac(addr))
            ?.let { shorten(it) }
        val v = if (vendor != null) " · $vendor" else ""
        return when (r.kind) {
            EntityKind.WIFI_AP -> (r.ssids.firstOrNull() ?: tr("(hidden network)", "(rete nascosta)")) + " · $addr$v"
            EntityKind.WIFI_CLIENT -> tr("Wi-Fi device", "Dispositivo Wi-Fi") + " $addr$v"
            EntityKind.BLE_TRACKER -> (r.tracker?.label ?: "Tracker") + " · $addr"
            EntityKind.BLE_DEVICE -> (r.bleName?.let { "“${shorten(it)}”" } ?: tr("Bluetooth device", "Dispositivo Bluetooth")) + " $addr$v"
        }
    }

    private fun shorten(s: String) = if (s.length > 28) s.take(27) + "…" else s

    fun reason(r: Reason): String = when (r) {
        is Reason.SeenAtPlaces -> tr("Seen at ${r.places} different places", "Visto in ${r.places} luoghi diversi")
        is Reason.FamiliarDiscount -> tr(
            "${r.familiarPlaces} of those are places you are at all the time (counted for less)",
            "${r.familiarPlaces} di questi sono luoghi dove sei sempre (contano meno)",
        )
        is Reason.MovedWithYou -> tr(
            "Heard continuously at a steady strength while you moved ${r.meters.toInt()} m",
            "Sentito di continuo, con segnale stabile, mentre ti spostavi di ${r.meters.toInt()} m",
        )
        is Reason.PresentInWindows -> tr(
            "Present in ${r.windows} of ${r.of} time windows",
            "Presente in ${r.windows} finestre temporali su ${r.of}",
        )
        is Reason.SeenFor -> tr("Seen over ${r.durationMs / 60_000} min", "Visto per ${r.durationMs / 60_000} min")
        is Reason.TravelledWithYou -> tr(
            "Stayed with you over ${r.meters.toInt()} m of travel",
            "Presente lungo ${r.meters.toInt()} m di spostamento",
        )
        is Reason.Tracker -> tr("Tracker: ${r.kind.label}", "Tracker: ${r.kind.label}") +
            when (r.separatedFromOwner) {
                true -> tr(" (away from its owner)", " (lontano dal proprietario)")
                else -> ""
            }
        is Reason.MovingAccessPoint -> tr(
            "Moving access point (${mobileApKind(r.kind)}): ${r.ssid}",
            "Access point mobile (${mobileApKind(r.kind)}): ${r.ssid}",
        )
        is Reason.RotatedAddresses -> tr(
            "${r.addresses} randomised addresses linked to one device",
            "${r.addresses} indirizzi casuali collegati a un solo dispositivo",
        )
    }

    private fun mobileApKind(k: MobileAp.Kind) = when (k) {
        MobileAp.Kind.PHONE_HOTSPOT -> tr("phone hotspot", "hotspot telefono")
        MobileAp.Kind.VEHICLE -> tr("vehicle", "veicolo")
        MobileAp.Kind.CAMERA -> tr("camera", "videocamera")
        MobileAp.Kind.WIFI_DIRECT -> "Wi-Fi Direct"
    }
}
