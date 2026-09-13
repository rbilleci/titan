package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class SemanticJsonAssertions {
    private SemanticJsonAssertions() {
    }

    static void assertJsonSemanticallyEquals(Object expected, Object actual) {
        compare("$", expected, actual, false);
    }

    static void assertJsonSemanticallyEqualsOrderSensitive(Object expected, Object actual) {
        compare("$", expected, actual, true);
    }

    static void assertJsonTextSemanticallyEquals(String expectedJson, String actualJson) {
        compare("$", parseJson(expectedJson), parseJson(actualJson), false);
    }

    static void assertJsonTextSemanticallyEquals(Object expected, String actualJson) {
        compare("$", expected, parseJson(actualJson), false);
    }

    static void assertJsonTextSemanticallyEqualsOrderSensitive(String expectedJson, String actualJson) {
        compare("$", parseJson(expectedJson), parseJson(actualJson), true);
    }

    static void assertJsonTextSemanticallyEqualsOrderSensitive(Object expected, String actualJson) {
        compare("$", expected, parseJson(actualJson), true);
    }

    static Object parseJson(String json) {
        try {
            return new Parser(json).parse();
        } catch (IllegalArgumentException ex) {
            fail("Invalid JSON text: " + ex.getMessage());
            throw ex;
        }
    }

    private static void compare(String path, Object expected, Object actual, boolean objectOrderSensitive) {
        if (expected == null || actual == null) {
            if (expected != actual) {
                fail(path + " expected <" + expected + "> but was <" + actual + ">");
            }
            return;
        }
        if (expected instanceof Map<?, ?> expectedMap) {
            if (!(actual instanceof Map<?, ?>)) {
                fail(path + " expected JSON object but was " + typeName(actual));
            }
            Map<?, ?> actualMap = (Map<?, ?>) actual;
            compareObjects(path, expectedMap, actualMap, objectOrderSensitive);
            return;
        }
        if (expected instanceof List<?> expectedList) {
            if (!(actual instanceof List<?>)) {
                fail(path + " expected JSON array but was " + typeName(actual));
            }
            List<?> actualList = (List<?>) actual;
            compareArrays(path, expectedList, actualList, objectOrderSensitive);
            return;
        }
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            if (new BigDecimal(expectedNumber.toString()).compareTo(new BigDecimal(actualNumber.toString())) != 0) {
                fail(path + " expected number <" + expected + "> but was <" + actual + ">");
            }
            return;
        }
        if (!Objects.equals(expected, actual)) {
            fail(path + " expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void compareObjects(
            String path,
            Map<?, ?> expected,
            Map<?, ?> actual,
            boolean objectOrderSensitive
    ) {
        List<String> expectedKeys = stringKeys(path, expected);
        List<String> actualKeys = stringKeys(path, actual);
        if (objectOrderSensitive) {
            if (!expectedKeys.equals(actualKeys)) {
                fail(path + " expected object keys " + expectedKeys + " but was " + actualKeys);
            }
        } else {
            if (!List.copyOf(expectedKeys).containsAll(actualKeys)
                    || !List.copyOf(actualKeys).containsAll(expectedKeys)) {
                fail(path + " expected object keys " + expectedKeys + " but was " + actualKeys);
            }
        }
        for (String key : expectedKeys) {
            compare(path + "." + key, expected.get(key), actual.get(key), objectOrderSensitive);
        }
    }

    private static void compareArrays(
            String path,
            List<?> expected,
            List<?> actual,
            boolean objectOrderSensitive
    ) {
        if (expected.size() != actual.size()) {
            fail(path + " expected array length " + expected.size() + " but was " + actual.size());
        }
        for (int index = 0; index < expected.size(); index++) {
            compare(path + "[" + index + "]", expected.get(index), actual.get(index), objectOrderSensitive);
        }
    }

    private static List<String> stringKeys(String path, Map<?, ?> map) {
        ArrayList<String> keys = new ArrayList<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                fail(path + " expected JSON object key to be a string but found " + typeName(key));
            }
            String stringKey = (String) key;
            keys.add(stringKey);
        }
        return keys;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static final class Parser {
        private final String input;
        private int offset;

        private Parser(String input) {
            this.input = Objects.requireNonNull(input, "input");
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (!isAtEnd()) {
                throw error("unexpected trailing content");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (isAtEnd()) {
                throw error("expected JSON value");
            }
            char current = input.charAt(offset);
            return switch (current) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (current == '-' || Character.isDigit(current)) {
                        yield parseNumber();
                    }
                    throw error("expected JSON value");
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                offset++;
                return values;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw error("expected object key string");
                }
                String key = parseString();
                if (values.containsKey(key)) {
                    throw error("duplicate object key '" + key + "'");
                }
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                values.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    offset++;
                    return values;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            ArrayList<Object> values = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                offset++;
                return values;
            }
            while (true) {
                values.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    offset++;
                    return values;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (!isAtEnd()) {
                char current = input.charAt(offset++);
                if (current == '"') {
                    return builder.toString();
                }
                if (current == '\\') {
                    if (isAtEnd()) {
                        throw error("unterminated string escape");
                    }
                    char escaped = input.charAt(offset++);
                    switch (escaped) {
                        case '"', '\\', '/' -> builder.append(escaped);
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> builder.append(parseUnicodeEscape());
                        default -> throw error("unsupported string escape '\\" + escaped + "'");
                    }
                } else {
                    if (current < 0x20) {
                        throw error("unescaped control character in string");
                    }
                    builder.append(current);
                }
            }
            throw error("unterminated string");
        }

        private char parseUnicodeEscape() {
            if (offset + 4 > input.length()) {
                throw error("incomplete unicode escape");
            }
            String digits = input.substring(offset, offset + 4);
            for (int index = 0; index < digits.length(); index++) {
                if (Character.digit(digits.charAt(index), 16) < 0) {
                    throw error("invalid unicode escape");
                }
            }
            offset += 4;
            return (char) Integer.parseInt(digits, 16);
        }

        private BigDecimal parseNumber() {
            int start = offset;
            if (peek('-')) {
                offset++;
            }
            if (peek('0')) {
                offset++;
            } else {
                requireDigit();
                while (!isAtEnd() && Character.isDigit(input.charAt(offset))) {
                    offset++;
                }
            }
            if (peek('.')) {
                offset++;
                requireDigit();
                while (!isAtEnd() && Character.isDigit(input.charAt(offset))) {
                    offset++;
                }
            }
            if (peek('e') || peek('E')) {
                offset++;
                if (peek('+') || peek('-')) {
                    offset++;
                }
                requireDigit();
                while (!isAtEnd() && Character.isDigit(input.charAt(offset))) {
                    offset++;
                }
            }
            return new BigDecimal(input.substring(start, offset));
        }

        private Object parseLiteral(String literal, Object value) {
            if (!input.startsWith(literal, offset)) {
                throw error("expected '" + literal + "'");
            }
            offset += literal.length();
            return value;
        }

        private void requireDigit() {
            if (isAtEnd() || !Character.isDigit(input.charAt(offset))) {
                throw error("expected digit");
            }
            offset++;
        }

        private void expect(char expected) {
            if (!peek(expected)) {
                throw error("expected '" + expected + "'");
            }
            offset++;
        }

        private boolean peek(char expected) {
            return !isAtEnd() && input.charAt(offset) == expected;
        }

        private void skipWhitespace() {
            while (!isAtEnd()) {
                char current = input.charAt(offset);
                if (current != ' ' && current != '\n' && current != '\r' && current != '\t') {
                    return;
                }
                offset++;
            }
        }

        private boolean isAtEnd() {
            return offset >= input.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at byte " + offset);
        }
    }
}
