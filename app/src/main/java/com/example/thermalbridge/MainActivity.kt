package com.example.thermalbridge

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private var targets: List<Target> = emptyList()
    private lateinit var spinner: Spinner
    private lateinit var status: TextView
    private val ACTION_USB = "com.example.thermalbridge.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == ACTION_USB) {
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) { commitSave(); toast("USB access granted") }
                else toast("USB permission denied")
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this).apply { text = "Pair a Bluetooth printer in Android settings, or plug a USB printer with an OTG cable, then tap Refresh." }
        spinner = Spinner(this)
        val refresh = Button(this).apply { text = "Refresh printers"; setOnClickListener { loadTargets() } }
        val save = Button(this).apply { text = "Save printer"; setOnClickListener { savePrinter() } }
        val start = Button(this).apply { text = "Start bridge"; setOnClickListener { startBridge() } }
        val stop = Button(this).apply { text = "Stop bridge"; setOnClickListener { stopService(Intent(this@MainActivity, BridgeService::class.java)); status.text = "Stopped" } }
        val test = Button(this).apply { text = "Test print"; setOnClickListener { testPrint() } }
        listOf<View>(status, spinner, refresh, save, start, stop, test).forEach { root.addView(it) }
        setContentView(root)

        val f = IntentFilter(ACTION_USB)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(usbReceiver, f)
        ensurePermissions()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); loadTargets() }
    override fun onDestroy() { unregisterReceiver(usbReceiver); super.onDestroy() }

    private fun needed(): Array<String> {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) l += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        return l.toTypedArray()
    }

    private fun ensurePermissions() {
        val missing = needed().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else loadTargets()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { loadTargets() }

    private fun loadTargets() {
        targets = try { Printer.listTargets(this) } catch (e: SecurityException) { emptyList() }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, targets.map { it.label })
        val saved = getSharedPreferences("p", MODE_PRIVATE).getString("target", null)
        targets.indexOfFirst { it.id == saved }.takeIf { it >= 0 }?.let { spinner.setSelection(it) }
        if (targets.isEmpty()) status.text = "No printers found. Pair Bluetooth or connect USB via OTG, then Refresh."
    }

    private fun savePrinter() {
        val t = targets.getOrNull(spinner.selectedItemPosition) ?: return toast("No printers found")
        if (t.id.startsWith("usb:")) {
            val dev = Printer.findUsb(this, t.id) ?: return toast("USB printer not connected")
            val usb = getSystemService(Context.USB_SERVICE) as UsbManager
            if (!usb.hasPermission(dev)) {
                val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB).setPackage(packageName),
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                usb.requestPermission(dev, pi)
                pendingId = t.id
                return
            }
        }
        pendingId = t.id
        commitSave()
    }

    private var pendingId: String? = null
    private fun commitSave() {
        val id = pendingId ?: return
        getSharedPreferences("p", MODE_PRIVATE).edit().putString("target", id).apply()
        toast("Printer saved")
    }

    private fun startBridge() {
        val i = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        status.text = "Running at http://127.0.0.1:${BridgeService.PORT}"
    }

    private fun testPrint() {
        val id = getSharedPreferences("p", MODE_PRIVATE).getString("target", null) ?: return toast("Save a printer first")
        Thread {
            try { Printer.send(this, id, Printer.buildText("Thermal Bridge\nTest OK\n", "center", true)) }
            catch (e: Exception) { runOnUiThread { toast("Failed: ${e.message}") } }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
