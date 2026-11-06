package com.aijob.backend.job;

import java.util.List;
import java.util.Set;

import com.aijob.backend.common.BusinessException;

public final class CompanySizes {

    public static final String SIZE_0_20 = "SIZE_0_20";
    public static final String SIZE_21_99 = "SIZE_21_99";
    public static final String SIZE_100_499 = "SIZE_100_499";
    public static final String SIZE_500_999 = "SIZE_500_999";
    public static final String SIZE_1000_9999 = "SIZE_1000_9999";
    public static final String SIZE_10000_PLUS = "SIZE_10000_PLUS";
    public static final String SIZE_UNKNOWN = "SIZE_UNKNOWN";

    public static final List<String> VALUES = List.of(SIZE_0_20, SIZE_21_99, SIZE_100_499, SIZE_500_999,
            SIZE_1000_9999, SIZE_10000_PLUS, SIZE_UNKNOWN);
    private static final Set<String> KNOWN = Set.copyOf(VALUES.subList(0, 6));

    private CompanySizes() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return SIZE_UNKNOWN;
        }
        String text = value.trim().toUpperCase();
        return KNOWN.contains(text) || SIZE_UNKNOWN.equals(text) ? text : SIZE_UNKNOWN;
    }

    public static String forHeadcount(Integer count) {
        if (count == null || count < 0) {
            return SIZE_UNKNOWN;
        }
        if (count <= 20) {
            return SIZE_0_20;
        }
        if (count <= 99) {
            return SIZE_21_99;
        }
        if (count <= 499) {
            return SIZE_100_499;
        }
        if (count <= 999) {
            return SIZE_500_999;
        }
        if (count <= 9999) {
            return SIZE_1000_9999;
        }
        return SIZE_10000_PLUS;
    }

    public static String parse(String value) {
        if (value == null || value.isBlank()) {
            return SIZE_UNKNOWN;
        }
        String text = value.replace(",", "").replace("，", "").replace(" ", "").toLowerCase();
        if (text.contains("未知") || text.contains("保密") || text.contains("未公开") || text.contains("无法确定")) {
            return SIZE_UNKNOWN;
        }
        if (text.contains("10000") || text.contains("1万") || text.contains("一万")) {
            return SIZE_10000_PLUS;
        }
        if (text.contains("20人以下") || text.contains("少于20") || text.contains("0-20") || text.contains("0~20")) {
            return SIZE_0_20;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d{1,6})\\s*(?:-|~|至|到|—)\\s*(\\d{1,6})").matcher(text);
        while (matcher.find()) {
            int low = Integer.parseInt(matcher.group(1));
            int high = Integer.parseInt(matcher.group(2));
            if (low == 0 && high == 20) return SIZE_0_20;
            if (low == 21 && high == 99) return SIZE_21_99;
            if (low == 100 && high == 499) return SIZE_100_499;
            if (low == 500 && high == 999) return SIZE_500_999;
            if (low == 1000 && high == 9999) return SIZE_1000_9999;
            if (low == 10000) return SIZE_10000_PLUS;
        }
        java.util.regex.Matcher count = java.util.regex.Pattern.compile("(\\d{1,6})人").matcher(text);
        if (count.find()) {
            return forHeadcount(Integer.parseInt(count.group(1)));
        }
        return SIZE_UNKNOWN;
    }

    public static void validate(String value) {
        if (!VALUES.contains(value)) {
            throw BusinessException.validation("companySize", "公司规模必须为统一枚举值");
        }
    }
}