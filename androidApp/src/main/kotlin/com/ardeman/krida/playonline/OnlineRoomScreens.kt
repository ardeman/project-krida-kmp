package com.ardeman.krida.playonline

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R
import com.ardeman.krida.core.games.LudoGame
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.core.session.RoomCode
import com.ardeman.krida.nearby.CodeBoxes
import com.ardeman.krida.nearby.NearbyFrame
import com.ardeman.krida.nearby.card
import com.ardeman.krida.nearby.gameTitle
import com.ardeman.krida.nearby.subtitle
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.Segmented
import com.ardeman.krida.ui.Theme

@Composable
fun CreateOnlineRoomScreen(
    game: String,
    onBack: () -> Unit,
    onCreate: (public: Boolean, seats: Int) -> Unit,
    options: @Composable () -> Unit,
) {
    var public by rememberSaveable(game) { mutableStateOf(false) }
    var seats by rememberSaveable(game) { mutableIntStateOf(if ((game == UlarTanggaGame.id || game == LudoGame.id)) 4 else 2) }
    NearbyFrame(gameTitle(game), subtitle(true), true, stringResource(R.string.congklak_back), onBack) {
        Column(Modifier.weight(1f).fillMaxHeight().card().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RoomHeading(R.string.room_setup)
            RoomPlayerCount(game, seats) { seats = it }
            options()
        }
        Column(Modifier.weight(1f).fillMaxHeight().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RoomHeading(R.string.room_create)
            RoomDetail(R.string.room_who_can_join)
            Segmented(listOf(false to stringResource(R.string.room_private), true to stringResource(R.string.room_public)), public,
                stringResource(R.string.room_who_can_join), { public = it }, fill = true)
            RoomDetail(if (public) R.string.room_public_detail else R.string.room_private_detail)
            RoomDetail(R.string.room_code_also_works)
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.room_create), { onCreate(public, seats) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun JoinOnlineRoomScreen(
    game: String,
    code: String,
    onCode: (String) -> Unit,
    onScan: () -> Unit,
    onJoin: () -> Unit,
    onSearch: (Int) -> Unit,
    onBack: () -> Unit,
    error: Int?,
) {
    var seats by rememberSaveable(game) { mutableIntStateOf(if ((game == UlarTanggaGame.id || game == LudoGame.id)) 4 else 2) }
    val valid = RoomCode.isValidOnline(code)
    NearbyFrame(gameTitle(game), subtitle(true), true, stringResource(R.string.congklak_back), onBack) {
        Column(Modifier.weight(1f).fillMaxHeight().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RoomHeading(R.string.room_find)
            RoomDetail(R.string.room_find_detail)
            RoomPlayerCount(game, seats) { seats = it }
            RoomDetail(R.string.match_fallback_hint)
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.room_find_match), { onSearch(seats) }, Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f).fillMaxHeight().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RoomHeading(R.string.room_have_code)
            RoomDetail(R.string.room_join_code_detail)
            CodeBoxes(code, 6, valid, onCode, onJoin, compact = true)
            if (error != null) Text(stringResource(error), style = Theme.font(12, FontWeight.Bold, Theme.error), maxLines = 2)
            Spacer(Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.room_scan_qr), onScan, Modifier.fillMaxWidth(), tint = Theme.muted)
            PrimaryButton(stringResource(R.string.room_join), onJoin, Modifier.fillMaxWidth(), enabled = valid)
        }
    }
}

@Composable
private fun RoomPlayerCount(game: String, seats: Int, onChange: (Int) -> Unit) {
    if ((game == UlarTanggaGame.id || game == LudoGame.id)) {
        RoomDetail(R.string.play_players)
        Segmented((2..4).map { it to "$it" }, seats, stringResource(R.string.play_players), onChange, fill = true)
    } else RoomDetail(R.string.match_two_players)
}

@Composable
private fun RoomHeading(id: Int) = Text(stringResource(id), style = Theme.font(20, FontWeight.Black, Theme.ink))

@Composable
private fun RoomDetail(id: Int) = Text(stringResource(id), style = Theme.font(14, FontWeight.Bold, Theme.muted))
