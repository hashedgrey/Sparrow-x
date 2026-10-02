package com.sparrowx.internal.valueobjects;

import java.util.Locale;

public enum EngineerRole {

    INTERN,
    JUNIOR_ENGINEER,
    ENGINEER,
    SENIOR_ENGINEER,
    STAFF_ENGINEER;

    public static EngineerRole from(String value) {
        if (value == null || value.isBlank()) {
            return INTERN;
        }

        String normalized = value
                .trim()
                .toUpperCase(Locale.ROOT);

        if (normalized.startsWith("ENGINEER_ROLE_")) {
            normalized = normalized.substring(
                    "ENGINEER_ROLE_".length()
            );
        }

        try {
            return EngineerRole.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            return INTERN;
        }
    }
}