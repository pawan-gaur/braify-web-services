package com.braify.shared;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Platform feature flags.
 * Add a new constant here whenever a new module is introduced.
 * The key() value is stored in Organization.features and returned to the frontend.
 */
public enum Feature {

    PDF_TEMPLATES("PDF_TEMPLATES", "PDF Templates"),
    EMAIL_TEMPLATES("EMAIL_TEMPLATES", "Email Templates"),
    E_SIGN("E_SIGN", "E-Sign"),
    FILE_STORAGE("FILE_STORAGE", "File Storage");

    private final String key;
    private final String label;

    Feature(String key, String label) {
        this.key   = key;
        this.label = label;
    }

    public String getKey()   { return key; }
    public String getLabel() { return label; }

    /** All valid feature keys as a list of strings. */
    public static List<String> allKeys() {
        return Arrays.stream(values()).map(Feature::getKey).collect(Collectors.toList());
    }

    /** Returns true if the given string is a recognised feature key. */
    public static boolean isValid(String key) {
        return Arrays.stream(values()).anyMatch(f -> f.key.equals(key));
    }

    /** Filters a raw list, dropping any unknown keys (safe for storage). */
    public static List<String> sanitise(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream().filter(Feature::isValid).distinct().collect(Collectors.toList());
    }
}
