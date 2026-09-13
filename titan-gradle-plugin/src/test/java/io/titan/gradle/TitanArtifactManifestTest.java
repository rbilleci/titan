package io.titan.gradle;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitanArtifactManifestTest {

    @Test
    void serializesDeterministicJsonWithStableOrdering() {
        TitanArtifactManifest manifest = sampleManifest(List.of("postgresql", "mysql"));
        TitanArtifactManifest reordered = sampleManifest(List.of("mysql", "postgresql"));

        assertEquals(manifest.toJson(), reordered.toJson());

        String json = manifest.toJson();
        assertTrue(json.indexOf("\"mysql\"") < json.indexOf("\"postgresql\""), json);
        assertTrue(json.indexOf("\"mysql/b.sql\"") < json.indexOf("\"postgresql/a.sql\""), json);
        assertTrue(json.contains("\"schemaVersion\": \"titan.artifact.v1\""));
        assertTrue(json.contains("\"artifactId\": \"titan.generated-sql.test\""));
        assertTrue(json.contains("\"status\": \"generated\""));
        // B-5: the additive rollbackScripts array serializes with the same stable dialect
        // ordering and reproducible shape (path / statementCount / sha256) as the other arrays.
        assertTrue(json.contains("\"rollbackScripts\": ["), json);
        assertTrue(json.contains("\"path\": \"titan-rollback.mysql.sql\""), json);
        assertTrue(json.contains("\"path\": \"titan-rollback.postgresql.sql\""), json);
        assertTrue(json.contains("\"statementCount\": 2"), json);
        assertTrue(json.indexOf("\"path\": \"titan-rollback.mysql.sql\"")
                < json.indexOf("\"path\": \"titan-rollback.postgresql.sql\""), json);
        assertEquals(json, new String(manifest.toJsonBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void rollbackScriptEntryRejectsMalformedIntegrityFields() {
        // B-5: integrity fields are validated like every other manifest hash/field.
        IllegalArgumentException badHash = assertThrows(
                IllegalArgumentException.class,
                () -> new TitanArtifactManifest.RollbackScript(
                        "postgresql", "titan-rollback.postgresql.sql", 3, "not-a-sha"));
        assertTrue(badHash.getMessage().contains("rollback script sha256"));

        IllegalArgumentException negativeCount = assertThrows(
                IllegalArgumentException.class,
                () -> new TitanArtifactManifest.RollbackScript(
                        "postgresql", "titan-rollback.postgresql.sql", -1, sha("pg")));
        assertTrue(negativeCount.getMessage().contains("non-negative rollback statement count"));

        IllegalArgumentException blankPath = assertThrows(
                IllegalArgumentException.class,
                () -> new TitanArtifactManifest.RollbackScript("postgresql", "  ", 3, sha("pg")));
        assertTrue(blankPath.getMessage().contains("rollback script path"));
    }

    @Test
    void canonicalContentHashCoversManifestFieldsExceptSelfHash() {
        TitanArtifactManifest manifest = sampleManifest(List.of("postgresql", "mysql"));
        TitanArtifactManifest changedWarnings = new TitanArtifactManifest(
                TitanArtifactManifest.CURRENT_SCHEMA_VERSION,
                "titan.generated-sql.test",
                "1.0.0",
                "migration",
                List.of("postgresql", "mysql"),
                List.of(
                        new TitanArtifactManifest.SourceInput("postgresql", "postgresql/a.sql", sha("a")),
                        new TitanArtifactManifest.SourceInput("mysql", "mysql/b.sql", sha("b"))),
                List.of(
                        entryPoint("demo.AKernel.a()", "demo.AKernel", "a", "postgresql", "a", "postgresql/a.sql"),
                        entryPoint("demo.BKernel.b()", "demo.BKernel", "b", "mysql", "b", "mysql/b.sql")),
                List.of(
                        new TitanArtifactManifest.GeneratedObjectReference("postgresql:a", "inventoried"),
                        new TitanArtifactManifest.GeneratedObjectReference("mysql:b", "inventoried")),
                List.of(),
                new TitanArtifactManifest.ManifestHashes(
                        sha("sources"),
                        TitanArtifactManifest.MANIFEST_CONTENT_HASH_PLACEHOLDER),
                new TitanArtifactManifest.Validation("generated", List.of("different warning")));

        assertEquals(
                manifest.canonicalContentHash(),
                manifest.withManifestContentSha256(sha("different self hash")).canonicalContentHash());
        assertTrue(!manifest.canonicalContentHash().equals(changedWarnings.canonicalContentHash()));

        TitanArtifactManifest finalized = manifest.withManifestContentSha256(manifest.canonicalContentHash());
        assertTrue(finalized.toJson().contains("\"manifestContentSha256\": \"" + manifest.canonicalContentHash() + "\""));
    }

    @Test
    void rejectsMissingRequiredFieldsBeforePublishingIncompleteMetadata() {
        IllegalArgumentException missingDialect = assertThrows(
                IllegalArgumentException.class,
                () -> new TitanArtifactManifest(
                        TitanArtifactManifest.CURRENT_SCHEMA_VERSION,
                        "titan.generated-sql.test",
                        "1.0.0",
                        "migration",
                        List.of(),
                        List.of(new TitanArtifactManifest.SourceInput("postgresql", "postgresql/a.sql", sha("a"))),
                        List.of(entryPoint("demo.AKernel.a()", "demo.AKernel", "a", "postgresql", "a", "postgresql/a.sql")),
                        List.of(),
                        List.of(),
                        new TitanArtifactManifest.ManifestHashes(sha("sources"), sha("manifest")),
                        new TitanArtifactManifest.Validation("generated", List.of())));
        assertTrue(missingDialect.getMessage().contains("at least one dialect"));

        IllegalArgumentException badStatus = assertThrows(
                IllegalArgumentException.class,
                () -> new TitanArtifactManifest(
                        TitanArtifactManifest.CURRENT_SCHEMA_VERSION,
                        "titan.generated-sql.test",
                        "1.0.0",
                        "migration",
                        List.of("postgresql"),
                        List.of(new TitanArtifactManifest.SourceInput("postgresql", "postgresql/a.sql", sha("a"))),
                        List.of(entryPoint("demo.AKernel.a()", "demo.AKernel", "a", "postgresql", "a", "postgresql/a.sql")),
                        List.of(),
                        List.of(),
                        new TitanArtifactManifest.ManifestHashes(sha("sources"), sha("manifest")),
                        new TitanArtifactManifest.Validation("verified", List.of())));
        assertTrue(badStatus.getMessage().contains("must be 'generated'"));

        IllegalArgumentException missingSql = assertThrows(
                IllegalArgumentException.class,
                () -> new TitanArtifactManifest.EntryPoint(
                        "demo.AKernel.a()",
                        new TitanArtifactManifest.JavaEntryPoint(
                                "demo.AKernel",
                                "a",
                                List.of(),
                                "StoredFunction",
                                new TitanArtifactManifest.SourceLocation("src/test/demo/AKernel.java", 10)),
                        List.of(),
                        "invoker"));
        assertTrue(missingSql.getMessage().contains("SQL metadata"));
    }

    private static TitanArtifactManifest sampleManifest(List<String> dialects) {
        return new TitanArtifactManifest(
                TitanArtifactManifest.CURRENT_SCHEMA_VERSION,
                "titan.generated-sql.test",
                "1.0.0",
                "migration",
                dialects,
                List.of(
                        new TitanArtifactManifest.SourceInput("postgresql", "postgresql/a.sql", sha("a")),
                        new TitanArtifactManifest.SourceInput("mysql", "mysql/b.sql", sha("b"))),
                List.of(
                        entryPoint("demo.AKernel.a()", "demo.AKernel", "a", "postgresql", "a", "postgresql/a.sql"),
                        entryPoint("demo.BKernel.b()", "demo.BKernel", "b", "mysql", "b", "mysql/b.sql")),
                List.of(
                        new TitanArtifactManifest.GeneratedObjectReference("postgresql:a", "inventoried"),
                        new TitanArtifactManifest.GeneratedObjectReference("mysql:b", "inventoried")),
                List.of(
                        new TitanArtifactManifest.RollbackScript(
                                "postgresql", "titan-rollback.postgresql.sql", 3, sha("pg-rollback")),
                        new TitanArtifactManifest.RollbackScript(
                                "mysql", "titan-rollback.mysql.sql", 2, sha("mysql-rollback"))),
                new TitanArtifactManifest.ManifestHashes(sha("sources"), sha("manifest")),
                new TitanArtifactManifest.Validation("generated", List.of(
                        "install verification pending GAP005-M5.1")));
    }

    private static TitanArtifactManifest.EntryPoint entryPoint(
            String id,
            String className,
            String methodName,
            String dialect,
            String routineName,
            String sourceInputPath
    ) {
        return new TitanArtifactManifest.EntryPoint(
                id,
                new TitanArtifactManifest.JavaEntryPoint(
                        className,
                        methodName,
                        List.of(),
                        "StoredFunction",
                        new TitanArtifactManifest.SourceLocation("src/test/" + className.replace('.', '/') + ".java", 10)),
                List.of(new TitanArtifactManifest.SqlEntryPoint(
                        dialect,
                        dialect + ".public." + routineName + ".function",
                        routineName,
                        "function",
                        List.of(),
                        "integer",
                        sourceInputPath)),
                "invoker");
    }

    private static String sha(String value) {
        return TitanArtifactManifest.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }
}
