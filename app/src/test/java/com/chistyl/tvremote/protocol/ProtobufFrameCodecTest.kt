package com.chistyl.tvremote.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProtobufFrameCodecTest {
    @Test fun roundTripsFramesAcrossVarintBoundaries() {
        listOf(0, 1, 127, 128, 255, 300, 16_384).forEach { size ->
            val payload = ByteArray(size) { (it % 251).toByte() }
            val output = ByteArrayOutputStream()
            ProtobufFrameCodec.writeFrame(output, payload)
            assertArrayEquals(payload, ProtobufFrameCodec.readFrame(ByteArrayInputStream(output.toByteArray())))
        }
    }
}
