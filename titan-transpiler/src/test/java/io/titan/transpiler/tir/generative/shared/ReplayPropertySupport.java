package io.titan.transpiler.tir.generative.shared;

import java.util.function.Predicate;
import org.junit.jupiter.api.Assumptions;

public final class ReplayPropertySupport {

    public record ReplayRequest(String profile, String seed) {
    }

    private ReplayPropertySupport() {
    }

    public static ReplayRequest resolve(
            String normalizedProfileKey,
            String normalizedSeedKey,
            String legacyProfileKey,
            String legacySeedKey,
            Predicate<String> acceptsProfile,
            String assumptionMessage
    ) {
        String rawProfile = firstNonBlank(System.getProperty(normalizedProfileKey), System.getProperty(legacyProfileKey));
        String rawSeed = firstNonBlank(System.getProperty(normalizedSeedKey), System.getProperty(legacySeedKey));
        Assumptions.assumeTrue(rawProfile != null && rawSeed != null && acceptsProfile.test(rawProfile), assumptionMessage);
        return new ReplayRequest(rawProfile, rawSeed);
    }

    public static String resolveOptional(String normalizedKey, String legacyKey) {
        return firstNonBlank(System.getProperty(normalizedKey), System.getProperty(legacyKey));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
