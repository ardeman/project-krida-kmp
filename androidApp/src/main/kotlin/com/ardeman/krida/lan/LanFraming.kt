package com.ardeman.krida.lan

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 4-byte big-endian length + payload (docs/protocol.md). Same as the iOS LAN transport. */
internal object LanFraming {
    /** Match the online relay's cap so a bad peer cannot flood the phone. */
    const val MAX_MESSAGE = 64 * 1024

    fun write(out: OutputStream, data: ByteArray) {
        if (data.size > MAX_MESSAGE) throw IOException("message too large")
        val dos = DataOutputStream(out)
        dos.writeInt(data.size)
        dos.write(data)
        dos.flush()
    }

    /** Null at EOF. Throws if the length is illegal. */
    fun read(input: InputStream): ByteArray? {
        val dis = DataInputStream(input)
        val size = try {
            dis.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (size < 0 || size > MAX_MESSAGE) throw IOException("bad length $size")
        val data = ByteArray(size)
        dis.readFully(data)
        return data
    }
}
