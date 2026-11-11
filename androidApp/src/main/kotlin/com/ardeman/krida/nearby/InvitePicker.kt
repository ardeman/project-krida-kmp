package com.ardeman.krida.nearby

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R
import com.ardeman.krida.account.Account
import com.ardeman.krida.account.Invites
import com.ardeman.krida.ui.CircleIconButton
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.Theme
import kotlinx.coroutines.delay

/** Where a friend stands in this room's picker. */
internal enum class InviteState { NONE, SENT, JOINED }

/**
 * The invites sent from [room] that still count, by friend id: JOINED while that friend
 * holds a seat, SENT until the invite runs out. Recomposes when the next one runs out.
 */
@Composable
internal fun inviteStates(room: NearbyRoom): Map<String, InviteState> {
    var tick by remember { mutableIntStateOf(0) }
    val mine = Invites.sent.filterValues { it.code == room.code }
    val now = remember(tick, mine) { SystemClock.elapsedRealtime() }
    val next = mine.values.map { it.until }.filter { it > now }.minOrNull()
    LaunchedEffect(next) {
        if (next != null) {
            delay(next - SystemClock.elapsedRealtime() + 50)
            tick++
        }
    }
    return mine.mapValues { (_, sent) ->
        when {
            sent.id.isNotEmpty() && sent.id in room.joinedInvites -> InviteState.JOINED
            sent.until > now -> InviteState.SENT
            else -> InviteState.NONE
        }
    }
}

/** Under the seats: the friends whose invite is still waiting ("Budi · invite sent"). */
@Composable
internal fun WaitingInvites(room: NearbyRoom) {
    val states = inviteStates(room)
    val player = stringResource(R.string.account_player)
    val names = Account.friends.filter { states[it.id] == InviteState.SENT }.map { it.name.ifEmpty { player } }
    if (names.isEmpty()) return
    Text(
        stringResource(R.string.invite_waiting, names.joinToString(", ")),
        style = Theme.font(13, FontWeight.Bold, Theme.muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** The friends list in place of the seats: the host invites friends to the open seats. */
@Composable
internal fun ColumnScope.InvitePicker(room: NearbyRoom, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        Invites.sendFailed = false
        Account.loadFriends()
    }
    val states = inviteStates(room)
    val open = room.maxPlayers - room.players.size
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircleIconButton(R.drawable.ic_chevron_left, stringResource(R.string.congklak_back), onBack)
        Text(stringResource(R.string.invite_friends), style = Theme.font(17, FontWeight.Black, Theme.ink), maxLines = 1, modifier = Modifier.weight(1f))
        Text(pluralStringResource(R.plurals.invite_open_seats, open, open), style = Theme.font(13, FontWeight.Bold, Theme.muted), maxLines = 1)
    }
    if (Invites.sendFailed) Text(stringResource(R.string.invite_failed), style = Theme.font(13, FontWeight.Bold, Theme.error))
    if (Account.friends.isEmpty()) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.account_friends_empty_title), style = Theme.font(17, FontWeight.Black, Theme.ink))
                Text(stringResource(R.string.invite_no_friends), style = Theme.font(14, FontWeight.Bold, Theme.muted))
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
        Account.friends.forEachIndexed { index, friend ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.line))
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).background(Theme.chip, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = Theme.muted, modifier = Modifier.size(18.dp))
                }
                Text(
                    friend.name.ifEmpty { stringResource(R.string.account_player) },
                    style = Theme.font(16, FontWeight.Bold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                when (states[friend.id] ?: InviteState.NONE) {
                    InviteState.JOINED -> Text(stringResource(R.string.invite_joined), style = Theme.font(14, FontWeight.Bold, Theme.muted))
                    InviteState.SENT -> Text(stringResource(R.string.invite_sent), style = Theme.font(14, FontWeight.Bold, Theme.muted))
                    InviteState.NONE -> PrimaryButton(
                        stringResource(R.string.invite_action),
                        onClick = { room.hostKey?.let { Invites.send(friend.id, room.code, it) } },
                        height = 44.dp,
                    )
                }
            }
        }
    }
}
