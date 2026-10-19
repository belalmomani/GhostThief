package com.ghostthief.game

import com.ghostthief.game.data.InMemoryKeyValueStore
import com.ghostthief.game.logic.ParentLock
import com.ghostthief.game.logic.ScoreRules
import org.junit.Assert.*
import org.junit.Test

class ParentLockTest {
    @Test fun noPinByDefault() = assertFalse(ParentLock(InMemoryKeyValueStore()).isSet())

    @Test fun setVerifyAndClear() {
        val kv = InMemoryKeyValueStore(); val l = ParentLock(kv)
        assertTrue(l.set("4821")); assertTrue(l.isSet())
        assertTrue(l.verify("4821")); assertFalse(l.verify("1234"))
        assertTrue(ParentLock(kv).verify("4821")) // يُستعاد من التخزين
        l.clear(); assertFalse(l.isSet()); assertFalse(l.verify("4821"))
    }

    @Test fun rejectsInvalidPins() {
        val l = ParentLock(InMemoryKeyValueStore())
        assertFalse(l.set("12")); assertFalse(l.set("12ab")); assertFalse(l.set("1234567")); assertFalse(l.isSet())
    }

    @Test fun pinIsNotStoredInPlainText() {
        val kv = InMemoryKeyValueStore(); ParentLock(kv).set("9090")
        assertFalse((kv.getString("lock_hash", "") ?: "").contains("9090"))
    }

    @Test fun seekSpeedBonus_decreasesWithTime() {
        assertTrue(ScoreRules.seekSpeedBonus(3f) > ScoreRules.seekSpeedBonus(8f))
        assertTrue(ScoreRules.seekSpeedBonus(8f) > ScoreRules.seekSpeedBonus(15f))
        assertEquals(0, ScoreRules.seekSpeedBonus(30f))
    }
}
