package com.ghostthief.game.game

import com.ghostthief.game.net.NetProtocol
import com.ghostthief.game.net.Packet

/** مستمع المضيف: يعرض الحدث محلياً ويرسله أيضاً إلى هاتف الضيف (رسائل وأصوات). */
class NetForwardingListener(
    private val inner: GameListener,
    private val send: (Packet.Event) -> Unit
) : GameListener {
    override fun onMessage(msg: GameMessage, points: Int) {
        inner.onMessage(msg, points)
        send(Packet.Event(NetProtocol.EV_MESSAGE, msg.ordinal, points, 0f, ""))
    }

    override fun onSound(effect: SoundEffect) {
        inner.onSound(effect)
        send(Packet.Event(NetProtocol.EV_SOUND, effect.ordinal, 0, 0f, ""))
    }

    override fun onLootMessage(msg: GameMessage, name: String, points: Int) {
        inner.onLootMessage(msg, name, points)
        send(Packet.Event(NetProtocol.EV_LOOT_MESSAGE, msg.ordinal, points, 0f, name))
    }

    override fun onLoop(sound: LoopSound, on: Boolean, rate: Float) {
        inner.onLoop(sound, on, rate)
        send(Packet.Event(NetProtocol.EV_LOOP, sound.ordinal, if (on) 1 else 0, rate, ""))
    }
}
