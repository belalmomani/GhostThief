package com.ghostthief.game.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** بروتوكول اللعب بين هاتفين على الشبكة المحلية (TCP). كل رسالة: [نوع:1 بايت][طول:2 بايت][محتوى]. */
object NetProtocol {
    const val VERSION = 1
    const val TCP_PORT = 47654
    const val UDP_PORT = 47655
    const val MAX_FRAME = 4096

    const val T_HELLO = 1
    const val T_WELCOME = 2
    const val T_REJECT = 3
    const val T_INPUT = 4
    const val T_SNAPSHOT = 5
    const val T_EVENT = 6
    const val T_PING = 7
    const val T_BYE = 8

    const val EV_MESSAGE = 1
    const val EV_LOOT_MESSAGE = 2
    const val EV_SOUND = 3
    const val EV_LOOP = 4

    const val ROLE_POLICE = 0
    const val ROLE_THIEF = 1

    fun otherRole(role: Int) = if (role == ROLE_POLICE) ROLE_THIEF else ROLE_POLICE
}

sealed class Packet {
    /** الضيف يعرّف نفسه: الإصدار، رقم البناء (يجب أن يتطابق)، ورمز الغرفة. */
    data class Hello(val version: Int, val build: Int, val code: String) : Packet()
    /** المضيف يقبل ويخبر الضيف بدوره هو (الضيف يأخذ الدور الآخر). */
    data class Welcome(val hostRole: Int) : Packet()
    data class Reject(val reason: String) : Packet()
    /** إدخال الضيف: اتجاه الأسهم وضغطة «اختباء». */
    data class Input(val dx: Float, val dy: Float, val hide: Boolean) : Packet()
    /** لقطة حالة اللعبة من المضيف. */
    data class Snapshot(val f: FloatArray, val i: IntArray, val vehicle: String) : Packet()
    /** حدث للعرض عند الضيف: رسالة أو صوت. */
    data class Event(val kind: Int, val a: Int, val b: Int, val c: Float, val text: String) : Packet()
    object Ping : Packet()
    object Bye : Packet()
}

object Codec {
    private fun clean(v: Float): Float = if (v.isNaN() || v.isInfinite()) 0f else v

    fun write(out: DataOutputStream, p: Packet) {
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        val type: Int = when (p) {
            is Packet.Hello -> { d.writeInt(p.version); d.writeInt(p.build); d.writeUTF(p.code); NetProtocol.T_HELLO }
            is Packet.Welcome -> { d.writeByte(p.hostRole); NetProtocol.T_WELCOME }
            is Packet.Reject -> { d.writeUTF(p.reason); NetProtocol.T_REJECT }
            is Packet.Input -> { d.writeFloat(clean(p.dx)); d.writeFloat(clean(p.dy)); d.writeBoolean(p.hide); NetProtocol.T_INPUT }
            is Packet.Snapshot -> {
                d.writeByte(p.f.size); for (v in p.f) d.writeFloat(clean(v))
                d.writeByte(p.i.size); for (v in p.i) d.writeInt(v)
                d.writeUTF(p.vehicle)
                NetProtocol.T_SNAPSHOT
            }
            is Packet.Event -> { d.writeByte(p.kind); d.writeInt(p.a); d.writeInt(p.b); d.writeFloat(clean(p.c)); d.writeUTF(p.text.take(60)); NetProtocol.T_EVENT }
            is Packet.Ping -> NetProtocol.T_PING
            is Packet.Bye -> NetProtocol.T_BYE
        }
        val bytes = bos.toByteArray()
        out.writeByte(type); out.writeShort(bytes.size); out.write(bytes); out.flush()
    }

    fun read(inp: DataInputStream): Packet {
        val type = inp.readUnsignedByte()
        val len = inp.readUnsignedShort()
        if (len > NetProtocol.MAX_FRAME) throw IOException("frame too large")
        val buf = ByteArray(len)
        inp.readFully(buf)
        val d = DataInputStream(ByteArrayInputStream(buf))
        return when (type) {
            NetProtocol.T_HELLO -> Packet.Hello(d.readInt(), d.readInt(), d.readUTF().take(16))
            NetProtocol.T_WELCOME -> Packet.Welcome(d.readUnsignedByte())
            NetProtocol.T_REJECT -> Packet.Reject(d.readUTF().take(80))
            NetProtocol.T_INPUT -> Packet.Input(clean(d.readFloat()).coerceIn(-1f, 1f), clean(d.readFloat()).coerceIn(-1f, 1f), d.readBoolean())
            NetProtocol.T_SNAPSHOT -> {
                val nf = d.readUnsignedByte(); if (nf > 64) throw IOException("bad snapshot")
                val f = FloatArray(nf) { clean(d.readFloat()) }
                val ni = d.readUnsignedByte(); if (ni > 64) throw IOException("bad snapshot")
                val i = IntArray(ni) { d.readInt() }
                Packet.Snapshot(f, i, d.readUTF().take(16))
            }
            NetProtocol.T_EVENT -> Packet.Event(d.readUnsignedByte(), d.readInt(), d.readInt(), clean(d.readFloat()), d.readUTF().take(60))
            NetProtocol.T_PING -> Packet.Ping
            NetProtocol.T_BYE -> Packet.Bye
            else -> throw IOException("unknown packet type $type")
        }
    }
}
