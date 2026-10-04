// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
// Apple Continuity and Google Fast Pair layouts and the model tables are adapted from Fieldwatch
// (AdvPayloadDecoder, FastPairModels), (c) 2026 Off Grid Pete LLC, MIT License,
// https://github.com/OffGridPete/Fieldwatch
package dev.retrovision.core.identity

/**
 * What a nearby phone, earbuds or accessory says about itself beyond its name: the AirPods/Beats
 * model (Apple Proximity Pairing), the Fast Pair model (Android accessories), and what an Apple
 * device is doing (Apple Nearby Info: screen on, call, *driving*). Only published or widely
 * reverse-engineered layouts; anything else is left alone.
 */
object PayloadDecoder {
    /** Apple Nearby Info activity, the low nibble of its first byte. */
    enum class AppleActivity { LOCKED, AUDIO, SCREEN_ON, VIDEO, WATCH_ON_WRIST, RECENT, DRIVING, CALL, OTHER }

    class Decoded(val model: String?, val activity: AppleActivity?)

    private const val APPLE = 0x004C
    private const val FAST_PAIR = 0xFE2C

    fun decode(info: AdvertisementInfo): Decoded? {
        var model: String? = null
        var activity: AppleActivity? = null
        if (info.manufacturerId == APPLE) {
            val b = info.manufacturerData ?: return null
            var i = 0
            while (i + 2 <= b.size) {
                val type = b[i].toInt() and 0xFF
                val len = b[i + 1].toInt() and 0xFF
                if (len <= 0 || i + 2 + len > b.size) break
                val d = b.copyOfRange(i + 2, i + 2 + len)
                when (type) {
                    0x07 -> model = airPods(d) ?: model
                    0x10 -> activity = nearbyInfo(d) ?: activity
                }
                i += 2 + len
            }
        }
        info.serviceData16[FAST_PAIR]?.let { d ->
            // 3 bytes = model id (pairing mode); longer = account data, no model.
            if (d.size == 3) {
                val id = ((d[0].toInt() and 0xFF) shl 16) or ((d[1].toInt() and 0xFF) shl 8) or (d[2].toInt() and 0xFF)
                model = FAST_PAIR_MODELS[id] ?: model
            }
        }
        return if (model == null && activity == null) null else Decoded(model, activity)
    }

    private fun airPods(d: ByteArray): String? {
        val start = if (d.isNotEmpty() && d[0] == 0x01.toByte()) 1 else 0
        if (d.size < start + 2) return null
        val id = ((d[start].toInt() and 0xFF) shl 8) or (d[start + 1].toInt() and 0xFF)
        return APPLE_AUDIO[id]
    }

    private fun nearbyInfo(d: ByteArray): AppleActivity? {
        if (d.isEmpty()) return null
        return when (d[0].toInt() and 0x0F) {
            0x03 -> AppleActivity.LOCKED
            0x05 -> AppleActivity.AUDIO
            0x07 -> AppleActivity.SCREEN_ON
            0x09 -> AppleActivity.VIDEO
            0x0A -> AppleActivity.WATCH_ON_WRIST
            0x0B -> AppleActivity.RECENT
            0x0D -> AppleActivity.DRIVING
            0x0E -> AppleActivity.CALL
            else -> AppleActivity.OTHER
        }
    }

    private val APPLE_AUDIO = mapOf(
        0x0220 to "AirPods (1st generation)", 0x0F20 to "AirPods (2nd generation)", 0x1320 to "AirPods (3rd generation)",
        0x1920 to "AirPods (4th generation)", 0x1C20 to "AirPods 4", 0x0E20 to "AirPods Pro", 0x1420 to "AirPods Pro (2nd generation)",
        0x2420 to "AirPods Pro 2 (USB-C)", 0x1F20 to "AirPods Max", 0x2D20 to "AirPods Max 2", 0x0A20 to "Beats Solo3",
        0x0B20 to "Powerbeats 3", 0x0C20 to "Beats Studio Buds", 0x0D20 to "Beats Fit Pro", 0x1020 to "Powerbeats Pro",
        0x1120 to "Beats Studio Buds +", 0x1220 to "Beats Solo Pro", 0x1720 to "Beats Flex", 0x1A20 to "Beats Studio Pro",
        0x1B20 to "Beats Fit Pro", 0x0520 to "BeatsX", 0x0920 to "Beats Studio3 Wireless", 0x1620 to "Beats Studio Buds +",
        0x2520 to "Beats Solo 4", 0x2620 to "Beats Solo Buds", 0x3820 to "Beats 360", 0x038F to "Beats Studio Buds",
    )

    private val FAST_PAIR_MODELS: Map<Int, String> = hashMapOf(
        0x000006 to "Google Pixel Buds",
        0x000007 to "Android Auto",
        0x00000B to "Google Gphones",
        0x00000C to "Google Set Up Device",
        0x00000F to "Google Pixel Buds A-Series",
        0x000035 to "Fast Pair test device",
        0x000047 to "Arduino 101",
        0x000048 to "Fast Pair Headphones",
        0x000049 to "Fast Pair Headphones",
        0x0000F0 to "Bose QuietComfort 35 II",
        0x0001F0 to "Bisto CSR8670",
        0x0002F0 to "JBL Everest 110GA",
        0x0003F0 to "LG HBS-835S",
        0x001000 to "LG HBS1110",
        0x002000 to "AIAIAI TMA-2",
        0x003000 to "Libratone Q Adapt On-Ear",
        0x003001 to "Libratone Q Adapt On-Ear",
        0x003B41 to "M&D MW65",
        0x003D8A to "Cleer FLOW II",
        0x005BC3 to "Panasonic RP-HD610N",
        0x008F7D to "soundcore Glow Mini",
        0x00A168 to "boAt Airdopes 621",
        0x00AA48 to "Jabra Elite 2",
        0x00AA91 to "Beoplay E8 2.0",
        0x00C95C to "Sony WF-1000X",
        0x00FA72 to "Pioneer SE-MS9BN",
        0x0100F0 to "Bose QuietComfort 35 II",
        0x011242 to "Nirvana Ion",
        0x013D8A to "Cleer EDGE Voice",
        0x01AA91 to "Beoplay H9 3rd gen",
        0x01C95C to "Sony WF-1000X",
        0x01EEB4 to "Sony WH-1000XM4",
        0x02AA91 to "B&O Earset",
        0x02C95C to "Sony WH-1000XM2",
        0x02D815 to "Audio-Technica ATH-CK1TW",
        0x02D886 to "JBL Reflect Mini NC",
        0x02DD4F to "JBL Tune 770NC",
        0x02E2A9 to "TCL MOVEAUDIO S200",
        0x02F637 to "JBL Live Flex",
        0x035754 to "Plantronics PLT K2",
        0x035764 to "Plantronics V8200",
        0x038B91 to "DENON AH-C830NCW",
        0x038CC7 to "JBL Tune 760NC",
        0x038F16 to "Beats Studio Buds",
        0x03AA91 to "B&O Beoplay H8i",
        0x03C95C to "Sony WH-1000XM2",
        0x03C99C to "Moto Buds 135",
        0x045754 to "Plantronics PLT K2",
        0x04AA91 to "Beoplay H4",
        0x04ACFC to "JBL Wave Beam",
        0x04AFB8 to "JBL Tune 720BT",
        0x04C95C to "Sony WI-1000X",
        0x050F0C to "Marshall Major III Voice",
        0x052CC7 to "Marshall Minor III",
        0x054B2D to "JBL Tune 125TWS",
        0x0577B1 to "Galaxy S23 Ultra",
        0x057802 to "TicWatch Pro 5",
        0x0582FD to "Google Pixel Buds",
        0x058D08 to "Sony WH-1000XM4",
        0x05A963 to "Wonderboom 3",
        0x05A9BC to "Galaxy S20+",
        0x05AA91 to "B&O Beoplay E6",
        0x05C452 to "JBL Live 220BT",
        0x05C95C to "Sony WI-1000X",
        0x060000 to "Google Pixel Buds",
        0x06AE20 to "Galaxy S21 5G",
        0x06C197 to "OPPO Enco Air3 Pro",
        0x06C95C to "Sony WH-1000XM2",
        0x06D8FC to "soundcore Liberty 4 NC",
        0x0660D7 to "JBL Live 770NC",
        0x0744B6 to "Technics EAH-AZ60M2",
        0x07A41C to "Sony WF-C700N",
        0x07C95C to "Sony WH-1000XM2",
        0x07F426 to "Nest Hub Max",
        0x0E30C3 to "Razer Hammerhead TWS",
        0x2D7A23 to "Sony WF-1000XM4",
        0x718FA4 to "JBL Live 300TWS",
        0x72EF8D to "Razer Hammerhead TWS X",
        0x72FB00 to "soundcore Spirit Pro",
        0x821F66 to "JBL Flip 6",
        0x92BBBD to "Google Pixel Buds",
        0xCD8256 to "Bose Noise Cancelling 700",
        0xD446A7 to "Sony WH-1000XM5",
        0xF00000 to "Bose QuietComfort 35 II",
        0xF00200 to "JBL Everest 110GA",
        0xF00203 to "JBL Everest 310GA",
        0xF00207 to "JBL Everest 710GA",
        0xF00209 to "JBL Live 400BT",
        0xF0020E to "JBL Live 500BT",
        0xF00213 to "JBL Live 650BTNC",
        0xF00300 to "LG HBS-835S",
        0xF00301 to "LG HBS-835",
        0xF00302 to "LG HBS-830",
        0xF00303 to "LG HBS-930",
        0xF00304 to "LG HBS-1010",
        0xF00305 to "LG HBS-1500",
        0xF00306 to "LG HBS-1700",
        0xF00307 to "LG HBS-1120",
        0xF00308 to "LG HBS-1125",
        0xF00309 to "LG HBS-2000",
        0xF00002 to "Bose QuietComfort Earbuds II",
        0xF0B77F to "soundcore Liberty 4 NC",
        0xF52494 to "JBL Buds Pro",
        0x30018E to "Google Pixel Buds Pro 2",
        0x9D3F8A to "soundcore Liberty 4",
        0xAE3989 to "Xiaomi Redmi Buds 5 Pro",
        0xD0A72C to "Nothing Ear (a)",
        0xD446F9 to "Jabra Elite 8 Active",
        0xD5BC6B to "Sony WH-1000XM6",
        0xD97EBA to "OnePlus Nord Buds 3 Pro",
    )
}
