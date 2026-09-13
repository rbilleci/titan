package io.titan.gradle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitanInstallVerificationTest {

    @Test
    void plannedReportIsDeterministicAndHonestAboutScratchExecution() {
        TitanInstallVerification report = TitanInstallVerification.planned(manifest("postgresql"), installPlan("postgresql"));
        TitanInstallVerification reordered = new TitanInstallVerification(
                TitanInstallVerification.CURRENT_SCHEMA_VERSION,
                report.artifactId(),
                report.manifestContentSha256(),
                report.installPlanContentSha256(),
                report.status(),
                report.database(),
                List.of(new TitanInstallVerification.DialectReport("mysql", "pending", List.of()),
                        new TitanInstallVerification.DialectReport("postgresql", "pending", List.of())),
                List.of(new TitanInstallVerification.Drift(
                        "postgresql",
                        "postgresql.public.demo.function",
                        "objectSqlSha256",
                        sha("expected"),
                        sha("actual"))),
                List.of(new TitanInstallVerification.Diagnostic(
                                "postgresql",
                                "verify.exists.postgresql.public.demo.function",
                                "postgresql.public.demo.function",
                                "TITAN-GAP005-OBJECT-MISSING",
                                "missing"),
                        new TitanInstallVerification.Diagnostic(
                                "all",
                                "verification.pending",
                                "",
                                "TITAN-GAP005-VERIFY-PENDING",
                                "Scratch database verification has not been executed for this package.")));

        assertArrayEquals(report.toJsonBytes(), TitanInstallVerification.planned(manifest("postgresql"), installPlan("postgresql")).toJsonBytes());
        String json = report.toJson();
        String sorted = reordered.toJson();
        assertTrue(json.contains("\"schemaVersion\": \"titan.install-verification.v1\""));
        assertTrue(json.contains("\"status\": \"pending\""));
        assertTrue(json.contains("\"kind\": \"scratch-required\""));
        assertTrue(json.contains("\"code\": \"TITAN-GAP005-VERIFY-PENDING\""));
        assertTrue(sorted.contains("\"drift\": ["));
        assertTrue(sorted.contains("\"check\": \"objectSqlSha256\""));
        assertTrue(sorted.indexOf("\"dialect\": \"mysql\"") < sorted.indexOf("\"dialect\": \"postgresql\""));
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new TitanInstallVerification.VerifiedObject("", List.of("exists")));
        assertThrows(IllegalArgumentException.class,
                () -> new TitanInstallVerification.Diagnostic("postgresql", "", "", "CODE", "message"));
        assertThrows(IllegalArgumentException.class,
                () -> new TitanInstallVerification.Drift("postgresql", "", "", sha("expected"), sha("actual")));
    }

    private static TitanArtifactManifest manifest(String dialect) {
        TitanArtifactManifest manifest = new TitanArtifactManifest(
                TitanArtifactManifest.CURRENT_SCHEMA_VERSION,
                "titan.generated-sql.test",
                "test",
                "direct",
                List.of(dialect),
                List.of(new TitanArtifactManifest.SourceInput(dialect, dialect + "/routine.sql", sha("routine"))),
                List.of(new TitanArtifactManifest.EntryPoint(
                        "demo.Kernel.routine(int)",
                        new TitanArtifactManifest.JavaEntryPoint(
                                "demo.Kernel",
                                "routine",
                                List.of("int"),
                                "StoredFunction",
                                new TitanArtifactManifest.SourceLocation("src/test/demo/Kernel.java", 1)),
                        List.of(new TitanArtifactManifest.SqlEntryPoint(
                                dialect,
                                dialect + ".public.routine.function",
                                "routine",
                                "function",
                                List.of(new TitanArtifactManifest.SqlParameter(0, "input_value", "integer")),
                                "integer",
                                dialect + "/routine.sql")),
                        "invoker")),
                List.of(new TitanArtifactManifest.GeneratedObjectReference(dialect + ".public.routine.function", "inventoried")),
                List.of(),
                new TitanArtifactManifest.ManifestHashes(sha("sources"), TitanArtifactManifest.MANIFEST_CONTENT_HASH_PLACEHOLDER),
                new TitanArtifactManifest.Validation("generated", List.of()));
        return manifest.withManifestContentSha256(manifest.canonicalContentHash());
    }

    private static TitanInstallPlan installPlan(String dialect) {
        TitanObjectInventory inventory = new TitanObjectInventory(
                TitanObjectInventory.CURRENT_SCHEMA_VERSION,
                "titan.generated-sql.test",
                List.of(new TitanObjectInventory.GeneratedObject(
                        dialect + ".public.routine.function",
                        dialect,
                        "function",
                        "public",
                        "routine",
                        "(input_value integer)",
                        dialect + "/routine.sql",
                        "demo.Kernel.routine()",
                        "invoker",
                        0,
                        List.of(),
                        sha("sql"))),
                new TitanObjectInventory.InventoryHashes(TitanObjectInventory.INVENTORY_CONTENT_HASH_PLACEHOLDER));
        inventory = inventory.withInventoryContentSha256(inventory.canonicalContentHash());
        return TitanInstallPlan.from(manifest(dialect), inventory);
    }

    private static String sha(String value) {
        return TitanArtifactManifest.sha256Hex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
