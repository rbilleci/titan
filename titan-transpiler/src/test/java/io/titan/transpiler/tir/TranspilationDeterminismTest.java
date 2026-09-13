package io.titan.transpiler.tir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Determinism gate (plan §10.4 invariant 7, Phase 1 exit): generated SQL is byte-identical
 * across two runs of the same input, for both dialects, including artifact set, ordering and
 * metadata. Reuses the {@code EmitterDeployabilityIT} fixture (control flow, exception
 * handling, string builtins, switches, enums, and a DSL insert) so the gate covers the
 * emission shapes the deployability IT exercises — without requiring containers.
 */
class TranspilationDeterminismTest {

    @TempDir
    Path tempDir;

    @Test
    void transpilationIsByteIdenticalAcrossRunsForBothDialects() throws Exception {
        Path source = tempDir.resolve("DeterminismFixture.java");
        Files.writeString(source, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class DeterminismFixture {
                    enum Tier { BASIC, PRO }

                    static final GateEventsTable GATE_EVENTS = new GateEventsTable();

                    @StoredFunction
                    public static String guardedLabel(String input, boolean failFast) {
                        String label = "start";
                        try {
                            if (failFast) {
                                throw new IllegalStateException("TITAN_GATE_FAIL_FAST");
                            }
                            label += ":" + input;
                        } catch (IllegalStateException error) {
                            label = "caught";
                        } finally {
                            label += ":done";
                        }
                        return label;
                    }

                    @StoredFunction
                    public static int rejectNegative(int value) {
                        if (value < 0) {
                            throw new RuntimeException("TITAN_GATE_NEGATIVE");
                        }
                        return value * 2;
                    }

                    @StoredFunction
                    public static int stringProbe(String text, String needle) {
                        char first = text.charAt(0);
                        int score = first - 'a';
                        int at = text.indexOf(needle, 1);
                        if (text.startsWith(needle, 2)) {
                            score = score + 10;
                        }
                        if (text.equals(needle)) {
                            score = score + 100;
                        }
                        return score + at;
                    }

                    @StoredFunction
                    public static int switchScore(int input) {
                        int score = 0;
                        switch (input) {
                            case 1 -> {
                                score = 10;
                            }
                            case 2 -> {
                                score = 20;
                            }
                            default -> {
                                score = -1;
                            }
                        }
                        return score;
                    }

                    @StoredFunction
                    public static int tierRank(Tier tier) {
                        int rank = -1;
                        switch (tier) {
                            case BASIC -> {
                                rank = 1;
                            }
                            case PRO -> {
                                rank = 2;
                            }
                            default -> {
                                rank = 0;
                            }
                        }
                        return rank;
                    }

                    @StoredProcedure
                    public static void recordEvent(int id, String name) {
                        insertInto(GATE_EVENTS)
                                .set(GATE_EVENTS.ID, id)
                                .set(GATE_EVENTS.NAME, name)
                                .onConflict(GATE_EVENTS.ID)
                                .doUpdate()
                                .execute();
                    }

                    static final class GateEventsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> NAME = column("name", SQLType.VARCHAR, Nullability.NOT_NULL);

                        GateEventsTable() {
                            super("gate_events", "test");
                        }
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> firstRun = transpile(source);
        List<TranspilationPipeline.GeneratedSql> secondRun = transpile(source);

        assertFalse(firstRun.isEmpty(), "fixture must produce artifacts for the gate to be meaningful");
        assertEquals(firstRun.size(), secondRun.size(), "artifact count must be identical across runs");

        for (int i = 0; i < firstRun.size(); i++) {
            TranspilationPipeline.GeneratedSql first = firstRun.get(i);
            TranspilationPipeline.GeneratedSql second = secondRun.get(i);
            assertArrayEquals(first.sql().getBytes(UTF_8), second.sql().getBytes(UTF_8),
                    "SQL must be byte-identical across runs for " + first.target() + " artifact '"
                            + first.artifactName() + "' (index " + i + ")");
            assertEquals(first, second,
                    "generated artifact (metadata included) must be identical across runs at index " + i);
        }
    }

    private List<TranspilationPipeline.GeneratedSql> transpile(Path source) {
        // A fresh pipeline per run: determinism must not depend on per-instance state.
        return new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("test"),
                true);
    }
}
