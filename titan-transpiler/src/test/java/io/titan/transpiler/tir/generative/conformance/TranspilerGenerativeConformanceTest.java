package io.titan.transpiler.tir.generative.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.tir.TranspilationPipeline;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranspilerGenerativeConformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void transpilerBasicSelectProfileProducesDialectSqlForSeedCorpus() throws Exception {
        TranspilerGenerativeHarness harness = new TranspilerGenerativeHarness();

        for (long seed : TranspilerSeedCorpus.curatedBasicSelectSeeds()) {
            TranspilerGenerativeHarness.HarnessResult result = harness.runBasicSelect(seed, tempDir);
            GeneratedTranspilerCase generatedCase = result.generatedCase();
            var generatedSql = result.generatedSql();
            String failureContext = "seed=" + Long.toUnsignedString(seed)
                    + ", profile=" + generatedCase.profile()
                    + ", family=" + generatedCase.family()
                    + ", artifacts=" + result.artifactDir()
                    + ", summary=" + generatedCase.summary();

            assertFalse(generatedCase.shouldFail(), failureContext);
            assertEquals(2, generatedSql.size(), failureContext);
            assertEquals(Set.of("postgresql", "mysql"), generatedSql.stream().map(TranspilationPipeline.GeneratedSql::target).collect(java.util.stream.Collectors.toSet()), failureContext);

            for (TranspilationPipeline.GeneratedSql sql : generatedSql) {
                assertEquals(generatedCase.className(), sql.className(), failureContext + ", target=" + sql.target());
                assertEquals("run", sql.methodName(), failureContext + ", target=" + sql.target());
                assertFalse(sql.sql().isBlank(), failureContext + ", target=" + sql.target());
                assertTrue(sql.sql().toLowerCase().contains("create"), failureContext + ", target=" + sql.target());
                for (String fragment : generatedCase.expectedFragments()) {
                    assertTrue(sql.sql().toLowerCase().contains(fragment.toLowerCase()), failureContext + ", target=" + sql.target() + ", missingFragment=" + fragment);
                }
                assertFalse(sql.sql().contains("Unsupported node type"), failureContext + ", target=" + sql.target());
                assertFalse(sql.sql().contains("<error>"), failureContext + ", target=" + sql.target());
                assertFalse(sql.sql().contains("java.lang"), failureContext + ", target=" + sql.target());
            }
        }
    }

    @Test
    void transpilerInvalidProfilesFailWithExpectedDiagnostics() throws Exception {
        TranspilerGenerativeHarness harness = new TranspilerGenerativeHarness();

        for (long seed : TranspilerSeedCorpus.curatedInvalidSeeds()) {
            TranspilerGenerativeHarness.HarnessResult result = harness.runInvalidCase(seed, tempDir);
            assertInvalidDiagnostic(result);
        }
    }

    @Test
    void transpilerInvalidCompositionProfilesFailWithExpectedDiagnostics() throws Exception {
        TranspilerGenerativeHarness harness = new TranspilerGenerativeHarness();

        for (long seed : TranspilerSeedCorpus.curatedInvalidCompositionSeeds()) {
            TranspilerGenerativeHarness.HarnessResult result = harness.runInvalidComposition(seed, tempDir);
            assertInvalidDiagnostic(result);
        }
    }

    @Test
    void transpilerSubqueryCteProfileProducesDialectSqlForSeedCorpus() throws Exception {
        TranspilerGenerativeHarness harness = new TranspilerGenerativeHarness();

        for (long seed : TranspilerSeedCorpus.curatedSubqueryCteSeeds()) {
            TranspilerGenerativeHarness.HarnessResult result = harness.runSubqueryCte(seed, tempDir);
            assertValidDialectSql(result);
        }
    }

    @Test
    void transpilerAggregationProfileProducesDialectSqlForSeedCorpus() throws Exception {
        TranspilerGenerativeHarness harness = new TranspilerGenerativeHarness();

        for (long seed : TranspilerSeedCorpus.curatedAggregationSeeds()) {
            TranspilerGenerativeHarness.HarnessResult result = harness.runAggregation(seed, tempDir);
            assertValidDialectSql(result);
        }
    }

    @Test
    void transpilerJoinCompositionProfileProducesDialectSqlForSeedCorpus() throws Exception {
        TranspilerGenerativeHarness harness = new TranspilerGenerativeHarness();

        for (long seed : TranspilerSeedCorpus.curatedJoinCompositionSeeds()) {
            TranspilerGenerativeHarness.HarnessResult result = harness.runJoinComposition(seed, tempDir);
            assertValidDialectSql(result);
        }
    }

    private static void assertValidDialectSql(TranspilerGenerativeHarness.HarnessResult result) {
        GeneratedTranspilerCase generatedCase = result.generatedCase();
        var generatedSql = result.generatedSql();
        String failureContext = "seed=" + Long.toUnsignedString(generatedCase.seed())
                + ", profile=" + generatedCase.profile()
                + ", family=" + generatedCase.family()
                + ", artifacts=" + result.artifactDir()
                + ", summary=" + generatedCase.summary()
                + ", error=" + result.errorMessage();

        assertFalse(generatedCase.shouldFail(), failureContext);
        assertEquals(2, generatedSql.size(), failureContext);
        assertEquals(Set.of("postgresql", "mysql"), generatedSql.stream().map(TranspilationPipeline.GeneratedSql::target).collect(java.util.stream.Collectors.toSet()), failureContext);

        for (TranspilationPipeline.GeneratedSql sql : generatedSql) {
            assertEquals(generatedCase.className(), sql.className(), failureContext + ", target=" + sql.target());
            assertEquals("run", sql.methodName(), failureContext + ", target=" + sql.target());
            assertFalse(sql.sql().isBlank(), failureContext + ", target=" + sql.target());
            assertTrue(sql.sql().toLowerCase().contains("create"), failureContext + ", target=" + sql.target());
            for (String fragment : generatedCase.expectedFragments()) {
                assertTrue(sql.sql().toLowerCase().contains(fragment.toLowerCase()), failureContext + ", target=" + sql.target() + ", missingFragment=" + fragment);
            }
            assertFalse(sql.sql().contains("Unsupported node type"), failureContext + ", target=" + sql.target());
            assertFalse(sql.sql().contains("<error>"), failureContext + ", target=" + sql.target());
            assertFalse(sql.sql().contains("java.lang"), failureContext + ", target=" + sql.target());
        }
    }

    private static void assertInvalidDiagnostic(TranspilerGenerativeHarness.HarnessResult result) {
        GeneratedTranspilerCase generatedCase = result.generatedCase();
        String failureContext = "seed=" + Long.toUnsignedString(generatedCase.seed())
                + ", profile=" + generatedCase.profile()
                + ", family=" + generatedCase.family()
                + ", artifacts=" + result.artifactDir()
                + ", summary=" + generatedCase.summary();

        assertTrue(generatedCase.shouldFail(), failureContext);
        assertTrue(result.generatedSql().isEmpty(), failureContext);
        assertNotNull(result.errorMessage(), failureContext);
        for (String fragment : generatedCase.expectedFragments()) {
            assertTrue(result.errorMessage().contains(fragment), failureContext + ", missingFragment=" + fragment);
        }
        assertFalse(result.errorMessage().contains("NullPointerException"), failureContext);
        assertFalse(result.errorMessage().contains("java.lang.AssertionError"), failureContext);
    }
}
