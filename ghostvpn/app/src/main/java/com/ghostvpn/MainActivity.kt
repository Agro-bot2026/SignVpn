package com.ghostvpn

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.libbox.Libbox

class MainActivity : AppCompatActivity() {

    private lateinit var etConnection: EditText
    private lateinit var etPayload: EditText
    private lateinit var spMode: Spinner
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private var isRunning = false
    private var currentMode = 2L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etConnection = findViewById(R.id.etConnection)
        etPayload = findViewById(R.id.etPayload)
        spMode = findViewById(R.id.spMode)
        btnConnect = findViewById(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        // Default payload
        etPayload.setText(
            "CONNECT / HTTP/1.1[crlf]Host: recargas.personal.com.ar[crlf][crlf]" +
            "[split][crlf][crlf]GET / HTTP/1.1[crlf]Host: recargas.personal.com.ar[lf][lf]" +
            "GET /vpsx HTTP/1.1[crlf]Host:[rotate=cdn1.panda2.fun]" +
            "[lf]Backend: vps146[lf]Connection: Upgrade[lf]Upgrade: websocket[lf]" +
            "User-Agent: Googlebot/2.1[lf][lf]"
        )

        // Mode selector
        val modes = arrayOf("0 - SSH Direct", "1 - SSH+Proxy", "2 - SSH WebSocket",
            "3 - SSL+Proxy", "4 - SSL Direct")
        spMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        spMode.setSelection(2) // default WebSocket
        spMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentMode = pos.toLong()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

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

        val payload = etPayload.text.toString().trim()

        isRunning = true
        btnConnect.text = "DISCONNECT"
        tvStatus.text = "Conectando..."
        log("Conectando modo $currentMode a $server:$port...")

        Thread {
            try {
                val err = Libbox.startHTTPCustomTunnel(server, port.toLong(), user, pass, payload, 1080L, currentMode)
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
