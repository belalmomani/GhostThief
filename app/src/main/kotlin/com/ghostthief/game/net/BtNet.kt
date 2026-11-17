package com.ghostthief.game.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * اللعب عبر البلوتوث الكلاسيكي (RFCOMM). الاقتران بين الهاتفين من إعدادات النظام هو التوثيق،
 * فنستخدم رمزاً ثابتاً في المصافحة (يبقى فحص الإصدار). لا بحث عن أجهزة: الضيف يختار من الأجهزة المقترنة.
 * يجب أن يكون إذن BLUETOOTH_CONNECT ممنوحاً (Android 12+) قبل استدعاء أي دالة هنا (تتحقق منه الشاشة).
 */
object BtNet {
    val SERVICE_UUID: UUID = UUID.fromString("7b1f6a52-3c1e-4c6b-9a55-47686f737431")
    const val SERVICE_NAME = "GhostThief"
    const val CODE = "BT"
}

/** المضيف: يستمع على خدمة RFCOMM وينتظر ضيفاً واحداً. */
@SuppressLint("MissingPermission")
class BtHost(
    private val adapter: BluetoothAdapter,
    private val hostRole: Int,
    private val build: Int,
    private val onPacket: (Packet) -> Unit,
    private val onClosed: () -> Unit,
    private val onGuest: (NetLink) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var stopped = false
    @Volatile private var server: BluetoothServerSocket? = null

    fun start() {
        Thread({ run() }, "gt-bt-host").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopped = true
        try { server?.close() } catch (_: Exception) {}
    }

    private fun run() {
        try {
            val ss = adapter.listenUsingRfcommWithServiceRecord(BtNet.SERVICE_NAME, BtNet.SERVICE_UUID)
            server = ss
            while (!stopped) {
                val s = ss.accept()
                val link = handshake(s)
                if (link != null) {
                    stop()
                    link.start()
                    onGuest(link)
                    return
                }
            }
        } catch (e: Exception) {
            if (!stopped) onError("تعذّر إنشاء غرفة البلوتوث. تأكد أن البلوتوث يعمل.")
        }
    }

    private fun handshake(s: BluetoothSocket): NetLink? {
        try {
            val inp = DataInputStream(s.inputStream)
            val out = DataOutputStream(s.outputStream)
            return if (Handshake.serve(inp, out, BtNet.CODE, hostRole, build) == Handshake.Result.OK) {
                NetLink(s.inputStream, s.outputStream, { try { s.close() } catch (_: Exception) {} }, onPacket, onClosed)
            } else { s.close(); null }
        } catch (_: Exception) {
            try { s.close() } catch (_: Exception) {}
            return null
        }
    }
}

object BtClient {
    /** عملية حاجبة (قد تستغرق حتى ~12 ثانية): شغّلها في خيط خلفي. */
    @SuppressLint("MissingPermission")
    fun connect(
        adapter: BluetoothAdapter, device: BluetoothDevice, build: Int,
        onPacket: (Packet) -> Unit, onClosed: () -> Unit
    ): ConnectResult {
        var socket: BluetoothSocket? = null
        try {
            adapter.cancelDiscovery()
            val s = device.createRfcommSocketToServiceRecord(BtNet.SERVICE_UUID)
            socket = s
            s.connect()
            val inp = DataInputStream(s.inputStream)
            val out = DataOutputStream(s.outputStream)
            return when (val reply = Handshake.join(inp, out, BtNet.CODE, build)) {
                is Packet.Welcome -> {
                    val link = NetLink(s.inputStream, s.outputStream, { try { s.close() } catch (_: Exception) {} }, onPacket, onClosed)
                    link.start()
                    ConnectResult(link, reply.hostRole, null)
                }
                is Packet.Reject -> { s.close(); ConnectResult(null, 0, reply.reason) }
                else -> { s.close(); ConnectResult(null, 0, "رد غير متوقع") }
            }
        } catch (e: Exception) {
            try { socket?.close() } catch (_: Exception) {}
            return ConnectResult(null, 0, "تعذّر الاتصال بالبلوتوث. تأكد أن صديقك أنشأ «غرفة بلوتوث» وأن الهاتفين مقترنان.")
        }
    }
}
