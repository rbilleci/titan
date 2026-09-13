package io.titan.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.titan.management.ManagementAudit.AuditedCommandResult;
import org.junit.jupiter.api.Test;

/**
 * Guards the RD-1 status-code <-> TITAN-MGMT-E03x mapping. This is the titan-management mirror of the
 * {@code activate_deployment} @StoredFunction's {@code ACTIVATION_E03x} constants in
 * titan-management-routines (which titan-management cannot import — TG-BLK-009). The function's emitted
 * SQL returns these exact ints (verified by the routines module's deployability IT + transpile test);
 * this test pins the adapter side of the contract so the two cannot silently drift.
 */
class DeploymentActivationStatusTest {

    @Test
    void okMapsToNullOutcome() {
        assertEquals(0, DeploymentActivationStatus.OK);
        assertNull(DeploymentActivationStatus.toOutcome(DeploymentActivationStatus.OK),
                "code 0 is success -> no failure outcome");
    }

    @Test
    void eachE03xCodeMapsToItsDomainError() {
        // All SEVEN activation preconditions now have a routine code — including E032 (the GAP-005
        // metadata-PATH suffix match), which moved server-side as the final residual. The adapter is a
        // pure pass-through that maps each int back to its TITAN-MGMT-E03x domain error.
        assertCode(30, "TITAN-MGMT-E030");
        assertCode(31, "TITAN-MGMT-E031");
        assertCode(32, "TITAN-MGMT-E032");
        assertCode(33, "TITAN-MGMT-E033");
        assertCode(34, "TITAN-MGMT-E034");
        assertCode(35, "TITAN-MGMT-E035");
        assertCode(36, "TITAN-MGMT-E036");
    }

    @Test
    void e032NowMapsToItsRoutineCode() {
        // E032 (GAP-005 metadata PATHS) moved into the activate_deployment @StoredFunction (faithful
        // via Column.like("%titan-*.json")), so int 32 is now a recognized routine code mapping to the
        // TITAN-MGMT-E032 domain error — no longer rejected as unknown.
        assertCode(32, "TITAN-MGMT-E032");
    }

    @Test
    void unknownCodeIsRejected() {
        assertThrows(IllegalStateException.class, () -> DeploymentActivationStatus.toOutcome(99));
    }

    private static void assertCode(int code, String expectedErrorCode) {
        AuditedCommandResult outcome = DeploymentActivationStatus.toOutcome(code);
        assertEquals(expectedErrorCode, outcome.errorCode(), "code " + code);
    }
}
