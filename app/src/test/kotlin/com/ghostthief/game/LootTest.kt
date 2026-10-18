package com.ghostthief.game

import com.ghostthief.game.logic.Loot
import com.ghostthief.game.logic.LootKind
import com.ghostthief.game.logic.LootRules
import org.junit.Assert.*
import org.junit.Test

class LootTest {
    @Test fun sizeDecidesHowTheThiefSteals() {
        assertEquals(LootKind.SMALL, Loot("a", 5L * 1024 * 1024, true).kind)
        assertEquals(LootKind.MEDIUM, Loot("b", 60L * 1024 * 1024, true).kind)
        assertEquals(LootKind.LARGE, Loot("c", 400L * 1024 * 1024, false).kind)
    }
    @Test fun rescueBonus_growsWithSize() {
        assertTrue(LootRules.rescueBonus(LootKind.SMALL) < LootRules.rescueBonus(LootKind.MEDIUM))
        assertTrue(LootRules.rescueBonus(LootKind.MEDIUM) < LootRules.rescueBonus(LootKind.LARGE))
    }
    @Test fun formatSize() { assertEquals("5 MB", LootRules.formatSize(5L * 1024 * 1024)); assertEquals("512 B", LootRules.formatSize(512)) }
}
