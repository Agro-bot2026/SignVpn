package com.singvpn

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(16, 16, 16, 16)

        fun addField(label: String, hint: String, def: String = ""): EditText {
            val tv = TextView(this).apply { text = label; textSize = 14f }
            val et = EditText(this).apply {
                setHint(hint)
                setText(def)
                setEms(12)
            }
            layout.addView(tv)
            layout.addView(et)
            return et
        }

        val serverField = addField("Servidor", "IP del proxy", "149.33.19.164")
        val portField = addField("Puerto", "80", "80")
        val userField = addField("Usuario SSH", "Charly100", "Charly100")
        val passField = addField("Contrase\u00f1a", "******")
        val payloadField = addField("Payload", "CONNECT /...")
        payloadField.setText(
            "CONNECT / HTTP/1.1[crlf]Host: recargas.personal.com.ar[crlf][crlf]" +
            "[split][crlf][crlf]GET / HTTP/1.1[crlf]Host: recargas.personal.com.ar[lf][lf]" +
            "GET /vpsx HTTP/1.1[crlf]Host:[rotate=cdn1.panda2.fun]" +
            "[lf]Backend: vps146[lf]Connection: Upgrade[lf]Upgrade: websocket[lf]" +
            "User-Agent: Googlebot/2.1[lf][lf]"
        )

        statusText = TextView(this).apply { text = "\u23F8 Desconectado"; textSize = 16f; textAlignment = TextView.TEXT_ALIGNMENT_CENTER }
        logText = TextView(this).apply { text = ""; textSize = 11f }
        val scroll = ScrollView(this).apply { addView(logText) }

        val btn = Button(this).apply { text = "Conectar" }
        btn.setOnClickListener {
            if (!isRunning) {
                isRunning = true
                btn.text = "Desconectar"
                statusText.text = "Conectando..."
                logText.text = ""
                startTunnel(
                    serverField.text.toString(),
                    portField.text.toString().toIntOrNull() ?: 80,
                    userField.text.toString(),
                    passField.text.toString(),
                    payloadField.text.toString(),
                    1080
                )
            } else {
                isRunning = false
                btn.text = "Conectar"
                statusText.text = "\u23F8 Desconectado"
                stopTunnel()
            }
        }

        layout.addView(statusText)
        layout.addView(btn)
        layout.addView(scroll)
        setContentView(layout)
    }

    private fun startTunnel(server: String, port: Int, user: String, pass: String, payload: String, socksPort: Int) {
        Thread {
            val cb = object : tunnel.TunnelCallback {
                override fun onLog(msg: String?) {
                    runOnUiThread { logText.append("$msg\n") }
                }
                override fun onStatus(status: String?) {
                    runOnUiThread { statusText.text = status }
                }
                override fun onError(err: String?) {
                    runOnUiThread { logText.append("\u274C $err\n"); statusText.text = "\u274C Error" }
                }
                override fun onConnected() {
                    runOnUiThread { statusText.text = "Conectado - SOCKS5 en 127.0.0.1:1080" }
                }
                override fun onDisconnected() {
                    runOnUiThread { statusText.text = "\u23F8 Desconectado" }
                }
            }
            tunnel.Tunnel.startTunnel(server, port, user, pass, payload, socksPort, cb)
        }.start()
    }

    private fun stopTunnel() {
        Thread { tunnel.Tunnel.stopTunnel() }.start()
    }
}
