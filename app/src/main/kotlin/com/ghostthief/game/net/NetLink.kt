package com.ghostthief.game.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * وصلة بين الهاتفين فوق أي قناة بايتات (TCP للواي فاي وQR، أو RFCOMM للبلوتوث).
 * خيط قراءة، وخيط إرسال واحد، وخيط «نبض» مستقل عن الشاشة: يرسل نبضاً كل ~2 ثانية ويغلق الوصلة
 * إن لم يصل أي شيء من الطرف الآخر خلال [timeoutMs] (فلا ينقطع الاتصال لمجرد أن الشاشة في الخلفية).
 */
class NetLink(
    input: InputStream,
    output: OutputStream,
    private val closer: () -> Unit,
    private val onPacket: (Packet) -> Unit,
    private val onClosed: () -> Unit,
    private val timeoutMs: Long = READ_TIMEOUT_MS,
    private val pingMs: Long = PING_INTERVAL_MS
) {
    /** وصلة TCP: بلا مهلة قراءة على المقبس نفسه (تتولاها مراقبة النبض). */
    constructor(socket: Socket, onPacket: (Packet) -> Unit, onClosed: () -> Unit) : this(
        socket.getInputStream(), socket.getOutputStream(),
        { try { socket.close() } catch (_: Exception) {} },
        onPacket, onClosed
    ) {
        try { socket.tcpNoDelay = true; socket.soTimeout = 0 } catch (_: Exception) {}
    }

    private val input = DataInputStream(BufferedInputStream(input))
    private val output = DataOutputStream(BufferedOutputStream(output))
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "gt-net-send").apply { isDaemon = true } }
    private val pending = AtomicInteger(0)
    @Volatile private var lastRx = System.currentTimeMillis()
    @Volatile var closed = false
        private set

    fun start() {
        lastRx = System.currentTimeMillis()
        Thread({
            try {
                while (!closed) {
                    val p = Codec.read(input)
                    lastRx = System.currentTimeMillis()
                    if (p !== Packet.Ping) onPacket(p)
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }, "gt-net-read").apply { isDaemon = true }.start()

        Thread({
            try {
                var sinceMs = 0L
                val tick = (pingMs / 2).coerceIn(20L, 500L)
                while (!closed) {
                    Thread.sleep(tick)
                    sinceMs += tick
                    if (System.currentTimeMillis() - lastRx > timeoutMs) { close(); break }
                    if (sinceMs >= pingMs) { sinceMs = 0; send(Packet.Ping) }
                }
            } catch (_: InterruptedException) {
            }
        }, "gt-net-keepalive").apply { isDaemon = true }.start()
    }

    fun send(p: Packet) {
        if (closed) return
        // لقطات الحالة قديمة الصلاحية: إن تراكم الطابور (قناة بطيئة) نتخطى اللقطة بدل تضخيم الذاكرة
        if (p is Packet.Snapshot && pending.get() > MAX_PENDING_SNAPSHOTS) return
        pending.incrementAndGet()
        try {
            sender.execute {
                try { synchronized(output) { Codec.write(output, p) } } catch (_: Exception) { close() }
                finally { pending.decrementAndGet() }
            }
        } catch (_: RejectedExecutionException) { pending.decrementAndGet() }
    }

    fun close() {
        if (closed) return
        closed = true
        try { closer() } catch (_: Exception) {}
        sender.shutdownNow()
        try { onClosed() } catch (_: Exception) {}
    }

    companion object {
        const val READ_TIMEOUT_MS = 8000L
        const val PING_INTERVAL_MS = 2000L
        private const val MAX_PENDING_SNAPSHOTS = 20
    }
}
