package com.chistyl.tvremote.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Android TV Remote v2 messages are protobuf payloads prefixed by an unsigned varint length.
 * A single-byte prefix is incorrect for payloads whose encoded size is 128 bytes or greater.
 */
object ProtobufFrameCodec {
    private const val MAX_FRAME_SIZE = 4 * 1024 * 1024

    @Throws(EOFException::class)
    fun readFrame(input: InputStream): ByteArray {
        val size = readVarint(input)
        require(size in 0..MAX_FRAME_SIZE) { "Invalid protobuf frame size: $size" }
        val payload = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(payload, offset, size - offset)
            if (count < 0) throw EOFException("Stream ended inside protobuf frame")
            offset += count
        }
        return payload
    }

    @Synchronized
    fun writeFrame(output: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_SIZE) { "Protobuf frame is too large: ${payload.size}" }
        writeVarint(output, payload.size)
        output.write(payload)
        output.flush()
    }

    private fun readVarint(input: InputStream): Int {
        var result = 0
        var shift = 0
        while (shift < 35) {
            val value = input.read()
            if (value < 0) throw EOFException("Stream ended before protobuf length prefix")
            result = result or ((value and 0x7F) shl shift)
            if ((value and 0x80) == 0) {
                require(result >= 0) { "Negative protobuf frame size" }
                return result
            }
            shift += 7
        }
        throw IllegalArgumentException("Malformed protobuf varint length")
    }

    private fun writeVarint(output: OutputStream, value: Int) {
        var remaining = value
        while ((remaining and 0x7F.inv()) != 0) {
            output.write((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        output.write(remaining)
    }
}
