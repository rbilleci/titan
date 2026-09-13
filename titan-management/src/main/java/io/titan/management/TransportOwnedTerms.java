package io.titan.management;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Exact-token matching for the transport-owned blocklists (audit G-11 defect fix).
 *
 * <p>The previous implementation used {@code String.contains} over the lowercased value, which
 * produced substring false-positives: a workspace id like {@code curl-team} was rejected because
 * it contains {@code url}. Matching is now exact-token based: the value is split into tokens at
 * non-alphanumeric separators ({@code -}, {@code _}, {@code .}, {@code :}, {@code /}, ...) and at
 * camelCase boundaries, and a blocked term matches only if it equals a single token or the
 * concatenation of up to three adjacent tokens (so compound terms such as {@code fieldname},
 * {@code uistate} or {@code productworkflow} still catch {@code fieldName}, {@code uiState} and
 * {@code productWorkflow}).
 */
final class TransportOwnedTerms {

    private TransportOwnedTerms() {
    }

    /** Longest blocked compound spans this many camel/separator tokens. */
    private static final int MAX_ADJACENT_TOKENS = 3;

    static boolean containsTerm(Set<String> lowerCaseTerms, String value) {
        List<String> tokens = tokenize(value);
        for (int start = 0; start < tokens.size(); start++) {
            StringBuilder joined = new StringBuilder();
            for (int end = start; end < Math.min(tokens.size(), start + MAX_ADJACENT_TOKENS); end++) {
                joined.append(tokens.get(end));
                if (lowerCaseTerms.contains(joined.toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char previous = 0;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (!Character.isLetterOrDigit(ch)) {
                flush(tokens, current);
            } else {
                boolean camelBoundary = Character.isUpperCase(ch)
                        && (Character.isLowerCase(previous) || Character.isDigit(previous));
                if (camelBoundary) {
                    flush(tokens, current);
                }
                current.append(Character.toLowerCase(ch));
            }
            previous = ch;
        }
        flush(tokens, current);
        return tokens;
    }

    private static void flush(List<String> tokens, StringBuilder current) {
        if (current.length() > 0) {
            tokens.add(current.toString().toLowerCase(Locale.ROOT));
            current.setLength(0);
        }
    }
}
