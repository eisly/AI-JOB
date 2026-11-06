package com.aijob.backend.job;

import java.net.URI;

public final class JobSearchLinks {

    private JobSearchLinks() {
    }

    public static boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}