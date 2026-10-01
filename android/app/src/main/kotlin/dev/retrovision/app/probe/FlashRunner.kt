package dev.retrovision.app.probe

import android.content.Context
import android.hardware.usb.UsbDevice
import dev.retrovision.app.Collector
import dev.retrovision.app.FlashUi
import com.hoho.android.usbserial.driver.UsbSerialPort
import dev.retrovision.core.flash.EspFlasher
import dev.retrovision.core.wire.Framing
import dev.retrovision.proto.v1.Command
import dev.retrovision.proto.v1.Envelope
import dev.retrovision.proto.v1.Reboot
import dev.retrovision.core.flash.FlashException
import dev.retrovision.core.flash.ResetStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Flashes a bundled firmware image over the same USB cable the probe streams on.
 * Keeps running if the UI goes away, but the app must stay in the foreground:
 * Android may freeze a background process in the middle of a write.
 */
object FlashRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Flashes whichever of [images] matches the chip found on the board. */
    fun start(ctx: Context, images: List<FirmwareImage>) {
        if (Collector.flash.value.running || images.isEmpty()) return
        Collector.flash.value = FlashUi(running = true, stage = "Preparazione")
        scope.launch { run(ctx.applicationContext, images) }
    }

    private fun log(msg: String) {
        val f = Collector.flash.value
        Collector.flash.value = f.copy(log = (f.log + msg).takeLast(40))
    }

    private fun stage(stage: String, done: Long = 0, total: Long = 0) {
        Collector.flash.value = Collector.flash.value.copy(stage = stage, done = done, total = total)
    }

    private suspend fun run(ctx: Context, images: List<FirmwareImage>) {
        val usb = UsbAccess(ctx)
        try {
            // 1. If a probe is streaming, ask it to reboot into the ROM bootloader first.
            val live = Collector.session
            if (live != null) {
                stage("Riavvio della sonda in modalità download")
                live.rebootProbe(intoBootloader = true)
                delay(500)
            }
            Collector.usbPaused.set(true)
            delay(1500) // let the connection loop release the port and the device re-enumerate

            // 2. Find the board and get permission.
            stage("Ricerca scheda")
            var dev = waitForDevice(usb) ?: throw FlashException("Nessuna scheda USB trovata")
            if (!usb.ensurePermission(dev)) throw FlashException("Permesso USB negato")
            log("Dispositivo: ${dev.productName ?: "USB"} (%04x:%04x)".format(dev.vendorId, dev.productId))

            var port = openPort(usb, dev)
            try {
                var flasher = flasherFor(port)
                stage("Connessione al bootloader")

                // (a) Maybe the board is already in download mode.
                var ok = trySync(flasher, 4)

                // (b) Ask the probe firmware to reboot into download mode. This works with no
                //     handshake (docs/protocol.md §5) and does not depend on DTR/RTS behaving.
                //     Only the native-USB chips can do it: a classic ESP32 needs the GPIO0 strap.
                if (!ok && UsbIds.isNativeUsb(dev)) {
                    log("Riavvio in download mode via firmware")
                    runCatching { port.write(rebootFrame(), 1000) }
                    runCatching { port.close() }
                    delay(2000) // the chip reboots and USB re-enumerates
                    dev = waitForDevice(usb) ?: throw FlashException("La scheda non è tornata dopo il riavvio")
                    if (!usb.ensurePermission(dev)) throw FlashException("Permesso USB negato")
                    port = openPort(usb, dev)
                    flasher = flasherFor(port)
                    ok = trySync(flasher, 8)
                }

                // (c) Classic DTR/RTS reset (UART boards, or firmware that is not running).
                if (!ok) {
                    log("Reset via DTR/RTS")
                    val style = if (UsbIds.isNativeUsb(dev)) ResetStyle.USB_JTAG else ResetStyle.CLASSIC
                    flasher.resetIntoBootloader(style)
                    ok = trySync(flasher, 10)
                }
                if (!ok) {
                    throw FlashException(
                        "Nessuna risposta dal bootloader ROM. Tieni premuto BOOT, premi RESET, rilascia BOOT e riprova.",
                    )
                }

                val chip = flasher.detectChip()
                val image = images.firstOrNull { it.chip == chip }
                    ?: throw FlashException("Questa app non contiene firmware per ${chip.label}")
                log("Scheda: ${chip.label} → firmware ${image.id} @0x%x".format(image.offset))
                val flashSize = EspFlasher.flashSizeFromHeader(image.data, headerAt = 0)
                    ?: throw FlashException("Immagine firmware non valida (header)")
                val flasher2 = flasher
                val md5 = flasher2.writeImage(image.data, image.offset, image.chip, flashSize)
                log("MD5 verificato: $md5")
                stage("Riavvio")
                flasher2.hardReset()
            } finally {
                runCatching { port.close() }
            }
            Collector.flash.value = Collector.flash.value.copy(running = false, success = true, stage = "Completato")
        } catch (e: FlashException) {
            Collector.flash.value = Collector.flash.value.copy(running = false, error = e.message ?: "Errore", stage = "Errore")
        } catch (e: Exception) {
            Collector.flash.value = Collector.flash.value.copy(
                running = false, error = "${e.javaClass.simpleName}: ${e.message}", stage = "Errore",
            )
        } finally {
            Collector.usbPaused.set(false)
        }
    }

    private fun flasherFor(port: UsbSerialPort) = EspFlasher(
        UsbSerialLink(port),
        progress = { s, d, t -> stage(s, d, t) },
        log = { log(it) },
    )

    private fun trySync(f: EspFlasher, attempts: Int): Boolean =
        try {
            f.sync(attempts)
            true
        } catch (_: FlashException) {
            false
        } catch (_: java.io.IOException) {
            false
        }

    private suspend fun openPort(usb: UsbAccess, dev: UsbDevice): UsbSerialPort =
        withContext(Dispatchers.IO) { usb.openPort(dev) }
            ?: throw FlashException("Impossibile aprire la porta seriale")

    /** Leading 0x00 flushes any half-sent frame on the probe side. */
    private fun rebootFrame(): ByteArray {
        val env = Envelope.newBuilder().setSeq(1).setCommand(
            Command.newBuilder().setReboot(Reboot.newBuilder().setIntoBootloader(true)),
        ).build()
        return byteArrayOf(0) + Framing.encode(env.toByteArray())
    }

    private suspend fun waitForDevice(usb: UsbAccess): UsbDevice? {
        repeat(40) {
            usb.findProbe()?.let { return it }
            delay(250)
        }
        return null
    }
}
