package dev.retrovision.app.probe

import android.content.Context
import android.hardware.usb.UsbDevice
import dev.retrovision.app.Collector
import dev.retrovision.app.FlashUi
import dev.retrovision.core.flash.EspFlasher
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

    fun start(ctx: Context, image: FirmwareImage) {
        if (Collector.flash.value.running) return
        Collector.flash.value = FlashUi(running = true, stage = "Preparazione")
        scope.launch { run(ctx.applicationContext, image) }
    }

    private fun log(msg: String) {
        val f = Collector.flash.value
        Collector.flash.value = f.copy(log = (f.log + msg).takeLast(40))
    }

    private fun stage(stage: String, done: Long = 0, total: Long = 0) {
        Collector.flash.value = Collector.flash.value.copy(stage = stage, done = done, total = total)
    }

    private suspend fun run(ctx: Context, image: FirmwareImage) {
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
            val dev = waitForDevice(usb) ?: throw FlashException("Nessuna scheda USB trovata")
            if (!usb.ensurePermission(dev)) throw FlashException("Permesso USB negato")
            log("Dispositivo: ${dev.productName ?: "USB"} (%04x:%04x)".format(dev.vendorId, dev.productId))

            val port = withContext(Dispatchers.IO) { usb.openPort(dev) }
                ?: throw FlashException("Impossibile aprire la porta seriale")
            try {
                val link = UsbSerialLink(port)
                val flasher = EspFlasher(
                    link,
                    progress = { s, d, t -> stage(s, d, t) },
                    log = { log(it) },
                )
                val style = if (UsbIds.isNativeUsb(dev)) ResetStyle.USB_JTAG else ResetStyle.CLASSIC
                stage("Connessione al bootloader")
                try {
                    flasher.sync(attempts = 4)
                } catch (_: FlashException) {
                    // Not in download mode yet (e.g. firmware not running, or UART board): toggle the reset lines.
                    log("Reset via DTR/RTS")
                    flasher.resetIntoBootloader(style)
                    flasher.sync()
                }
                val flashSize = EspFlasher.flashSizeFromHeader(image.data, headerAt = 0)
                    ?: throw FlashException("Immagine firmware non valida (header)")
                val md5 = flasher.writeImage(image.data, image.offset, image.chip, flashSize)
                log("MD5 verificato: $md5")
                stage("Riavvio")
                flasher.hardReset()
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

    private suspend fun waitForDevice(usb: UsbAccess): UsbDevice? {
        repeat(40) {
            usb.findProbe()?.let { return it }
            delay(250)
        }
        return null
    }
}
