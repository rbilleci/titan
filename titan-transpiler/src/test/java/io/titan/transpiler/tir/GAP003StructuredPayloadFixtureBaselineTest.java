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
import org.junit.jupiter.api.Test;

class GAP003StructuredPayloadFixtureBaselineTest {
    private final StructuredOutputRenderer renderer = new StructuredOutputRenderer();

    @Test
    void modelsNonGraphqlSuccessPayloadAliasesTypedNullsAndPageMetadata() {
        StructuredOutputPlan output = dashboardPayloadPlan();
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                output,
                RowMaterializationRuntimePlan.from(
                        dashboardRows(),
                        new QueryTemplatePlan.RuntimeParameter("p_dashboard_id", new TIntType())));

        Map<String, Object> assembled = runtimeOutput.assemble(row(
                "dashboardId", 10,
                "displayTitle", "Operations",
                "hasNext", true,
                "endCursor", "cursor-10"), List.of());

        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "payload", row(
                                "id", 10,
                                "title", "Operations",
                                "archivedAt", null),
                        "window", row(
                                "hasNext", true,
                                "endCursor", "cursor-10")),
                assembled);
        assertEquals(
                "jsonb_build_object('payload', jsonb_build_object('id', to_jsonb(\"d\".\"id\"), "
                        + "'title', to_jsonb(\"d\".\"title\"), "
                        + "'archivedAt', to_jsonb(CAST(NULL AS TIMESTAMPTZ))), "
                        + "'window', jsonb_build_object('hasNext', to_jsonb(\"d\".\"has_next\"), "
                        + "'endCursor', to_jsonb(\"d\".\"end_cursor\")))",
                renderer.renderPostgreSql(output));
        assertEquals(
                "JSON_OBJECT('payload', JSON_OBJECT('id', `d`.`id`, 'title', `d`.`title`, "
                        + "'archivedAt', CAST(NULL AS DATETIME)), "
                        + "'window', JSON_OBJECT('hasNext', `d`.`has_next`, 'endCursor', `d`.`end_cursor`))",
                renderer.renderMySql(output));
    }

    @Test
    void assemblesOrderPayloadWithNestedLineRowsAndEmptyCollections() {
        RowMaterializationPlan rows = orderRows();
        StructuredOutputPlan output = StructuredOutputPlan.fromRowMaterialization(rows);
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                output,
                RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_order_id", new TIntType())));

        List<Map<String, Object>> assembled = runtimeOutput.assembleBatch(
                List.of(
                        row("id", 100, "customerName", "Ada", "o_identity", 100),
                        row("id", 200, "customerName", "Grace", "o_identity", 200)),
                List.of(
                        row("sku", "titan-pro", "quantity", 2, "l_key", 100),
                        row("sku", "titan-seat", "quantity", 5, "l_key", 100)));

        assertJsonSemanticallyEqualsOrderSensitive(List.of(
                row(
                        "id", 100,
                        "customerName", "Ada",
                        "order_lines", List.of(
                                row("sku", "titan-pro", "quantity", 2),
                                row("sku", "titan-seat", "quantity", 5))),
                row(
                        "id", 200,
                        "customerName", "Grace",
                        "order_lines", List.of())), assembled);
    }

    @Test
    void modelsDeterministicValidationProblemPayloadShape() {
        StructuredOutputPlan output = new StructuredOutputPlan(new StructuredOutputPlan.ObjectValue(List.of(
                entry("problem", new StructuredOutputPlan.ObjectValue(List.of(
                        entry("code", scalar("code", "v", "code", new TTextType())),
                        entry("message", scalar("message", "v", "message", new TTextType())),
                        entry("path", scalar("path", "v", "path", new TTextType())),
                        entry("location", new StructuredOutputPlan.NullValue(new TTextType()))))))));
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                output,
                RowMaterializationRuntimePlan.from(
                        validationRows(),
                        new QueryTemplatePlan.RuntimeParameter("p_request_id", new TIntType())));

        Map<String, Object> assembled = runtimeOutput.assemble(row(
                "code", "VALIDATION_FAILED",
                "message", "title is required",
                "path", "payload.title"), List.of());

        assertJsonSemanticallyEqualsOrderSensitive(row(
                "problem", row(
                        "code", "VALIDATION_FAILED",
                        "message", "title is required",
                        "path", "payload.title",
                        "location", null)), assembled);
        assertEquals(
                "jsonb_build_object('problem', jsonb_build_object('code', to_jsonb(\"v\".\"code\"), "
                        + "'message', to_jsonb(\"v\".\"message\"), 'path', to_jsonb(\"v\".\"path\"), "
                        + "'location', to_jsonb(CAST(NULL AS TEXT))))",
                renderer.renderPostgreSql(output));
        assertEquals(
                "JSON_OBJECT('problem', JSON_OBJECT('code', `v`.`code`, 'message', `v`.`message`, "
                        + "'path', `v`.`path`, 'location', CAST(NULL AS CHAR)))",
                renderer.renderMySql(output));
    }

    @Test
    void keepsCurrentUnsupportedPayloadBoundariesPrecise() {
        IllegalArgumentException dynamicKey = assertThrows(
                IllegalArgumentException.class,
                () -> StructuredOutputPlan.OutputKey.runtimeValue("p_selected_key"));
        assertTrue(dynamicKey.getMessage().contains("TITAN-E001"));
        assertTrue(dynamicKey.getMessage().contains("compiler-known"));

        StructuredOutputPlan.ArrayValue nested = new StructuredOutputPlan.ArrayValue(
                new StructuredOutputPlan.ObjectValue(List.of()));
        IllegalArgumentException nestedArray = assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredOutputPlan.ArrayValue(nested));
        assertTrue(nestedArray.getMessage().contains("TITAN-E001"));
        assertTrue(nestedArray.getMessage().contains("nested arrays"));

        StructuredOutputPlan rootOnlyArray = new StructuredOutputPlan(new StructuredOutputPlan.ObjectValue(List.of(
                entry("errors", new StructuredOutputPlan.ArrayValue(new StructuredOutputPlan.ObjectValue(List.of(
                        entry("code", scalar("code", "e", "code", new TTextType())))))))));
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                rootOnlyArray,
                RowMaterializationRuntimePlan.from(
                        validationRows(),
                        new QueryTemplatePlan.RuntimeParameter("p_request_id", new TIntType())));

        IllegalArgumentException rootOnlyArrayRuntime = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeOutput.assemble(row("code", "VALIDATION_FAILED"), List.of()));
        assertTrue(rootOnlyArrayRuntime.getMessage().contains("TITAN-E001"));
        assertTrue(rootOnlyArrayRuntime.getMessage().contains("relation output requires materialized relation rows"));
    }

    @Test
    void semanticPayloadComparisonReportsNestedPathMismatches() {
        AssertionError mismatch = assertThrows(
                AssertionError.class,
                () -> assertJsonSemanticallyEqualsOrderSensitive(
                        row("payload", row("id", 10)),
                        row("payload", row("id", 20))));

        assertTrue(mismatch.getMessage().contains("$.payload.id"));
    }

    private static StructuredOutputPlan dashboardPayloadPlan() {
        return new StructuredOutputPlan(new StructuredOutputPlan.ObjectValue(List.of(
                entry("payload", new StructuredOutputPlan.ObjectValue(List.of(
                        entry("id", scalar("dashboardId", "d", "id", new TIntType())),
                        entry("title", scalar("displayTitle", "d", "title", new TTextType())),
                        entry("archivedAt", new StructuredOutputPlan.NullValue(new TTimestampTzType()))))),
                entry("window", new StructuredOutputPlan.ObjectValue(List.of(
                        entry("hasNext", scalar("hasNext", "d", "has_next", new TBooleanType())),
                        entry("endCursor", scalar("endCursor", "d", "end_cursor", new TTextType()))))))));
    }

    private static RowMaterializationPlan dashboardRows() {
        return new RowMaterializationPlan(
                table("dashboards", "d"),
                new RowMaterializationPlan.RowKey("d_identity", column("d", "id"), new TIntType()),
                List.of(
                        new RowMaterializationPlan.FieldBinding("dashboardId", column("d", "id"), new TIntType()),
                        new RowMaterializationPlan.FieldBinding("displayTitle", column("d", "title"), new TTextType()),
                        new RowMaterializationPlan.FieldBinding("hasNext", column("d", "has_next"), new TBooleanType()),
                        new RowMaterializationPlan.FieldBinding("endCursor", column("d", "end_cursor"), new TTextType())),
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

    private static RowMaterializationPlan validationRows() {
        return new RowMaterializationPlan(
                table("validation_events", "v"),
                new RowMaterializationPlan.RowKey("v_identity", column("v", "id"), new TIntType()),
                List.of(
                        new RowMaterializationPlan.FieldBinding("code", column("v", "code"), new TTextType()),
                        new RowMaterializationPlan.FieldBinding("message", column("v", "message"), new TTextType()),
                        new RowMaterializationPlan.FieldBinding("path", column("v", "path"), new TTextType())),
                List.of());
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
