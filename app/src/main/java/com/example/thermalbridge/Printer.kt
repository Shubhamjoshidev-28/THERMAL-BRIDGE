package com.example.thermalbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.util.UUID

/** A selectable printer. id is "bt:<MAC>" or "usb:<vendorId>:<productId>". */
data class Target(val label: String, val id: String)

object Printer {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun listTargets(ctx: Context): List<Target> {
        val out = mutableListOf<Target>()
        val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bt?.bondedDevices?.forEach { out += Target("Bluetooth: ${it.name ?: "Unknown"} (${it.address})", "bt:${it.address}") }
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        usb.deviceList.values.forEach {
            out += Target("USB: ${it.productName ?: "Device"} (${"%04X".format(it.vendorId)}:${"%04X".format(it.productId)})",
                "usb:${it.vendorId}:${it.productId}")
        }
        return out
    }

    fun findUsb(ctx: Context, id: String): UsbDevice? {
        val parts = id.split(":")
        if (parts.size != 3) return null
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        return usb.deviceList.values.firstOrNull { it.vendorId == parts[1].toInt() && it.productId == parts[2].toInt() }
    }

    /** Builds ESC/POS bytes for simple text receipts. */
    fun buildText(text: String, align: String = "left", bold: Boolean = false,
                  feed: Int = 3, cut: Boolean = true): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0x1B, 0x40))                                   // init
        o.write(byteArrayOf(0x1B, 0x61, when (align) { "center" -> 1; "right" -> 2; else -> 0 }))
        o.write(byteArrayOf(0x1B, 0x45, if (bold) 1 else 0))
        o.write(text.toByteArray(charset("CP437")))
        o.write(ByteArray(feed) { 0x0A })
        if (cut) o.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))              // partial cut
        return o.toByteArray()
    }

    @Synchronized
    fun send(ctx: Context, id: String, data: ByteArray) {
        when {
            id.startsWith("bt:") -> sendBluetooth(ctx, id.removePrefix("bt:"), data)
            id.startsWith("usb:") -> sendUsb(ctx, id, data)
            else -> throw IllegalStateException("No printer selected")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendBluetooth(ctx: Context, mac: String, data: ByteArray) {
        val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: throw IllegalStateException("No Bluetooth")
        bt.cancelDiscovery()
        val socket = bt.getRemoteDevice(mac).createRfcommSocketToServiceRecord(SPP)
        try {
            socket.connect()
            socket.outputStream.write(data)
            socket.outputStream.flush()
            Thread.sleep(300)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendUsb(ctx: Context, id: String, data: ByteArray) {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = findUsb(ctx, id) ?: throw IllegalStateException("USB printer not connected (check OTG cable)")
        if (!usb.hasPermission(dev))
            throw IllegalStateException("USB permission missing: open the app, select the printer, tap Save printer and allow")

        // Prefer the printer-class interface (class 7); otherwise use any interface with a bulk OUT endpoint.
        var iface: UsbInterface? = null
        var ep: UsbEndpoint? = null
        for (pass in 0..1) {
            for (i in 0 until dev.interfaceCount) {
                val itf = dev.getInterface(i)
                if (pass == 0 && itf.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue
                for (e in 0 until itf.endpointCount) {
                    val endpoint = itf.getEndpoint(e)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        iface = itf; ep = endpoint; break
                    }
                }
                if (ep != null) break
            }
            if (ep != null) break
        }
        if (iface == null || ep == null) throw IllegalStateException("No USB output endpoint found on this device")

        val conn = usb.openDevice(dev) ?: throw IllegalStateException("Could not open USB device")
        try {
            if (!conn.claimInterface(iface, true)) throw IllegalStateException("Could not claim USB interface")
            var off = 0
            while (off < data.size) {
                val n = minOf(16384, data.size - off)
                val r = conn.bulkTransfer(ep, data.copyOfRange(off, off + n), n, 5000)
                if (r < 0) throw IllegalStateException("USB write failed")
                off += n
            }
            Thread.sleep(200)
        } finally {
            try { conn.releaseInterface(iface) } catch (_: Exception) {}
            conn.close()
        }
    }
}
