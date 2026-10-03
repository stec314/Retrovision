// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.flash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class EspFlasherTest {
    private fun image(size: Int): ByteArray {
        val r = Random(42)
        val b = ByteArray(size)
        // Half random, half padding: exercises both incompressible and compressible data.
        r.nextBytes(b)
        for (i in size / 2 until size) b[i] = 0xFF.toByte()
        b[0] = 0xE9.toByte(); b[3] = 0x30 // magic, 8 MB flash nibble
        return b
    }

    private fun flasher(sim: RomSimulator, progress: MutableList<String> = ArrayList()) =
        EspFlasher(sim, { s, d, t -> progress += "$s $d/$t" }, {}, { sim.now += it }, { sim.now })

    @Test fun slipRoundTrip() {
        val payload = byteArrayOf(0x01, 0xC0.toByte(), 0xDB.toByte(), 0x00, 0xDC.toByte(), 0xDD.toByte())
        val frames = SlipDecoder().feed(Slip.encode(payload))
        assertEquals(1, frames.size)
        assertArrayEquals(payload, frames[0])
    }

    @Test fun slipDiscardsBannerAndSplitsFrames() {
        val a = Slip.encode(byteArrayOf(1, 2, 3))
        val b = Slip.encode(byteArrayOf(4, 5))
        val stream = "ESP-ROM:esp32s3-20210327\r\n".toByteArray() + a + b
        val d = SlipDecoder()
        val frames = ArrayList<ByteArray>()
        // Feed one byte at a time.
        for (x in stream) frames += d.feed(byteArrayOf(x))
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(4, 5), frames[1])
    }

    @Test fun flashesAndVerifies() {
        val img = image(300_001) // not a multiple of 4 or 1 KiB
        val sim = RomSimulator()
        val f = flasher(sim)
        f.sync()
        val md5 = f.writeImage(img, 0, Chip.ESP32_S3, 8 shl 20)
        assertEquals(32, md5.length)
        assertArrayEquals(img, sim.flash.copyOf(img.size))
        // padding added to reach a multiple of 4 is 0xFF
        assertEquals(0xFF.toByte(), sim.flash[img.size])
    }

    @Test fun handlesTwoByteStatusAndBanner() {
        val sim = RomSimulator(statusLen = 2, banner = "rst:0x15 (USB_UART_CHIP_RESET)\r\n".toByteArray())
        val f = flasher(sim)
        f.sync()
        f.writeImage(image(5000), 0, Chip.ESP32_S3, 8 shl 20)
    }

    @Test fun syncRetriesUntilRomAnswers() {
        val sim = RomSimulator().also { it.syncsToIgnore = 4 }
        flasher(sim).sync()
        assertTrue(sim.commands.count { it == EspProtocol.CMD_SYNC } >= 5)
    }

    @Test fun syncGivesUpWithClearError() {
        val sim = RomSimulator().also { it.syncsToIgnore = 1000 }
        try {
            flasher(sim).sync(attempts = 3)
            fail()
        } catch (e: FlashException) {
            assertTrue(e.message!!.contains("download mode"))
        }
    }

    @Test fun wrongChipIsRefusedBeforeErasing() {
        val sim = RomSimulator(chipMagic = 0x00F01D83) // classic ESP32
        val f = flasher(sim)
        f.sync()
        try {
            f.writeImage(image(4096), 0, Chip.ESP32_S3, 8 shl 20)
            fail()
        } catch (e: FlashException) {
            assertTrue(e.message!!.contains("Wrong chip"))
        }
        assertFalse(sim.erased)
    }

    @Test fun rejectedBlockIsResentOnce() {
        val sim = RomSimulator().also { it.rejectBlockOnce = 3 }
        val f = flasher(sim)
        f.sync()
        val img = image(200_000)
        f.writeImage(img, 0, Chip.ESP32_S3, 8 shl 20)
        assertArrayEquals(img, sim.flash.copyOf(img.size))
    }

    @Test fun corruptionIsCaughtByMd5() {
        val sim = RomSimulator().also { it.corruptAt = 1000 }
        val f = flasher(sim)
        f.sync()
        try {
            f.writeImage(image(50_000), 0, Chip.ESP32_S3, 8 shl 20)
            fail()
        } catch (e: FlashException) {
            assertTrue(e.message!!.contains("Verification failed"))
        }
    }

    @Test fun imageLargerThanFlashIsRefused() {
        val sim = RomSimulator(flashSize = 1 shl 20)
        val f = flasher(sim)
        f.sync()
        try {
            f.writeImage(image(2 shl 20), 0, Chip.ESP32_S3, 1 shl 20)
            fail()
        } catch (e: FlashException) {
            assertTrue(e.message!!.contains("fit"))
        }
        assertFalse(sim.erased)
    }

    @Test fun offsetWriteLeavesRestUntouched() {
        val sim = RomSimulator(chipMagic = 0x00F01D83)
        val f = flasher(sim)
        f.sync()
        val img = image(8192)
        f.writeImage(img, 0x10000, Chip.ESP32, 8 shl 20)
        assertArrayEquals(img, sim.flash.copyOfRange(0x10000, 0x10000 + img.size))
        assertEquals(0xFF.toByte(), sim.flash[0])
    }

    @Test fun c5DetectedByChipIdWhenNoMagicMatches() {
        val sim = RomSimulator(chipMagic = 0, chipId = 23)
        val f = flasher(sim)
        f.sync()
        assertEquals(Chip.ESP32_C5, f.detectChip())
        assertTrue(EspProtocol.CMD_GET_SECURITY_INFO in sim.commands)
    }

    @Test fun c5ImageWrittenAtBootloaderOffset() {
        val sim = RomSimulator(chipMagic = 0, chipId = 23)
        val f = flasher(sim)
        f.sync()
        val img = image(40_000)
        f.writeImage(img, 0x2000, Chip.ESP32_C5, 8 shl 20)
        assertArrayEquals(img, sim.flash.copyOfRange(0x2000, 0x2000 + img.size))
        assertEquals(0xFF.toByte(), sim.flash[0x1FFF])
    }

    @Test fun magicChipsNeverAskForSecurityInfo() {
        val sim = RomSimulator(chipId = 23) // S3 magic wins even if a chip id were present
        val f = flasher(sim)
        f.sync()
        assertEquals(Chip.ESP32_S3, f.detectChip())
        assertFalse(EspProtocol.CMD_GET_SECURITY_INFO in sim.commands)
    }

    @Test fun chipIdZeroIsNotAClassicEsp32() {
        val sim = RomSimulator(chipMagic = 0, chipId = 0)
        val f = flasher(sim)
        f.sync()
        try {
            f.detectChip()
            fail()
        } catch (e: FlashException) {
            assertTrue(e.message!!.contains("Unknown chip"))
        }
    }

    @Test fun unknownChipStillThrows() {
        val sim = RomSimulator(chipMagic = 0x12345678)
        val f = flasher(sim)
        f.sync()
        try {
            f.writeImage(image(4096), 0x2000, Chip.ESP32_C5, 8 shl 20)
            fail()
        } catch (e: FlashException) {
            assertTrue(e.message!!.contains("Unknown chip"))
        }
        assertFalse(sim.erased)
    }

    @Test fun flashSizeFromHeader() {
        assertEquals(8 shl 20, EspFlasher.flashSizeFromHeader(image(16)))
        assertEquals(null, EspFlasher.flashSizeFromHeader(ByteArray(16)))
    }

    @Test fun usbJtagResetFollowsEsptoolSequence() {
        val calls = ArrayList<String>()
        val link = object : SerialLink {
            override fun write(data: ByteArray) {}
            override fun read(buf: ByteArray, timeoutMs: Int) = 0
            override fun setDtr(on: Boolean) { calls += "D${if (on) 1 else 0}" }
            override fun setRts(on: Boolean) { calls += "R${if (on) 1 else 0}" }
            override fun discardInput() {}
        }
        EspFlasher(link, sleep = {}).resetIntoBootloader(ResetStyle.USB_JTAG)
        // Never passes through DTR=0,RTS=0 between "DTR=1" and the reset: that would drop the boot strap.
        assertEquals(
            listOf("R0", "D0", "D1", "R0", "R1", "D0", "R1", "R0", "D0"),
            calls,
        )
    }
}
