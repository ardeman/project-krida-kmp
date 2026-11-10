package com.ardeman.krida.core.session

/** Who sits at a seat of a game played on one phone. */
enum class LocalSeat { PERSON, COMPUTER, EMPTY }

/**
 * The seat tiles of a game on one phone: people pass the phone round, computers play on it,
 * empty seats are left out. Every table keeps at least [MIN_FILLED] filled seats and at least
 * one person.
 */
object LocalSeats {
    const val MIN_FILLED = 2

    /** Whether [seats] can start a game. */
    fun isValid(seats: List<LocalSeat>): Boolean =
        seats.count { it != LocalSeat.EMPTY } >= MIN_FILLED && seats.any { it == LocalSeat.PERSON }

    /**
     * The seats after a tap on [index]: person → computer → empty → person (empty is skipped
     * when [allowEmpty] is false), passing over a kind that would break [isValid]. Returns
     * [seats] unchanged when no other kind fits.
     */
    fun next(seats: List<LocalSeat>, index: Int, allowEmpty: Boolean): List<LocalSeat> {
        if (index !in seats.indices) return seats
        val order = if (allowEmpty) LocalSeat.entries else listOf(LocalSeat.PERSON, LocalSeat.COMPUTER)
        val at = order.indexOf(seats[index]).coerceAtLeast(0)
        for (step in 1 until order.size) {
            val candidate = seats.toMutableList().also { it[index] = order[(at + step) % order.size] }
            if (isValid(candidate)) return candidate
        }
        return seats
    }

    /** Swift-friendly overload with empty seats allowed. */
    fun next(seats: List<LocalSeat>, index: Int): List<LocalSeat> = next(seats, index, allowEmpty = true)

    /** Seats as a short string for settings: P (person), C (computer), - (empty). */
    fun encode(seats: List<LocalSeat>): String = seats.joinToString("") {
        when (it) {
            LocalSeat.PERSON -> "P"
            LocalSeat.COMPUTER -> "C"
            LocalSeat.EMPTY -> "-"
        }
    }

    /** Reads [encode]'s string back as [size] seats; [fallback] when it is missing or invalid. */
    fun decode(text: String?, size: Int, fallback: List<LocalSeat>): List<LocalSeat> {
        if (text == null || text.length != size) return fallback
        val seats = text.map {
            when (it) {
                'P' -> LocalSeat.PERSON
                'C' -> LocalSeat.COMPUTER
                '-' -> LocalSeat.EMPTY
                else -> return fallback
            }
        }
        return if (isValid(seats)) seats else fallback
    }

    /**
     * Pawn colour per seat index (`-1` when empty). Colours stay on the seat index when free.
     * With exactly one person and [personColor] in `0 until colorCount`, that person takes
     * [personColor] (switching with a computer that held it, or a free colour).
     */
    fun colors(seats: List<LocalSeat>, personColor: Int = -1, colorCount: Int = 4): List<Int> {
        val assigned = MutableList(seats.size) { -1 }
        val used = mutableSetOf<Int>()
        fun take(prefer: Int): Int {
            val c = if (prefer in 0 until colorCount && prefer !in used) prefer
            else (0 until colorCount).first { it !in used }
            used += c
            return c
        }
        val people = seats.indices.filter { seats[it] == LocalSeat.PERSON }
        if (people.size == 1 && personColor in 0 until colorCount) {
            assigned[people[0]] = take(personColor)
        }
        for (i in seats.indices) {
            if (seats[i] != LocalSeat.EMPTY && assigned[i] < 0) assigned[i] = take(i)
        }
        return assigned
    }
}
