package com.ardeman.krida.core.protocol

/**
 * Splits encoded messages into BLE-sized frames.
 *
 * Frame = [messageId, index, count] + payload slice. See docs/protocol.md.
 * One Chunker per sending side (it owns the message id counter).
 */
class Chunker {
    private var nextId = 0

    /** [mtu] is the negotiated ATT MTU. */
    fun split(message: ByteArray, mtu: Int): List<ByteArray> {
        val maxPayload = maxPayload(mtu)
        val count = maxOf(1, (message.size + maxPayload - 1) / maxPayload)
        require(count <= 255) { "Message too large for BLE framing: ${message.size} bytes" }

        val id = nextId
        nextId = (nextId + 1) and 0xFF

        return List(count) { index ->
            val start = index * maxPayload
            val end = minOf(start + maxPayload, message.size)
            val frame = ByteArray(HEADER_SIZE + (end - start))
            frame[0] = id.toByte()
            frame[1] = index.toByte()
            frame[2] = count.toByte()
            message.copyInto(frame, HEADER_SIZE, start, end)
            frame
        }
    }

    companion object {
        const val HEADER_SIZE = 3
        const val ATT_OVERHEAD = 3
        /** Smallest MTU BLE allows. */
        const val MIN_MTU = 23

        fun maxPayload(mtu: Int): Int {
            val payload = mtu.coerceAtLeast(MIN_MTU) - ATT_OVERHEAD - HEADER_SIZE
            check(payload > 0)
            return payload
        }
    }
}

/**
 * Rebuilds messages from frames, per sender. Frames of one message may arrive in any order;
 * a new message id from the same sender drops the unfinished previous one.
 */
class Reassembler {
    private class Partial(val id: Int, val count: Int) {
        val parts = arrayOfNulls<ByteArray>(count)
        var received = 0
    }

    private val pending = HashMap<String, Partial>()

    /** Returns the complete message when [frame] was the last missing piece, else null. */
    fun accept(sender: String, frame: ByteArray): ByteArray? {
        if (frame.size < Chunker.HEADER_SIZE) return null
        val id = frame[0].toInt() and 0xFF
        val index = frame[1].toInt() and 0xFF
        val count = frame[2].toInt() and 0xFF
        if (count == 0 || index >= count) return null

        var partial = pending[sender]
        if (partial == null || partial.id != id || partial.count != count) {
            partial = Partial(id, count)
            pending[sender] = partial
        }
        if (partial.parts[index] == null) {
            partial.parts[index] = frame.copyOfRange(Chunker.HEADER_SIZE, frame.size)
            partial.received++
        }
        if (partial.received < count) return null

        pending.remove(sender)
        val total = partial.parts.sumOf { it!!.size }
        val out = ByteArray(total)
        var offset = 0
        for (part in partial.parts) {
            part!!.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    fun clear(sender: String) {
        pending.remove(sender)
    }
}
