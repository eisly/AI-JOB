package com.aijob.backend.common;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class ApiView {

    private final JsonService json;

    public ApiView(JsonService json) {
        this.json = json;
    }

    public Map<String, Object> map(Map<String, Object> row, String... omitted) {
        Set<String> exclusions = Set.of(omitted);
        Map<String, Object> result = new LinkedHashMap<>();
        if (row == null) {
            return result;
        }
        row.forEach((key, value) -> {
            if (!exclusions.contains(key)) {
                if (key.endsWith("_json")) {
                    String target = camel(key.substring(0, key.length() - 5));
                    result.put(target, value == null ? null : json.convert(json.parse(String.valueOf(value)), Object.class));
                } else {
                    result.put(camel(key), value(value));
                }
            }
        });
        return result;
    }

    public Object value(Object value) {
        if (value instanceof LocalDateTime time) {
            return time.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return value;
    }

    private String camel(String key) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char character : key.toLowerCase(Locale.ROOT).toCharArray()) {
            if (character == '_') {
                upper = true;
            } else {
                result.append(upper ? Character.toUpperCase(character) : character);
                upper = false;
            }
        }
        return result.toString();
    }
}