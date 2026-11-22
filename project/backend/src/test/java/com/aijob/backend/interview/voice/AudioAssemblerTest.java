package com.aijob.backend.interview.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

class AudioAssemblerTest {

    @Test
    void mergesWavSegmentsAndRebuildsSingleHeader() {
        byte[] first = wav(320, (byte) 0x11);
        byte[] second = wav(160, (byte) 0x22);

        AudioAssembler.Assembled result = AudioAssembler.assemble(List.of(
                new AudioAssembler.Segment(first, "audio/wav"),
                new AudioAssembler.Segment(second, "audio/wav")));

        assertEquals("audio/wav", result.mimeType());
        assertEquals("wav", result.format());
        byte[] merged = result.audio();
        assertEquals(44 + 640 + 320, merged.length);
        assertEquals("RIFF", new String(merged, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(merged, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(44 + 640 + 320 - 8, le32(merged, 4));
        assertEquals(960, le32(merged, 40));
        byte[] pcm = java.util.Arrays.copyOfRange(merged, 44, merged.length);
        byte[] expected = new byte[960];
        java.util.Arrays.fill(expected, 0, 640, (byte) 0x11);
        java.util.Arrays.fill(expected, 640, 960, (byte) 0x22);
        assertArrayEquals(expected, pcm);
    }

    @Test
    void detectsWavFromMagicWhenMimeIsMissing() {
        byte[] first = wav(32, (byte) 0x01);
        byte[] second = wav(32, (byte) 0x02);

        AudioAssembler.Assembled result = AudioAssembler.assemble(List.of(
                new AudioAssembler.Segment(first, null),
                new AudioAssembler.Segment(second, "")));

        assertEquals("audio/wav", result.mimeType());
        assertEquals(44 + 128, result.audio().length);
    }

    @Test
    void concatenatesMp3FramesWithoutAddingHeader() {
        byte[] frame = new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64};
        AudioAssembler.Assembled result = AudioAssembler.assemble(List.of(
                new AudioAssembler.Segment(frame, "audio/mpeg"),
                new AudioAssembler.Segment(frame, "audio/mpeg")));

        assertEquals("audio/mpeg", result.mimeType());
        assertEquals("mp3", result.format());
        assertEquals(8, result.audio().length);
    }

    @Test
    void rejectsMixedFormats() {
        byte[] frame = new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64};
        assertThrows(IllegalArgumentException.class, () -> AudioAssembler.assemble(List.of(
                new AudioAssembler.Segment(wav(32, (byte) 0x01), "audio/wav"),
                new AudioAssembler.Segment(frame, "audio/mpeg"))));
    }

    @Test
    void rejectsEmptySegments() {
        assertThrows(IllegalArgumentException.class, () -> AudioAssembler.assemble(List.of()));
    }

    @Test
    void rejectsWavWithoutDataChunk() {
        byte[] broken = java.util.Arrays.copyOf(wav(32, (byte) 0x01), 36);
        assertThrows(IllegalArgumentException.class, () -> AudioAssembler.assemble(List.of(
                new AudioAssembler.Segment(broken, "audio/wav"))));
    }

    private static byte[] wav(int samples, byte value) {
        int dataLen = samples * 2;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeLe32(output, 36 + dataLen);
        output.writeBytes("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.writeBytes("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeLe32(output, 16);
        output.write((1) & 0xFF);
        output.write((1 >>> 8) & 0xFF);
        output.write((1) & 0xFF);
        output.write((1 >>> 8) & 0xFF);
        writeLe32(output, 16000);
        writeLe32(output, 32000);
        output.write((2) & 0xFF);
        output.write((2 >>> 8) & 0xFF);
        output.write((16) & 0xFF);
        output.write((16 >>> 8) & 0xFF);
        output.writeBytes("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeLe32(output, dataLen);
        byte[] pcm = new byte[dataLen];
        java.util.Arrays.fill(pcm, value);
        output.writeBytes(pcm);
        return output.toByteArray();
    }

    private static void writeLe32(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private static int le32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8
                | (data[offset + 2] & 0xFF) << 16 | (data[offset + 3] & 0xFF) << 24;
    }
}