package com.aijob.backend.interview.voice;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/** 题目合成音频的内存 LRU 缓存：不落库、不落盘，随实例重启失效。 */
@Component
public class SpeechAudioCache {

    private static final int MAX_ENTRIES = 200;
    private static final int MAX_BYTES_PER_ENTRY = 512 * 1024;
    private static final long TTL_MILLIS = 5 * 60 * 1000L;

    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public byte[] get(String key) {
        synchronized (entries) {
            Entry entry = entries.get(key);
            if (entry == null) return null;
            if (System.currentTimeMillis() - entry.createdAt > TTL_MILLIS) {
                entries.remove(key);
                return null;
            }
            return entry.data;
        }
    }

    public void put(String key, byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_BYTES_PER_ENTRY) return;
        synchronized (entries) {
            entries.put(key, new Entry(data, System.currentTimeMillis()));
        }
    }

    private record Entry(byte[] data, long createdAt) {
    }
}