package com.aijob.backend.job;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

public final class JobSearchFingerprint {

    private JobSearchFingerprint() {
    }

    public static String of(String company, String title, String cityCode) {
        String raw = normalizeCompany(company) + "|" + normalizeTitle(title) + "|" + normalizeCity(cityCode);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 16; index++) {
                result.append(String.format("%02x", hash[index]));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public static String normalizeCompany(String value) {
        String text = normalizeKey(value);
        for (String suffix : List.of("股份有限公司", "有限责任公司", "有限公司", "集团", "科技", "网络", "信息技术")) {
            if (text.endsWith(suffix) && text.length() > suffix.length()) {
                text = text.substring(0, text.length() - suffix.length());
            }
        }
        return text;
    }

    public static String normalizeTitle(String value) {
        String text = normalizeKey(value);
        for (String suffix : List.of("急聘", "热招", "招聘", "高薪")) {
            if (text.endsWith(suffix) && text.length() > suffix.length()) {
                text = text.substring(0, text.length() - suffix.length());
            }
        }
        return text;
    }

    public static String normalizeCity(String value) {
        String text = normalizeKey(value);
        return text.endsWith("市") && text.length() > 1 ? text.substring(0, text.length() - 1) : text;
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[（(].*?[）)]", "")
                .replaceAll("[\\s\\-_/·,，。:：;；'\"“”‘’、|]+", "");
    }
}