package com.ghostthief.game.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** غرفة ظاهرة على الشبكة. الرمز لا يُبث أبداً: يعرفه من يرى شاشة المضيف فقط. */
data class Room(val name: String, val hostRole: Int, val address: String, val port: Int)

/** المضيف يبثّ اسم الغرفة ودوره كل ثانية على الشبكة المحلية (UDP). */
class RoomBroadcaster(private val name: String, private val hostRole: Int, private val tcpPort: Int = NetProtocol.TCP_PORT) {
    @Volatile private var running = false

    fun start() {
        running = true
        Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                val payload = "GT1|${name.replace("|", " ")}|$hostRole|$tcpPort".toByteArray()
                while (running) {
                    for (addr in NetUtil.broadcastAddresses()) {
                        try { socket.send(DatagramPacket(payload, payload.size, addr, NetProtocol.UDP_PORT)) } catch (_: Exception) {}
                    }
                    Thread.sleep(1000)
                }
            } catch (_: Exception) {
            } finally { try { socket?.close() } catch (_: Exception) {} }
        }, "gt-broadcast").apply { isDaemon = true }.start()
    }

    fun stop() { running = false }
}

/** الضيف يستمع لبثّ الغرف ويستدعي [onRoom] لكل غرفة (تتكرر كل ثانية). */
class RoomFinder(private val onRoom: (Room) -> Unit) {
    @Volatile private var running = false

    fun start() {
        running = true
        Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(NetProtocol.UDP_PORT))
                socket.soTimeout = 1000
                val buf = ByteArray(256)
                while (running) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        parse(String(p.data, 0, p.length), p.address.hostAddress ?: continue)?.let(onRoom)
                    } catch (_: java.net.SocketTimeoutException) {}
                }
            } catch (_: Exception) {
            } finally { try { socket?.close() } catch (_: Exception) {} }
        }, "gt-finder").apply { isDaemon = true }.start()
    }

    fun stop() { running = false }

    companion object {
        fun parse(text: String, address: String): Room? {
            val parts = text.split("|")
            if (parts.size != 4 || parts[0] != "GT1") return null
            val role = parts[2].toIntOrNull() ?: return null
            val port = parts[3].toIntOrNull() ?: return null
            if (role !in 0..1 || port !in 1024..65535) return null
            return Room(parts[1].take(24), role, address, port)
        }
    }
}
