package io.titan.gradle;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitanArtifactInstallVerifierPacketPreflightTest {

    @Test
    void mysqlPacketPreflightUsesLargestUtf8StatementAndLeavesProtocolReserve() {
        TitanArtifactInstallVerifier.MysqlPacketRequirement requirement =
                TitanArtifactInstallVerifier.mysqlPacketRequirement(List.of("SELECT 1", "SELECT '€'"));

        // The UTF-8 euro sign is three bytes. The preflight measures wire bytes rather than Java
        // UTF-16 characters and keeps a fixed reserve for MySQL packet framing.
        assertEquals("SELECT '€'".getBytes(StandardCharsets.UTF_8).length, requirement.largestStatementBytes());
        assertTrue(requirement.requiredBytes() > requirement.largestStatementBytes());
        assertEquals("", TitanArtifactInstallVerifier.mysqlPacketPreflightError(
                requirement.requiredBytes(), requirement));

        String failure = TitanArtifactInstallVerifier.mysqlPacketPreflightError(
                requirement.requiredBytes() - 1L, requirement);
        assertTrue(failure.contains("@@max_allowed_packet"), failure);
        assertTrue(failure.contains("no package SQL was executed"), failure);
    }
}
