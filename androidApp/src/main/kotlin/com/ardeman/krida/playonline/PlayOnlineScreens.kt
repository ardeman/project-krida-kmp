package com.ardeman.krida.playonline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ardeman.krida.core.games.LudoGame
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.R
import com.ardeman.krida.nearby.NearbyFrame
import com.ardeman.krida.nearby.NearbyRoom
import com.ardeman.krida.nearby.card
import com.ardeman.krida.nearby.gameTitle
import com.ardeman.krida.nearby.subtitle
import com.ardeman.krida.ui.CountdownRing
import com.ardeman.krida.ui.LoadingIndicator
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.Segmented
import com.ardeman.krida.ui.Theme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** A group stays together until full, or until everyone agrees on how to start. */
@Composable
fun PlayOnlineScreen(
    game: String,
    onCancel: () -> Unit,
    createHost: (code: String, humans: Int, bots: Int, onReady: () -> Unit) -> NearbyRoom,
    onHost: (NearbyRoom) -> Unit,
    onJoin: (String, String?) -> Unit,
    onComputer: (Int) -> Unit,
    target: Int = if ((game == UlarTanggaGame.id || game == LudoGame.id)) 4 else 2,
) {
    var attempt by remember(game) { mutableIntStateOf(0) }
    NearbyFrame(gameTitle(game), subtitle(true), true, stringResource(R.string.nearby_cancel), onCancel) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            key(attempt) { MatchSearch(game, target, onCancel, { attempt++ }, createHost, onHost, onJoin, onComputer) }
        }
    }
}

@Composable
private fun MatchSearch(
    game: String,
    target: Int,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    createHost: (String, Int, Int, () -> Unit) -> NearbyRoom,
    onHost: (NearbyRoom) -> Unit,
    onJoin: (String, String?) -> Unit,
    onComputer: (Int) -> Unit,
) {
    val client = remember { QuickMatchClient(game, target) }
    var humans by remember { mutableIntStateOf(1) }
    var seconds by remember { mutableIntStateOf(20) }
    var countdownVersion by remember { mutableIntStateOf(0) }
    var offer by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var agreed by remember { mutableIntStateOf(0) }
    var acceptedHumans by remember { mutableStateOf(false) }
    var agreedHumans by remember { mutableIntStateOf(0) }
    var voteSeconds by remember { mutableStateOf<Int?>(null) }
    var restart by remember { mutableStateOf<String?>(null) }
    var soloSeats by remember { mutableIntStateOf(target) }
    var preparing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(client) {
        var pendingRoom: NearbyRoom? = null
        var transferred = false
        fun fail() {
            failed = true
            client.close()
            pendingRoom?.leave()
            pendingRoom = null
        }
        client.start(onEvent = { event ->
            fun number(key: String) = event[key]?.jsonPrimitive?.int ?: error("Missing count")
            when (event["type"]?.jsonPrimitive?.content) {
                "queue" -> {
                    humans = number("humans")
                    seconds = number("remaining")
                    countdownVersion++
                    offer = event["offer"]?.jsonPrimitive?.boolean == true
                    accepted = event["accepted"]?.jsonPrimitive?.boolean == true
                    agreed = number("agreed")
                    acceptedHumans = event["acceptedHumans"]?.jsonPrimitive?.boolean == true
                    agreedHumans = event["agreedHumans"]?.jsonPrimitive?.int ?: 0
                    voteSeconds = event["voteRemaining"]?.jsonPrimitive?.int
                    // Why the search started over (vote timed out); kept until the next offer.
                    event["restart"]?.jsonPrimitive?.content?.let { restart = it }
                    if (offer) restart = null
                    revision = number("revision")
                }
                "prepare_host" -> {
                    preparing = true
                    pendingRoom = createHost(event["code"]!!.jsonPrimitive.content, number("humans"), number("bots"), client::hostReady)
                }
                "preparing" -> preparing = true
                "match_error" -> fail()
                "matched" -> {
                    client.close()
                    when (event["role"]?.jsonPrimitive?.content) {
                        "solo" -> onComputer(event["seats"]?.jsonPrimitive?.int ?: number("target"))
                        "host" -> {
                            val room = pendingRoom ?: error("Missing host room")
                            transferred = true
                            onHost(room)
                        }
                        "guest" -> onJoin(event["code"]!!.jsonPrimitive.content, event["reservation"]?.jsonPrimitive?.content)
                        else -> fail()
                    }
                }
            }
        }, onError = ::fail)
        onDispose {
            client.close()
            if (!transferred) pendingRoom?.leave()
        }
    }
    // Cosmetic only: the relay decides when the offer is available.
    LaunchedEffect(countdownVersion, offer, preparing, failed) {
        while (!offer && !preparing && !failed && seconds > 0) { delay(1000); seconds-- }
    }
    LaunchedEffect(countdownVersion, preparing, failed) {
        while (!preparing && !failed && (voteSeconds ?: 0) > 0) { delay(1000); voteSeconds = voteSeconds?.minus(1) }
    }
    Column(
        Modifier.width(480.dp).card().padding(horizontal = 24.dp, vertical = if (offer) 16.dp else 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (offer) 6.dp else 12.dp),
    ) {
        val voting = voteSeconds
        when {
            failed -> Box(Modifier.size(64.dp).background(Theme.error.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_error), null, Modifier.size(32.dp), tint = Theme.error)
            }
            preparing -> LoadingIndicator()
            offer && voting != null -> CountdownRing(voting, VOTE_SECONDS, size = 52.dp)
            offer -> Box(Modifier.size(32.dp).background(Theme.chip, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_computer), null, Modifier.size(32.dp), tint = Theme.accent)
            }
            else -> CountdownRing(seconds, SEARCH_SECONDS, size = 64.dp)
        }
        Title(stringResource(when {
            failed -> R.string.match_error_title
            preparing -> R.string.match_preparing
            offer -> R.string.match_offer_title
            else -> R.string.play_online_searching_title
        }))
        Detail(when {
            failed -> stringResource(R.string.match_error_detail)
            preparing -> stringResource(R.string.match_preparing_detail)
            offer && humans == 1 -> stringResource(R.string.match_solo_detail)
            offer -> stringResource(R.string.match_offer_detail, humans, target, target - humans)
            else -> listOfNotNull(
                when (restart) {
                    "no_votes" -> stringResource(R.string.match_restart_no_votes)
                    "tie" -> stringResource(R.string.match_restart_tie)
                    else -> null
                },
                stringResource(R.string.match_search_detail, humans, target),
            ).joinToString("\n")
        })
        if (offer && !preparing && !failed) {
            if (humans == 1 && (game == UlarTanggaGame.id || game == LudoGame.id)) Segmented(
                (2..4).map { it to "$it" }, soloSeats, stringResource(R.string.play_players), { soloSeats = it }, fill = true,
            )
            if (humans >= 2) {
                // A live poll: every player sees how the group is voting before and after choosing.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PrimaryButton(stringResource(R.string.match_play_humans, humans), { client.acceptHumans(revision) }, Modifier.fillMaxWidth(), enabled = !acceptedHumans)
                        VoteTally(agreedHumans, humans, acceptedHumans)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PrimaryButton(stringResource(R.string.match_play_bots), { client.acceptBots(revision) }, Modifier.fillMaxWidth(), enabled = !accepted)
                        VoteTally(agreed, humans, accepted)
                    }
                }
                val undecided = (humans - agreed - agreedHumans).coerceAtLeast(0)
                if (undecided > 0) Text(
                    pluralStringResource(R.plurals.match_undecided, undecided, undecided),
                    style = Theme.font(12, FontWeight.Bold, Theme.muted), textAlign = TextAlign.Center,
                )
            } else PrimaryButton(
                stringResource(R.string.match_play_bots),
                {
                    // Leave the queue before starting locally; no relay approval is needed.
                    client.close()
                    onComputer(soloSeats)
                },
                Modifier.width(220.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.nearby_cancel), onCancel, Modifier.weight(1f), tint = Theme.muted)
                SecondaryButton(stringResource(R.string.match_keep_searching), { client.keepSearching() }, Modifier.weight(1f), tint = Theme.muted)
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(if (failed) R.string.congklak_back else R.string.nearby_cancel), onCancel, Modifier.widthIn(min = 130.dp), tint = Theme.muted)
            if (failed) PrimaryButton(stringResource(R.string.nearby_try_again), onRetry, Modifier.weight(1f))
        }
    }
}

/** One dot per player found, filled for each vote this option has, plus the count. */
@Composable
private fun VoteTally(votes: Int, players: Int, mine: Boolean) = Row(
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
) {
    repeat(players) { index ->
        Box(Modifier.size(8.dp).background(if (index < votes) Theme.accent else Theme.line, CircleShape))
    }
    Spacer(Modifier.width(4.dp))
    Text(
        if (mine) stringResource(R.string.match_your_vote, votes, players) else stringResource(R.string.match_votes, votes, players),
        style = Theme.font(12, FontWeight.Bold, if (mine) Theme.accent else Theme.muted),
    )
}

/** The relay's search window and vote window (relay/src/group-matchmaker.ts), for the rings. */
private const val SEARCH_SECONDS = 20
private const val VOTE_SECONDS = 15

@Composable
private fun Title(text: String) = Text(text, style = Theme.font(22, FontWeight.Black, Theme.ink), textAlign = TextAlign.Center)

@Composable
private fun Detail(text: String) = Text(text, style = Theme.font(14, FontWeight.Bold, Theme.muted), textAlign = TextAlign.Center)
