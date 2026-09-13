package io.titan.transpiler.tir;

import java.util.Locale;
import java.util.Set;

final class CronExpressionValidator {
    private static final Set<String> MONTH_NAMES = Set.of(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    private static final Set<String> DAY_NAMES = Set.of(
            "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");

    private CronExpressionValidator() {
    }

    static void validateStandardCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("Scheduled job cron expression must not be blank.");
        }

        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Scheduled job cron expression must use 5 fields (minute hour day-of-month month day-of-week): '" + cron + "'.");
        }

        validateField(parts[0], 0, 59, null, "minute", cron);
        validateField(parts[1], 0, 23, null, "hour", cron);
        validateField(parts[2], 1, 31, null, "day-of-month", cron);
        validateField(parts[3], 1, 12, MONTH_NAMES, "month", cron);
        validateField(parts[4], 0, 7, DAY_NAMES, "day-of-week", cron);
    }

    private static void validateField(
            String field,
            int min,
            int max,
            Set<String> symbolicTokens,
            String fieldName,
            String fullCron
    ) {
        if (field == null || field.isBlank()) {
            fail(fieldName, fullCron, "field is blank");
        }

        for (String part : field.split(",")) {
            validatePart(part.trim(), min, max, symbolicTokens, fieldName, fullCron);
        }
    }

    private static void validatePart(
            String part,
            int min,
            int max,
            Set<String> symbolicTokens,
            String fieldName,
            String fullCron
    ) {
        if (part.isBlank()) {
            fail(fieldName, fullCron, "empty list item");
        }

        String base = part;
        Integer step = null;
        if (part.contains("/")) {
            String[] split = part.split("/", -1);
            if (split.length != 2 || split[0].isBlank() || split[1].isBlank()) {
                fail(fieldName, fullCron, "invalid step syntax '" + part + "'");
            }
            base = split[0];
            step = parseNumber(split[1], 1, Integer.MAX_VALUE, fieldName, fullCron, "step");
        }

        if ("*".equals(base)) {
            if (step != null) {
                int span = max - min + 1;
                if (step > span) {
                    fail(fieldName, fullCron, "step exceeds field span " + span + " in '" + part + "'");
                }
            }
            return;
        }

        if (base.contains("-")) {
            String[] split = base.split("-", -1);
            if (split.length != 2 || split[0].isBlank() || split[1].isBlank()) {
                fail(fieldName, fullCron, "invalid range syntax '" + base + "'");
            }
            int start = parseToken(split[0], min, max, symbolicTokens, fieldName, fullCron);
            int end = parseToken(split[1], min, max, symbolicTokens, fieldName, fullCron);
            if (start > end) {
                fail(fieldName, fullCron, "range start > end in '" + base + "'");
            }
            if (step != null) {
                int span = end - start + 1;
                if (step > span) {
                    fail(fieldName, fullCron, "step exceeds selected range span " + span + " in '" + part + "'");
                }
            }
            return;
        }

        int value = parseToken(base, min, max, symbolicTokens, fieldName, fullCron);
        if (step != null) {
            int span = max - value + 1;
            if (step > span) {
                fail(fieldName, fullCron, "step exceeds value-to-end span " + span + " in '" + part + "'");
            }
        }
    }

    private static int parseToken(
            String token,
            int min,
            int max,
            Set<String> symbolicTokens,
            String fieldName,
            String fullCron
    ) {
        String normalized = token.trim().toUpperCase(Locale.ROOT);
        if (symbolicTokens != null && symbolicTokens.contains(normalized)) {
            return symbolicToValue(normalized, symbolicTokens);
        }
        return parseNumber(normalized, min, max, fieldName, fullCron, "value");
    }

    private static int symbolicToValue(String token, Set<String> symbols) {
        if (symbols == MONTH_NAMES) {
            return switch (token) {
                case "JAN" -> 1;
                case "FEB" -> 2;
                case "MAR" -> 3;
                case "APR" -> 4;
                case "MAY" -> 5;
                case "JUN" -> 6;
                case "JUL" -> 7;
                case "AUG" -> 8;
                case "SEP" -> 9;
                case "OCT" -> 10;
                case "NOV" -> 11;
                case "DEC" -> 12;
                default -> throw new IllegalArgumentException("Unsupported month token: " + token);
            };
        }

        return switch (token) {
            case "SUN" -> 0;
            case "MON" -> 1;
            case "TUE" -> 2;
            case "WED" -> 3;
            case "THU" -> 4;
            case "FRI" -> 5;
            case "SAT" -> 6;
            default -> throw new IllegalArgumentException("Unsupported day token: " + token);
        };
    }

    private static int parseNumber(
            String token,
            int min,
            int max,
            String fieldName,
            String fullCron,
            String context
    ) {
        int value;
        try {
            value = Integer.parseInt(token);
        } catch (NumberFormatException ex) {
            fail(fieldName, fullCron, "invalid " + context + " '" + token + "'");
            return -1;
        }
        if (value < min || value > max) {
            fail(fieldName, fullCron, context + " out of range " + min + ".." + max + ": " + value);
        }
        return value;
    }

    private static void fail(String fieldName, String cron, String detail) {
        throw new IllegalArgumentException("Invalid cron expression for " + fieldName + " field (" + detail + "): '" + cron + "'.");
    }
}
