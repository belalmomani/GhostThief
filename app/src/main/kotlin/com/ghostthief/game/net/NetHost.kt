package com.ghostthief.game.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/** المصافحة المشتركة بين كل القنوات (واي فاي، QR، بلوتوث): الإصدار + رقم البناء + الرمز. */
object Handshake {
    enum class Result { OK, BAD_CODE, REJECTED }

    /** يقرأ Hello من الضيف ويردّ بـ Welcome أو Reject. [build] لا يُستخدم للرفض (يبقى للتوافق مع الاستدعاءات). قد يرمي IOException. */
    fun serve(inp: DataInputStream, out: DataOutputStream, code: String, hostRole: Int, build: Int): Result {
        val hello = Codec.read(inp) as? Packet.Hello
        val (result, reason) = when {
            hello == null -> Result.REJECTED to "طلب غير صحيح"
            // التوافق = رقم البروتوكول فقط: هاتفان ببناءين مختلفين (أو نسختين ads/noads) يلعبان معاً.
            // ارفع NetProtocol.VERSION كلما تغيّر شكل لقطة الحالة (NetIdx) أو الرسائل.
            hello.version != NetProtocol.VERSION ->
                Result.REJECTED to "الإصدار مختلف: حدّث اللعبة على الهاتفين"
            NetUtil.normalizeDigits(hello.code) != NetUtil.normalizeDigits(code) -> Result.BAD_CODE to "الرمز غير صحيح"
            else -> Result.OK to null
        }
        if (reason != null) {
            try { Codec.write(out, Packet.Reject(reason)) } catch (_: Exception) {}
            return result
        }
        Codec.write(out, Packet.Welcome(hostRole))
        return Result.OK
    }

    /** الضيف: يرسل Hello وينتظر الرد. يرجع الرد (Welcome أو Reject) أو يرمي IOException. */
    fun join(inp: DataInputStream, out: DataOutputStream, code: String, build: Int): Packet {
        Codec.write(out, Packet.Hello(NetProtocol.VERSION, build, code))
        return Codec.read(inp)
    }
}

/** المضيف: ينتظر ضيفاً واحداً، يتحقق من الرمز والإصدار، ثم يسلّم الوصلة. بعد [MAX_BAD_CODES] رموز خاطئة تُغلق الغرفة. */
class HostServer(
    private val code: String,
    private val hostRole: Int,
    private val build: Int,
    private val port: Int = NetProtocol.TCP_PORT,
    private val onPacket: (Packet) -> Unit,
    private val onClosed: () -> Unit,
    private val onGuest: (NetLink) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var stopped = false
    @Volatile private var server: ServerSocket? = null
    private var badCodes = 0
    val localPort: Int get() = server?.localPort ?: -1

    fun start() {
        Thread({ run() }, "gt-host").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopped = true
        try { server?.close() } catch (_: Exception) {}
    }

    private fun run() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            server = ss
            while (!stopped) {
                val s = ss.accept()
                val link = handshake(s)
                if (link != null) {
                    stop()                 // ضيف واحد فقط
                    link.start()
                    onGuest(link)
                    return
                }
                if (badCodes >= MAX_BAD_CODES) {
                    stop()
                    onError("محاولات كثيرة برمز خاطئ، أُغلقت الغرفة. أنشئ غرفة جديدة.")
                    return
                }
            }
        } catch (e: Exception) {
            if (!stopped) onError(e.message ?: "تعذّر إنشاء الغرفة")
        }
    }

    private fun handshake(s: Socket): NetLink? {
        try {
            s.soTimeout = 3000
            val inp = DataInputStream(s.getInputStream())
            val out = DataOutputStream(s.getOutputStream())
            return when (Handshake.serve(inp, out, code, hostRole, build)) {
                Handshake.Result.OK -> NetLink(s, onPacket, onClosed)
                Handshake.Result.BAD_CODE -> { badCodes++; s.close(); null }
                Handshake.Result.REJECTED -> { s.close(); null }
            }
        } catch (_: Exception) {
            try { s.close() } catch (_: Exception) {}
            return null
        }
    }

    companion object { const val MAX_BAD_CODES = 5 }
}

/** نتيجة الاتصال: وصلة جاهزة ودور المضيف، أو سبب الفشل. */
class ConnectResult(val link: NetLink?, val hostRole: Int, val error: String?)

object NetClient {
    /** عملية حاجبة: شغّلها في خيط خلفي. */
    fun connect(
        host: String, code: String, build: Int, port: Int = NetProtocol.TCP_PORT,
        onPacket: (Packet) -> Unit, onClosed: () -> Unit
    ): ConnectResult {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 4000)
            s.soTimeout = 5000
            val out = DataOutputStream(s.getOutputStream())
            val inp = DataInputStream(s.getInputStream())
            return when (val reply = Handshake.join(inp, out, code, build)) {
                is Packet.Welcome -> {
                    val link = NetLink(s, onPacket, onClosed)
                    link.start()
                    ConnectResult(link, reply.hostRole, null)
                }
                is Packet.Reject -> { s.close(); ConnectResult(null, 0, reply.reason) }
                else -> { s.close(); ConnectResult(null, 0, "رد غير متوقع") }
            }
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            return ConnectResult(null, 0, "تعذّر الاتصال. تأكد أن الهاتفين على نفس شبكة الواي فاي")
        }
    }
}

object NetUtil {
    /** عنوان الهاتف على الشبكة المحلية (IPv4 خاص) أو null. */
    fun localIpv4(): String? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is java.net.Inet4Address && a.isSiteLocalAddress) return a.hostAddress
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out.add(it) }
            }
        } catch (_: Exception) {}
        try { out.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        return out
    }

    /** رمز من 4 أرقام لاتينية دائماً (String.format يتبع لغة الهاتف فيعطي أرقاماً هندية ٠١٢٣ على الهاتف العربي). */
    fun randomCode(): String {
        val r = SecureRandom()
        return buildString { repeat(4) { append('0' + r.nextInt(10)) } }
    }

    /** يحوّل الأرقام الهندية/الفارسية (٠١٢٣ ۰۱۲۳) والفاصلة العربية إلى أرقام ونقاط لاتينية، ويحذف المسافات الطرفية. */
    fun normalizeDigits(s: String): String = buildString(s.length) {
        for (c in s.trim()) append(
            when (c) {
                in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                '\u066B', '\u06D4' -> '.'
                else -> c
            }
        )
    }
}
