package com.ardeman.krida.core

import com.ardeman.krida.core.session.LocalSeat.COMPUTER
import com.ardeman.krida.core.session.LocalSeat.EMPTY
import com.ardeman.krida.core.session.LocalSeat.PERSON
import com.ardeman.krida.core.session.LocalSeats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalSeatsTest {
    @Test
    fun tapCyclesPersonComputerEmpty() {
        var seats = listOf(PERSON, PERSON, PERSON, EMPTY)
        seats = LocalSeats.next(seats, 2, allowEmpty = true)
        assertEquals(listOf(PERSON, PERSON, COMPUTER, EMPTY), seats)
        seats = LocalSeats.next(seats, 2, allowEmpty = true)
        assertEquals(listOf(PERSON, PERSON, EMPTY, EMPTY), seats)
        seats = LocalSeats.next(seats, 2, allowEmpty = true)
        assertEquals(listOf(PERSON, PERSON, PERSON, EMPTY), seats)
    }

    @Test
    fun keepsTwoFilledSeats() {
        // Emptying seat 1 would leave one player: it goes back to a person.
        val seats = listOf(PERSON, COMPUTER, EMPTY, EMPTY)
        assertEquals(listOf(PERSON, PERSON, EMPTY, EMPTY), LocalSeats.next(seats, 1, allowEmpty = true))
    }

    @Test
    fun keepsOnePerson() {
        // The only person can't turn into a computer; emptying the seat would also leave no one.
        val seats = listOf(PERSON, COMPUTER, COMPUTER, EMPTY)
        assertEquals(seats, LocalSeats.next(seats, 0, allowEmpty = true))
        // With two computers left after emptying, still no person: unchanged.
        val two = listOf(PERSON, COMPUTER, EMPTY, EMPTY)
        assertEquals(two, LocalSeats.next(two, 0, allowEmpty = true))
    }

    @Test
    fun withoutEmptySeatsTogglesPersonAndComputer() {
        val seats = listOf(PERSON, PERSON)
        assertEquals(listOf(PERSON, COMPUTER), LocalSeats.next(seats, 1, allowEmpty = false))
        assertEquals(listOf(PERSON, PERSON), LocalSeats.next(listOf(PERSON, COMPUTER), 1, allowEmpty = false))
    }

    @Test
    fun encodeAndDecode() {
        val seats = listOf(PERSON, COMPUTER, EMPTY, PERSON)
        assertEquals("PC-P", LocalSeats.encode(seats))
        assertEquals(seats, LocalSeats.decode("PC-P", 4, listOf()))
        val fallback = listOf(PERSON, PERSON, EMPTY, EMPTY)
        assertEquals(fallback, LocalSeats.decode(null, 4, fallback))
        assertEquals(fallback, LocalSeats.decode("PX--", 4, fallback))
        assertEquals(fallback, LocalSeats.decode("CC--", 4, fallback))
        assertTrue(LocalSeats.isValid(listOf(COMPUTER, PERSON)))
        assertFalse(LocalSeats.isValid(listOf(PERSON, EMPTY)))
    }

    @Test
    fun coloursFollowSeatsOrSwitchWithAComputer() {
        val vs = listOf(PERSON, COMPUTER, EMPTY, EMPTY)
        assertEquals(listOf(0, 1, -1, -1), LocalSeats.colors(vs))
        // Person takes yellow; the computer keeps blue (seat 1).
        assertEquals(listOf(3, 1, -1, -1), LocalSeats.colors(vs, personColor = 3))
        // Person takes the computer's blue; the computer moves to red.
        assertEquals(listOf(1, 0, -1, -1), LocalSeats.colors(vs, personColor = 1))
        // Two people keep seat colours; personColor is ignored.
        assertEquals(
            listOf(0, 1, 2, -1),
            LocalSeats.colors(listOf(PERSON, COMPUTER, PERSON, EMPTY), personColor = 3),
        )
    }
}
