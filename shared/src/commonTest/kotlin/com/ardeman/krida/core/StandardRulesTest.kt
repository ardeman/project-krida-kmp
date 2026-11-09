package com.ardeman.krida.core

import com.ardeman.krida.core.games.CongklakStarter
import com.ardeman.krida.core.games.StandardRules
import com.ardeman.krida.core.games.UlarTanggaOptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandardRulesTest {
    @Test
    fun quickMatchCongklakUsesIndonesianTembakAndRandomStarter() {
        val rules = StandardRules.congklak
        assertTrue(rules.tembakAfterStore)
        assertEquals(CongklakStarter.RANDOM, rules.starter)
        assertTrue(rules.initialState(2, Random(1)).tembakAfterStore)
    }

    @Test
    fun quickMatchUlarTanggaUsesDefaultOptions() {
        val state = StandardRules.ularTangga.initialState(4, Random(1))
        assertEquals(UlarTanggaOptions(), state.options)
        assertTrue(state.options.sixRollsAgain)
    }
}
