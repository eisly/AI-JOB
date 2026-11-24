package com.aijob.backend.account;

/**
 * 手机号脱敏：11 位号码输出前 3 位 + **** + 后 4 位；NULL 返回 NULL；
 * 非 11 位号码不输出原值（返回 NULL），避免异常数据泄露。
 */
public final class PhoneMask {

    private PhoneMask() {
    }

    public static String mask(String phone) {
        if (phone == null || phone.length() != 11) {
            return null;
        }
        for (int i = 0; i < phone.length(); i++) {
            if (!Character.isDigit(phone.charAt(i))) {
                return null;
            }
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}