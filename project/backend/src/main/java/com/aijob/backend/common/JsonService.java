package com.aijob.backend.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class JsonService {

    private final ObjectMapper objectMapper;

    public JsonService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String stringify(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    public JsonNode parse(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.nullNode();
        }
        return objectMapper.readTree(value);
    }

    public JsonNode tree(Object value) {
        return objectMapper.valueToTree(value);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.readValue(value, LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(JsonNode value) {
        if (value == null || value.isNull()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(value, LinkedHashMap.class);
    }

    public <T> T convert(Object value, Class<T> type) {
        return objectMapper.convertValue(value, type);
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }

    public String digest(Object value) {
        return sha256(canonical(value));
    }

    public String canonical(Object value) {
        return stringify(sort(value));
    }

    public String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Object sort(Object value) {
        if (value instanceof JsonNode node) {
            value = objectMapper.convertValue(node, Object.class);
        }
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> sorted = new TreeMap<>();
            source.forEach((key, item) -> sorted.put(String.valueOf(key), sort(item)));
            return sorted;
        }
        if (value instanceof Iterable<?> source) {
            List<Object> result = new ArrayList<>();
            source.forEach(item -> result.add(sort(item)));
            return result;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                result.add(sort(java.lang.reflect.Array.get(value, i)));
            }
            return result;
        }
        return value;
    }
}