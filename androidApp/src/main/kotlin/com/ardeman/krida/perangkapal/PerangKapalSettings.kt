package com.ardeman.krida.perangkapal

import com.ardeman.krida.core.games.PerangKapalGame
import com.ardeman.krida.core.games.StandardRules
import com.ardeman.krida.core.session.GameRules
import com.ardeman.krida.core.session.LocalSeat

/**
 * Perang kapal has no host rule chips in v1. This phone is always you vs the computer
 * (two seats); nearby / online rooms also use two seats.
 */
object PerangKapalSettings {
    const val SEATS = 2

    /** Fixed local lineup: person, then computer. */
    val phoneSeats: List<LocalSeat> = listOf(LocalSeat.PERSON, LocalSeat.COMPUTER)

    fun init(@Suppress("UNUSED_PARAMETER") context: android.content.Context) = Unit

    val rules: GameRules<*, *> get() = StandardRules.perangKapal

    val gameId: String get() = PerangKapalGame.id
}
