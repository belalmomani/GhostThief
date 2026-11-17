package com.ghostthief.game.net

/**
 * محتوى رمز QR الذي يعرضه المضيف ويمسحه الضيف: العنوان والمنفذ والرمز (صيغة «GT1|ip|port|code»).
 * يُقبل عنوان IPv4 خاص فقط (شبكة منزلية أو نقطة اتصال)، فلا يدفع رمز QR غريب التطبيق للاتصال بجهة على الإنترنت.
 */
data class QrPayload(val host: String, val port: Int, val code: String) {
    fun encode(): String = "GT1|$host|$port|$code"

    companion object {
        fun parse(text: String?): QrPayload? {
            if (text == null) return null
            val parts = text.trim().split("|")
            if (parts.size != 4 || parts[0] != "GT1") return null
            val host = parts[1]
            val port = parts[2].toIntOrNull() ?: return null
            val code = parts[3]
            if (!isPrivateIpv4(host)) return null
            if (port !in 1024..65535) return null
            if (code.length != 4 || !code.all { it in '0'..'9' }) return null
            return QrPayload(host, port, code)
        }

        fun isPrivateIpv4(host: String): Boolean {
            val p = host.split(".")
            if (p.size != 4) return false
            val n = p.map { s -> if (s.isEmpty() || s.length > 3 || !s.all { it in '0'..'9' }) return false else s.toInt() }
            if (n.any { it > 255 }) return false
            return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
        }
    }
}
