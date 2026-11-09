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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.R
import com.ardeman.krida.nearby.NearbyFrame
import com.ardeman.krida.nearby.NearbyRoom
import com.ardeman.krida.nearby.card
import com.ardeman.krida.nearby.gameTitle
import com.ardeman.krida.nearby.subtitle
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
    onJoin: (String) -> Unit,
    onComputer: (Int) -> Unit,
) {
    var playerCount by remember(game) { mutableIntStateOf(if (game == UlarTanggaGame.id) 4 else 2) }
    var searching by remember(game) { mutableStateOf(false) }
    var attempt by remember(game) { mutableIntStateOf(0) }
    NearbyFrame(gameTitle(game), subtitle(true), true, stringResource(R.string.nearby_cancel), onCancel) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (searching) key(attempt) {
                MatchSearch(game, playerCount, onCancel, { attempt++ }, createHost, onHost, onJoin, onComputer)
            } else Column(
                Modifier.width(480.dp).card().padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Title(stringResource(R.string.play_online_quick_match))
                Detail(stringResource(R.string.play_online_description))
                if (game == UlarTanggaGame.id) {
                    Text(stringResource(R.string.play_players), Modifier.fillMaxWidth(), style = Theme.font(13, FontWeight.ExtraBold, Theme.muted))
                    Segmented((2..4).map { it to "$it" }, playerCount, stringResource(R.string.play_players), { playerCount = it }, fill = true)
                } else Detail(stringResource(R.string.match_two_players))
                Detail(stringResource(R.string.match_fallback_hint))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(stringResource(R.string.nearby_cancel), onCancel, Modifier.weight(1f), tint = Theme.muted)
                    PrimaryButton(stringResource(R.string.button_search), { searching = true }, Modifier.weight(1f))
                }
            }
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
    onJoin: (String) -> Unit,
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
                        "guest" -> onJoin(event["code"]!!.jsonPrimitive.content)
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
    Column(
        Modifier.width(480.dp).card().padding(horizontal = 24.dp, vertical = if (offer) 16.dp else 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (offer) 6.dp else 12.dp),
    ) {
        Box(Modifier.size(if (offer && !preparing && !failed) 32.dp else 64.dp).background(if (failed) Theme.error.copy(alpha = 0.14f) else if (offer && !preparing) Theme.chip else androidx.compose.ui.graphics.Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            when {
                failed -> Icon(painterResource(R.drawable.ic_error), null, Modifier.size(32.dp), tint = Theme.error)
                offer && !preparing -> Icon(painterResource(R.drawable.ic_computer), null, Modifier.size(32.dp), tint = Theme.accent)
                else -> LoadingIndicator()
            }
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
            else -> stringResource(R.string.match_search_detail, humans, target, seconds)
        })
        if (offer && !preparing && !failed) {
            if (humans == 1 && game == UlarTanggaGame.id) Segmented(
                (2..4).map { it to "$it" }, soloSeats, stringResource(R.string.play_players), { soloSeats = it }, fill = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (humans >= 2) PrimaryButton(
                    if (acceptedHumans) stringResource(R.string.match_agreed, agreedHumans, humans)
                    else stringResource(R.string.match_play_humans, humans),
                    { client.acceptHumans(revision) }, Modifier.weight(1f), enabled = !acceptedHumans,
                )
                PrimaryButton(
                    if (accepted) stringResource(R.string.match_agreed, agreed, humans) else stringResource(R.string.match_play_bots),
                    {
                        if (humans == 1) {
                            // Leave the queue before starting locally; no relay approval is needed.
                            client.close()
                            onComputer(soloSeats)
                        } else client.acceptBots(revision)
                    },
                    Modifier.weight(1f), enabled = !accepted,
                )
            }
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

@Composable
private fun Title(text: String) = Text(text, style = Theme.font(22, FontWeight.Black, Theme.ink), textAlign = TextAlign.Center)

@Composable
private fun Detail(text: String) = Text(text, style = Theme.font(14, FontWeight.Bold, Theme.muted), textAlign = TextAlign.Center)
