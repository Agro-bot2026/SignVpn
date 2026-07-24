package com.ghostvpn

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.gson.Gson
import io.nekohasekai.libbox.Libbox

data class Profile(
    val server: String = "",
    val port: Int = 80,
    val user: String = "",
    val password: String = "",
    val payload: String = "",
    val sni: String = "",
    val badvpnEnabled: Boolean = false,
    val badvpnPort: Int = 7300,
    val dns: String = "8.8.8.8",
    val autoReconnect: Boolean = false
)

class MainActivity : AppCompatActivity() {
    companion object {
        const val PREFS_NAME = "ghostvpn_prefs"
        const val EXPORT_REQ = 1001
        const val IMPORT_REQ = 1002
    }

    private lateinit var etServer: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etPayload: EditText
    private lateinit var etSNI: EditText
    private lateinit var etBadvpnPort: EditText
    private lateinit var etDNS: EditText
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var btnConnect: Button
    private lateinit var swBadvpn: Switch
    private lateinit var swReconnect: Switch
    private lateinit var spDNS: Spinner

    private var isRunning = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean("dark_theme", true)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }

        val scroll = ScrollView(this)
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        // Toolbar with export/import
        val tb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tb.addView(TextView(this).apply { text = "GhostVpn"; textSize = 24f })
        tb.addView(Button(this).apply { text = "Exportar"; setOnClickListener { exportProfile() } })
        tb.addView(Button(this).apply { text = "Importar"; setOnClickListener { importProfile() } })
        l.addView(tb)

        // Helper function for fields
        fun campo(label: String, hint: String, valor: String = "", multi: Boolean = false): EditText {
            l.addView(TextView(this).apply { text = label; textSize = 14f; setPadding(0, 12, 0, 2) })
            return EditText(this).apply {
                setHint(hint)
                setText(valor)
                if (multi) { minLines = 4; maxLines = 8 }
                l.addView(this)
            }
        }

        // Main fields
        etServer = campo("Servidor", "IP del proxy", "149.33.19.164")
        etPort = campo("Puerto", "80", "80")

        // Credentials side by side
        l.addView(TextView(this).apply { text = "Credenciales SSH"; textSize = 14f; setPadding(0, 12, 0, 2) })
        val cred = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etUser = EditText(this).apply {
            hint = "Usuario"
            setText("Charly100")
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        etPass = EditText(this).apply {
            hint = "Contrase\u00f1a"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        cred.addView(etUser)
        cred.addView(etPass)
        l.addView(cred)

        // Payload editor
        etPayload = campo("Payload", "CONNECT / HTTP/1.1...", "", true)
        etPayload.setText(
            "CONNECT / HTTP/1.1[crlf]Host: recargas.personal.com.ar[crlf][crlf]" +
            "[split][crlf][crlf]GET / HTTP/1.1[crlf]Host: recargas.personal.com.ar[lf][lf]" +
            "GET /vpsx HTTP/1.1[crlf]Host:[rotate=cdn1.panda2.fun]" +
            "[lf]Backend: vps146[lf]Connection: Upgrade[lf]Upgrade: websocket[lf]" +
            "User-Agent: Googlebot/2.1[lf][lf]"
        )

        // SNI / Host
        etSNI = campo("SNI / Host", "Bughost (opcional)")

        // BadVPN
        l.addView(TextView(this).apply { text = "BadVPN UDPGW"; textSize = 14f; setPadding(0, 12, 0, 2) })
        val bv = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        swBadvpn = Switch(this).apply { text = "Activar" }
        etBadvpnPort = EditText(this).apply {
            hint = "7300"
            setText("7300")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        bv.addView(swBadvpn)
        bv.addView(etBadvpnPort)
        l.addView(bv)

        // DNS selector
        l.addView(TextView(this).apply { text = "DNS"; textSize = 14f; setPadding(0, 12, 0, 2) })
        spDNS = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Google (8.8.8.8)", "Cloudflare (1.1.1.1)",
                    "OpenDNS (208.67.222.222)", "Quad9 (9.9.9.9)", "Personalizado"))
        }
        l.addView(spDNS)
        etDNS = EditText(this).apply { hint = "DNS personalizado"; setText("8.8.8.8") }
        l.addView(etDNS)

        spDNS.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                etDNS.setText(arrayOf("8.8.8.8", "1.1.1.1", "208.67.222.222", "9.9.9.9", "8.8.8.8")[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Auto-reconnect
        swReconnect = Switch(this).apply { text = "Auto-reconexi\u00f3n" }
        l.addView(swReconnect)

        // Status
        statusText = TextView(this).apply {
            text = "\u23F8 Desconectado"
            textSize = 18f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, 20, 0, 10)
        }
        l.addView(statusText)

        // Connect button
        btnConnect = Button(this).apply { text = "CONECTAR" }
        btnConnect.setOnClickListener {
            if (!isRunning) conectar() else desconectar()
        }
        l.addView(btnConnect)

        // Log
        l.addView(TextView(this).apply { text = "Log:"; textSize = 12f; setPadding(0, 10, 0, 2) })
        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 350)
        }
        logText = TextView(this).apply { textSize = 10f }
        logScroll.addView(logText)
        l.addView(logScroll)
        l.addView(Button(this).apply { text = "Limpiar"; setOnClickListener { logText.text = "" } })

        scroll.addView(l)
        setContentView(scroll)

        cargarPrefs()
    }

    private fun conectar() {
        val server = etServer.text.toString().trim()
        val port = etPort.text.toString().toIntOrNull() ?: 80
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()
        val payload = etPayload.text.toString().trim()

        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "Complet\u00e1 servidor, usuario y contrase\u00f1a", Toast.LENGTH_SHORT).show()
            return
        }

        isRunning = true
        btnConnect.text = "DESCONECTAR"
        statusText.text = "Conectando..."
        log("Conectando a $server:$port...")

        Thread {
            try {
                val err = Libbox.startHTTPCustomTunnel(server, port.toLong(), user, pass, payload, 1080L, 0L)
                handler.post {
                    if (err != null) {
                        log("Error: $err")
                        resetUI()
                    } else {
                        log("Conectado! SOCKS5 en 127.0.0.1:1080")
                        statusText.text = "Conectado"
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    log("Excepci\u00f3n: ${e.message}")
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
        btnConnect.text = "CONECTAR"
        statusText.text = "\u23F8 Desconectado"
    }

    private fun log(msg: String) {
        logText.append("$msg\n")
    }

    private fun exportProfile() {
        val p = Profile(
            server = etServer.text.toString(),
            port = etPort.text.toString().toIntOrNull() ?: 80,
            user = etUser.text.toString(),
            password = etPass.text.toString(),
            payload = etPayload.text.toString(),
            sni = etSNI.text.toString(),
            badvpnEnabled = swBadvpn.isChecked,
            badvpnPort = etBadvpnPort.text.toString().toIntOrNull() ?: 7300,
            dns = etDNS.text.toString(),
            autoReconnect = swReconnect.isChecked
        )
        val json = Gson().toJson(p)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("export_json", json).apply()

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "ghostvpn_profile.json")
        }
        startActivityForResult(intent, EXPORT_REQ)
    }

    private fun importProfile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }, IMPORT_REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            EXPORT_REQ -> {
                val json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("export_json", "") ?: ""
                try {
                    contentResolver.openOutputStream(data.data!!)?.use { it.write(json.toByteArray()) }
                    Toast.makeText(this, "Perfil exportado", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            IMPORT_REQ -> {
                try {
                    val json = contentResolver.openInputStream(data.data!!)?.bufferedReader()?.readText() ?: ""
                    val profile = Gson().fromJson(json, Profile::class.java)
                    etServer.setText(profile.server)
                    etPort.setText(profile.port.toString())
                    etUser.setText(profile.user)
                    etPass.setText(profile.password)
                    etPayload.setText(profile.payload)
                    etSNI.setText(profile.sni)
                    swBadvpn.isChecked = profile.badvpnEnabled
                    etBadvpnPort.setText(profile.badvpnPort.toString())
                    etDNS.setText(profile.dns)
                    swReconnect.isChecked = profile.autoReconnect
                    Toast.makeText(this, "Perfil importado", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun cargarPrefs() {
        val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        etServer.setText(p.getString("server", "149.33.19.164"))
        etPort.setText(p.getInt("port", 80).toString())
        etUser.setText(p.getString("user", "Charly100"))
        etDNS.setText(p.getString("dns", "8.8.8.8"))
        swBadvpn.isChecked = p.getBoolean("badvpn", false)
        swReconnect.isChecked = p.getBoolean("reconnect", false)
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putString("server", etServer.text.toString())
            putInt("port", etPort.text.toString().toIntOrNull() ?: 80)
            putString("user", etUser.text.toString())
            putString("dns", etDNS.text.toString())
            putBoolean("badvpn", swBadvpn.isChecked)
            putBoolean("reconnect", swReconnect.isChecked)
            apply()
        }
    }
}
