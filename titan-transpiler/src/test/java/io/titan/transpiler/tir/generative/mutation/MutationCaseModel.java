package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

public final class MutationCaseModel {

    public enum MutationClassification {
        STABLE_PASS("stable-pass"),
        STABLE_EXPECTED_REJECTION("stable-expected-rejection"),
        LOWERER_FAILURE("lowerer-failure"),
        SQL_EMISSION_FAILURE("sql-emission-failure"),
        SQL_EXECUTION_FAILURE("sql-execution-failure"),
        SEMANTIC_MISMATCH("semantic-mismatch"),
        UNSTABLE_RESULT("unstable-result");

        private final String id;

        MutationClassification(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum MutatorId {
        ADD_SAFE_CONJUNCT("add-safe-conjunct"),
        ADD_SAFE_DISJUNCT("add-safe-disjunct"),
        DUPLICATE_PROJECTION("duplicate-projection"),
        INJECT_DERIVED_PROJECTION("inject-derived-projection");

        private final String id;

        MutatorId(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record MutationParameter(String key, String value) {
        public MutationParameter {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("mutation parameter key must not be blank");
            }
            Objects.requireNonNull(value, "value");
        }

        public String toStableJson() {
            return """
                    {
                      "key": "%s",
                      "value": "%s"
                    }
                    """.formatted(jsonEscape(key), jsonEscape(value));
        }
    }

    public record MutationParameterSet(List<MutationParameter> parameters) {
        public MutationParameterSet {
            parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
            if (parameters.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("mutation parameters must not contain null");
            }
        }

        public static MutationParameterSet empty() {
            return new MutationParameterSet(List.of());
        }

        public String toStableJson() {
            StringJoiner joined = new StringJoiner(", ", "[", "]");
            for (MutationParameter parameter : parameters) {
                joined.add(parameter.toStableJson());
            }
            return joined.toString();
        }
    }

    public record ReductionStep(String operation, boolean preservedClassification, String note) {
        public ReductionStep {
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("reduction operation must not be blank");
            }
            Objects.requireNonNull(note, "note");
        }

        public String toStableJson() {
            return """
                    {
                      "operation": "%s",
                      "preservedClassification": %s,
                      "note": "%s"
                    }
                    """.formatted(jsonEscape(operation), preservedClassification, jsonEscape(note));
        }
    }

    public record MutationCase(
            String profileId,
            String baseProfileId,
            long baseSeed,
            MutatorId mutatorId,
            MutationParameterSet mutationParameters,
            SelectCaseModel.SelectCase baseCase,
            SelectCaseModel.SelectCase mutatedCase,
            MutationClassification classification,
            List<ReductionStep> reductionSteps
    ) {
        public MutationCase {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            if (baseProfileId == null || baseProfileId.isBlank()) {
                throw new IllegalArgumentException("baseProfileId must not be blank");
            }
            Objects.requireNonNull(mutatorId, "mutatorId");
            Objects.requireNonNull(mutationParameters, "mutationParameters");
            Objects.requireNonNull(baseCase, "baseCase");
            Objects.requireNonNull(mutatedCase, "mutatedCase");
            Objects.requireNonNull(classification, "classification");
            reductionSteps = List.copyOf(Objects.requireNonNull(reductionSteps, "reductionSteps"));
            if (reductionSteps.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("reductionSteps must not contain null");
            }
        }

        public String toStableJson() {
            StringJoiner reductionJson = new StringJoiner(", ", "[", "]");
            for (ReductionStep step : reductionSteps) {
                reductionJson.add(step.toStableJson());
            }
            return """
                    {
                      "profileId": "%s",
                      "baseProfileId": "%s",
                      "baseSeed": "%s",
                      "mutatorId": "%s",
                      "mutationParameters": %s,
                      "classification": "%s",
                      "baseCase": %s,
                      "mutatedCase": %s,
                      "reductionSteps": %s
                    }
                    """.formatted(
                    jsonEscape(profileId),
                    jsonEscape(baseProfileId),
                    Long.toUnsignedString(baseSeed),
                    mutatorId.id(),
                    mutationParameters.toStableJson(),
                    classification.id(),
                    baseCase.toStableJson(),
                    mutatedCase.toStableJson(),
                    reductionJson);
        }
    }

    public static MutationParameter parameter(String key, String value) {
        return new MutationParameter(key, value);
    }

    public static MutationParameterSet parameterSet(MutationParameter... parameters) {
        return new MutationParameterSet(List.of(parameters));
    }

    public static ReductionStep reductionStep(String operation, boolean preservedClassification, String note) {
        return new ReductionStep(operation, preservedClassification, note);
    }

    public static MutationCase mutationCase(
            String profileId,
            String baseProfileId,
            long baseSeed,
            MutatorId mutatorId,
            MutationParameterSet mutationParameters,
            SelectCaseModel.SelectCase baseCase,
            SelectCaseModel.SelectCase mutatedCase,
            MutationClassification classification,
            List<ReductionStep> reductionSteps
    ) {
        return new MutationCase(
                profileId,
                baseProfileId,
                baseSeed,
                mutatorId,
                mutationParameters,
                baseCase,
                mutatedCase,
                classification,
                reductionSteps);
    }

    private static String jsonEscape(String raw) {
        return raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
