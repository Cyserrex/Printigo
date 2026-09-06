package com.escpr.usbprint.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.escpr.usbprint.escpr.PrinterSink
import java.io.Closeable

/**
 * Sambungan ke printer USB kelas 7 (Printer Class) lewat USB Host / OTG.
 *
 * Data cetak dikirim ke bulk-OUT endpoint. Bulk-IN dipakai untuk membaca
 * status balik dari printer, dan control transfer dipakai untuk membaca
 * IEEE-1284 Device ID yang memberi tahu bahasa apa saja yang printer mengerti.
 */
class UsbPrinter private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointOut: UsbEndpoint,
    private val endpointIn: UsbEndpoint?
) : Closeable {

    @Volatile
    private var closed = false

    /**
     * IEEE-1284 Device ID, mis.
     * "MFG:EPSON;CMD:ESCPL2,BDC,D4,D4PX,ESCPR7;MDL:L3110 Series;..."
     *
     * Kalau bagian CMD memuat ESCPR, printer ini pasti bisa dicetaki aplikasi ini.
     */
    fun readDeviceId(): String? {
        val buffer = ByteArray(1024)
        val length = connection.controlTransfer(
            0xA1,                                   // IN | Class | Interface
            0,                                      // GET_DEVICE_ID
            0,                                      // indeks konfigurasi
            usbInterface.id shl 8,                  // interface << 8 | alt setting
            buffer, buffer.size, 3000
        )
        if (length < 2) return null

        // Dua byte pertama adalah panjang total, big-endian, termasuk dirinya sendiri.
        val declared = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
        val end = declared.coerceIn(2, length)
        return String(buffer, 2, end - 2, Charsets.US_ASCII).trim()
    }

    fun supportsEscpR(): Boolean {
        val id = runCatching { readDeviceId() }.getOrNull() ?: return false
        return id.uppercase().contains("ESCPR")
    }

    /** Mengirim seluruh [length] byte; melempar [PrinterException] kalau gagal. */
    fun writeBulk(data: ByteArray, offset: Int, length: Int, timeoutMs: Int = 15000) {
        if (closed) throw PrinterException(
            PrinterErrorKind.DISCONNECTED, "Sambungan printer sudah ditutup"
        )

        var sent = 0
        val chunk = ByteArray(minOf(length, TRANSFER_CHUNK))
        while (sent < length) {
            val n = minOf(chunk.size, length - sent)
            System.arraycopy(data, offset + sent, chunk, 0, n)

            var transferred = connection.bulkTransfer(endpointOut, chunk, n, timeoutMs)
            if (transferred < 0) {
                // Satu kali percobaan ulang: printer kadang menahan buffer saat
                // sedang menarik kertas atau membersihkan head.
                transferred = connection.bulkTransfer(endpointOut, chunk, n, timeoutMs)
            }
            if (transferred < 0) {
                throw PrinterException(
                    PrinterErrorKind.TRANSFER_FAILED,
                    "Transfer USB gagal setelah $sent dari $length byte"
                )
            }
            if (transferred < n) {
                // Terkirim sebagian: geser sisanya pada iterasi berikutnya.
                sent += transferred
            } else {
                sent += n
            }
        }
    }

    /** Membaca balasan status printer, kalau ada. Tidak memblokir lama. */
    fun readStatus(timeoutMs: Int = 300): ByteArray {
        val ep = endpointIn ?: return ByteArray(0)
        val buffer = ByteArray(ep.maxPacketSize.coerceAtLeast(64))
        val n = connection.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
        return if (n > 0) buffer.copyOf(n) else ByteArray(0)
    }

    fun sink(): PrinterSink = BufferedUsbSink(this)

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        const val EPSON_VENDOR_ID = 0x04B8

        /** 16 KB per transfer: kompromi antara jumlah syscall dan pemakaian memori. */
        private const val TRANSFER_CHUNK = 16 * 1024

        /** Perangkat USB terpasang yang tampak seperti printer. */
        fun findPrinters(manager: UsbManager): List<UsbDevice> =
            manager.deviceList.values.filter { device ->
                printerInterface(device) != null || device.vendorId == EPSON_VENDOR_ID
            }

        private fun printerInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) return iface
            }
            return null
        }

        /**
         * Membuka dan mengunci antarmuka printer. Pemanggil harus sudah punya izin
         * USB untuk [device], jika tidak openDevice akan mengembalikan null.
         */
        fun open(manager: UsbManager, device: UsbDevice): UsbPrinter {
            val iface = printerInterface(device)
                ?: throw PrinterException(
                    PrinterErrorKind.NOT_A_PRINTER,
                    "Perangkat ${device.productName ?: device.deviceName} tidak punya " +
                        "antarmuka printer (USB class 7)."
                )

            var endpointOut: UsbEndpoint? = null
            var endpointIn: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_OUT && endpointOut == null) endpointOut = ep
                if (ep.direction == UsbConstants.USB_DIR_IN && endpointIn == null) endpointIn = ep
            }
            val out = endpointOut ?: throw PrinterException(
                PrinterErrorKind.NO_ENDPOINT, "Printer tidak punya bulk endpoint keluar"
            )

            val connection = manager.openDevice(device)
                ?: throw PrinterException(
                    PrinterErrorKind.PERMISSION_DENIED,
                    "Tidak bisa membuka perangkat: izin USB belum diberikan"
                )

            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw PrinterException(
                    PrinterErrorKind.BUSY, "Antarmuka printer sedang dipakai proses lain"
                )
            }

            return UsbPrinter(device, connection, iface, out, endpointIn)
        }
    }
}

/**
 * Menumpuk tulisan kecil-kecil menjadi transfer USB berukuran penuh.
 * Tanpa ini, setiap baris raster jadi satu transfer dan kecepatannya anjlok.
 */
private class BufferedUsbSink(
    private val printer: UsbPrinter,
    bufferSize: Int = 16 * 1024
) : PrinterSink {

    private val buffer = ByteArray(bufferSize)
    private var used = 0

    var bytesSent: Long = 0
        private set

    override fun write(data: ByteArray, offset: Int, length: Int) {
        if (length >= buffer.size) {
            flush()
            printer.writeBulk(data, offset, length)
            bytesSent += length
            return
        }
        if (used + length > buffer.size) flush()
        System.arraycopy(data, offset, buffer, used, length)
        used += length
    }

    override fun flush() {
        if (used == 0) return
        printer.writeBulk(buffer, 0, used)
        bytesSent += used
        used = 0
    }

    override fun close() {
        flush()
    }
}
