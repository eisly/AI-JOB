package com.aijob.backend.common;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Data {

    private Data() {
    }

    public static String text(Map<String, Object> body, String field, boolean required, int max) {
        Object raw = body.get(field);
        if (raw == null) {
            if (required) {
                throw BusinessException.validation(field, field + " 不能为空");
            }
            return null;
        }
        if (!(raw instanceof String value)) {
            throw BusinessException.validation(field, field + " 必须为字符串");
        }
        String result = value.trim();
        if (required && result.isEmpty()) {
            throw BusinessException.validation(field, field + " 不能为空");
        }
        if (result.length() > max) {
            throw BusinessException.validation(field, field + " 长度不能超过 " + max);
        }
        return result;
    }

    public static String uuid(Map<String, Object> body, String field, boolean required) {
        String value = text(body, field, required, 36);
        if (value == null) {
            return null;
        }
        return uuid(value, field);
    }

    public static String uuid(String value, String field) {
        try {
            return UUID.fromString(value).toString().toLowerCase();
        } catch (RuntimeException ex) {
            throw BusinessException.validation(field, field + " 必须是合法 UUID");
        }
    }

    public static String idempotencyKey(String value) {
        if (value == null) {
            throw BusinessException.validation("Idempotency-Key", "必须携带 Idempotency-Key");
        }
        return uuid(value, "Idempotency-Key");
    }

    public static Integer integer(Map<String, Object> body, String field, Integer defaultValue, int min, int max) {
        Object raw = body.get(field);
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw BusinessException.validation(field, field + " 必须为整数");
        }
        int value = number.intValue();
        if (value < min || value > max) {
            throw BusinessException.validation(field, field + " 必须在 " + min + ".." + max + " 之间");
        }
        return value;
    }

    public static Boolean bool(Map<String, Object> body, String field, boolean defaultValue) {
        Object raw = body.get(field);
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Boolean value)) {
            throw BusinessException.validation(field, field + " 必须为布尔值");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> body, String field, boolean required, int max) {
        Object raw = body.get(field);
        if (raw == null) {
            if (required) {
                throw BusinessException.validation(field, field + " 不能为空");
            }
            return new ArrayList<>();
        }
        if (!(raw instanceof List<?> value)) {
            throw BusinessException.validation(field, field + " 必须为数组");
        }
        if (value.size() > max || (required && value.isEmpty())) {
            throw BusinessException.validation(field, field + " 数组长度不合法");
        }
        return new ArrayList<>((List<Object>) value);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Map<String, Object> body, String field, boolean required) {
        Object raw = body.get(field);
        if (raw == null) {
            if (required) {
                throw BusinessException.validation(field, field + " 不能为空");
            }
            return new LinkedHashMap<>();
        }
        if (!(raw instanceof Map<?, ?>)) {
            throw BusinessException.validation(field, field + " 必须为对象");
        }
        return (Map<String, Object>) raw;
    }

    public static String url(Map<String, Object> body, String field) {
        String value = text(body, field, false, 2000);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (RuntimeException ex) {
            throw BusinessException.validation(field, field + " 仅允许合法的 http/https URL");
        }
    }

    public static long offset(Integer current, Integer size) {
        int page = current == null ? 1 : current;
        int pageSize = size == null ? 20 : size;
        if (page < 1) {
            throw BusinessException.validation("current", "页码必须从 1 开始");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw BusinessException.validation("size", "每页数量必须在 1..100 之间");
        }
        long offset = (long) (page - 1) * pageSize;
        if (offset > Integer.MAX_VALUE) {
            throw BusinessException.validation("current", "页码过大");
        }
        return offset;
    }

    public static int page(Integer current) {
        int value = current == null ? 1 : current;
        if (value < 1) {
            throw BusinessException.validation("current", "页码必须从 1 开始");
        }
        return value;
    }

    public static int size(Integer size) {
        int value = size == null ? 20 : size;
        if (value < 1 || value > 100) {
            throw BusinessException.validation("size", "每页数量必须在 1..100 之间");
        }
        return value;
    }
}