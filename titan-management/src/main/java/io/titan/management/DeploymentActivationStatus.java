package io.titan.management;

import io.titan.management.ManagementAudit.AuditedCommandResult;

/**
 * The {@code activate_deployment} {@code @StoredFunction}'s int status code <-> {@code TITAN-MGMT-E03x}
 * domain error mapping (RD-1). The routine returns one of these ints; the adapter maps each back to
 * the matching {@link AuditedCommandResult} failure (or success on {@link #OK}).
 *
 * <p>This MIRRORS the {@code static final int ACTIVATION_*} constants in
 * {@code io.titan.management.routines.ManagementRoutines} — the single source of truth on the routine
 * side. titan-management has NO compile edge to titan-management-routines (TG-BLK-009: a
 * {@code project(":titan-management-routines")} dependency would drag the routines module's
 * project edges into the titan-graphql consumer's Quarkus configuration-time dependency walk), so the
 * values are duplicated here and guarded by {@code DeploymentActivationStatusTest} (which asserts each
 * int maps to the documented code). Keep the two in lockstep.
 *
 * <p>All SEVEN activation preconditions (E030–E036) now have a routine code — including E032, the
 * GAP-005 metadata-PATH suffix match, which the routine expresses faithfully via
 * {@code Column.like("%titan-*.json")}. The adapter is a pure pass-through: it CALLs the function and
 * maps the returned int back through {@link #toOutcome(int)} with no precondition logic of its own.
 */
final class DeploymentActivationStatus {

    static final int OK = 0;
    static final int E030_MISSING_ARTIFACT = 30;
    static final int E031_HASH_MISMATCH = 31;
    // E032 (GAP-005 metadata PATHS) is now a SERVER-SIDE routine code: the four metadata path columns
    // must each end with their fixed titan-*.json filename, expressed faithfully as four AND-chained
    // Column.like('%titan-*.json') conditions (the '.' is a literal in SQL LIKE — only % and _ are
    // wildcards — so the suffix match is exactly endsWith). The adapter no longer pre-checks it.
    static final int E032_METADATA_PATHS = 32;
    static final int E033_NOT_VERIFIED = 33;
    static final int E034_TERMINAL = 34;
    static final int E035_UNAUTHORIZED = 35;
    static final int E036_EVIDENCE_MISMATCH = 36;

    private DeploymentActivationStatus() {
    }

    /**
     * Maps the routine's returned status code to the domain outcome. {@link #OK} returns {@code null}
     * (the caller proceeds to read the activated/superseded rows); any E03x code returns the matching
     * {@code TITAN-MGMT-E03x} failure with the same message the file store produces.
     */
    static AuditedCommandResult toOutcome(int code) {
        return switch (code) {
            case OK -> null;
            case E030_MISSING_ARTIFACT -> AuditedCommandResult.failure(
                    "TITAN-MGMT-E030", "deployment activation requires existing artifact ref");
            case E031_HASH_MISMATCH -> AuditedCommandResult.failure(
                    "TITAN-MGMT-E031", "deployment activation artifact hash mismatch");
            case E032_METADATA_PATHS -> AuditedCommandResult.failure(
                    "TITAN-MGMT-E032", "deployment activation requires GAP-005 artifact metadata");
            case E033_NOT_VERIFIED -> AuditedCommandResult.failure(
                    "TITAN-MGMT-E033", "deployment activation requires passed install verification");
            case E034_TERMINAL -> AuditedCommandResult.failure(
                    "TITAN-MGMT-E034", "deployment activation cannot activate terminal deployment");
            case E035_UNAUTHORIZED -> AuditedCommandResult.failure(
                    "TITAN-MGMT-E035", "deployment activation requires admin or platform actor");
            case E036_EVIDENCE_MISMATCH -> AuditedCommandResult.failure(
                    "TITAN-MGMT-E036", "deployment activation GAP-005 artifact evidence mismatch");
            default -> throw new IllegalStateException(
                    "TITAN-GAP006-TRANSACTION: unknown activate_deployment status code: " + code);
        };
    }
}
