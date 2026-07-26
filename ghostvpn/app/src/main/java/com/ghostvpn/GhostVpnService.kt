package com.ghostvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

class GhostVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private val connections = ConcurrentHashMap<Int, TcpProxy>()
    private val CHANNEL_ID = "ghostvpn_tunnel"

    // SOCKS5 header: 5 bytes
    // VER=0x05, NMETHODS, METHODS...
    // Request: VER, CMD=0x01, RSV=0x00, ATYP, DST.ADDR, DST.PORT

    inner class TcpProxy(val srcPort: Int) {
        var sock: Socket? = null
        var outStream: java.io.OutputStream? = null
        var inStream: java.io.InputStream? = null
        var connected = false

        fun connect(dstIp: String, dstPort: Int): Boolean {
            return try {
                sock = Socket()
                sock?.connect(InetSocketAddress("127.0.0.1", 1080), 5000)
                outStream = sock?.getOutputStream()
                inStream = sock?.getInputStream()

                // SOCKS5 handshake
                outStream?.write(byteArrayOf(0x05, 0x01, 0x00)) // VER, NMETHODS, NO AUTH
                val resp = ByteArray(2)
                inStream?.read(resp)
                if (resp[0].toInt() != 5 || resp[1].toInt() != 0) return false

                // Build SOCKS5 request
                val dstBytes = dstIp.toByteArray(Charsets.UTF_8)
                val req = ByteArray(7 + dstBytes.size)
                req[0] = 0x05          // VER
                req[1] = 0x01          // CMD CONNECT
                req[2] = 0x00          // RSV
                req[3] = 0x03          // ATYP DOMAINNAME
                req[4] = dstBytes.size.toByte()
                System.arraycopy(dstBytes, 0, req, 5, dstBytes.size)
                req[5 + dstBytes.size] = (dstPort shr 8).toByte()
                req[6 + dstBytes.size] = dstPort.toByte()

                outStream?.write(req)
                val rep = ByteArray(10)
                inStream?.read(rep)
                if (rep[1].toInt() != 0) return false

                connected = true
                true
            } catch (e: Exception) {
                close()
                false
            }
        }

        fun write(data: ByteArray, offset: Int, len: Int) {
            try { outStream?.write(data, offset, len) } catch (_: Exception) {}
        }

        fun read(): ByteArray? {
            return try {
                val buf = ByteArray(65535)
                val n = inStream?.read(buf) ?: -1
                if (n <= 0) null else buf.copyOf(n)
            } catch (_: Exception) { null }
        }

        fun close() {
            try { sock?.close() } catch (_: Exception) {}
            connected = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        val builder = Builder()
        builder.setSession("GhostVPN")
        builder.setMtu(1500)
        builder.addAddress("10.0.0.2", 32)
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")

        tunInterface = builder.establish() ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        Thread(null, { tunnelLoop() }, "GhostTunnel").start()
        return START_STICKY
    }

    private fun tunnelLoop() {
        val tun = tunInterface ?: return
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val buf = ByteBuffer.allocate(65535)

        try {
            while (isRunning) {
                buf.clear()
                val n = input.channel?.read(buf) ?: break
                if (n <= 0) continue

                buf.flip()
                val packet = ByteArray(n)
                buf.get(packet)
                processPacket(packet, output)
            }
        } catch (_: Exception) {}
    }

    private fun processPacket(packet: ByteArray, output: FileOutputStream) {
        if (packet.size < 20) return

        val version = (packet[0].toInt() shr 4) and 0xf
        if (version != 4 && version != 6) return

        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (ihl < 20 || ihl > packet.size) return

        val protocol = packet[9].toInt() and 0xff
        val totalLen = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        if (totalLen > packet.size) return

        // Solo TCP (6)
        if (protocol != 6) return

        val srcIp = "${packet[12].toInt() and 0xff}.${packet[13].toInt() and 0xff}." +
                    "${packet[14].toInt() and 0xff}.${packet[15].toInt() and 0xff}"
        val dstIp = "${packet[16].toInt() and 0xff}.${packet[17].toInt() and 0xff}." +
                    "${packet[18].toInt() and 0xff}.${packet[19].toInt() and 0xff}"

        if (ihl + 20 > packet.size) return
        val tcpHeaderOffset = ihl

        val srcPort = ((packet[tcpHeaderOffset].toInt() and 0xff) shl 8) or
                      (packet[tcpHeaderOffset + 1].toInt() and 0xff)
        val dstPort = ((packet[tcpHeaderOffset + 2].toInt() and 0xff) shl 8) or
                      (packet[tcpHeaderOffset + 3].toInt() and 0xff)
        val dataOffset = ((packet[tcpHeaderOffset + 12].toInt() shr 4) and 0x0f) * 4
        val flags = packet[tcpHeaderOffset + 13].toInt() and 0xff

        val payloadOffset = ihl + dataOffset
        val payloadLen = totalLen - payloadOffset

        val connectionKey = srcPort

        if ((flags and 0x02) != 0) {
            // SYN: crear conexion SOCKS5
            val proxy = TcpProxy(srcPort)
            connections[connectionKey] = proxy
            if (proxy.connect(dstIp, dstPort)) {
                if (payloadLen > 0) {
                    val tcpData = packet.copyOfRange(payloadOffset, totalLen)
                    proxy.write(tcpData, 0, tcpData.size)
                }
            } else {
                // Connection failed - send RST
                connections.remove(connectionKey)
                sendRst(packet, output)
            }
        } else if ((flags and 0x10) != 0 || (flags and 0x08) != 0) {
            // ACK or PSH: forward data
            val proxy = connections[connectionKey]
            if (proxy != null && proxy.connected && payloadLen > 0) {
                val tcpData = packet.copyOfRange(payloadOffset, totalLen)
                proxy.write(tcpData, 0, tcpData.size)
            }
        } else if ((flags and 0x01) != 0 || (flags and 0x04) != 0) {
            // FIN or RST: close connection
            connections.remove(connectionKey)?.close()
        }
    }

    private fun sendRst(originalPacket: ByteArray, output: FileOutputStream) {
        try {
            val ipLen = 20
            val tcpLen = 20
            val pkt = ByteArray(ipLen + tcpLen)

            // IP header
            pkt[0] = 0x45
            val totalLen = pkt.size
            pkt[2] = (totalLen shr 8).toByte()
            pkt[3] = totalLen.toByte()
            pkt[8] = 64 // TTL
            pkt[9] = 6  // TCP

            // Swap src/dst IP
            System.arraycopy(originalPacket, 12, pkt, 16, 4) // dst <- src
            System.arraycopy(originalPacket, 16, pkt, 12, 4) // src <- dst

            // TCP header
            // Swap ports
            System.arraycopy(originalPacket, 20, pkt, 22, 2) // dst port <- src port
            System.arraycopy(originalPacket, 22, pkt, 20, 2) // src port <- dst port

            // SEQ/ACK
            System.arraycopy(originalPacket, 28, pkt, 24, 4) // ACK = their SEQ + 1 (simplified)
            pkt[33] = 0x14 // RST + ACK, data offset 5

            // IP checksum
            var sum = 0
            for (i in 0 until 20 step 2) {
                sum += ((pkt[i].toInt() and 0xff) shl 8) or (pkt[i + 1].toInt() and 0xff)
            }
            sum = (sum and 0xffff) + (sum shr 16)
            pkt[10] = (sum.inv() shr 8).toByte()
            pkt[11] = sum.inv().toByte()

            output.write(pkt)
            output.flush()
        } catch (_: Exception) {}
    }

    override fun onRevoke() {
        stop()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun stop() {
        isRunning = false
        connections.values.forEach { it.close() }
        connections.clear()
        try { tunInterface?.close() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "GhostVPN Tunnel",
            NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GhostVPN")
            .setContentText("Túnel activo")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
