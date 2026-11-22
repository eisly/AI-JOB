package com.aijob.backend.interview.voice;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 题目播报分段音频合并。
 *
 * MP3 等连续码流可直接首尾相接；WAV 每段都带 RIFF 头，必须取出 PCM 数据帧后重建文件头，
 * 否则浏览器会把第二段当成损坏数据。合并前先确认所有分段格式一致。
 */
public final class AudioAssembler {

    private AudioAssembler() {
    }

    public record Segment(byte[] data, String mimeType) {
    }

    public record Assembled(byte[] audio, String mimeType, String format) {
    }

    public static Assembled assemble(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("没有可合并的音频分段");
        }
        String format = detectFormat(segments.getFirst());
        if (!"wav".equals(format)) {
            for (Segment segment : segments) {
                if (!format.equals(detectFormat(segment))) {
                    throw new IllegalArgumentException("音频分段格式不一致");
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (Segment segment : segments) {
                output.writeBytes(segment.data());
            }
            return new Assembled(output.toByteArray(), mimeOf(format), format);
        }
        byte[] merged = mergeWav(segments);
        return new Assembled(merged, "audio/wav", "wav");
    }

    static String detectFormat(Segment segment) {
        String mime = segment.mimeType() == null ? "" : segment.mimeType().toLowerCase().split(";")[0].trim();
        if ("audio/wav".equals(mime) || "audio/wave".equals(mime) || "audio/x-wav".equals(mime)) return "wav";
        if ("audio/mpeg".equals(mime) || "audio/mp3".equals(mime)) return "mp3";
        if ("audio/ogg".equals(mime)) return "ogg";
        if ("audio/mp4".equals(mime)) return "mp4";
        return detectByMagic(segment.data());
    }

    private static String detectByMagic(byte[] data) {
        if (data != null && data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E') {
            return "wav";
        }
        if (data != null && data.length >= 3 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') return "mp3";
        if (data != null && data.length >= 4 && data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S') {
            return "ogg";
        }
        return "unknown";
    }

    private static String mimeOf(String format) {
        return switch (format) {
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "mp4" -> "audio/mp4";
            default -> "audio/mpeg";
        };
    }

    private static byte[] mergeWav(List<Segment> segments) {
        WavParts first = parseWav(segments.getFirst().data());
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (Segment segment : segments) {
            WavParts parts = parseWav(segment.data());
            if (!Arrays.equals(first.fmt, parts.fmt)) {
                throw new IllegalArgumentException("WAV 分段格式不一致");
            }
            data.writeBytes(parts.data);
        }
        byte[] pcm = data.toByteArray();
        int padding = pcm.length & 1;
        int riffSize = 4 + 8 + first.fmt.length + 8 + pcm.length + padding;
        ByteArrayOutputStream output = new ByteArrayOutputStream(8 + riffSize);
        output.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeLe32(output, riffSize);
        output.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes("fmt ".getBytes(StandardCharsets.US_ASCII));
        writeLe32(output, first.fmt.length);
        output.writeBytes(first.fmt);
        output.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
        writeLe32(output, pcm.length);
        output.writeBytes(pcm);
        if (padding == 1) output.write(0);
        return output.toByteArray();
    }

    private static WavParts parseWav(byte[] wav) {
        if (wav == null || wav.length < 12 || detectByMagic(wav) == null || !"wav".equals(detectByMagic(wav))) {
            throw new IllegalArgumentException("WAV 分段缺少 RIFF 头");
        }
        byte[] fmt = null;
        byte[] data = null;
        int offset = 12;
        while (offset + 8 <= wav.length) {
            String id = new String(wav, offset, 4, StandardCharsets.US_ASCII);
            int size = le32(wav, offset + 4);
            int body = offset + 8;
            if (size < 0 || body + size > wav.length) {
                throw new IllegalArgumentException("WAV 分段长度无效");
            }
            if ("fmt ".equals(id)) fmt = Arrays.copyOfRange(wav, body, body + size);
            if ("data".equals(id)) data = Arrays.copyOfRange(wav, body, body + size);
            offset = body + size + (size & 1);
        }
        if (fmt == null || data == null) {
            throw new IllegalArgumentException("WAV 分段缺少 fmt 或 data 块");
        }
        return new WavParts(fmt, data);
    }

    private static int le32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8
                | (data[offset + 2] & 0xFF) << 16 | (data[offset + 3] & 0xFF) << 24;
    }

    private static void writeLe32(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private record WavParts(byte[] fmt, byte[] data) {
    }
}