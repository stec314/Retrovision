// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.flash

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Byte-level pieces of the ESP ROM serial bootloader protocol. */
object EspProtocol {
    const val CMD_FLASH_BEGIN = 0x02
    const val CMD_SYNC = 0x08
    const val CMD_READ_REG = 0x0A
    const val CMD_SPI_SET_PARAMS = 0x0B
    const val CMD_SPI_ATTACH = 0x0D
    const val CMD_FLASH_DEFL_BEGIN = 0x10
    const val CMD_FLASH_DEFL_DATA = 0x11
    const val CMD_FLASH_DEFL_END = 0x12
    const val CMD_SPI_FLASH_MD5 = 0x13

    /** Returns a security-info struct; on newer chips (C5/C6…) it carries the chip id used to detect them. */
    const val CMD_GET_SECURITY_INFO = 0x14

    const val DIR_REQUEST = 0x00
    const val DIR_RESPONSE = 0x01
    const val CHECKSUM_SEED = 0xEF

    /** Flash write block size of the ROM loader. */
    const val BLOCK_SIZE = 0x400

    fun checksum(data: ByteArray): Int {
        var c = CHECKSUM_SEED
        for (b in data) c = c xor (b.toInt() and 0xFF)
        return c
    }

    fun request(cmd: Int, data: ByteArray, checksum: Int = 0): ByteArray {
        val bb = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(DIR_REQUEST.toByte()).put(cmd.toByte()).putShort(data.size.toShort()).putInt(checksum)
        bb.put(data)
        return bb.array()
    }

    fun words(vararg w: Int): ByteArray {
        val bb = ByteBuffer.allocate(w.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in w) bb.putInt(x)
        return bb.array()
    }

    /** One parsed response frame. [data] excludes nothing: status bytes are still in it. */
    class Response(val cmd: Int, val value: Int, val data: ByteArray)

    fun parseResponse(frame: ByteArray): Response? {
        if (frame.size < 8 || (frame[0].toInt() and 0xFF) != DIR_RESPONSE) return null
        val bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.get(1).toInt() and 0xFF
        val size = bb.getShort(2).toInt() and 0xFFFF
        val value = bb.getInt(4)
        if (frame.size < 8 + size) return null
        return Response(cmd, value, frame.copyOfRange(8, 8 + size))
    }

    val SYNC_PAYLOAD: ByteArray = ByteArray(36).also {
        it[0] = 0x07; it[1] = 0x07; it[2] = 0x12; it[3] = 0x20
        for (i in 4 until 36) it[i] = 0x55
    }
}
