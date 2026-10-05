// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.identity

/**
 * What a device says about itself that survives its address rotating, for "ignore this kind of
 * advert" (your own phone, watch, earbuds). Ignoring by address does not work for these: an own
 * phone advertising its name took a new address every 8–9 minutes, 40 in one day, and each
 * ignore expired within minutes.
 *
 * Only adverts with a local name qualify, and the signature is the name plus the coarse shape
 * (16-bit service UUIDs, company id, appearance): never the payload, which rotates. Trackers never
 * get one: a tag planted on you also travels with you all day, and silencing a whole class of
 * trackers is exactly what a stalker would want. Two unrelated devices with the same name and
 * shape share a signature, so the UI says so when you choose it; a device could also copy your
 * name on purpose, which is why it stays your choice per signature and is listed where you can
 * undo it.
 */
object BleSignature {
    /** Prefix for signature rows in the ignore list (which otherwise stores entity ids). */
    const val IGNORE_PREFIX = "sig:"

    fun of(advData: ByteArray): String? {
        if (advData.isEmpty()) return null
        val info = AdvertisementInfo.of(advData)
        val name = info.name?.trim().orEmpty()
        if (name.isEmpty()) return null
        if (TrackerClassifier.classify(info) != null) return null
        val uuids = (info.serviceUuids16 + info.serviceData16.keys).toSortedSet()
        val sb = StringBuilder(48)
        sb.append("n=").append(name)
        info.manufacturerId?.let { sb.append("|m=").append("%04x".format(it)) }
        if (uuids.isNotEmpty()) sb.append("|u=").append(uuids.joinToString(",") { "%04x".format(it) })
        info.appearance?.let { sb.append("|a=").append(it) }
        return sb.toString()
    }

    /** The name part of a signature, for showing it ("n=Stefano|u=1805" -> "Stefano"). */
    fun displayName(signature: String): String =
        signature.substringAfter("n=").substringBefore('|')
}
