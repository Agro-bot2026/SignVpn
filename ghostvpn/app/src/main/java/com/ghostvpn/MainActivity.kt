package com.ghostvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.libbox.Libbox
import org.json.JSONObject

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
    private val VPN_REQ = 100
    private val IMPORT_REQ = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etConnection = findViewById(R.id.etConnection)
        etPayload = findViewById(R.id.etPayload)
        spMode = findViewById(R.id.spMode)
        btnConnect = findViewById(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        // Handle .gv file opened directly
        handleIntent(intent)

        // Toolbar buttons
        findViewById<View>(R.id.btnImport).setOnClickListener { importConfig() }
        findViewById<View>(R.id.btnExport).setOnClickListener { exportConfig() }

        // Default payload (placeholder - se reemplaza al importar .gv)
        etPayload.setText("CONNECT / HTTP/1.1[crlf]Host: ejemplo.com[crlf][crlf]")

        val modes = arrayOf("0 - SSH Direct", "1 - SSH+Proxy", "2 - SSH WebSocket",
            "3 - SSL+Proxy", "4 - SSL Direct")
        spMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        spMode.setSelection(2)
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

        // Solicitar permiso VPN
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQ)
            return
        }

        startVpn(server, port, user, pass, payload)
    }

    private fun startVpn(server: String, port: Int, user: String, pass: String, payload: String) {
        isRunning = true
        btnConnect.text = "DISCONNECT"
        tvStatus.text = "Conectando..."
        log("Conectando modo $currentMode a $server:$port...")

        Thread {
            try {
                val err = Libbox.startHTTPCustomTunnel(server, port.toLong(), user, pass, payload, 1080L, currentMode)
                handler.post {
                    if (err != null) {
                        log("Error SSH: $err")
                        resetUI()
                    } else {
                        log("SOCKS5 listo en 127.0.0.1:1080")
                        // Iniciar VpnService
                        val intent = Intent(this, GhostVpnService::class.java)
                        startForegroundService(intent)
                        tvStatus.text = "Conectado"
                        log("VPN Service iniciado")
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
            handler.post {
                stopService(Intent(this, GhostVpnService::class.java))
                resetUI()
            }
        }.start()
    }

    private fun resetUI() {
        isRunning = false
        btnConnect.text = "CONNECT"
        tvStatus.text = "⏸ Desconectado"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQ && resultCode == Activity.RESULT_OK) {
            val text = etConnection.text.toString().trim()
            var server = "149.33.19.164"; var port = 80; var user = "Charly100"; var pass = ""
            if (text.contains("@")) {
                val parts = text.split("@")
                val sp = parts[0].split(":"); if (sp.size >= 2) { server = sp[0]; port = sp[1].toIntOrNull() ?: 80 }
                val up = parts[1].split(":"); if (up.size >= 1) user = up[0]; if (up.size >= 2) pass = up[1]
            }
            startVpn(server, port, user, pass, etPayload.text.toString().trim())
        }
        if (requestCode == IMPORT_REQ && resultCode == Activity.RESULT_OK && data?.data != null) {
            try {
                val json = contentResolver.openInputStream(data.data!!)?.bufferedReader()?.readText() ?: return
                importFromJson(json)
            } catch (e: Exception) {
                log("Error import: ${e.message}")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ─── IMPORT / EXPORT .gv ───

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            try {
                val json = contentResolver.openInputStream(intent.data!!)?.bufferedReader()?.readText() ?: return
                importFromJson(json)
            } catch (e: Exception) {
                log("Error al abrir archivo: ${e.message}")
            }
        }
    }

    private fun importConfig() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, IMPORT_REQ)
    }

    private fun exportConfig() {
        val text = etConnection.text.toString().trim()
        var server = "149.33.19.164"; var port = 80; var user = "Charly100"; var pass = ""
        if (text.contains("@")) {
            val parts = text.split("@")
            val sp = parts[0].split(":"); if (sp.size >= 2) { server = sp[0]; port = sp[1].toIntOrNull() ?: 80 }
            val up = parts[1].split(":"); if (up.size >= 1) user = up[0]; if (up.size >= 2) pass = up[1]
        }
        val payload = etPayload.text.toString().trim()

        val json = JSONObject().apply {
            put("server", server)
            put("port", port)
            put("user", user)
            put("pass", pass)
            put("payload", payload)
            put("mode", currentMode.toInt())
            put("name", "GhostVPN $user")
        }.toString(2)

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "GhostVPN_${user}.gv")
        }
        startActivityForResult(intent, EXPORT_REQ)
    }

    private fun importFromJson(json: String) {
        val obj = JSONObject(json)
        val server = obj.optString("server", "149.33.19.164")
        val port = obj.optInt("port", 80)
        val user = obj.optString("user", "Charly100")
        val pass = obj.optString("pass", "")
        val payload = obj.optString("payload", "")
        val mode = obj.optInt("mode", 2)

        etConnection.setText("$server:$port@$user:$pass")
        if (payload.isNotEmpty()) etPayload.setText(payload)
        spMode.setSelection(mode)
        currentMode = mode.toLong()

        Toast.makeText(this, "Config .gv importada", Toast.LENGTH_SHORT).show()
        log("Importada config: $server:$port@$user")
    }

    companion object {
        const val EXPORT_REQ = 200
    }

    private fun log(msg: String) {
        tvLog.append("$msg\n")
    }
}
