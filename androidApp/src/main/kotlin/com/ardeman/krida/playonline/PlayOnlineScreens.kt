package com.ardeman.krida.playonline

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ardeman.krida.R
import com.ardeman.krida.core.protocol.Krida
import com.ardeman.krida.nearby.NearbyRoom
import com.ardeman.krida.ui.CircleIconButton
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.Theme
import com.ardeman.krida.ui.gameInsets
import kotlinx.coroutines.launch

private val cardShape = RoundedCornerShape(20.dp)

/**
 * Quick match for online play: select game and player count, connect to the matchmaker,
 * wait for a match, then transition to hosting or joining a room.
 */
@Composable
fun PlayOnlineScreen(
    game: String,
    onCancel: () -> Unit,
    onHost: (code: String, game: String) -> Unit,
    onJoin: (code: String, game: String) -> Unit,
) {
    BackHandler { onCancel() }
    
    var playerCount by mutableIntStateOf(2)
    var state by mutableStateOf<MatchState>(MatchState.SelectPlayers)
    
    Box(
        Modifier.fillMaxSize().background(Theme.backdropBrush).windowInsetsPadding(gameInsets),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (state) {
                MatchState.SelectPlayers -> {
                    SelectPlayersCard(
                        game = game,
                        playerCount = playerCount,
                        onPlayerCountChange = { playerCount = it },
                        onStart = { state = MatchState.Waiting },
                        onCancel = onCancel,
                    )
                }
                MatchState.Waiting -> {
                    WaitingForMatchCard(
                        game = game,
                        playerCount = playerCount,
                        onMatched = { role, code ->
                            if (role == "host") onHost(code, game) else onJoin(code, game)
                        },
                        onCancel = {
                            state = MatchState.SelectPlayers
                            onCancel()
                        },
                    )
                }
            }
        }
    }
}

sealed class MatchState {
    data object SelectPlayers : MatchState()
    data object Waiting : MatchState()
}

@Composable
private fun SelectPlayersCard(
    game: String,
    playerCount: Int,
    onPlayerCountChange: (Int) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(0.9f)
            .clip(cardShape)
            .background(Theme.surface)
            .border(1.5.dp, Theme.line, cardShape)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            stringResource(R.string.play_online_title),
            style = Theme.font(24, FontWeight.Black, Theme.ink),
            textAlign = TextAlign.Center
        )
        
        Text(
            stringResource(R.string.play_online_description),
            style = Theme.font(14, FontWeight.Normal, Theme.muted),
            textAlign = TextAlign.Center
        )
        
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.play_players),
                style = Theme.font(13, FontWeight.SemiBold, Theme.ink)
            )
            
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                (2..4).forEach { count ->
                    PlayerCountButton(
                        count = count,
                        selected = playerCount == count,
                        onClick = { onPlayerCountChange(count) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SecondaryButton(
                stringResource(R.string.button_cancel),
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                stringResource(R.string.button_search),
                onClick = onStart,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PlayerCountButton(
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (selected) Theme.accent else Theme.surface
    val borderColor = if (selected) Theme.accent else Theme.line
    
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = true) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$count",
            style = Theme.font(16, FontWeight.SemiBold, if (selected) Color.White else Theme.ink),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WaitingForMatchCard(
    game: String,
    playerCount: Int,
    onMatched: (role: String, code: String) -> Unit,
    onCancel: () -> Unit,
) {
    var seconds by mutableIntStateOf(20)
    var errorMessage by mutableStateOf<String?>(null)
    val scope = rememberCoroutineScope()
    
    // Connect to matchmaker
    val matchmaker = remember {
        QuickMatchClient(game, playerCount)
    }
    
    LaunchedEffect(matchmaker) {
        matchmaker.start(
            onMatched = { role, code ->
                onMatched(role, code)
            },
            onError = { error ->
                errorMessage = error
            }
        )
    }
    
    // Countdown timer for 20s fallback
    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                seconds--
                if (seconds > 0) {
                    handler.postDelayed(this, 1000L)
                } else {
                    // Timeout: fallback to computer play
                    errorMessage = "timeout"
                    matchmaker.close()
                }
            }
        }
        handler.postDelayed(runnable, 1000L)
        
        onDispose {
            handler.removeCallbacks(runnable)
            matchmaker.close()
        }
    }
    
    if (errorMessage == "timeout") {
        // After 20 seconds without a match, fall back to computer play
        Column(
            Modifier.fillMaxWidth(0.9f)
                .clip(cardShape)
                .background(Theme.surface)
                .border(1.5.dp, Theme.line, cardShape)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                stringResource(R.string.play_online_timeout_title),
                style = Theme.font(20, FontWeight.Black, Theme.ink),
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.play_online_timeout_detail),
                style = Theme.font(14, FontWeight.Normal, Theme.muted),
                textAlign = TextAlign.Center
            )
            PrimaryButton(
                stringResource(R.string.button_play_computer),
                onClick = onCancel  // Will trigger fallback to computer
            )
        }
    } else if (errorMessage != null) {
        Column(
            Modifier.fillMaxWidth(0.9f)
                .clip(cardShape)
                .background(Theme.surface)
                .border(1.5.dp, Theme.line, cardShape)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                stringResource(R.string.play_online_error_title),
                style = Theme.font(20, FontWeight.Black, Theme.ink),
                textAlign = TextAlign.Center
            )
            Text(
                errorMessage ?: "",
                style = Theme.font(14, FontWeight.Normal, Theme.muted),
                textAlign = TextAlign.Center
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SecondaryButton(
                    stringResource(R.string.button_cancel),
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    stringResource(R.string.button_retry),
                    onClick = { /* Retry logic */ },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        Column(
            Modifier.fillMaxWidth(0.9f)
                .clip(cardShape)
                .background(Theme.surface)
                .border(1.5.dp, Theme.line, cardShape)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Theme.accent,
                strokeWidth = 2.dp
            )
            
            Text(
                stringResource(R.string.play_online_searching_title),
                style = Theme.font(18, FontWeight.Black, Theme.ink),
                textAlign = TextAlign.Center
            )
            
            Text(
                stringResource(R.string.play_online_searching_detail, playerCount),
                style = Theme.font(14, FontWeight.Normal, Theme.muted),
                textAlign = TextAlign.Center
            )
            
            Text(
                "$seconds",
                style = Theme.font(32, FontWeight.Black, Theme.accent),
                textAlign = TextAlign.Center
            )
            
            SecondaryButton(
                stringResource(R.string.button_cancel),
                onClick = onCancel
            )
        }
    }
}
