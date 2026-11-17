package com.ghostthief.game.net

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** جلسة اللعب الشبكي الحالية (تعيش بين شاشة الغرفة وشاشة اللعب). */
object NetSession {
    @Volatile var link: NetLink? = null
        private set
    @Volatile var isHost = false
        private set
    /** دوري أنا على هذا الهاتف (ROLE_POLICE أو ROLE_THIEF). */
    @Volatile var myRole = NetProtocol.ROLE_POLICE
        private set
    @Volatile var disconnected = false
        private set

    private val snapshot = AtomicReference<Packet.Snapshot?>(null)
    val events = ConcurrentLinkedQueue<Packet.Event>()
    private val hidePending = AtomicBoolean(false)
    @Volatile var remoteDx = 0f
        private set
    @Volatile var remoteDy = 0f
        private set

    fun begin(link: NetLink, host: Boolean, myRole: Int) {
        end()
        this.link = link; isHost = host; this.myRole = myRole; disconnected = false
        events.clear(); snapshot.set(null); remoteDx = 0f; remoteDy = 0f; hidePending.set(false)
    }

    /** يستقبل الرسائل من خيط الشبكة (ليس خيط الواجهة). */
    fun onPacket(p: Packet) {
        when (p) {
            is Packet.Snapshot -> snapshot.set(p)
            is Packet.Event -> if (events.size < 200) events.add(p)
            is Packet.Input -> { remoteDx = p.dx; remoteDy = p.dy; if (p.hide) hidePending.set(true) }
            is Packet.Bye -> { disconnected = true; link?.close() }
            else -> {}
        }
    }

    fun onClosed() { disconnected = true }

    fun takeSnapshot(): Packet.Snapshot? = snapshot.getAndSet(null)
    fun takeHide(): Boolean = hidePending.getAndSet(false)

    fun send(p: Packet) { link?.send(p) }

    fun active(): Boolean { val l = link; return l != null && !disconnected && !l.closed }

    fun end() {
        val l = link
        link = null
        if (l != null) { l.send(Packet.Bye); Thread { try { Thread.sleep(150) } catch (_: Exception) {}; l.close() }.start() }
        disconnected = false; isHost = false
    }
}
