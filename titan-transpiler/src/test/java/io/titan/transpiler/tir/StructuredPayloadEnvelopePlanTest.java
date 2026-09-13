package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.COLUMN;
import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.TABLE;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.MANY;
import static io.titan.transpiler.tir.SemanticJsonAssertions.assertJsonSemanticallyEqualsOrderSensitive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StructuredPayloadEnvelopePlanTest {
    private final StructuredOutputRenderer renderer = new StructuredOutputRenderer();

    @Test
    void lowersSuccessPayloadWithDeterministicDataThenEmptyErrorsOrder() {
        StructuredPayloadEnvelopePlan envelope = StructuredPayloadEnvelopePlan.success(
                new StructuredOutputPlan.ObjectValue(List.of(
                        entry("id", scalar("dashboardId", "d", "id", new TIntType())),
                        entry("title", scalar("title", "d", "title", new TTextType())))));
        StructuredOutputPlan output = envelope.toStructuredOutputPlan();
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                output,
                RowMaterializationRuntimePlan.from(
                        dashboardRows(),
                        new QueryTemplatePlan.RuntimeParameter("p_dashboard_id", new TIntType())));

        assertEquals(List.of("data", "errors"), keys(output.root()));
        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "data", row("id", 10, "title", "Operations"),
                        "errors", List.of()),
                runtimeOutput.assemble(row("dashboardId", 10, "title", "Operations"), List.of()));
        assertEquals(
                "jsonb_build_object('data', jsonb_build_object('id', to_jsonb(\"d\".\"id\"), "
                        + "'title', to_jsonb(\"d\".\"title\")), 'errors', jsonb_build_array())",
                renderer.renderPostgreSql(output));
        assertEquals(
                "JSON_OBJECT('data', JSON_OBJECT('id', `d`.`id`, 'title', `d`.`title`), "
                        + "'errors', JSON_ARRAY())",
                renderer.renderMySql(output));
    }

    @Test
    void lowersErrorPayloadWithNullDataAndExtensionsThroughSameEnvelopeContract() {
        StructuredPayloadEnvelopePlan envelope = new StructuredPayloadEnvelopePlan(
                new StructuredOutputPlan.JsonNullValue(),
                List.of(errorObject()),
                Optional.of(new StructuredOutputPlan.ObjectValue(List.of(
                        entry("traceId", scalar("traceId", "v", "trace_id", new TTextType()))))),
                List.of(
                        StructuredPayloadEnvelopePlan.Field.ERRORS,
                        StructuredPayloadEnvelopePlan.Field.DATA,
                        StructuredPayloadEnvelopePlan.Field.EXTENSIONS));
        StructuredOutputPlan output = envelope.toStructuredOutputPlan();
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                output,
                RowMaterializationRuntimePlan.from(
                        validationRows(),
                        new QueryTemplatePlan.RuntimeParameter("p_request_id", new TIntType())));

        assertEquals(List.of("errors", "data", "extensions"), keys(output.root()));
        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "errors", List.of(row(
                                "code", "VALIDATION_FAILED",
                                "message", "title is required")),
                        "data", null,
                        "extensions", row("traceId", "trace-7")),
                runtimeOutput.assemble(row(
                        "code", "VALIDATION_FAILED",
                        "message", "title is required",
                        "traceId", "trace-7"), List.of()));
        assertEquals(
                "jsonb_build_object('errors', jsonb_build_array(jsonb_build_object('code', to_jsonb(\"v\".\"code\"), "
                        + "'message', to_jsonb(\"v\".\"message\"))), 'data', 'null'::jsonb, "
                        + "'extensions', jsonb_build_object('traceId', to_jsonb(\"v\".\"trace_id\")))",
                renderer.renderPostgreSql(output));
        assertEquals(
                "JSON_OBJECT('errors', JSON_ARRAY(JSON_OBJECT('code', `v`.`code`, 'message', `v`.`message`)), "
                        + "'data', CAST(NULL AS CHAR), 'extensions', JSON_OBJECT('traceId', `v`.`trace_id`))",
                renderer.renderMySql(output));
    }

    @Test
    void assemblesEnvelopeWrappedRelationPayloadsThroughDataSlot() {
        RowMaterializationPlan rows = orderRows();
        StructuredPayloadEnvelopePlan envelope = StructuredPayloadEnvelopePlan.success(
                StructuredOutputPlan.fromRowMaterialization(rows).root());
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                envelope.toStructuredOutputPlan(),
                RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_order_id", new TIntType())));

        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "data", row(
                                "id", 100,
                                "customerName", "Ada",
                                "order_lines", List.of(
                                        row("sku", "titan-pro", "quantity", 2),
                                        row("sku", "titan-seat", "quantity", 5))),
                        "errors", List.of()),
                runtimeOutput.assemble(
                        row("id", 100, "customerName", "Ada"),
                        List.of(
                                row("sku", "titan-pro", "quantity", 2, "l_key", 100),
                                row("sku", "titan-seat", "quantity", 5, "l_key", 100))));
        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "data", row(
                                "id", 200,
                                "customerName", "Grace",
                                "order_lines", List.of()),
                        "errors", List.of()),
                runtimeOutput.assemble(
                        row("id", 200, "customerName", "Grace"),
                        List.of()));
    }

    @Test
    void assemblesEnvelopeWrappedNestedRelationPayloadsThroughDataSlot() {
        RowMaterializationPlan rows = courseLessonResourceRows();
        StructuredPayloadEnvelopePlan envelope = StructuredPayloadEnvelopePlan.success(
                StructuredOutputPlan.fromRowMaterialization(rows).root());
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                envelope.toStructuredOutputPlan(),
                RowMaterializationRuntimePlan.fromListRoot(rows));

        assertJsonSemanticallyEqualsOrderSensitive(
                List.of(
                        row(
                                "data", row(
                                        "id", 10,
                                        "displayTitle", "Structured Runtime",
                                        "course_lessons", List.of(
                                                row(
                                                        "lessonSummary", "Intro",
                                                        "lesson_resources", List.of(
                                                                row("resourceUrl", "/intro.sql"),
                                                                row("resourceUrl", "/intro.java"))),
                                                row(
                                                        "lessonSummary", "Windows",
                                                        "lesson_resources", List.of()))),
                                "errors", List.of()),
                        row(
                                "data", row(
                                        "id", 20,
                                        "displayTitle", "Runtime SQL",
                                        "course_lessons", List.of(row(
                                                "lessonSummary", "Arrays",
                                                "lesson_resources", List.of(row("resourceUrl", "/arrays.sql"))))),
                                "errors", List.of())),
                runtimeOutput.assembleNestedBatch(
                        List.of(
                                row("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10),
                                row("id", 20, "displayTitle", "Runtime SQL", "c_identity", 20)),
                        Map.of(
                                "course_lessons",
                                List.of(
                                        row("lessonSummary", "Intro", "l_course_id_key", 10, "l_key", 100),
                                        row("lessonSummary", "Windows", "l_course_id_key", 10, "l_key", 110),
                                        row("lessonSummary", "Arrays", "l_course_id_key", 20, "l_key", 200)),
                                "lesson_resources",
                                List.of(
                                        row("resourceUrl", "/intro.sql", "r_key", 100),
                                        row("resourceUrl", "/intro.java", "r_key", 100),
                                        row("resourceUrl", "/arrays.sql", "r_key", 200)))));
    }

    @Test
    void rejectsEnvelopeFieldOrdersThatOmitRequiredSlotsOrDuplicateSlots() {
        IllegalArgumentException missingData = assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredPayloadEnvelopePlan(
                        new StructuredOutputPlan.JsonNullValue(),
                        List.of(),
                        Optional.empty(),
                        List.of(StructuredPayloadEnvelopePlan.Field.ERRORS)));
        assertTrue(missingData.getMessage().contains("TITAN-E001"));
        assertTrue(missingData.getMessage().contains("data and errors"));

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredPayloadEnvelopePlan(
                        new StructuredOutputPlan.JsonNullValue(),
                        List.of(),
                        Optional.empty(),
                        List.of(
                                StructuredPayloadEnvelopePlan.Field.DATA,
                                StructuredPayloadEnvelopePlan.Field.ERRORS,
                                StructuredPayloadEnvelopePlan.Field.ERRORS)));
        assertTrue(duplicate.getMessage().contains("TITAN-E001"));
        assertTrue(duplicate.getMessage().contains("unique"));
    }

    @Test
    void rejectsExtensionsWhenConfiguredOrderWouldOmitThem() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredPayloadEnvelopePlan(
                        new StructuredOutputPlan.JsonNullValue(),
                        List.of(),
                        Optional.of(new StructuredOutputPlan.ObjectValue(List.of())),
                        List.of(
                                StructuredPayloadEnvelopePlan.Field.DATA,
                                StructuredPayloadEnvelopePlan.Field.ERRORS)));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("extensions"));
    }

    private static StructuredOutputPlan.ObjectValue errorObject() {
        return new StructuredOutputPlan.ObjectValue(List.of(
                entry("code", scalar("code", "v", "code", new TTextType())),
                entry("message", scalar("message", "v", "message", new TTextType()))));
    }

    private static RowMaterializationPlan dashboardRows() {
        return new RowMaterializationPlan(
                table("dashboards", "d"),
                new RowMaterializationPlan.RowKey("d_identity", column("d", "id"), new TIntType()),
                List.of(
                        new RowMaterializationPlan.FieldBinding("dashboardId", column("d", "id"), new TIntType()),
                        new RowMaterializationPlan.FieldBinding("title", column("d", "title"), new TTextType())),
                List.of());
    }

    private static RowMaterializationPlan validationRows() {
        return new RowMaterializationPlan(
                table("validation_events", "v"),
                new RowMaterializationPlan.RowKey("v_identity", column("v", "id"), new TIntType()),
                List.of(
                        new RowMaterializationPlan.FieldBinding("code", column("v", "code"), new TTextType()),
                        new RowMaterializationPlan.FieldBinding("message", column("v", "message"), new TTextType()),
                        new RowMaterializationPlan.FieldBinding("traceId", column("v", "trace_id"), new TTextType())),
                List.of());
    }

    private static RowMaterializationPlan orderRows() {
        return new RowMaterializationPlan(
                table("orders", "o"),
                new RowMaterializationPlan.RowKey("o_identity", column("o", "id"), new TIntType()),
                List.of(
                        new RowMaterializationPlan.FieldBinding("id", column("o", "id"), new TIntType()),
                        new RowMaterializationPlan.FieldBinding(
                                "customerName",
                                column("o", "customer_name"),
                                new TTextType())),
                List.of(new RowMaterializationPlan.RelationRows(
                        "order_lines",
                        table("order_lines", "l"),
                        new RowMaterializationPlan.RowKey("o_key", column("o", "id"), new TIntType()),
                        new RowMaterializationPlan.RowKey("l_key", column("l", "order_id"), new TIntType()),
                        MANY,
                        List.of(
                                new RowMaterializationPlan.FieldBinding("sku", column("l", "sku"), new TTextType()),
                                new RowMaterializationPlan.FieldBinding(
                                        "quantity",
                                        column("l", "quantity"),
                                new TIntType())))));
    }

    private static RowMaterializationPlan courseLessonResourceRows() {
        return new RowMaterializationPlan(
                table("courses", "c"),
                QueryTemplatePlan.RootKind.LIST,
                new RowMaterializationPlan.RowKey("c_identity", column("c", "id"), new TIntType()),
                List.of(
                        new RowMaterializationPlan.FieldBinding("id", column("c", "id"), new TIntType()),
                        new RowMaterializationPlan.FieldBinding(
                                "displayTitle",
                                column("c", "title"),
                                new TTextType())),
                List.of(
                        new RowMaterializationPlan.RelationRows(
                                "course_lessons",
                                table("lessons", "l"),
                                new RowMaterializationPlan.RowKey("c_key", column("c", "id"), new TIntType()),
                                new RowMaterializationPlan.RowKey(
                                        "l_course_id_key",
                                        column("l", "course_id"),
                                        new TIntType()),
                                MANY,
                                List.of(new RowMaterializationPlan.FieldBinding(
                                        "lessonSummary",
                                        column("l", "summary"),
                                        new TTextType()))),
                        new RowMaterializationPlan.RelationRows(
                                "lesson_resources",
                                table("resources", "r"),
                                new RowMaterializationPlan.RowKey("l_key", column("l", "id"), new TIntType()),
                                new RowMaterializationPlan.RowKey("r_key", column("r", "lesson_id"), new TIntType()),
                                MANY,
                                List.of(new RowMaterializationPlan.FieldBinding(
                                        "resourceUrl",
                                        column("r", "url"),
                                        new TTextType())))),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    private static List<String> keys(StructuredOutputPlan.ObjectValue object) {
        return object.entries().stream()
                .map(entry -> entry.key().value())
                .toList();
    }

    private static StructuredOutputPlan.Entry entry(String key, StructuredOutputPlan.Value value) {
        return new StructuredOutputPlan.Entry(StructuredOutputPlan.OutputKey.compilerKnown(key), value);
    }

    private static StructuredOutputPlan.ScalarValue scalar(
            String fieldName,
            String tableAlias,
            String column,
            TirType type
    ) {
        return new StructuredOutputPlan.ScalarValue(fieldName, column(tableAlias, column), type);
    }

    private static QueryTemplatePlan.TableRef table(String table, String alias) {
        return new QueryTemplatePlan.TableRef(null, compilerKnown(TABLE, table), alias);
    }

    private static QueryTemplatePlan.ColumnRef column(String alias, String column) {
        return new QueryTemplatePlan.ColumnRef(alias, compilerKnown(COLUMN, column));
    }

    private static QueryTemplatePlan.StructuralIdentifier compilerKnown(
            QueryTemplatePlan.IdentifierKind kind,
            String value
    ) {
        return QueryTemplatePlan.StructuralIdentifier.compilerKnown(kind, value);
    }

    private static LinkedHashMap<String, Object> row(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("row requires key/value pairs");
        }
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            row.put((String) pairs[index], pairs[index + 1]);
        }
        return row;
    }
}
