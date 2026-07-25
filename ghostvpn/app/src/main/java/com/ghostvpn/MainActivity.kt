package com.ghostvpn

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import io.nekohasekai.libbox.Libbox

data class Profile(
    val server: String = "149.33.19.164",
    val port: Int = 80,
    val user: String = "Charly100",
    val password: String = "",
    val payload: String = "",
    val mode: Int = 2
)

class MainActivity : AppCompatActivity(), SshFragment.ConnectionCallback, LogFragment.LogCallback {

    companion object {
        const val PREFS_NAME = "ghostvpn_prefs"
        const val EXPORT_REQ = 1001
        const val IMPORT_REQ = 1002
        const val IMPORT_FILE_REQ = 1003
    }

    private lateinit var pagerAdapter: PagerAdapter
    private var isRunning = false
    private var currentMode = 2L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Toolbar
        findViewById<ImageButton>(R.id.btnImport).setOnClickListener { importProfile() }
        findViewById<ImageButton>(R.id.btnExport).setOnClickListener { exportProfile() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            Toast.makeText(this, "Configuracion", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.btnOverflow).setOnClickListener {
            Toast.makeText(this, "GhostVpn v1.0", Toast.LENGTH_SHORT).show()
        }

        // ViewPager + Tabs
        val viewPager = findViewById<ViewPager>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        pagerAdapter = PagerAdapter(supportFragmentManager)
        viewPager.adapter = pagerAdapter
        tabLayout.setupWithViewPager(viewPager)

        val sshFragment = pagerAdapter.getSshFragment()
        sshFragment.setCallback(this)
        pagerAdapter.getLogFragment().setCallback(this)

        // FAB for import/export
        findViewById<FloatingActionButton>(R.id.fabImport).setOnClickListener {
            val options = arrayOf("Importar perfil", "Exportar perfil", "Importar archivo")
            AlertDialog.Builder(this)
                .setTitle("Gestión de perfil")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> importProfile()
                        1 -> exportProfile()
                        2 -> importFileProfile()
                    }
                }
                .show()
        }

        cargarPrefs()
    }

    // ─── Connection Callback ───
    override fun onConnect(server: String, port: Int, user: String, pass: String, payload: String, mode: Int) {
        currentMode = mode.toLong()
        isRunning = true
        log("Conectando a $server:$port (modo $mode)...")
        Thread {
            try {
                val err = Libbox.startHTTPCustomTunnel(server, port.toLong(), user, pass, payload, 1080L, currentMode)
                handler.post {
                    if (err != null) {
                        log("Error: $err")
                        resetUI()
                    } else {
                        log("Conectado! SOCKS5 en 127.0.0.1:1080")
                        pagerAdapter.getSshFragment().updateStatus("Conectado", true)
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

    override fun onDisconnect() {
        log("Deteniendo...")
        Thread {
            Libbox.stopHTTPCustomTunnel()
            handler.post { resetUI() }
        }.start()
    }

    override fun isRunning(): Boolean = isRunning

    private fun resetUI() {
        isRunning = false
        pagerAdapter.getSshFragment().updateStatus("⏸ Desconectado", false)
    }

    // ─── Log ───
    private fun log(msg: String) {
        pagerAdapter.getLogFragment().appendLog(msg)
    }

    override fun onClearLog() {}

    // ─── Import / Export ───
    private fun exportProfile() {
        val ssh = pagerAdapter.getSshFragment()
        val text = ssh.getConnectionText()
        var server = "149.33.19.164"; var port = 80; var user = "Charly100"; var pass = ""
        if (text.contains("@")) {
            val parts = text.split("@")
            val sp = parts[0].split(":"); if (sp.size >= 2) { server = sp[0]; port = sp[1].toIntOrNull() ?: 80 }
            val up = parts[1].split(":"); if (up.size >= 1) user = up[0]; if (up.size >= 2) pass = up[1]
        }
        val p = Profile(server, port, user, pass, ssh.getPayload(), currentMode.toInt())
        val json = Gson().toJson(p)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("export_json", json).apply()
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "ghostvpn.json")
        }, EXPORT_REQ)
    }

    private fun importProfile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }, IMPORT_REQ)
    }

    private fun importFileProfile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, IMPORT_FILE_REQ)
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
                    val ssh = pagerAdapter.getSshFragment()
                    ssh.setConnectionText("${profile.server}:${profile.port}@${profile.user}:${profile.password}")
                    ssh.setPayload(profile.payload)
                    currentMode = profile.mode.toLong()
                    Toast.makeText(this, "Perfil importado", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            IMPORT_FILE_REQ -> {
                try {
                    val text = contentResolver.openInputStream(data.data!!)?.bufferedReader()?.readText() ?: ""
                    val ssh = pagerAdapter.getSshFragment()
                    // Try json first
                    try {
                        val profile = Gson().fromJson(text, Profile::class.java)
                        ssh.setConnectionText("${profile.server}:${profile.port}@${profile.user}:${profile.password}")
                        ssh.setPayload(profile.payload)
                        currentMode = profile.mode.toLong()
                    } catch (e: Exception) {
                        // Treat as plain text connection string
                        ssh.setConnectionText(text.trim())
                    }
                    Toast.makeText(this, "Importado", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun cargarPrefs() {
        val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val server = p.getString("server", "149.33.19.164") ?: "149.33.19.164"
        val port = p.getInt("port", 80)
        val user = p.getString("user", "Charly100") ?: "Charly100"
        val pass = p.getString("pass", "") ?: ""
        val ssh = pagerAdapter.getSshFragment()
        ssh.setConnectionText("$server:$port@$user:$pass")
        currentMode = p.getLong("mode", 2)
    }

    override fun onPause() {
        super.onPause()
        val ssh = pagerAdapter.getSshFragment()
        val text = ssh.getConnectionText()
        var server = "149.33.19.164"; var port = 80; var user = "Charly100"; var pass = ""
        if (text.contains("@")) {
            val parts = text.split("@")
            val sp = parts[0].split(":"); if (sp.size >= 2) { server = sp[0]; port = sp[1].toIntOrNull() ?: 80 }
            val up = parts[1].split(":"); if (up.size >= 1) user = up[0]; if (up.size >= 2) pass = up[1]
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putString("server", server); putInt("port", port); putString("user", user); putString("pass", pass)
            putLong("mode", currentMode); apply()
        }
    }
}
