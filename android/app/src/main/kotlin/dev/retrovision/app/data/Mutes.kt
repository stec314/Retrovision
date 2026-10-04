// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.data

import dev.retrovision.core.analysis.EntityReport
import dev.retrovision.core.identity.MacTrust
import dev.retrovision.core.identity.NotableKind

/**
 * Silencing a whole type of device for a while ("Samsung SmartTag-type", "Find Hub tag").
 *
 * "It's mine" works by address, and a device whose address rotates comes back under a new one:
 * your own phone, watch or earbuds that take part in a finding network keep reappearing. A type
 * mute stops their alerts and notifications for a set time. The cost is explicit: a real tag of
 * the same type planted on you is silenced too for that time. Muted devices stay in Devices.
 */
object Mutes {
    class Mute(val key: String, val label: String, val untilMs: Long)

    /** The type a report belongs to, or null when there is none worth muting (stable address: use "It's mine"). */
    fun typeKey(r: EntityReport): String? {
        if (r.macTrust == MacTrust.STABLE) return null
        r.tracker?.let { return "tracker:${it.name}" }
        r.notable.firstOrNull { it.kind == NotableKind.FINDER }?.let { return "notable:${it.id}" }
        return null
    }

    fun typeLabel(r: EntityReport): String =
        r.tracker?.label ?: r.notable.firstOrNull { it.kind == NotableKind.FINDER }?.name ?: "?"

    fun active(prefs: Prefs, now: Long = System.currentTimeMillis()): List<Mute> =
        prefs.mutedTypes.mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 3) return@mapNotNull null
            val until = p[1].toLongOrNull() ?: return@mapNotNull null
            if (until <= now) null else Mute(p[0], p.drop(2).joinToString("|"), until)
        }

    fun isMuted(r: EntityReport, prefs: Prefs, now: Long = System.currentTimeMillis()): Boolean {
        val k = typeKey(r) ?: return false
        return active(prefs, now).any { it.key == k }
    }

    fun mute(prefs: Prefs, key: String, label: String, untilMs: Long) {
        val keep = active(prefs).filter { it.key != key }.map { "${it.key}|${it.untilMs}|${it.label}" }
        prefs.mutedTypes = (keep + "$key|$untilMs|$label").toSet()
    }

    fun unmute(prefs: Prefs, key: String) {
        prefs.mutedTypes = active(prefs).filter { it.key != key }.map { "${it.key}|${it.untilMs}|${it.label}" }.toSet()
    }
}
