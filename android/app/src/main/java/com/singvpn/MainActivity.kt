package com.singvpn

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.libbox.Libbox

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
            layout.addView(TextView(this).apply { text = label; textSize = 14f })
            val et = EditText(this).apply { setHint(hint); setText(def); setEms(12) }
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

        statusText = TextView(this).apply {
            text = "\u23F8 Desconectado"; textSize = 16f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        logText = TextView(this).apply { text = ""; textSize = 11f }
        val scroll = ScrollView(this).apply { addView(logText) }

        val btn = Button(this).apply { text = "Conectar" }
        btn.setOnClickListener {
            if (!isRunning) {
                isRunning = true
                btn.text = "Desconectar"
                statusText.text = "Conectando..."
                logText.text = ""
                val s = serverField.text.toString()
                val p = portField.text.toString().toIntOrNull() ?: 80
                val u = userField.text.toString()
                val pw = passField.text.toString()
                val pl = payloadField.text.toString()
                Thread {
                    try {
                        logText.append("Conectando a $s:$p...\n")
                        val err = Libbox.startHTTPCustomTunnel(s, p, u, pw, pl, 1080)
                        if (err != null) {
                            logText.append("\u274C Error: $err\n")
                            isRunning = false
                            btn.text = "Conectar"
                            statusText.text = "\u23F8 Desconectado"
                        } else {
                            logText.append("Conectado! SOCKS5 en 127.0.0.1:1080\n")
                            statusText.text = "Conectado"
                        }
                    } catch (e: Exception) {
                        logText.append("\u274C ${e.message}\n")
                        isRunning = false
                        btn.text = "Conectar"
                        statusText.text = "\u23F8 Desconectado"
                    }
                }.start()
            } else {
                isRunning = false
                btn.text = "Conectar"
                statusText.text = "\u23F8 Desconectado"
                Thread { Libbox.stopHTTPCustomTunnel() }.start()
            }
        }

        layout.addView(statusText)
        layout.addView(btn)
        layout.addView(scroll)
        setContentView(layout)
    }
}
