package com.ardeman.krida.core

import com.ardeman.krida.core.protocol.Chunker
import com.ardeman.krida.core.protocol.Codec
import com.ardeman.krida.core.protocol.Message
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.protocol.PlayerInfo
import com.ardeman.krida.core.protocol.Reassembler
import com.ardeman.krida.core.session.RoomCode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProtocolTest {

    @Test
    fun colourIsOptionalOnTheWire() {
        val bytes = Codec.encode(Message.Lobby(listOf(PlayerInfo(0, "Redo", Platform.ANDROID)), you = 0))
        assertTrue("color" !in bytes.decodeToString())
        assertEquals("{\"type\":\"color\",\"color\":2}", Codec.encode(Message.PickColor(2)).decodeToString())
    }

    @Test
    fun codecRoundTripsEveryMessage() {
        val players = listOf(PlayerInfo(0, "Redo", Platform.ANDROID, local = true), PlayerInfo(1, "Ayu", Platform.IOS))
        val messages = listOf(
            Message.Hello(1, "Ayu", Platform.IOS),
            Message.Hello(1, "Ayu", Platform.IOS, rejoinToken = "abc"),
            Message.Welcome(1, "KRD4", "congklak", "tok"),
            Message.Reject("full"),
            Message.Lobby(players, you = 1),
            Message.Lobby(players.map { it.copy(color = 3 - it.seat) }, you = 0),
            Message.PickColor(2),
            Message.Intent(3, buildJsonObject { put("pit", 2) }),
            Message.State(7, 1, false, buildJsonObject { put("total", 5) }, players),
            Message.Invalid(3, "not_your_turn"),
            Message.Matched("host", "ABC234"),
            Message.Matched("guest", null),
            Message.Ping(123L),
            Message.Pong(123L),
            Message.Bye,
        )
        for (m in messages) assertEquals(m, Codec.decode(Codec.encode(m)))
    }

    @Test
    fun wireFormatUsesTypeDiscriminator() {
        val text = Codec.encode(Message.Hello(1, "Ayu", Platform.IOS)).decodeToString()
        assertEquals("""{"type":"hello","protocol":1,"name":"Ayu","platform":"ios"}""", text)
    }

    @Test
    fun decoderIgnoresUnknownFieldsAndGarbage() {
        val future = """{"type":"ping","t":5,"addedInV2":true}""".encodeToByteArray()
        assertEquals(Message.Ping(5), Codec.decode(future))
        assertNull(Codec.decode("not json".encodeToByteArray()))
        assertNull(Codec.decode("""{"type":"unknown"}""".encodeToByteArray()))
    }

    @Test
    fun chunkerRoundTripsAcrossMtus() {
        val random = Random(42)
        for (mtu in listOf(23, 185, 512)) {
            for (size in listOf(0, 1, 16, 17, 18, 179, 180, 1000, 3000)) {
                val msg = random.nextBytes(size)
                val frames = Chunker().split(msg, mtu)
                assertTrue(frames.all { it.size <= mtu - Chunker.ATT_OVERHEAD })
                val r = Reassembler()
                val out = frames.shuffled(random).mapNotNull { r.accept("a", it) }
                assertEquals(1, out.size, "mtu=$mtu size=$size")
                assertContentEquals(msg, out[0])
            }
        }
    }

    @Test
    fun reassemblerKeepsSendersApart() {
        val a = Random(1).nextBytes(500)
        val b = Random(2).nextBytes(400)
        val fa = Chunker().split(a, 23)
        val fb = Chunker().split(b, 23)
        val r = Reassembler()
        val done = mutableMapOf<String, ByteArray>()
        for (i in 0 until maxOf(fa.size, fb.size)) {
            fa.getOrNull(i)?.let { f -> r.accept("a", f)?.let { done["a"] = it } }
            fb.getOrNull(i)?.let { f -> r.accept("b", f)?.let { done["b"] = it } }
        }
        assertContentEquals(a, done["a"])
        assertContentEquals(b, done["b"])
    }

    @Test
    fun roomCodes() {
        val code = RoomCode.generate(Random(7))
        assertTrue(RoomCode.isValid(code))
        assertEquals(code, RoomCode.parse(RoomCode.advertisedName(code)))
        assertEquals(code, RoomCode.parse(RoomCode.joinUrl(code)))
        assertEquals(code, RoomCode.parse(code.lowercase()))
        assertNull(RoomCode.parse("KRD0")) // 0 is not in the alphabet
    }

    @Test
    fun onlineRoomCodesAndShareLinks() {
        val code = RoomCode.generateOnline(Random(7))
        assertEquals(6, code.length)
        assertTrue(RoomCode.isValidOnline(code))
        assertFalse(RoomCode.isValid(code))
        assertEquals("https://krida.ardeman.com/r/$code", RoomCode.shareUrl(code))

        assertEquals(code, RoomCode.parseOnline(RoomCode.shareUrl(code)))
        assertEquals(code, RoomCode.parseOnline("krida.ardeman.com/r/${code.lowercase()}/"))
        assertEquals(code, RoomCode.parseOnline("HTTPS://Krida.Ardeman.com/r/$code?utm=x#top"))
        assertEquals(code, RoomCode.parseOnline("krida://join?room=$code"))
        assertEquals(code, RoomCode.parseOnline(" ${code.lowercase()} "))

        assertNull(RoomCode.parseOnline("KRD4"))                       // a nearby code
        assertNull(RoomCode.parseOnline("https://krida.ardeman.com/r/AB0OI1"))  // 0, O, I, 1 not used
        assertNull(RoomCode.parseOnline("https://example.com/x/$code"))
        assertNull(RoomCode.parse(code))                               // nearby parsing stays 4 chars
    }
}
