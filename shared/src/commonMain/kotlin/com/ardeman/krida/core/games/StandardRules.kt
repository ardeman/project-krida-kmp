package com.ardeman.krida.core.games

/**
 * The rules a quick match is played with, whatever the matched host saved for its own games:
 * strangers should not land in a game with house rules nobody showed them. Custom rules stay
 * in rooms someone creates (private or public). Congklak keeps the Indonesian tembak rule and
 * a random first player, so the host isn't always first.
 */
object StandardRules {
    val congklak: CongklakRules get() = CongklakRules(true, CongklakStarter.RANDOM)
    val ularTangga: UlarTanggaRules get() = UlarTanggaRules(UlarTanggaOptions())
    val ludo: LudoRules get() = LudoRules(LudoOptions())
    /** Perang kapal has no host options in v1; this is the game object itself. */
    val perangKapal: PerangKapalGame get() = PerangKapalGame
    /** Gaple without partners: matchmaking never pairs strangers into teams in v1. */
    val gaple: GapleRules get() = GapleRules(GapleOptions())
}
