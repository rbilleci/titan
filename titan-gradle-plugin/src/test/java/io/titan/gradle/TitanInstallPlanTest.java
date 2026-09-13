package io.titan.gradle;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitanInstallPlanTest {

    @Test
    void serializesDeterministicJsonWithDependencyRespectingSteps() {
        TitanObjectInventory inventory = inventory(List.of(
                object("postgresql.public.visible_lesson_title.function", "postgresql", "function",
                        "visible_lesson_title", 10, List.of("postgresql.public.lesson_descriptor.type")),
                object("postgresql.public.lesson_descriptor.type", "postgresql", "type",
                        "lesson_descriptor", 30, List.of())));
        TitanInstallPlan plan = TitanInstallPlan.from(manifest("postgresql", inventory), inventory);

        String json = plan.toJson();
        assertTrue(json.contains("\"schemaVersion\": \"titan.install-plan.v1\""));
        assertTrue(json.contains("\"artifactId\": \"titan.generated-sql.test\""));
        assertTrue(json.contains("\"inventoryContentSha256\": \"" + inventory.hashes().inventoryContentSha256() + "\""));
        assertTrue(json.contains("\"transactionMode\": \"singleTransaction\""));
        assertTrue(json.contains("\"transactionNote\": \"PostgreSQL install steps may be reviewed as a single transaction"));
        assertTrue(json.contains("\"kind\": \"preflight\""));
        assertTrue(json.contains("\"kind\": \"createOrReplace\""));
        assertTrue(json.contains("\"kind\": \"verificationProbe\""));
        assertTrue(json.indexOf("\"objectId\": \"postgresql.public.lesson_descriptor.type\"")
                < json.indexOf("\"objectId\": \"postgresql.public.visible_lesson_title.function\""), json);
        assertEquals(json, new String(plan.toJsonBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void recordsMysqlRoutineDdlTransactionMode() {
        TitanObjectInventory inventory = inventory(List.of(
                object("mysql.public.visible_lesson_title.function", "mysql", "function",
                        "visible_lesson_title", 20, List.of())));

        String json = TitanInstallPlan.from(manifest("mysql", inventory), inventory).toJson();

        assertTrue(json.contains("\"dialect\": \"mysql\""));
        assertTrue(json.contains("\"transactionMode\": \"statementBoundaryDdl\""));
        assertTrue(json.contains("\"transactionNote\": \"MySQL routine DDL observes statement boundaries"));
    }

    @Test
    void canonicalContentHashCoversPlanFieldsExceptSelfHash() {
        TitanObjectInventory inventory = inventory(List.of(
                object("postgresql.public.visible_lesson_title.function", "postgresql", "function",
                        "visible_lesson_title", 20, List.of())));
        TitanInstallPlan plan = TitanInstallPlan.from(manifest("postgresql", inventory), inventory);
        TitanObjectInventory changedInventory = inventory(List.of(
                object("postgresql.public.visible_lesson_title.function", "postgresql", "function",
                        "visible_lesson_title", 21, List.of())));
        TitanInstallPlan changedPlan = TitanInstallPlan.from(manifest("postgresql", changedInventory), changedInventory);

        assertEquals(
                plan.canonicalContentHash(),
                plan.withPlanContentSha256(sha("different self hash")).canonicalContentHash());
        assertTrue(!plan.canonicalContentHash().equals(changedPlan.canonicalContentHash()));
        assertTrue(plan.toJson().contains("\"planContentSha256\": \"" + plan.canonicalContentHash() + "\""));
    }

    @Test
    void rejectsGeneratedObjectNameCollisionsBeforePublishingPlan() {
        TitanObjectInventory inventory = inventory(List.of(
                object("postgresql.public.visible_lesson_title.function", "postgresql", "function",
                        "visible_lesson_title", 20, List.of()),
                object("postgresql.public.visible_lesson_title.procedure", "postgresql", "procedure",
                        "visible_lesson_title", 21, List.of())));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> TitanInstallPlan.from(manifest("postgresql", inventory), inventory));

        assertTrue(exception.getMessage().contains("generated object name collision"));
    }

    @Test
    void rejectsGeneratedIdentifierExceedingDialectLimitBeforePublishingPlan() {
        // B-11 (TG-BLK-011 hardening): a future name family that bypasses SqlNames and produces
        // an over-limit identifier must fail at package time, not deploy silently truncated.
        // PostgreSQL's NamingRules.identifierMaxLength is 63 (read from the capabilities
        // registry, not hardcoded here) — construct a 64-char name to trip exactly that gate.
        String overLimitName = "a".repeat(64);
        TitanObjectInventory inventory = inventory(List.of(
                object("postgresql.public." + overLimitName + ".function", "postgresql", "function",
                        overLimitName, 10, List.of())));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> TitanInstallPlan.from(manifest("postgresql", inventory), inventory));

        // Names the dialect, the offending object, its length and the limit.
        assertTrue(exception.getMessage().contains("postgresql identifier limit of 63"), exception.getMessage());
        assertTrue(exception.getMessage().contains("postgresql.public." + overLimitName + ".function"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("is 64 characters"), exception.getMessage());
    }

    @Test
    void acceptsGeneratedIdentifierAtDialectLimit() {
        // The boundary case (exactly the limit) passes — the gate fires only past the ceiling.
        String atLimitName = "a".repeat(63);
        TitanObjectInventory inventory = inventory(List.of(
                object("postgresql.public." + atLimitName + ".function", "postgresql", "function",
                        atLimitName, 10, List.of())));

        TitanInstallPlan plan = TitanInstallPlan.from(manifest("postgresql", inventory), inventory);

        assertTrue(plan.toJson().contains(atLimitName), plan.toJson());
    }

    @Test
    void acceptsGeneratedIdentifierWithinMysqlLimit() {
        // MySQL's limit is 64 (one wider than PostgreSQL), also read from the registry: a 64-char
        // name that would fail on PostgreSQL packages cleanly for MySQL.
        String mysqlAtLimitName = "a".repeat(64);
        TitanObjectInventory inventory = inventory(List.of(
                object("mysql.public." + mysqlAtLimitName + ".function", "mysql", "function",
                        mysqlAtLimitName, 10, List.of())));

        TitanInstallPlan plan = TitanInstallPlan.from(manifest("mysql", inventory), inventory);

        assertTrue(plan.toJson().contains(mysqlAtLimitName), plan.toJson());
    }

    @Test
    void rejectsUnknownDependenciesBeforePublishingPlan() {
        TitanObjectInventory inventory = inventory(List.of(
                object("postgresql.public.visible_lesson_title.function", "postgresql", "function",
                        "visible_lesson_title", 20, List.of("postgresql.public.missing_type.type"))));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> TitanInstallPlan.from(manifest("postgresql", inventory), inventory));

        assertTrue(exception.getMessage().contains("dependency not found"));
        assertTrue(exception.getMessage().contains("postgresql.public.missing_type.type"));
    }

    @Test
    void rejectsDependencyCyclesBeforePublishingPlan() {
        TitanObjectInventory inventory = inventory(List.of(
                object("postgresql.public.first_type.type", "postgresql", "type",
                        "first_type", 10, List.of("postgresql.public.second_type.type")),
                object("postgresql.public.second_type.type", "postgresql", "type",
                        "second_type", 20, List.of("postgresql.public.first_type.type"))));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> TitanInstallPlan.from(manifest("postgresql", inventory), inventory));

        assertTrue(exception.getMessage().contains("dependency cycle"));
    }

    private static TitanArtifactManifest manifest(String dialect, TitanObjectInventory inventory) {
        TitanArtifactManifest manifest = new TitanArtifactManifest(
                TitanArtifactManifest.CURRENT_SCHEMA_VERSION,
                "titan.generated-sql.test",
                "1.0.0",
                "migration",
                List.of(dialect),
                List.of(new TitanArtifactManifest.SourceInput(dialect, dialect + "/routine.sql", sha("routine"))),
                List.of(new TitanArtifactManifest.EntryPoint(
                        "demo.CourseBrowseKernel.visibleLessonTitle(long)",
                        new TitanArtifactManifest.JavaEntryPoint(
                                "demo.CourseBrowseKernel",
                                "visibleLessonTitle",
                                List.of("long"),
                                "StoredFunction",
                                new TitanArtifactManifest.SourceLocation("src/test/demo/CourseBrowseKernel.java", 10)),
                        List.of(new TitanArtifactManifest.SqlEntryPoint(
                                dialect,
                                dialect + ".public.visible_lesson_title.function",
                                "visible_lesson_title",
                                "function",
                                List.of(),
                                "text",
                                dialect + "/routine.sql")),
                        "invoker")),
                inventory.objects().stream()
                        .map(object -> new TitanArtifactManifest.GeneratedObjectReference(object.id(), "inventoried"))
                        .toList(),
                List.of(),
                new TitanArtifactManifest.ManifestHashes(sha("sources"), TitanArtifactManifest.MANIFEST_CONTENT_HASH_PLACEHOLDER),
                new TitanArtifactManifest.Validation("generated", List.of("install verification pending GAP005-M5.1")));
        return manifest.withManifestContentSha256(manifest.canonicalContentHash());
    }

    private static TitanObjectInventory inventory(List<TitanObjectInventory.GeneratedObject> objects) {
        TitanObjectInventory inventory = new TitanObjectInventory(
                TitanObjectInventory.CURRENT_SCHEMA_VERSION,
                "titan.generated-sql.test",
                objects,
                new TitanObjectInventory.InventoryHashes(TitanObjectInventory.INVENTORY_CONTENT_HASH_PLACEHOLDER));
        return inventory.withInventoryContentSha256(inventory.canonicalContentHash());
    }

    private static TitanObjectInventory.GeneratedObject object(
            String id,
            String dialect,
            String kind,
            String name,
            int createOrder,
            List<String> dependsOn
    ) {
        return new TitanObjectInventory.GeneratedObject(
                id,
                dialect,
                kind,
                "public",
                name,
                kind.equals("function") || kind.equals("procedure") ? "(course_id bigint)" : "()",
                dialect + "/demo_CourseBrowseKernel__visibleLessonTitle.sql",
                "demo.CourseBrowseKernel.visibleLessonTitle(long)",
                "invoker",
                createOrder,
                dependsOn,
                sha(id + createOrder));
    }

    private static String sha(String value) {
        return TitanArtifactManifest.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }
}
