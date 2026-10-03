// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.flash

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Deflater

/** Transport under the flasher: a serial port with DTR/RTS. */
interface SerialLink {
    fun write(data: ByteArray)

    /** Reads up to buf.size bytes; returns the count, 0 on timeout. */
    fun read(buf: ByteArray, timeoutMs: Int): Int

    /**
     * DTR and RTS are set one at a time, in the caller's order: the USB-Serial-JTAG reset logic
     * reacts to the sequence of intermediate states, so the order is part of the protocol.
     */
    fun setDtr(on: Boolean)

    fun setRts(on: Boolean)

    fun discardInput()
}

class FlashException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * @param magic values read at register 0x40001000 (older chips). Empty for chips that esptool marks
 *   `USES_MAGIC_VALUE = False` (ESP32-C5/C6): those have no magic and are identified by [imageChipId].
 * @param imageChipId the chip id reported by GET_SECURITY_INFO (esptool `IMAGE_CHIP_ID`).
 */
enum class Chip(
    val label: String,
    internal val magic: Set<Int>,
    internal val beginHasEncryptWord: Boolean,
    internal val imageChipId: Int,
) {
    ESP32("ESP32", setOf(0x00F01D83), false, 0),
    ESP32_S2("ESP32-S2", setOf(0x000007C6), true, 2),
    ESP32_S3("ESP32-S3", setOf(0x00000009), true, 9),
    ESP32_C3("ESP32-C3", setOf(0x6921506F, 0x1B31506F, 0x4881606F, 0x4361606F), true, 5),
    // EXPERIMENTAL, verified on one real board (2026-10-03): the C5 has no magic register value; it is detected by its
    // chip id (23) via GET_SECURITY_INFO. Its bootloader lives at 0x2000 (see the manifest offset).
    ESP32_C5("ESP32-C5", emptySet(), true, 23),
    ;

    companion object {
        fun fromMagic(m: Int): Chip? = entries.firstOrNull { it.magic.isNotEmpty() && m in it.magic }
        /** Only for chips without a magic value: a stray id 0 must never be read as a classic ESP32. */
        fun fromChipId(id: Int): Chip? = entries.firstOrNull { it.magic.isEmpty() && it.imageChipId == id }
        fun fromId(id: String): Chip? = entries.firstOrNull {
            it.label.replace("-", "").equals(id.replace("-", "").replace("_", ""), ignoreCase = true)
        }
    }
}

enum class ResetStyle {
    /** Native USB-Serial-JTAG (ESP32-S3 etc.): esptool's USBJTAGSerialReset sequence. */
    USB_JTAG,

    /** DTR=IO0, RTS=EN wiring used by CH340/CP210x dev boards. */
    CLASSIC,
}

fun interface FlashProgress {
    fun onProgress(stage: String, done: Long, total: Long)
}

/**
 * Talks to the ESP ROM serial bootloader (no stub loader) and writes one image.
 *
 * Safety checks before anything is erased: the detected chip must match the
 * image's target chip, and after writing the flash MD5 must match the image.
 * The probe firmware is never touched unless those pass up to that point.
 */
class EspFlasher(
    private val link: SerialLink,
    private val progress: FlashProgress = FlashProgress { _, _, _ -> },
    private val log: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val slip = SlipDecoder()
    private val readBuf = ByteArray(4096)
    private val pending = ArrayDeque<ByteArray>()

    // ---- reset ---------------------------------------------------------------

    /** Same line sequences as esptool (USBJTAGSerialReset / ClassicReset), step for step. */
    fun resetIntoBootloader(style: ResetStyle) {
        when (style) {
            ResetStyle.USB_JTAG -> {
                link.setRts(false); link.setDtr(false); sleep(100)
                link.setDtr(true); link.setRts(false); sleep(100)
                link.setRts(true); link.setDtr(false); link.setRts(true); sleep(100)
                link.setRts(false); link.setDtr(false)
            }
            ResetStyle.CLASSIC -> {
                link.setDtr(false); link.setRts(true); sleep(100)
                link.setDtr(true); link.setRts(false); sleep(50)
                link.setDtr(false)
            }
        }
        sleep(100)
        link.discardInput()
    }

    fun hardReset() {
        link.setDtr(false); link.setRts(true); sleep(100)
        link.setRts(false)
    }

    // ---- connect -------------------------------------------------------------

    /** Sends SYNC until the ROM answers. Call after the chip is in download mode. */
    fun sync(attempts: Int = 15) {
        repeat(attempts) {
            link.discardInput(); pending.clear()
            write(EspProtocol.CMD_SYNC, EspProtocol.SYNC_PAYLOAD)
            val r = waitResponse(EspProtocol.CMD_SYNC, 150)
            if (r != null) {
                // The ROM answers a SYNC several times; swallow the rest.
                sleep(50)
                link.discardInput(); pending.clear()
                return
            }
        }
        throw FlashException("No answer from the ROM bootloader (is the board in download mode?)")
    }

    fun detectChip(): Chip {
        // Existing chips keep their exact path: magic register first (ESP32/S2/S3/C3).
        val magic = runCatching { readReg(0x40001000) }.getOrNull()
        magic?.let { Chip.fromMagic(it)?.let { c -> return c } }
        // No magic matched: newer chips (ESP32-C5…) report USES_MAGIC_VALUE=False and are
        // identified by the chip id in GET_SECURITY_INFO. EXPERIMENTAL, verified on one real C5.
        val chipId = securityChipId()
        chipId?.let { Chip.fromChipId(it)?.let { c -> return c } }
        throw FlashException(
            "Unknown chip (magic 0x${Integer.toHexString(magic ?: 0)}, chip id ${chipId ?: "n/a"})",
        )
    }

    /**
     * Reads the chip id from the ROM's GET_SECURITY_INFO response, or null if the chip does not
     * support it (classic ESP32) or does not report a chip id (ESP32-S2). The security-info struct
     * is flags(4) · flash_crypt_cnt(1) · key_purposes(7) · chip_id(4) · api_version(4); chip_id is
     * the little-endian word at offset 12 and is only present when the struct is ≥ 20 bytes.
     */
    fun securityChipId(): Int? {
        val r = runCatching { command(EspProtocol.CMD_GET_SECURITY_INFO, ByteArray(0), timeoutMs = 3000) }.getOrNull()
            ?: return null
        val d = r.data
        if (d.size < 20) return null // error response or an S2-style struct without a chip id
        return (d[12].toInt() and 0xFF) or ((d[13].toInt() and 0xFF) shl 8) or
            ((d[14].toInt() and 0xFF) shl 16) or ((d[15].toInt() and 0xFF) shl 24)
    }

    fun readReg(addr: Int): Int {
        val r = command(EspProtocol.CMD_READ_REG, EspProtocol.words(addr), timeoutMs = 3000)
        checkStatus(r, 0, "read reg")
        return r.value
    }

    // ---- write ---------------------------------------------------------------

    /**
     * Writes [image] at [offset] and verifies it by MD5. Returns the verified MD5 (hex).
     * @param flashSizeBytes size declared to the ROM's SPI layer (from the image header).
     */
    fun writeImage(image: ByteArray, offset: Int, target: Chip, flashSizeBytes: Int): String {
        val chip = detectChip()
        if (chip != target) {
            throw FlashException("Wrong chip: this image is for ${target.label}, board is ${chip.label}")
        }
        log("Chip: ${chip.label}")
        if (offset + image.size > flashSizeBytes) {
            throw FlashException("Image does not fit in ${flashSizeBytes / 1024} KiB flash")
        }

        val padded = if (image.size % 4 == 0) image else image.copyOf(image.size + 4 - image.size % 4).also {
            for (i in image.size until it.size) it[i] = 0xFF.toByte()
        }

        check(command(EspProtocol.CMD_SPI_ATTACH, EspProtocol.words(0, 0), timeoutMs = 3000), 0, "spi attach")
        check(
            command(
                EspProtocol.CMD_SPI_SET_PARAMS,
                EspProtocol.words(0, flashSizeBytes, 64 * 1024, 4 * 1024, 256, 0xFFFF),
                timeoutMs = 3000,
            ),
            0, "spi set params",
        )

        val compressed = deflate(padded)
        val blocks = (compressed.size + EspProtocol.BLOCK_SIZE - 1) / EspProtocol.BLOCK_SIZE
        log("Writing ${padded.size} bytes (${compressed.size} compressed, $blocks blocks)")

        val begin = if (chip.beginHasEncryptWord) {
            EspProtocol.words(padded.size, blocks, EspProtocol.BLOCK_SIZE, offset, 0)
        } else {
            EspProtocol.words(padded.size, blocks, EspProtocol.BLOCK_SIZE, offset)
        }
        progress.onProgress("Erasing", 0, 1)
        // The ROM erases the whole region inside this call: slow, ~30 s per MB.
        check(
            command(EspProtocol.CMD_FLASH_DEFL_BEGIN, begin, timeoutMs = 10_000 + 30_000L * padded.size / (1 shl 20)),
            0, "flash begin",
        )
        progress.onProgress("Erasing", 1, 1)

        for (seq in 0 until blocks) {
            val from = seq * EspProtocol.BLOCK_SIZE
            val chunk = compressed.copyOfRange(from, minOf(from + EspProtocol.BLOCK_SIZE, compressed.size))
            val data = ByteArrayOutputStream(chunk.size + 16)
            data.write(EspProtocol.words(chunk.size, seq, 0, 0))
            data.write(chunk)
            var attempt = 0
            while (true) {
                try {
                    check(
                        command(
                            EspProtocol.CMD_FLASH_DEFL_DATA, data.toByteArray(),
                            checksum = EspProtocol.checksum(chunk), timeoutMs = 10_000,
                        ),
                        0, "flash data #$seq",
                    )
                    break
                } catch (e: FlashException) {
                    // A block that the ROM rejected for a checksum error can be resent once.
                    if (++attempt >= 2 || !e.message.orEmpty().contains("checksum")) throw e
                    log("Resending block $seq")
                }
            }
            progress.onProgress("Writing", (seq + 1).toLong(), blocks.toLong())
        }

        progress.onProgress("Verifying", 0, 1)
        val md5 = flashMd5(offset, padded.size)
        val expect = MessageDigest.getInstance("MD5").digest(padded).joinToString("") { "%02x".format(it) }
        if (!md5.equals(expect, ignoreCase = true)) {
            throw FlashException("Verification failed: flash MD5 $md5, expected $expect")
        }
        progress.onProgress("Verifying", 1, 1)

        // reboot flag 0: run the freshly written app
        runCatching { command(EspProtocol.CMD_FLASH_DEFL_END, EspProtocol.words(0), timeoutMs = 3000) }
        return expect
    }

    private fun flashMd5(offset: Int, size: Int): String {
        val r = command(
            EspProtocol.CMD_SPI_FLASH_MD5, EspProtocol.words(offset, size, 0, 0),
            timeoutMs = 10_000 + 8_000L * size / (1 shl 20),
        )
        // ROM: 32 ASCII hex chars + status. Stub: 16 raw bytes + status.
        val d = r.data
        return when {
            d.size >= 32 + 2 && d.take(32).all { it.toInt().toChar() in "0123456789abcdefABCDEF" } ->
                String(d, 0, 32, Charsets.US_ASCII)
            d.size >= 16 + 2 -> d.take(16).joinToString("") { "%02x".format(it) }
            else -> throw FlashException("Bad MD5 response")
        }
    }

    // ---- command plumbing ----------------------------------------------------

    private fun write(cmd: Int, data: ByteArray, checksum: Int = 0) {
        link.write(Slip.encode(EspProtocol.request(cmd, data, checksum)))
    }

    private fun command(cmd: Int, data: ByteArray, checksum: Int = 0, timeoutMs: Long): EspProtocol.Response {
        write(cmd, data, checksum)
        return waitResponse(cmd, timeoutMs)
            ?: throw FlashException("Timeout waiting for ROM answer to command 0x${cmd.toString(16)}")
    }

    private fun waitResponse(cmd: Int, timeoutMs: Long): EspProtocol.Response? {
        val deadline = clock() + timeoutMs
        while (true) {
            while (pending.isNotEmpty()) {
                val r = EspProtocol.parseResponse(pending.removeFirst()) ?: continue
                if (r.cmd == cmd) return r
            }
            val left = deadline - clock()
            if (left <= 0) return null
            val n = link.read(readBuf, minOf(left, 100L).toInt())
            if (n > 0) pending.addAll(slip.feed(readBuf, n))
        }
    }

    /** Status sits after [payload] bytes: [status, error] (+2 reserved on newer ROMs). */
    private fun check(r: EspProtocol.Response, payload: Int, what: String) = checkStatus(r, payload, what)

    private fun checkStatus(r: EspProtocol.Response, payload: Int, what: String) {
        if (r.data.size < payload + 2) throw FlashException("$what: short response")
        val status = r.data[payload].toInt() and 0xFF
        val err = r.data[payload + 1].toInt() and 0xFF
        if (status != 0) {
            val name = when (err) {
                0x05 -> "invalid command / checksum error"
                0x06 -> "failed to act on command"
                0x07 -> "invalid CRC (checksum)"
                0x08 -> "flash write error"
                0x09 -> "flash read error"
                0x0A -> "flash read length error"
                0x0B -> "deflate error"
                else -> "error 0x${err.toString(16)}"
            }
            throw FlashException("$what failed: $name")
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(9)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream(data.size / 4 + 64)
        val tmp = ByteArray(16 * 1024)
        while (!d.finished()) out.write(tmp, 0, d.deflate(tmp))
        d.end()
        return out.toByteArray()
    }

    companion object {
        /** Flash size declared in an ESP image header (byte 3, high nibble), or null if unknown. */
        fun flashSizeFromHeader(image: ByteArray, headerAt: Int = 0): Int? {
            if (image.size < headerAt + 4 || (image[headerAt].toInt() and 0xFF) != 0xE9) return null
            return when ((image[headerAt + 3].toInt() and 0xF0) shr 4) {
                0 -> 1 shl 20
                1 -> 2 shl 20
                2 -> 4 shl 20
                3 -> 8 shl 20
                4 -> 16 shl 20
                5 -> 32 shl 20
                6 -> 64 shl 20
                7 -> 128 shl 20
                else -> null
            }
        }
    }
}
