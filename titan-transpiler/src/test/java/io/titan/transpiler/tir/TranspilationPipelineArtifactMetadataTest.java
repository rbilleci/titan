package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 4.4 (audit G-10): the pipeline's structured artifact metadata — object kinds,
 * schema-qualified names, typed signatures, dependency edges — replaces regex discovery over
 * the emitted SQL.
 */
class TranspilationPipelineArtifactMetadataTest {

    @TempDir
    Path tempDir;

    @Test
    void describesTriggerArtifactsPerDialect() throws Exception {
        Path source = tempDir.resolve("MetadataTriggerDemo.java");
        Files.writeString(source, """
                import titan.dsl.Trigger;
                import titan.dsl.TriggerEvent;
                import titan.dsl.TriggerTiming;

                class MetadataTriggerDemo {
                    @Trigger(table = "accounts", timing = TriggerTiming.AFTER, event = { TriggerEvent.INSERT, TriggerEvent.UPDATE })
                    static void afterWrite() {
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        TranspilationPipeline.GeneratedSql postgres = byTarget(generated, "postgresql");
        assertEquals("trigger", postgres.artifactKind());
        assertEquals("app", postgres.schemaName());
        // PostgreSQL triggers are a trigger function plus the CREATE TRIGGER attaching it —
        // the old CREATE-regex saw only the function and missed the trigger object entirely.
        assertEquals(2, postgres.sqlObjects().size());
        SqlObject triggerFunction = postgres.sqlObjects().get(0);
        assertEquals(SqlObject.Kind.FUNCTION, triggerFunction.kind());
        assertEquals("after_write_fn", triggerFunction.name());
        assertEquals("trigger", triggerFunction.returnType());
        SqlObject trigger = postgres.sqlObjects().get(1);
        assertEquals(SqlObject.Kind.TRIGGER, trigger.kind());
        assertEquals("after_write_trg", trigger.name());
        assertEquals("app.accounts", trigger.onTable());

        TranspilationPipeline.GeneratedSql mysql = byTarget(generated, "mysql");
        assertEquals("trigger", mysql.artifactKind());
        // MySQL has no trigger functions: one trigger per event.
        assertEquals(
                List.of("after_write_insert_trg", "after_write_update_trg"),
                mysql.sqlObjects().stream().map(SqlObject::name).toList());
        assertTrue(mysql.sqlObjects().stream().allMatch(object -> object.kind() == SqlObject.Kind.TRIGGER));
        assertTrue(mysql.sqlObjects().stream().allMatch(object -> object.onTable().equals("app.accounts")));
    }

    @Test
    void describesScheduledJobArtifactsPerDialect() throws Exception {
        Path source = tempDir.resolve("MetadataScheduledDemo.java");
        Files.writeString(source, """
                import titan.dsl.ScheduledJob;

                class MetadataScheduledDemo {
                    @ScheduledJob(cron = "15 4 * * *", name = "nightly_refresh")
                    public static void refreshNightly() {}
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        TranspilationPipeline.GeneratedSql postgres = byTarget(generated, "postgresql");
        assertEquals("scheduled-job", postgres.artifactKind());
        // PostgreSQL schedules through pg_cron (a guarded DO block, not a catalog object).
        assertEquals(
                List.of(SqlObject.Kind.PROCEDURE),
                postgres.sqlObjects().stream().map(SqlObject::kind).toList());

        TranspilationPipeline.GeneratedSql mysql = byTarget(generated, "mysql");
        // MySQL adds the CREATE EVENT — invisible to the old CREATE-regex.
        assertEquals(
                List.of(SqlObject.Kind.PROCEDURE, SqlObject.Kind.EVENT),
                mysql.sqlObjects().stream().map(SqlObject::kind).toList());
        assertEquals("nightly_refresh", mysql.sqlObjects().get(1).name());
    }

    @Test
    void nameContainmentCreatesNoDependencyEdge() throws Exception {
        Path source = tempDir.resolve("MetadataFalseEdgeDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class MetadataFalseEdgeDemo {
                    @StoredFunction
                    public static long getUser(long id) {
                        return id;
                    }

                    @StoredFunction
                    public static long getUserOrders(long userId) {
                        return userId * 10;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("app"),
                true);

        // 'get_user' is a substring of 'get_user_orders'; the old packager inferred a false
        // edge from exactly that containment. The structured graph has no edge: neither
        // routine calls the other.
        for (TranspilationPipeline.GeneratedSql artifact : generated) {
            assertEquals(List.of(), artifact.dependsOn(),
                    artifact.sqlName() + " must not depend on anything");
        }
    }

    @Test
    void callGraphCreatesRealDependencyEdges() throws Exception {
        Path source = tempDir.resolve("MetadataCallEdgeDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class MetadataCallEdgeDemo {
                    @StoredFunction
                    public static long getUser(long id) {
                        return id;
                    }

                    @StoredFunction
                    public static long getUserOrders(long userId) {
                        return getUser(userId) * 10;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("app"),
                true);

        TranspilationPipeline.GeneratedSql orders = generated.stream()
                .filter(artifact -> artifact.sqlName().equals("get_user_orders"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                List.of(new SqlObjectRef(SqlObject.Kind.FUNCTION, "app", "get_user")),
                orders.dependsOn());

        TranspilationPipeline.GeneratedSql user = generated.stream()
                .filter(artifact -> artifact.sqlName().equals("get_user"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(), user.dependsOn());
    }

    @Test
    void typedSignatureKeepsParenthesizedNumericTypesIntact() throws Exception {
        Path source = tempDir.resolve("MetadataNumericDemo.java");
        Files.writeString(source, """
                import java.math.BigDecimal;
                import titan.dsl.StoredFunction;

                class MetadataNumericDemo {
                    @StoredFunction
                    public static BigDecimal applyDiscount(BigDecimal amount, long percent) {
                        return amount;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        TranspilationPipeline.GeneratedSql postgres = byTarget(generated, "postgresql");
        SqlObject function = postgres.sqlObjects().get(0);
        // The old manifest split signatures on bare commas, turning NUMERIC(38,10) into two
        // bogus parameters. The structured signature keeps it one typed parameter.
        assertEquals(2, function.parameters().size());
        assertEquals(new SqlObject.Parameter("p_amount", "NUMERIC(38,10)"), function.parameters().get(0));
        assertEquals(new SqlObject.Parameter("p_percent", "BIGINT"), function.parameters().get(1));
        assertEquals("(p_amount NUMERIC(38,10), p_percent BIGINT)", function.signature());

        TranspilationPipeline.GeneratedSql mysql = byTarget(generated, "mysql");
        assertEquals(2, mysql.sqlObjects().get(0).parameters().size());
        assertEquals("DECIMAL(38,10)", mysql.sqlObjects().get(0).parameters().get(0).type());
    }

    @Test
    void recordUsageCreatesEdgeToRecordTypeArtifact() throws Exception {
        Path source = tempDir.resolve("MetadataRecordDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                record PriceTier(long threshold, String label) {}

                class MetadataRecordDemo {
                    @StoredFunction
                    public static String tierLabel(long volume) {
                        PriceTier tier = new PriceTier(100L, "bulk");
                        if (volume >= tier.threshold()) {
                            return tier.label();
                        }
                        return "standard";
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("app"),
                true);

        TranspilationPipeline.GeneratedSql recordArtifact = generated.stream()
                .filter(artifact -> artifact.artifactKind().equals("record-type"))
                .findFirst()
                .orElseThrow();
        assertEquals("__record_price_tier", recordArtifact.sqlName());
        assertEquals(SqlObject.Kind.TYPE, recordArtifact.sqlObjects().get(0).kind());

        TranspilationPipeline.GeneratedSql routine = generated.stream()
                .filter(artifact -> artifact.sqlName().equals("tier_label"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                List.of(new SqlObjectRef(SqlObject.Kind.TYPE, "app", "__record_price_tier")),
                routine.dependsOn());
    }

    @Test
    void enumAccessorNamedAfterBackingFieldEmitsAndDescribesExactlyOneFunction() throws Exception {
        // B-4 (TG-BLK-004): a no-arg enum method named exactly after its backing field (the
        // record-style accessor default) used to emit a byte-identical duplicate accessor
        // function AND describe a duplicate inventory object, which the packaging uniqueness
        // gate then failed. Both spellings must produce exactly one function each:
        // transport() (field-named) shares the field accessor; isRetryable() (get/is-style)
        // gets its own distinct accessor alongside the field's.
        Path source = tempDir.resolve("MetadataEnumAccessorDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class MetadataEnumAccessorDemo {
                    enum Channel {
                        EMAIL("smtp", true), SMS("gateway", false);
                        final String transport;
                        final boolean retryable;
                        Channel(String transport, boolean retryable) {
                            this.transport = transport;
                            this.retryable = retryable;
                        }
                        String transport() { return transport; }
                        boolean isRetryable() { return retryable; }
                    }

                    @StoredFunction
                    public static String transportOf(Channel channel) {
                        return channel.transport();
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        for (String target : List.of("postgresql", "mysql")) {
            TranspilationPipeline.GeneratedSql enumArtifact = generated.stream()
                    .filter(artifact -> artifact.target().equals(target))
                    .filter(artifact -> artifact.artifactKind().equals("enum-lookup"))
                    .findFirst()
                    .orElseThrow();

            // describeEnumLookup: the table plus exactly one object per accessor id — no
            // duplicate for the field-named transport() method.
            List<String> objectNames = enumArtifact.sqlObjects().stream().map(SqlObject::name).toList();
            assertEquals(
                    List.of("__enum_metadata_enum_accessor_demo_channel",
                            "__enum_metadata_enum_accessor_demo_channel__transport",
                            "__enum_metadata_enum_accessor_demo_channel__retryable",
                            "__enum_metadata_enum_accessor_demo_channel__is_retryable"),
                    objectNames,
                    target + ": described enum objects");
            assertEquals(objectNames.size(), objectNames.stream().distinct().count(),
                    target + ": described enum object names must be unique");

            // emitEnumLookup: exactly one CREATE FUNCTION per accessor.
            String sql = enumArtifact.sql();
            for (String accessor : List.of("__enum_metadata_enum_accessor_demo_channel__transport",
                    "__enum_metadata_enum_accessor_demo_channel__retryable",
                    "__enum_metadata_enum_accessor_demo_channel__is_retryable")) {
                long creates = sql.lines()
                        .filter(line -> line.contains("CREATE") && line.contains("FUNCTION")
                                && (line.contains(accessor + "\"") || line.contains(accessor + "`")))
                        .count();
                assertEquals(1, creates, target + ": expected exactly one CREATE FUNCTION for "
                        + accessor + " in:\n" + sql);
            }

            // The call site still resolves: channel.transport() lowers to the (single)
            // field-derived accessor function.
            TranspilationPipeline.GeneratedSql routine = generated.stream()
                    .filter(artifact -> artifact.target().equals(target))
                    .filter(artifact -> artifact.sqlName().equals("transport_of"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(routine.sql().contains("__enum_metadata_enum_accessor_demo_channel__transport("),
                    target + ": call site must reference the deduplicated accessor:\n" + routine.sql());
        }
    }

    private static TranspilationPipeline.GeneratedSql byTarget(
            List<TranspilationPipeline.GeneratedSql> generated,
            String target
    ) {
        return generated.stream()
                .filter(artifact -> artifact.target().equals(target))
                .findFirst()
                .orElseThrow();
    }
}
