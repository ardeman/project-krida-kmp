package com.ardeman.krida.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R

/**
 * Krida's sounds: effects and voice lines in Indonesian and English (the `.m4a` files in `res/raw`, made with ElevenLabs in
 * project-pamerstudio-node/krida), and looping background music while the app is in front.
 * Sound and music are toggled apart and remembered. Call [init] once, then [setActive] from
 * the activity's onStart/onStop.
 */
object Sounds {
    enum class Effect(val res: Int, val volume: Float) {
        // Seed clicks repeat many times a move, so they sit under the voice and the music.
        Sow(R.raw.sfx_sow, 0.45f),
        Store(R.raw.sfx_store, 0.6f),
        Capture(R.raw.sfx_capture, 1f),
        Turn(R.raw.sfx_turn, 1f),
        Win(R.raw.sfx_win, 1f),
        Lose(R.raw.sfx_lose, 1f),
        Tap(R.raw.sfx_tap, 0.4f),
        // Ular tangga: the die's clack on each bounce, and a pawn's softer tok on each square.
        Die(R.raw.sfx_die, 0.6f),
        Step(R.raw.sfx_step, 0.45f),
    }

    /** Each line in Indonesian and in English; [english] picks which one is spoken. */
    enum class Line(val res: Int, val resEn: Int) {
        YourTurn(R.raw.vo_your_turn, R.raw.vo_your_turn_en),
        TheirTurn(R.raw.vo_their_turn, R.raw.vo_their_turn_en),
        Shoot(R.raw.vo_shoot, R.raw.vo_shoot_en),
        Win(R.raw.vo_win, R.raw.vo_win_en),
        Lose(R.raw.vo_lose, R.raw.vo_lose_en),
        Draw(R.raw.vo_draw, R.raw.vo_draw_en),
    }

    private const val PREFS = "sounds"
    private const val MUSIC_VOLUME = 0.35f

    private lateinit var context: Context
    private var pool: SoundPool? = null
    private val ids = mutableMapOf<Any, Int>()
    private val idsEn = mutableMapOf<Line, Int>()
    private var music: MediaPlayer? = null
    private var lineStream = 0
    private val gameStreams = mutableListOf<Int>()
    private var active = false

    /**
     * Speak the English lines. Set by the activity from the language its resources show; the
     * application context this object holds doesn't follow the in-app language before Android 13.
     */
    var english = false

    var soundOn by mutableStateOf(true)
        private set
    var musicOn by mutableStateOf(true)
        private set

    fun init(app: Context) {
        if (pool != null) return
        context = app.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        soundOn = prefs.getBoolean("soundOn", true)
        musicOn = prefs.getBoolean("musicOn", true)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attributes).build().also { pool ->
            Effect.entries.forEach { ids[it] = pool.load(context, it.res, 1) }
            Line.entries.forEach {
                ids[it] = pool.load(context, it.res, 1)
                idsEn[it] = pool.load(context, it.resEn, 1)
            }
        }
    }

    /** Not yet loaded sounds are skipped (SoundPool loads in the background). */
    fun play(effect: Effect) {
        if (!soundOn) return
        val id = ids[effect] ?: return
        val stream = pool?.play(id, effect.volume, effect.volume, 1, 0, 1f) ?: return
        if (effect != Effect.Tap && stream != 0) {
            gameStreams.add(stream)
        }
    }

    /** A voice line cuts off the one before it, so two never talk over each other. */
    fun say(line: Line) {
        if (!soundOn) return
        val pool = pool ?: return
        val id = (if (english) idsEn[line] else ids[line]) ?: return
        if (lineStream != 0) pool.stop(lineStream)
        lineStream = pool.play(id, 1f, 1f, 2, 0, 1f)
    }

    /** Stop board effects and voice on exit; menu music and tap feedback continue. */
    fun stopGameAudio() {
        gameStreams.forEach { pool?.stop(it) }
        gameStreams.clear()
        if (lineStream != 0) pool?.stop(lineStream)
        lineStream = 0
    }

    fun toggleSound() {
        soundOn = !soundOn
        save("soundOn", soundOn)
    }

    fun toggleMusic() {
        musicOn = !musicOn
        save("musicOn", musicOn)
        updateMusic()
    }

    /** Music plays only while the app is in the foreground. */
    fun setActive(value: Boolean) {
        active = value
        updateMusic()
    }

    private fun save(key: String, value: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()

    private fun updateMusic() {
        if (active && musicOn) {
            val player = music ?: MediaPlayer.create(context, R.raw.bgm)?.apply {
                isLooping = true
                setVolume(MUSIC_VOLUME, MUSIC_VOLUME)
            }?.also { music = it }
            if (player != null && !player.isPlaying) player.start()
        } else {
            music?.release()
            music = null
        }
    }
}

/** Sound and music on/off, as two round buttons. */
@Composable
fun SoundToggles(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircleIconButton(
            if (Sounds.soundOn) R.drawable.ic_sound_on else R.drawable.ic_sound_off,
            stringResource(if (Sounds.soundOn) R.string.sound_off else R.string.sound_on),
            Sounds::toggleSound,
        )
        CircleIconButton(
            if (Sounds.musicOn) R.drawable.ic_music_on else R.drawable.ic_music_off,
            stringResource(if (Sounds.musicOn) R.string.music_off else R.string.music_on),
            Sounds::toggleMusic,
        )
    }
}
