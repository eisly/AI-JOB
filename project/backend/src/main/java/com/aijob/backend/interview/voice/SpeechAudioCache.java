package com.aijob.backend.interview.voice;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/** 题目合成音频的内存 LRU 缓存：不落库、不落盘，随实例重启失效。 */
@Component
public class SpeechAudioCache {

    private static final int MAX_ENTRIES = 60;
    private static final int MAX_BYTES_PER_ENTRY = 8 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final long TTL_MILLIS = 5 * 60 * 1000L;

    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long totalBytes;

    public CachedAudio get(String key) {
        synchronized (entries) {
            Entry entry = entries.get(key);
            if (entry == null) return null;
            if (System.currentTimeMillis() - entry.createdAt > TTL_MILLIS) {
                entries.remove(key);
                totalBytes -= entry.data.length;
                return null;
            }
            return new CachedAudio(entry.data, entry.mimeType);
        }
    }

    public void put(String key, byte[] data, String mimeType) {
        if (data == null || data.length == 0 || data.length > MAX_BYTES_PER_ENTRY) return;
        synchronized (entries) {
            Entry previous = entries.remove(key);
            if (previous != null) totalBytes -= previous.data.length;
            entries.put(key, new Entry(data, mimeType, System.currentTimeMillis()));
            totalBytes += data.length;
            var iterator = entries.entrySet().iterator();
            while (totalBytes > MAX_TOTAL_BYTES && iterator.hasNext()) {
                Entry eldest = iterator.next().getValue();
                totalBytes -= eldest.data.length;
                iterator.remove();
            }
        }
    }

    public record CachedAudio(byte[] data, String mimeType) {
    }

    private record Entry(byte[] data, String mimeType, long createdAt) {
    }
}