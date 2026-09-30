package com.example.thermalbridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class BridgeService : Service() {
    private var server: Server? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("bridge", "Print bridge", NotificationManager.IMPORTANCE_LOW))
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "bridge") else Notification.Builder(this))
            .setContentTitle("Thermal Bridge running")
            .setContentText("Listening on 127.0.0.1:$PORT")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, n)
        if (server == null) server = Server(this).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        return START_STICKY
    }

    override fun onDestroy() { server?.stop(); server = null; super.onDestroy() }

    companion object { const val PORT = 9101 }

    private class Server(val ctx: Context) : NanoHTTPD("127.0.0.1", PORT) {
        private fun cors(r: Response): Response {
            r.addHeader("Access-Control-Allow-Origin", "*")
            r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            r.addHeader("Access-Control-Allow-Headers", "Content-Type")
            r.addHeader("Access-Control-Allow-Private-Network", "true")
            return r
        }
        private fun json(status: Response.IStatus, body: String) =
            cors(newFixedLengthResponse(status, "application/json", body))

        override fun serve(s: IHTTPSession): Response {
            if (s.method == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
            if (s.uri == "/status") return json(Response.Status.OK, """{"ok":true}""")
            if (s.uri != "/print" || s.method != Method.POST)
                return json(Response.Status.NOT_FOUND, """{"ok":false,"error":"not found"}""")
            return try {
                val files = HashMap<String, String>()
                s.parseBody(files)
                val j = JSONObject(files["postData"] ?: "{}")
                val target = ctx.getSharedPreferences("p", MODE_PRIVATE).getString("target", null)
                    ?: return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"no printer selected in app"}""")
                val bytes = if (j.has("raw")) android.util.Base64.decode(j.getString("raw"), android.util.Base64.DEFAULT)
                else Printer.buildText(j.optString("text"), j.optString("align", "left"),
                    j.optBoolean("bold", false), j.optInt("feed", 3), j.optBoolean("cut", true))
                Printer.send(ctx, target, bytes)
                json(Response.Status.OK, """{"ok":true}""")
            } catch (e: Exception) {
                json(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", e.message ?: "error").toString())
            }
        }
    }
}
