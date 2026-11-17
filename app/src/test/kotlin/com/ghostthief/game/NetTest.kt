package com.ghostthief.game

import com.ghostthief.game.net.Codec
import com.ghostthief.game.net.Handshake
import com.ghostthief.game.net.HostServer
import com.ghostthief.game.net.NetClient
import com.ghostthief.game.net.NetLink
import com.ghostthief.game.net.NetProtocol
import com.ghostthief.game.net.Packet
import com.ghostthief.game.net.QrPayload
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NetTest {
    private fun waitFor(ms: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(10) }
        return cond()
    }

    // ---------- رمز QR ----------
    @Test fun qr_roundTrip() {
        val p = QrPayload("192.168.1.5", 47654, "4821")
        assertEquals(p, QrPayload.parse(p.encode()))
        assertEquals(p, QrPayload.parse("  " + p.encode() + "\n"))
    }

    @Test fun qr_acceptsOnlyPrivateIpv4() {
        assertNotNull(QrPayload.parse("GT1|10.0.0.7|47654|0001"))
        assertNotNull(QrPayload.parse("GT1|172.20.10.1|47654|0001"))   // نقطة اتصال آيفون
        assertNotNull(QrPayload.parse("GT1|192.168.43.1|47654|0001"))  // نقطة اتصال أندرويد
        assertNull(QrPayload.parse("GT1|8.8.8.8|47654|0001"))
        assertNull(QrPayload.parse("GT1|172.32.0.1|47654|0001"))
        assertNull(QrPayload.parse("GT1|192.168.1.256|47654|0001"))
        assertNull(QrPayload.parse("GT1|example.com|47654|0001"))
    }

    @Test fun qr_rejectsMalformed() {
        assertNull(QrPayload.parse(null))
        assertNull(QrPayload.parse(""))
        assertNull(QrPayload.parse("https://example.com"))
        assertNull(QrPayload.parse("GT2|192.168.1.5|47654|4821"))
        assertNull(QrPayload.parse("GT1|192.168.1.5|80|4821"))        // منفذ منخفض
        assertNull(QrPayload.parse("GT1|192.168.1.5|47654|48"))       // رمز قصير
        assertNull(QrPayload.parse("GT1|192.168.1.5|47654|48a1"))
        assertNull(QrPayload.parse("GT1|192.168.1.5|47654|4821|x"))
    }

    // ---------- الترميز ----------
    @Test fun codec_snapshotRoundTrip() {
        val snap = Packet.Snapshot(FloatArray(31) { it * 0.5f }, IntArray(20) { it * 3 }, "police_car")
        val bos = ByteArrayOutputStream()
        Codec.write(DataOutputStream(bos), snap)
        val back = Codec.read(DataInputStream(ByteArrayInputStream(bos.toByteArray()))) as Packet.Snapshot
        assertArrayEquals(snap.f, back.f, 0f); assertArrayEquals(snap.i, back.i); assertEquals("police_car", back.vehicle)
    }

    @Test fun codec_rejectsOversizeFrameAndUnknownType() {
        val big = byteArrayOf(NetProtocol.T_PING.toByte(), 0x7F, 0x00) // طول 32512 > MAX_FRAME
        try { Codec.read(DataInputStream(ByteArrayInputStream(big))); fail() } catch (_: IOException) {}
        try { Codec.read(DataInputStream(ByteArrayInputStream(byteArrayOf(99, 0, 0)))); fail() } catch (_: IOException) {}
    }

    // ---------- المصافحة (نفس المسار الذي يستخدمه البلوتوث) ----------
    private fun serveWith(hello: Packet.Hello, code: String = "BT", build: Int = 5): Pair<Handshake.Result, Packet> {
        val inBytes = ByteArrayOutputStream().also { Codec.write(DataOutputStream(it), hello) }.toByteArray()
        val reply = ByteArrayOutputStream()
        val r = Handshake.serve(DataInputStream(ByteArrayInputStream(inBytes)), DataOutputStream(reply), code, NetProtocol.ROLE_THIEF, build)
        return r to Codec.read(DataInputStream(ByteArrayInputStream(reply.toByteArray())))
    }

    @Test fun handshake_okGivesHostRole() {
        val (r, reply) = serveWith(Packet.Hello(NetProtocol.VERSION, 5, "BT"))
        assertEquals(Handshake.Result.OK, r)
        assertEquals(NetProtocol.ROLE_THIEF, (reply as Packet.Welcome).hostRole)
    }

    @Test fun handshake_rejectsWrongCodeAndWrongBuild() {
        val (r1, p1) = serveWith(Packet.Hello(NetProtocol.VERSION, 5, "0000"))
        assertEquals(Handshake.Result.BAD_CODE, r1); assertTrue(p1 is Packet.Reject)
        val (r2, p2) = serveWith(Packet.Hello(NetProtocol.VERSION, 6, "BT"))
        assertEquals(Handshake.Result.REJECTED, r2); assertTrue(p2 is Packet.Reject)
        val (r3, _) = serveWith(Packet.Hello(NetProtocol.VERSION + 1, 5, "BT"))
        assertEquals(Handshake.Result.REJECTED, r3)
    }

    // ---------- TCP على المضيف المحلي ----------
    private class Host(code: String, role: Int, build: Int) {
        val received = CopyOnWriteArrayList<Packet>()
        val guestLink = AtomicReference<NetLink?>(null)
        val error = AtomicReference<String?>(null)
        val server = HostServer(code, role, build, 0, { received.add(it) }, {}, { guestLink.set(it) }, { error.set(it) })
        fun startAndWait(): Int {
            server.start()
            val end = System.currentTimeMillis() + 3000
            while (server.localPort <= 0 && System.currentTimeMillis() < end) Thread.sleep(10)
            assertTrue("server did not bind", server.localPort > 0)
            return server.localPort
        }
    }

    @Test fun tcp_guestJoinsAndPacketsFlow() {
        val host = Host("1234", NetProtocol.ROLE_POLICE, 9)
        val port = host.startAndWait()
        val guestGot = CopyOnWriteArrayList<Packet>()
        val r = NetClient.connect("127.0.0.1", "1234", 9, port, { guestGot.add(it) }, {})
        assertNull(r.error); assertEquals(NetProtocol.ROLE_POLICE, r.hostRole)
        assertTrue(waitFor { host.guestLink.get() != null })
        r.link!!.send(Packet.Input(0.5f, -1f, true))
        assertTrue(waitFor { host.received.any { it is Packet.Input } })
        val inp = host.received.first { it is Packet.Input } as Packet.Input
        assertEquals(0.5f, inp.dx, 0f); assertEquals(-1f, inp.dy, 0f); assertTrue(inp.hide)
        host.guestLink.get()!!.send(Packet.Event(NetProtocol.EV_SOUND, 2, 0, 0f, ""))
        assertTrue(waitFor { guestGot.any { it is Packet.Event } })
        r.link!!.close(); host.guestLink.get()!!.close()
    }

    @Test fun tcp_wrongCodeAndWrongBuildAreRejected() {
        val host = Host("1234", NetProtocol.ROLE_THIEF, 9)
        val port = host.startAndWait()
        val bad = NetClient.connect("127.0.0.1", "9999", 9, port, {}, {})
        assertNull(bad.link); assertNotNull(bad.error)
        val old = NetClient.connect("127.0.0.1", "1234", 8, port, {}, {})
        assertNull(old.link); assertNotNull(old.error)
        assertNull(host.guestLink.get())
        host.server.stop()
    }

    @Test fun tcp_roomClosesAfterTooManyWrongCodes() {
        val host = Host("1234", NetProtocol.ROLE_POLICE, 9)
        val port = host.startAndWait()
        repeat(HostServer.MAX_BAD_CODES) { NetClient.connect("127.0.0.1", "000$it", 9, port, {}, {}) }
        assertTrue(waitFor { host.error.get() != null })
        // حتى الرمز الصحيح لا يدخل بعد الإغلاق
        val late = NetClient.connect("127.0.0.1", "1234", 9, port, {}, {})
        assertNull(late.link)
        assertNull(host.guestLink.get())
    }

    // ---------- النبض ومراقبة الانقطاع ----------
    private fun linkOver(sock: Socket, timeoutMs: Long, pingMs: Long, closedFlag: AtomicBoolean, got: MutableList<Packet>) =
        NetLink(sock.getInputStream(), sock.getOutputStream(), { try { sock.close() } catch (_: Exception) {} },
            { got.add(it) }, { closedFlag.set(true) }, timeoutMs, pingMs)

    private fun socketPair(): Pair<Socket, Socket> {
        ServerSocket(0).use { ss ->
            val client = Socket("127.0.0.1", ss.localPort)
            return client to ss.accept()
        }
    }

    @Test fun keepalive_holdsIdleLinkOpenWithoutForwardingPings() {
        val (a, b) = socketPair()
        val closedA = AtomicBoolean(false); val closedB = AtomicBoolean(false)
        val gotA = CopyOnWriteArrayList<Packet>(); val gotB = CopyOnWriteArrayList<Packet>()
        val la = linkOver(a, 600, 100, closedA, gotA); val lb = linkOver(b, 600, 100, closedB, gotB)
        la.start(); lb.start()
        Thread.sleep(1800) // أكثر من ضعف المهلة بلا أي رسالة لعب
        assertFalse(closedA.get()); assertFalse(closedB.get())
        assertTrue(gotA.isEmpty() && gotB.isEmpty())
        la.send(Packet.Bye)
        assertTrue(waitFor { gotB.any { it is Packet.Bye } })
        la.close(); lb.close()
    }

    @Test fun watchdog_closesWhenPeerGoesSilent() {
        val (a, b) = socketPair()                       // b يقبل الاتصال لكنه صامت تماماً
        val closed = AtomicBoolean(false)
        val link = linkOver(a, 400, 100, closed, CopyOnWriteArrayList())
        link.start()
        assertTrue(waitFor(3000) { closed.get() })
        assertTrue(link.closed)
        b.close()
    }

    @Test fun link_closesWhenPeerDisconnects() {
        val (a, b) = socketPair()
        val closed = AtomicBoolean(false)
        val link = linkOver(a, 5000, 1000, closed, CopyOnWriteArrayList())
        link.start()
        b.close()
        assertTrue(waitFor { closed.get() })
    }
}
