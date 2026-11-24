package com.aijob.backend.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 审计来源摘要：IP + User-Agent 的 SHA-256 截断，不可逆且不含原始号码/UA 明文。
 */
public final class SourceDigest {

    private SourceDigest() {
    }

    public static String of(String ip, String userAgent) {
        String source = (ip == null ? "" : ip) + "|" + (userAgent == null ? "" : userAgent);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}