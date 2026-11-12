package com.ardeman.krida.perangkapal

import com.ardeman.krida.core.games.PerangKapalGame
import com.ardeman.krida.core.games.StandardRules
import com.ardeman.krida.core.session.GameRules
import com.ardeman.krida.core.session.LocalSeat

/**
 * Perang kapal has no host rule chips in v1. This phone plays you vs the computer or two
 * people passing it (two seats); nearby / online rooms also use two seats.
 */
object PerangKapalSettings {
    const val SEATS = 2

    /** Local lineup to start from: person, then computer (the second seat can be a person). */
    val phoneSeats: List<LocalSeat> = listOf(LocalSeat.PERSON, LocalSeat.COMPUTER)

    fun init(@Suppress("UNUSED_PARAMETER") context: android.content.Context) = Unit

    val rules: GameRules<*, *> get() = StandardRules.perangKapal

    val gameId: String get() = PerangKapalGame.id
}
