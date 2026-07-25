package com.ghostvpn

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment

class SshFragment : Fragment() {
    lateinit var etConnection: EditText
    lateinit var etPayload: EditText
    lateinit var btnConnect: Button
    lateinit var tvStatus: TextView
    lateinit var tvServerInfo: TextView
    lateinit var chkPayload: CheckBox
    lateinit var chkSSL: CheckBox
    lateinit var chkEnhanced: CheckBox
    lateinit var chkSlowDns: CheckBox
    lateinit var chkDNS: CheckBox
    lateinit var chkUDP: CheckBox
    lateinit var chkPsiphon: CheckBox
    lateinit var chkV2ray: CheckBox
    private var connectionCallback: ConnectionCallback? = null

    interface ConnectionCallback {
        fun onConnect(server: String, port: Int, user: String, pass: String, payload: String, mode: Int)
        fun onDisconnect()
        fun isRunning(): Boolean
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_ssh, container, false)
        etConnection = v.findViewById(R.id.etConnection)
        etPayload = v.findViewById(R.id.etPayload)
        btnConnect = v.findViewById(R.id.btnConnect)
        tvStatus = v.findViewById(R.id.tvStatus)
        tvServerInfo = v.findViewById(R.id.tvServerInfo)
        chkPayload = v.findViewById(R.id.chkPayload)
        chkSSL = v.findViewById(R.id.chkSSL)
        chkEnhanced = v.findViewById(R.id.chkEnhanced)
        chkSlowDns = v.findViewById(R.id.chkSlowDns)
        chkDNS = v.findViewById(R.id.chkDNS)
        chkUDP = v.findViewById(R.id.chkUDP)
        chkPsiphon = v.findViewById(R.id.chkPsiphon)
        chkV2ray = v.findViewById(R.id.chkV2ray)

        // Default payload text
        etPayload.setText("CONNECT / HTTP/1.1[crlf]Host: recargas.personal.com.ar[crlf][crlf][split][crlf][crlf]GET / HTTP/1.1[crlf]Host: recargas.personal.com.ar[lf][lf]GET /vpsx HTTP/1.1[crlf]Host:[rotate=cdn1.panda2.fun][lf]Backend: vps146[lf]Connection: Upgrade[lf]Upgrade: websocket[lf]User-Agent: Googlebot/2.1[lf][lf]")

        btnConnect.setOnClickListener { onConnectClick() }
        updateStatus("⏸ Desconectado", false)

        // Parse connection string on change
        etConnection.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) parseConnectionString()
        }

        return v
    }

    private fun parseConnectionString() {
        val text = etConnection.text.toString().trim()
        if (text.contains("@")) {
            val parts = text.split("@")
            val serverPort = parts[0].split(":")
            val userPass = parts[1].split(":")
            if (serverPort.size >= 2 && userPass.size >= 1) {
                val info = "${serverPort[0]}:${serverPort[1]} | User: ${userPass[0]}"
                tvServerInfo.text = info
            }
        }
    }

    fun onConnectClick() {
        val cb = connectionCallback ?: return
        val text = etConnection.text.toString().trim()

        if (cb.isRunning()) {
            cb.onDisconnect()
            return
        }

        // Parse connection string: ip:port@user:pass
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
        } else {
            val parts = text.split(":")
            if (parts.size >= 1 && parts[0].isNotEmpty()) server = parts[0]
            if (parts.size >= 2) port = parts[1].toIntOrNull() ?: 80
        }

        val payload = etPayload.text.toString().trim()

        // Determine mode from checkboxes
        val mode = when {
            chkSSL.isChecked -> 3
            chkSlowDns.isChecked -> 5
            else -> 2
        }

        updateStatus("🔄 Conectando...", false)
        cb.onConnect(server, port, user, pass, payload, mode)
    }

    fun updateStatus(text: String, isConnected: Boolean) {
        tvStatus.text = text
        if (isConnected) {
            tvStatus.setTextColor(android.graphics.Color.parseColor("#00e676"))
            btnConnect.text = "DISCONNECT"
        } else {
            tvStatus.setTextColor(android.graphics.Color.parseColor("#888888"))
            btnConnect.text = "CONNECT"
        }
    }

    fun setCallback(cb: ConnectionCallback) { connectionCallback = cb }
    fun setPayload(p: String) { etPayload.setText(p) }
    fun getPayload(): String = etPayload.text.toString()
    fun getConnectionText(): String = etConnection.text.toString()
    fun setConnectionText(s: String) { etConnection.setText(s) }
}
