package io.titan.transpiler.tir.generative.shared;

public final class FailureTriageSupport {

    public record Triage(String bucket, String likelyLayerHint) {
    }

    private FailureTriageSupport() {
    }

    public static Triage forMismatch(String mismatchSummary) {
        if (mismatchSummary == null || mismatchSummary.isBlank()) {
            return new Triage("green", "none");
        }
        if (mismatchSummary.startsWith("Explicit-vs-reference mismatch:")) {
            return new Triage("semantic-mismatch", "explicit-form lowering / SQL path, or reference oracle");
        }
        if (mismatchSummary.startsWith("Helper-vs-reference mismatch:")) {
            return new Triage("semantic-mismatch", "helper-form lowering / SQL path, or reference oracle");
        }
        if (mismatchSummary.startsWith("Explicit-vs-helper mismatch:")) {
            return new Triage("semantic-mismatch", "helper-vs-explicit equivalence lowering path");
        }
        if (mismatchSummary.startsWith("Original case mismatch vs reference:")) {
            return new Triage("semantic-mismatch", "original-form lowering / SQL path, or reference oracle");
        }
        if (mismatchSummary.startsWith("Reordered case mismatch vs reference:")) {
            return new Triage("semantic-mismatch", "rewritten/reordered-form lowering / SQL path, or reference oracle");
        }
        return new Triage("semantic-mismatch", "reference oracle vs lowered SQL result path");
    }

    public static Triage forMutationClassification(Enum<?> classification) {
        String name = classification == null ? null : classification.name();
        return switch (name) {
            case "STABLE_PASS" -> new Triage("green", "none");
            case "STABLE_EXPECTED_REJECTION" -> new Triage("stable-expected-rejection", "front-end rejection path (expected) / lowerer boundary");
            case "LOWERER_FAILURE" -> new Triage("lowering-failure", "Java-to-TIR lowering");
            case "SQL_EMISSION_FAILURE" -> new Triage("sql-emission-failure", "TIR-to-SQL emission");
            case "SQL_EXECUTION_FAILURE" -> new Triage("sql-execution-failure", "generated SQL execution against Postgres");
            case "SEMANTIC_MISMATCH" -> new Triage("semantic-mismatch", "reference oracle vs lowered SQL result path");
            case "UNSTABLE_RESULT" -> new Triage("unstable-flaky-replay", "nondeterministic replay or unstable lowering / execution path");
            default -> throw new IllegalArgumentException("Unknown mutation classification: " + classification);
        };
    }
}
