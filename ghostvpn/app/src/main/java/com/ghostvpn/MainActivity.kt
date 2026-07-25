package com.ghostvpn

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.libbox.Libbox

class MainActivity : AppCompatActivity() {

    private lateinit var etConnection: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private var isRunning = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etConnection = findViewById(R.id.etConnection)
        btnConnect = findViewById(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        btnConnect.setOnClickListener {
            if (!isRunning) conectar() else desconectar()
        }
    }

    private fun conectar() {
        val text = etConnection.text.toString().trim()
        var server = "149.33.19.164"
        var port = 80
        var user = "Charly100"
        var pass = ""

        if (text.contains("@")) {
            val parts = text.split("@")
            val sp = parts[0].split(":")
            if (sp.size >= 2) { server = sp[0]; port = sp[1].toIntOrNull() ?: 80 }
            val up = parts[1].split(":")
            if (up.size >= 1) user = up[0]
            if (up.size >= 2) pass = up[1]
        }

        isRunning = true
        btnConnect.text = "DISCONNECT"
        tvStatus.text = "Conectando..."
        log("Conectando a $server:$port...")

        Thread {
            try {
                val err = Libbox.startHTTPCustomTunnel(server, port.toLong(), user, pass, "", 1080L, 2L)
                handler.post {
                    if (err != null) {
                        log("Error: $err")
                        resetUI()
                    } else {
                        log("Conectado! SOCKS5 en 127.0.0.1:1080")
                        tvStatus.text = "Conectado"
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    log("Excepción: ${e.message}")
                    resetUI()
                }
            }
        }.start()
    }

    private fun desconectar() {
        log("Deteniendo...")
        Thread {
            Libbox.stopHTTPCustomTunnel()
            handler.post { resetUI() }
        }.start()
    }

    private fun resetUI() {
        isRunning = false
        btnConnect.text = "CONNECT"
        tvStatus.text = "⏸ Desconectado"
    }

    private fun log(msg: String) {
        tvLog.append("$msg\n")
    }
}
