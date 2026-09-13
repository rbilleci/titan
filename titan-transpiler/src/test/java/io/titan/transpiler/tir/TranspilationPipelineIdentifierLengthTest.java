package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TG-BLK-011 pipeline regression suite: a nested record/enum whose qualified SQL names exceed
 * both dialects' identifier ceilings (PostgreSQL 63, MySQL 64) must transpile with every
 * generated identifier within the limit, CONSISTENTLY across emission, the described inventory
 * (which feeds install plans, rollback scripts and the install verifier), call-site rendering
 * and dependency edges — no mismatch between emitted DDL and metadata, and no two long names
 * converging onto one truncated identifier.
 */
class TranspilationPipelineIdentifierLengthTest {

    /** Any bare SQL token longer than the strictest ceiling (63) overflows some dialect. */
    private static final Pattern OVERLONG_TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{63,}");

    @TempDir
    Path tempDir;

    private static final String LONG_NAME_FIXTURE = """
            import titan.dsl.StoredFunction;

            class DurableManagementStoreProductStateJournal {
                record ProductStateJournalEntryPageSnapshot(String pageLabel, int entryCount, boolean hasNextPage) {}

                enum ProductLifecycleStageClassificationChannel {
                    ACTIVE(5), RETIRED(11);
                    final int priorityWeight;
                    ProductLifecycleStageClassificationChannel(int priorityWeight) {
                        this.priorityWeight = priorityWeight;
                    }
                    int priorityWeight() { return priorityWeight; }
                }

                @StoredFunction
                public static int journalPageWeight(String pageLabel, int entryCount, boolean hasNextPage,
                        ProductLifecycleStageClassificationChannel stage) {
                    ProductStateJournalEntryPageSnapshot snapshot =
                            new ProductStateJournalEntryPageSnapshot(pageLabel, entryCount, hasNextPage);
                    int weight = stage.priorityWeight();
                    if (snapshot.hasNextPage()) {
                        return snapshot.entryCount() + weight;
                    }
                    return snapshot.entryCount() - weight;
                }
            }
            """;

    @Test
    void overLimitQualifiedNamesEmitWithinTheCeilingAndStayConsistentOnBothDialects() throws Exception {
        Path source = tempDir.resolve("DurableManagementStoreProductStateJournal.java");
        Files.writeString(source, LONG_NAME_FIXTURE);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        // Precondition: the fixture's raw names really overflow both ceilings (96 and 116 chars).
        assertTrue(("__record_durable_management_store_product_state_journal_"
                + "product_state_journal_entry_page_snapshot__has_next_page").length() > 64);

        for (String target : List.of("postgresql", "mysql")) {
            List<TranspilationPipeline.GeneratedSql> artifacts = generated.stream()
                    .filter(artifact -> artifact.target().equals(target))
                    .toList();
            assertEquals(3, artifacts.size(), "enum + record + routine expected for " + target);

            // 1. No emitted identifier may exceed the ceiling on either dialect.
            for (TranspilationPipeline.GeneratedSql artifact : artifacts) {
                Matcher matcher = OVERLONG_TOKEN.matcher(artifact.sql());
                if (matcher.find()) {
                    throw new AssertionError(target + " artifact '" + artifact.artifactName()
                            + "' emits an identifier over the dialect ceiling: " + matcher.group()
                            + "\n" + artifact.sql());
                }
            }

            // 2. Inventory <-> DDL consistency: every described object name (what install plans,
            // rollback scripts and the install verifier consume) appears verbatim in the same
            // artifact's emitted SQL, and every name fits the ceiling.
            Set<String> describedNames = new HashSet<>();
            for (TranspilationPipeline.GeneratedSql artifact : artifacts) {
                for (SqlObject object : artifact.sqlObjects()) {
                    assertTrue(object.name().length() <= 63,
                            target + " described object over ceiling: " + object.name());
                    assertTrue(artifact.sql().contains(object.name()),
                            target + " inventory/DDL mismatch: described object '" + object.name()
                                    + "' does not appear in the emitted SQL of '"
                                    + artifact.artifactName() + "':\n" + artifact.sql());
                    assertTrue(describedNames.add(object.schema() + "." + object.name()),
                            target + " duplicate described object name (PG silent-truncation "
                                    + "collision class): " + object.name());
                }
            }

            // 3. Call sites resolve to the described (truncated) names: the routine must invoke
            // the record constructor, both used component accessors and the enum accessor by
            // their inventory names.
            TranspilationPipeline.GeneratedSql routine = artifacts.stream()
                    .filter(artifact -> "journalPageWeight".equals(artifact.artifactName()))
                    .findFirst().orElseThrow();
            String constructor = SqlNames.recordMemberName(
                    "DurableManagementStoreProductStateJournal.ProductStateJournalEntryPageSnapshot", "new");
            String hasNextPage = SqlNames.recordMemberName(
                    "DurableManagementStoreProductStateJournal.ProductStateJournalEntryPageSnapshot", "has_next_page");
            String entryCount = SqlNames.recordMemberName(
                    "DurableManagementStoreProductStateJournal.ProductStateJournalEntryPageSnapshot", "entry_count");
            String enumAccessor = SqlNames.enumMemberName(
                    "DurableManagementStoreProductStateJournal.ProductLifecycleStageClassificationChannel",
                    "priority_weight");
            for (String calledName : List.of(constructor, hasNextPage, entryCount, enumAccessor)) {
                assertTrue(calledName.length() <= 63, "call-site name over ceiling: " + calledName);
                assertTrue(routine.sql().contains(calledName),
                        target + " routine must call the truncated name '" + calledName + "':\n" + routine.sql());
                assertTrue(describedNames.contains("app." + calledName),
                        target + " called name '" + calledName + "' must be a described inventory object");
            }

            // 4. Dependency edges carry the truncated names: the routine depends on the record
            // type/constructor object and the enum lookup table by their described names.
            TranspilationPipeline.GeneratedSql recordArtifact = artifacts.stream()
                    .filter(artifact -> "record-type".equals(artifact.artifactKind()))
                    .findFirst().orElseThrow();
            TranspilationPipeline.GeneratedSql enumArtifact = artifacts.stream()
                    .filter(artifact -> "enum-lookup".equals(artifact.artifactKind()))
                    .findFirst().orElseThrow();
            assertTrue(routine.dependsOn().stream()
                            .anyMatch(ref -> ref.name().equals(recordArtifact.sqlObjects().get(0).name())),
                    target + " routine must depend on the truncated record object: " + routine.dependsOn());
            assertTrue(routine.dependsOn().stream()
                            .anyMatch(ref -> ref.name().equals(enumArtifact.sqlObjects().get(0).name())),
                    target + " routine must depend on the truncated enum lookup table: " + routine.dependsOn());
        }
    }

    /**
     * The PostgreSQL silent-truncation collision class, end to end: two records whose generated
     * names agree for the entire truncation prefix and differ only past it must emit DISTINCT
     * objects (full-name hash suffix), with each routine calling its own record's functions.
     */
    @Test
    void longNamesDifferingOnlyPastTheTruncationPointEmitDistinctObjects() throws Exception {
        Path source = tempDir.resolve("GeneratedArtifactWorkflowCoordination.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class GeneratedArtifactWorkflowCoordination {
                    record ProductStateJournalEntrySnapshotAlpha(int depth) {}

                    record ProductStateJournalEntrySnapshotBeta(int depth) {}

                    @StoredFunction
                    public static int alphaDepth(int depth) {
                        ProductStateJournalEntrySnapshotAlpha snapshot =
                                new ProductStateJournalEntrySnapshotAlpha(depth);
                        return snapshot.depth();
                    }

                    @StoredFunction
                    public static int betaDepth(int depth) {
                        ProductStateJournalEntrySnapshotBeta snapshot =
                                new ProductStateJournalEntrySnapshotBeta(depth);
                        return snapshot.depth();
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String alphaBase = SqlNames.recordSqlBaseName(
                "GeneratedArtifactWorkflowCoordination.ProductStateJournalEntrySnapshotAlpha");
        String betaBase = SqlNames.recordSqlBaseName(
                "GeneratedArtifactWorkflowCoordination.ProductStateJournalEntrySnapshotBeta");
        assertEquals(alphaBase.substring(0, 54), betaBase.substring(0, 54),
                "fixture must share the full truncation prefix to prove the collision class");
        assertNotEquals(alphaBase, betaBase);

        for (String target : List.of("postgresql", "mysql")) {
            List<TranspilationPipeline.GeneratedSql> artifacts = generated.stream()
                    .filter(artifact -> artifact.target().equals(target))
                    .toList();

            // Global uniqueness of described names (the package-time install-plan gate's view).
            Set<String> seen = new HashSet<>();
            for (TranspilationPipeline.GeneratedSql artifact : artifacts) {
                for (SqlObject object : artifact.sqlObjects()) {
                    assertTrue(seen.add(object.schema() + "." + object.name()),
                            target + " post-truncation name collision: " + object.name());
                }
            }

            TranspilationPipeline.GeneratedSql alphaRoutine = artifacts.stream()
                    .filter(artifact -> "alphaDepth".equals(artifact.artifactName())).findFirst().orElseThrow();
            TranspilationPipeline.GeneratedSql betaRoutine = artifacts.stream()
                    .filter(artifact -> "betaDepth".equals(artifact.artifactName())).findFirst().orElseThrow();
            assertTrue(alphaRoutine.sql().contains(SqlNames.recordMemberName(
                            "GeneratedArtifactWorkflowCoordination.ProductStateJournalEntrySnapshotAlpha", "depth")),
                    target + " alphaDepth must call its own accessor:\n" + alphaRoutine.sql());
            assertTrue(!alphaRoutine.sql().contains(betaBase),
                    target + " alphaDepth must not reference Beta's objects:\n" + alphaRoutine.sql());
            assertTrue(betaRoutine.sql().contains(SqlNames.recordMemberName(
                            "GeneratedArtifactWorkflowCoordination.ProductStateJournalEntrySnapshotBeta", "depth")),
                    target + " betaDepth must call its own accessor:\n" + betaRoutine.sql());
            assertTrue(!betaRoutine.sql().contains(alphaBase),
                    target + " betaDepth must not reference Alpha's objects:\n" + betaRoutine.sql());
        }
    }
}
