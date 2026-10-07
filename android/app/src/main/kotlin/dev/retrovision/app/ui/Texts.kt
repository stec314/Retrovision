// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
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
    // Channel names show in system settings and to anyone handling the phone: kept neutral.
    fun channelAlerts() = tr("Alerts", "Avvisi")
    fun threat(t: dev.retrovision.core.analysis.WifiThreats.Threat): String = when (t.kind) {
        dev.retrovision.core.analysis.WifiThreats.Kind.DEAUTH_FLOOD ->
            tr("${t.count} deauth/disassoc frames — someone is forcing devices off a network.",
               "${t.count} frame deauth/disassoc — qualcuno sta buttando i dispositivi fuori da una rete.")
        dev.retrovision.core.analysis.WifiThreats.Kind.KARMA_AP ->
            tr("An AP answered for ${t.distinctSsids} different networks — a fake hotspot impersonating known Wi-Fi.",
               "Un AP ha risposto per ${t.distinctSsids} reti diverse — un hotspot fasullo che impersona reti note.")
        dev.retrovision.core.analysis.WifiThreats.Kind.EVIL_TWIN_OWN ->
            tr("Your network “${t.ssid}” is advertised by ${t.bssids.size} access points — one may be a clone.",
               "La tua rete “${t.ssid}” è annunciata da ${t.bssids.size} access point — uno potrebbe essere un clone.")
        dev.retrovision.core.analysis.WifiThreats.Kind.BEACON_FLOOD ->
            tr("${t.count} fake networks (${t.distinctSsids} names) appeared at once from one transmitter — beacon spam (Marauder, mdk4…).",
               "${t.count} reti fasulle (${t.distinctSsids} nomi) comparse insieme da un solo trasmettitore — beacon spam (Marauder, mdk4…).")
        dev.retrovision.core.analysis.WifiThreats.Kind.BLE_SPAM ->
            tr("${t.count} short-lived Bluetooth addresses sending pairing pop-ups from one transmitter — BLE spam (Flipper Zero or ESP32).",
               "${t.count} indirizzi Bluetooth usa-e-getta che inviano popup di abbinamento da un solo trasmettitore — BLE spam (Flipper Zero o ESP32).")
    }

    fun linkVia(v: dev.retrovision.core.analysis.LinkVia) = when (v) {
        dev.retrovision.core.analysis.LinkVia.ORIGINAL -> tr("first address", "primo indirizzo")
        dev.retrovision.core.analysis.LinkVia.SEQUENCE -> tr(
            "linked: same probe fingerprint, frame counter continued, similar signal",
            "collegato: stessa impronta delle richieste, contatore dei frame proseguito, segnale simile",
        )
        dev.retrovision.core.analysis.LinkVia.BLE_NAME -> tr(
            "linked: same distinctive Bluetooth name and advert, right after the previous address",
            "collegato: stesso nome Bluetooth distintivo e stesso annuncio, subito dopo l'indirizzo precedente",
        )
        dev.retrovision.core.analysis.LinkVia.BLE_HANDOVER -> tr(
            "probably the same device: identical advert from a new address, seconds after the previous one went quiet",
            "probabilmente lo stesso dispositivo: annuncio identico da un nuovo indirizzo, pochi secondi dopo che il precedente ha taciuto",
        )
        dev.retrovision.core.analysis.LinkVia.RARE_NETWORKS -> tr(
            "linked: asks for the same rare networks",
            "collegato: cerca le stesse reti rare",
        )
        dev.retrovision.core.analysis.LinkVia.AP_UPTIME -> tr(
            "linked: same access point (same boot moment), new name or address",
            "collegato: stesso access point (stesso momento di accensione), nuovo nome o indirizzo",
        )
    }

    /** Longer explanation for the alert detail: what it is, the evidence, and how it can be wrong. */
    fun threatExplain(t: dev.retrovision.core.analysis.WifiThreats.Threat): String = when (t.kind) {
        dev.retrovision.core.analysis.WifiThreats.Kind.DEAUTH_FLOOD -> tr(
            "Deauthentication/disassociation frames disconnect devices from a network. Normal traffic has a few, spread over many networks; here ${t.count} target one network. Usually done to push phones onto a fake copy (evil twin) or to capture reconnections. Can be wrong: a misconfigured or overloaded router kicking its own clients.",
            "I frame di deautenticazione/disassociazione scollegano i dispositivi da una rete. Nel traffico normale sono pochi e sparsi su molte reti; qui ${t.count} colpiscono una sola rete. Di solito serve a spingere i telefoni verso una copia falsa (evil twin) o a catturare le riconnessioni. Può sbagliare: un router mal configurato o sovraccarico che butta fuori i suoi client.",
        )
        dev.retrovision.core.analysis.WifiThreats.Kind.KARMA_AP -> tr(
            "One access point answered probe requests for ${t.distinctSsids} different network names: it pretends to be whatever network a phone is looking for, so phones join it automatically. Can be wrong: rarely, a hotspot gateway with many configured names.",
            "Un access point ha risposto a richieste per ${t.distinctSsids} nomi di rete diversi: finge di essere qualunque rete il telefono cerchi, così i telefoni si collegano da soli. Può sbagliare: raramente, un gateway con molti nomi configurati.",
        )
        dev.retrovision.core.analysis.WifiThreats.Kind.EVIL_TWIN_OWN -> tr(
            "Your network name is advertised by ${t.bssids.size} different access points. If you have only one router, one of them is a copy. Can be wrong: mesh nodes and extenders of your own network look exactly like this.",
            "Il nome della tua rete è annunciato da ${t.bssids.size} access point diversi. Se hai un solo router, uno è una copia. Può sbagliare: nodi mesh e ripetitori della tua rete appaiono esattamente così.",
        )
        dev.retrovision.core.analysis.WifiThreats.Kind.BEACON_FLOOD -> tr(
            "${t.count} networks (${t.distinctSsids} names) appeared within a minute on channel ${t.channel}, from a transmitter near you (median ${t.rssi} dBm), ${"%.0f".format(t.templateShare * 100)}% with the same beacon template, from ${t.radios} radio(s). Flood tools fill phone Wi-Fi lists with fake names. Can be wrong: a building full of identical access points switching on at once.",
            "${t.count} reti (${t.distinctSsids} nomi) comparse entro un minuto sul canale ${t.channel}, da un trasmettitore vicino (mediana ${t.rssi} dBm), il ${"%.0f".format(t.templateShare * 100)}% con lo stesso modello di beacon, da ${t.radios} radio. Gli strumenti di flood riempiono l'elenco Wi-Fi dei telefoni di nomi falsi. Può sbagliare: un edificio pieno di access point identici che si accendono insieme.",
        )
        dev.retrovision.core.analysis.WifiThreats.Kind.BLE_SPAM -> tr(
            "${t.count} Bluetooth addresses, each alive only a few seconds, all sending pairing pop-ups at nearly the same signal: one transmitter cycling fake identities to flood phones with pop-ups. Can be wrong: rarely, a shop demo of many earbuds.",
            "${t.count} indirizzi Bluetooth, ognuno vivo pochi secondi, tutti con popup di abbinamento e segnale quasi uguale: un solo trasmettitore che ruota identità finte per inondare i telefoni di popup. Può sbagliare: raramente, una vetrina con molti auricolari in demo.",
        )
    }

    fun threatTitle(k: dev.retrovision.core.analysis.WifiThreats.Kind) = when (k) {
        dev.retrovision.core.analysis.WifiThreats.Kind.DEAUTH_FLOOD -> tr("Wi-Fi deauth attack", "Attacco Wi-Fi deauth")
        dev.retrovision.core.analysis.WifiThreats.Kind.KARMA_AP -> tr("Fake Wi-Fi access point", "Access point Wi-Fi fasullo")
        dev.retrovision.core.analysis.WifiThreats.Kind.EVIL_TWIN_OWN -> tr("Clone of your network", "Clone della tua rete")
        dev.retrovision.core.analysis.WifiThreats.Kind.BEACON_FLOOD -> tr("Wi-Fi beacon spam", "Beacon spam Wi-Fi")
        dev.retrovision.core.analysis.WifiThreats.Kind.BLE_SPAM -> tr("Bluetooth spam attack", "Attacco BLE spam")
    }

    fun drone(d: dev.retrovision.core.analysis.Drones.Drone): String {
        val ago = ((System.currentTimeMillis() - d.lastMs) / 60_000).let { if (it < 1) tr("now", "ora") else tr("$it min ago", "$it min fa") }
        if (!d.remoteId) return tr(
            "${d.label} — drone/controller radio, no Remote ID, position unknown (strongest ${d.maxRssi} dBm, $ago)",
            "${d.label} — radio di drone/radiocomando, senza Remote ID, posizione ignota (max ${d.maxRssi} dBm, $ago)",
        )
        val sb = StringBuilder(d.label)
        sb.append(" (").append(dev.retrovision.core.identity.RemoteId.uaTypeName(d.uaType)).append(")")
        val dm = d.distanceM
        val db = d.bearingDeg
        if (dm != null && db != null) {
            sb.append(" — ").append(dist(dm)).append(" ").append(dev.retrovision.core.analysis.Drones.compass(db))
        } else if (d.lat != null) {
            sb.append(tr(" — position known, yours is not", " — posizione nota, la tua no"))
        } else {
            sb.append(tr(" — no position yet", " — posizione non ancora ricevuta"))
        }
        d.heightM?.let { sb.append(tr(", ${it.toInt()} m up", ", a ${it.toInt()} m di quota")) }
        val om = d.operatorDistanceM
        val ob = d.operatorBearingDeg
        if (om != null && ob != null) {
            sb.append(tr("; pilot ", "; pilota ")).append(dist(om)).append(" ")
                .append(dev.retrovision.core.analysis.Drones.compass(ob))
        }
        sb.append(" · ").append(ago)
        return sb.toString()
    }

    private fun dist(m: Double) = if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000)

    fun appleActivity(a: dev.retrovision.core.identity.PayloadDecoder.AppleActivity) = when (a) {
        dev.retrovision.core.identity.PayloadDecoder.AppleActivity.LOCKED -> tr("idle, screen locked", "inattivo, schermo bloccato")
        dev.retrovision.core.identity.PayloadDecoder.AppleActivity.AUDIO -> tr("playing audio, screen locked", "audio in riproduzione, schermo bloccato")
        dev.retrovision.core.identity.PayloadDecoder.AppleActivity.SCREEN_ON -> tr("screen on, in use", "schermo acceso, in uso")
        dev.retrovision.core.identity.PayloadDecoder.AppleActivity.VIDEO -> tr("playing video", "video in riproduzione")
        dev.retrovision.core.identity.PayloadDecoder.AppleActivity.WATCH_ON_WRIST -> tr("Watch on wrist", "Watch al polso")
        dev.retrovision.core.identity.PayloadDecoder.AppleActivity.RECENT -> tr("used a moment ago", "usato da poco")
        dev.retrovision.core.identity.PayloadDecoder.AppleActivity.DRIVING -> tr("driving", "alla guida")
        dev.retrovision.core.identity.PayloadDecoder.AppleActivity.CALL -> tr("in a call", "in chiamata")
        dev.retrovision.core.identity.PayloadDecoder.AppleActivity.OTHER -> tr("other", "altro")
    }

    fun notableKind(k: dev.retrovision.core.identity.NotableKind) = when (k) {
        dev.retrovision.core.identity.NotableKind.HACKING -> tr("pentest tool", "strumento di pentest")
        dev.retrovision.core.identity.NotableKind.SURVEILLANCE -> tr("surveillance / plate camera", "videosorveglianza / lettura targhe")
        dev.retrovision.core.identity.NotableKind.LAW_ENFORCEMENT -> tr("body cam / police gear", "bodycam / dotazione di polizia")
        dev.retrovision.core.identity.NotableKind.GLASSES -> tr("camera glasses", "occhiali con fotocamera")
        dev.retrovision.core.identity.NotableKind.RECORDER -> tr("recording pendant", "registratore indossabile")
        dev.retrovision.core.identity.NotableKind.DRONE -> tr("drone", "drone")
        dev.retrovision.core.identity.NotableKind.VEHICLE -> tr("vehicle", "veicolo")
        dev.retrovision.core.identity.NotableKind.FINDER -> tr("item finder tag", "tag localizzatore")
        dev.retrovision.core.identity.NotableKind.MESH -> tr("off-grid mesh radio", "radio mesh fuori rete")
        dev.retrovision.core.identity.NotableKind.HOTSPOT -> tr("phone hotspot", "hotspot di un telefono")
    }

    fun channelAlertsSilent() = tr("Alerts (silent)", "Avvisi (silenziosi)")
    fun stop() = tr("Stop", "Ferma")
    fun cannotOpenPort() = tr("Cannot open the serial port", "Impossibile aprire la porta seriale")
    fun alertTitle(label: String) = tr("Seen with you again: $label", "Ti segue? $label")

    /** Shown on the lock screen and in discreet mode: says nothing about what was found. */
    fun publicAlert() = tr("Something to check", "Qualcosa da controllare")

    fun cap(c: dev.retrovision.core.analysis.ScoreCap) = when (c) {
        dev.retrovision.core.analysis.ScoreCap.FEW_PLACES -> tr("heard at fewer than 2 places of yours (max 0.30)", "sentito in meno di 2 tuoi luoghi (max 0,30)")
        dev.retrovision.core.analysis.ScoreCap.STAYS_PUT -> tr("it stays in one spot (max 0.35)", "resta in un punto fisso (max 0,35)")
        dev.retrovision.core.analysis.ScoreCap.ONE_AREA -> tr("access point only heard around one area (max 0.45)", "access point sentito solo in una zona (max 0,45)")
        dev.retrovision.core.analysis.ScoreCap.RESIDENT -> tr("it belongs to your routine places (max 0.25)", "appartiene ai tuoi luoghi di routine (max 0,25)")
        dev.retrovision.core.analysis.ScoreCap.ONE_STRETCH -> tr("rotating address heard in one unbroken stretch (max 0.60)", "indirizzo che cambia, sentito in un solo tratto continuo (max 0,60)")
    }

    fun level(l: dev.retrovision.core.analysis.Level) = when (l) {
        dev.retrovision.core.analysis.Level.STRONG -> tr("Strong signs", "Segnali forti")
        dev.retrovision.core.analysis.Level.WORTH_A_LOOK -> tr("Worth a look", "Da guardare")
        dev.retrovision.core.analysis.Level.SOME -> tr("Some signs", "Qualche segnale")
        dev.retrovision.core.analysis.Level.LOW -> tr("Low", "Basso")
    }

    fun levelIcon(l: dev.retrovision.core.analysis.Level) = when (l) {
        dev.retrovision.core.analysis.Level.STRONG -> "⚠️"
        dev.retrovision.core.analysis.Level.WORTH_A_LOOK -> "👀"
        dev.retrovision.core.analysis.Level.SOME -> "·"
        dev.retrovision.core.analysis.Level.LOW -> ""
    }

    fun verdictTitle(s: dev.retrovision.core.analysis.Verdict.State) = when (s) {
        dev.retrovision.core.analysis.Verdict.State.STOPPED -> tr("Not collecting", "Raccolta ferma")
        dev.retrovision.core.analysis.Verdict.State.CANT_TELL -> tr("Can't tell yet", "Non posso dirlo ancora")
        dev.retrovision.core.analysis.Verdict.State.CLEAR -> tr("Nothing found", "Nessun problema")
        dev.retrovision.core.analysis.Verdict.State.CLEAR_PARTIAL -> tr("Nothing found, limited view", "Nessun problema, vista parziale")
        dev.retrovision.core.analysis.Verdict.State.WORTH_A_LOOK -> tr("Worth a look", "Da guardare")
        dev.retrovision.core.analysis.Verdict.State.STRONG -> tr("Strong signs", "Segnali forti")
    }

    fun verdictIcon(s: dev.retrovision.core.analysis.Verdict.State) = when (s) {
        dev.retrovision.core.analysis.Verdict.State.STOPPED -> "⏸"
        dev.retrovision.core.analysis.Verdict.State.CANT_TELL -> "⏳"
        dev.retrovision.core.analysis.Verdict.State.CLEAR -> "✅"
        dev.retrovision.core.analysis.Verdict.State.CLEAR_PARTIAL -> "◐"
        dev.retrovision.core.analysis.Verdict.State.WORTH_A_LOOK -> "👀"
        dev.retrovision.core.analysis.Verdict.State.STRONG -> "⚠️"
    }

    fun verdictLine(r: dev.retrovision.core.analysis.Verdict.Result) = when (r.state) {
        dev.retrovision.core.analysis.Verdict.State.STOPPED -> tr("Start collecting to check your surroundings.", "Avvia la raccolta per controllare cosa hai intorno.")
        dev.retrovision.core.analysis.Verdict.State.CANT_TELL -> tr("Not enough data yet to say anything. See what's missing below.", "Non ci sono ancora dati sufficienti per dire qualcosa. Sotto vedi cosa manca.")
        dev.retrovision.core.analysis.Verdict.State.CLEAR -> tr("Nothing has been moving with you in the analysed time.", "Niente si è mosso con te nel periodo analizzato.")
        dev.retrovision.core.analysis.Verdict.State.CLEAR_PARTIAL -> tr("Nothing found in what the app can see, but part of the picture is missing.", "Niente in ciò che l'app vede, ma manca una parte del quadro.")
        else -> {
            val n = r.strong + r.worth
            tr("$n finding(s): tap to see the evidence.", "$n risultati: tocca per vedere le prove.")
        }
    }

    fun gap(g: dev.retrovision.core.analysis.Verdict.Gap) = when (g) {
        dev.retrovision.core.analysis.Verdict.Gap.NO_RECEIVER -> tr("Nothing is listening: plug in the probe or turn on the phone's Bluetooth receiver", "Nessun ricevitore attivo: collega la sonda o attiva il Bluetooth del telefono")
        dev.retrovision.core.analysis.Verdict.Gap.NO_PROBE -> tr("Probe not connected: Wi-Fi is not heard, Bluetooth only from the phone", "Sonda non collegata: il Wi-Fi non si sente, il Bluetooth solo dal telefono")
        dev.retrovision.core.analysis.Verdict.Gap.NO_GPS -> tr("No GPS: places and routes are paused", "Niente GPS: luoghi e percorsi sono in pausa")
        dev.retrovision.core.analysis.Verdict.Gap.POOR_GPS -> tr("GPS too imprecise: places are paused", "GPS troppo impreciso: luoghi in pausa")
        dev.retrovision.core.analysis.Verdict.Gap.TOO_SHORT -> tr("Collecting for a short time: following takes 10–20 min to show", "Raccolta da poco: un pedinamento si vede dopo 10–20 min")
        dev.retrovision.core.analysis.Verdict.Gap.NO_ANALYSIS -> tr("Analysis not up to date yet", "Analisi non ancora aggiornata")
        dev.retrovision.core.analysis.Verdict.Gap.TRUNCATED -> tr("Too many devices: only the most recent part of the window is analysed", "Troppi dispositivi: si analizza solo la parte più recente della finestra")
    }


    fun entityLabel(r: EntityReport): String {
        val addr = r.addresses.first()
        val vendor = (r.bleCompanyId?.let { Vendors.forCompany(it) } ?: Vendors.forMac(addr))
            ?.let { shorten(it) }
        val v = if (vendor != null) " · $vendor" else ""
        val base = when (r.kind) {
            EntityKind.WIFI_AP -> (r.ssids.firstOrNull() ?: tr("(hidden network)", "(rete nascosta)")) + " · $addr$v"
            EntityKind.WIFI_CLIENT -> tr("Wi-Fi device", "Dispositivo Wi-Fi") + " $addr$v"
            EntityKind.BLE_TRACKER -> (r.tracker?.label ?: "Tracker") + " · $addr"
            EntityKind.BLE_DEVICE -> (r.bleName?.let { "“${shorten(it)}”" } ?: r.model ?: tr("Bluetooth device", "Dispositivo Bluetooth")) + " $addr$v"
        }
        return when {
            r.isDrone -> "🛸 " + (r.droneId ?: r.notable.firstOrNull()?.name ?: tr("Drone", "Drone")) + " · $addr"
            r.notable.any { it.kind.attention } -> "👁 " + r.notable.first { it.kind.attention }.name + " · " + base
            r.notable.isNotEmpty() -> when (r.notable.first().kind) {
                dev.retrovision.core.identity.NotableKind.VEHICLE -> "🚗 "
                dev.retrovision.core.identity.NotableKind.FINDER -> "🏷 "
                dev.retrovision.core.identity.NotableKind.MESH -> "📻 "
                else -> "📱 "
            } + r.notable.first().name + " · " + base
            else -> base
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
        Reason.KnownAtRoutine -> tr(
            "Belongs to your routine places (seen there over several days)",
            "Appartiene ai tuoi luoghi di routine (visto lì per più giorni)",
        )
        is Reason.SeenAcrossPeriods -> tr(
            "Reappeared in ${r.periods} different time periods",
            "Ricomparso in ${r.periods} fasce orarie diverse",
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
        is Reason.JoinedAfterYou -> tr(
            "Arrived after you and left with you at ${r.stops} stop(s)",
            "Arrivato dopo di te e ripartito con te in ${r.stops} sost${if (r.stops == 1) "a" else "e"}",
        )
        is Reason.StayedThroughTurns -> tr(
            "Stayed with you through ${r.turns} of your ${r.of} turns",
            "Rimasto con te in ${r.turns} delle tue ${r.of} svolte",
        )
        is Reason.ProbesForYourNetwork -> tr(
            "Away from your routine places, it asked for YOUR network by name (${r.ssids.joinToString()}): it has been on it",
            "Lontano dai tuoi luoghi abituali ha cercato per nome la TUA rete (${r.ssids.joinToString()}): ci è già stato collegato",
        )
        is Reason.LinkedByNetworks -> tr(
            "${r.addresses} rotating addresses linked: they ask for the same ${r.sharedSsids} rare networks",
            "${r.addresses} indirizzi casuali collegati: cercano le stesse ${r.sharedSsids} reti rare",
        )
        is Reason.SameApRenamed -> tr(
            "Same access point under a new name or address (same boot moment): “${r.from}” → “${r.to}”",
            "Stesso access point con nuovo nome o indirizzo (stesso istante di accensione): “${r.from}” → “${r.to}”",
        )
        is Reason.OneAreaOnly -> tr(
            "Access point only heard within ~${r.extentM.toInt()} m of one area. A fixed router fits that; a follower would have to be heard farther apart than its range (≥ 600 m). No alert until it is",
            "Access point sentito solo entro ~${r.extentM.toInt()} m da una zona. Un router fisso è compatibile; chi ti segue dovrebbe essere sentito a distanze maggiori della sua portata (≥ 600 m). Nessuna allerta finché non succede",
        )
        is Reason.OneStretchOnly -> tr(
            "Heard in one unbroken stretch of ${r.minutes} min with an address that rotates: it was near you (same car or train, a car alongside, or one of your own devices), but nothing shows it coming back after a break",
            "Sentito in un solo tratto continuo di ${r.minutes} min con un indirizzo che cambia: ti era vicino (stessa auto o treno, un'auto accanto, o un tuo dispositivo), ma niente mostra che sia tornato dopo una pausa",
        )
        is Reason.ApUptime -> tr(
            "Running for ${"%.0f".format(r.days)} days without a reboot (beacon clock): typical of a fixed router",
            "Acceso da ${"%.0f".format(r.days)} giorni senza riavvii (orologio del beacon): tipico di un router fisso",
        )
        is Reason.StaysPut -> tr(
            "Stays in one spot: its signal fades as you walk away from one point (heard within ~${r.reachM.toInt()} m). A fixed device you keep passing, not one moving with you",
            "Resta in un punto fisso: il segnale cala man mano che ti allontani da un punto (sentito entro ~${r.reachM.toInt()} m). Un dispositivo fisso vicino a cui continui a passare, non uno che si muove con te",
        )
        is Reason.TravelsInGroup -> tr(
            "Moves together with ${r.size - 1} other device(s): same places, same times",
            "Si muove insieme ad altri ${r.size - 1} dispositivi: stessi luoghi, stessi orari",
        )
        is Reason.ReportsDriving -> tr("Its Apple device said its owner was driving: it is in a vehicle", "Il suo dispositivo Apple ha detto che il proprietario stava guidando: è in un veicolo")
        is Reason.Notable -> tr(
            "Looks like: ${r.name} (${notableKind(r.kind)}) — a pattern match, not proof",
            "Sembra: ${r.name} (${notableKind(r.kind)}) — somiglianza, non prova",
        )
        is Reason.Drone -> if (r.remoteId) tr(
            "Drone broadcasting Remote ID" + (r.id?.let { " · $it" } ?: ""),
            "Drone che trasmette Remote ID" + (r.id?.let { " · $it" } ?: ""),
        ) else tr("Drone or drone controller radio (no Remote ID)", "Radio di drone o radiocomando (senza Remote ID)")
    }

    private fun mobileApKind(k: MobileAp.Kind) = when (k) {
        MobileAp.Kind.PHONE_HOTSPOT -> tr("phone hotspot", "hotspot telefono")
        MobileAp.Kind.VEHICLE -> tr("vehicle", "veicolo")
        MobileAp.Kind.CAMERA -> tr("camera", "videocamera")
        MobileAp.Kind.WIFI_DIRECT -> "Wi-Fi Direct"
    }
}
