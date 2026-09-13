package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.COLUMN;
import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.TABLE;
import static io.titan.transpiler.tir.QueryTemplatePlan.PredicateOperator.GT;
import static io.titan.transpiler.tir.QueryTemplatePlan.RootKind.LIST;
import static io.titan.transpiler.tir.SemanticJsonAssertions.assertJsonSemanticallyEqualsOrderSensitive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class StructuredPayloadPageMetadataPlanTest {

    @Test
    void assemblesFirstPageMetadataFromBoundedListRootRows() {
        RowMaterializationPlan rows = courseRows(null);
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rows, 2);
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                StructuredPayloadEnvelopePlan.success(StructuredOutputPlan.fromRowMaterialization(rows).root())
                        .toStructuredOutputPlan(),
                runtimeRows);
        List<Map<String, Object>> rootRows = List.of(
                row("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10),
                row("id", 20, "displayTitle", "Runtime SQL", "c_identity", 20));
        List<Map<String, Object>> envelopes = runtimeOutput.assembleBatch(rootRows, List.of());

        Map<String, Object> payload = row(
                "data", envelopes.stream().map(envelope -> envelope.get("data")).toList(),
                "errors", List.of(),
                "page", StructuredPayloadPageMetadataPlan.defaultWindow()
                        .assemble(runtimeRows, rootRows, Map.of(), OptionalInt.of(3)));

        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "data", List.of(
                                row("id", 10, "displayTitle", "Structured Runtime"),
                                row("id", 20, "displayTitle", "Runtime SQL")),
                        "errors", List.of(),
                        "page", row(
                                "hasNext", true,
                                "hasPrevious", false,
                                "startCursor", 10,
                                "endCursor", 20,
                                "totalCount", 3)),
                payload);
    }

    @Test
    void assemblesCursorPageMetadataWithPreviousPageFact() {
        QueryTemplatePlan.CursorWindow cursorWindow = new QueryTemplatePlan.CursorWindow(
                column("c", "id"),
                GT,
                new QueryTemplatePlan.RuntimeParameter("p_after_course_id", new TIntType()),
                2);
        RowMaterializationPlan rows = courseRows(cursorWindow);
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rows);
        List<Map<String, Object>> rootRows = List.of(
                row("id", 30, "displayTitle", "Cursor Runtime", "c_identity", 30));

        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "hasNext", false,
                        "hasPrevious", true,
                        "startCursor", 30,
                        "endCursor", 30,
                        "totalCount", 3),
                StructuredPayloadPageMetadataPlan.defaultWindow()
                        .assemble(runtimeRows, rootRows, Map.of("p_after_course_id", 20), OptionalInt.of(3)));
    }

    @Test
    void usesTotalCountToAvoidFalseNextPageOnFullFinalFirstPage() {
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(courseRows(null), 2);
        List<Map<String, Object>> rootRows = List.of(
                row("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10),
                row("id", 20, "displayTitle", "Runtime SQL", "c_identity", 20));

        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "hasNext", false,
                        "hasPrevious", false,
                        "startCursor", 10,
                        "endCursor", 20,
                        "totalCount", 2),
                StructuredPayloadPageMetadataPlan.defaultWindow()
                        .assemble(runtimeRows, rootRows, Map.of(), OptionalInt.of(2)));
    }

    @Test
    void rejectsHasNextForFullCursorPageWithoutRemainingRowFact() {
        QueryTemplatePlan.CursorWindow cursorWindow = new QueryTemplatePlan.CursorWindow(
                column("c", "id"),
                GT,
                new QueryTemplatePlan.RuntimeParameter("p_after_course_id", new TIntType()),
                2);
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(courseRows(cursorWindow));
        StructuredPayloadPageMetadataPlan page = new StructuredPayloadPageMetadataPlan(List.of(
                StructuredPayloadPageMetadataPlan.Field.HAS_NEXT));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> page.assemble(
                        runtimeRows,
                        List.of(
                                row("id", 30, "displayTitle", "Cursor Runtime", "c_identity", 30),
                                row("id", 40, "displayTitle", "Final Runtime", "c_identity", 40)),
                        Map.of("p_after_course_id", 20),
                        OptionalInt.of(4)));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("full cursor page"));
    }

    @Test
    void rejectsCursorFieldsForExpressionCursorWindowsWithoutMaterializedCursorAlias() {
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(
                expressionCursorCourseRows());
        StructuredPayloadPageMetadataPlan page = new StructuredPayloadPageMetadataPlan(List.of(
                StructuredPayloadPageMetadataPlan.Field.START_CURSOR));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> page.assemble(
                        runtimeRows,
                        List.of(row("id", 30, "displayTitle", "Cursor Runtime", "c_identity", 30)),
                        Map.of("p_after_title_length", 12),
                        OptionalInt.empty()));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("cursor alias"));
    }

    @Test
    void rejectsPageFieldsWhenBoundedWindowFactsAreUnavailable() {
        StructuredPayloadPageMetadataPlan page = new StructuredPayloadPageMetadataPlan(List.of(
                StructuredPayloadPageMetadataPlan.Field.HAS_NEXT));
        RowMaterializationRuntimePlan pointRows = RowMaterializationRuntimePlan.from(
                dashboardRows(),
                new QueryTemplatePlan.RuntimeParameter("p_dashboard_id", new TIntType()));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> page.assemble(pointRows, List.of(row("dashboardId", 10)), Map.of(), OptionalInt.empty()));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("bounded list-root window facts"));
    }

    @Test
    void rejectsTotalCountWhenRequestedButUnavailable() {
        StructuredPayloadPageMetadataPlan page = new StructuredPayloadPageMetadataPlan(List.of(
                StructuredPayloadPageMetadataPlan.Field.TOTAL_COUNT));
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(courseRows(null), 2);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> page.assemble(
                        runtimeRows,
                        List.of(row("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10)),
                        Map.of(),
                        OptionalInt.empty()));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("total count"));
    }

    @Test
    void preservesConfiguredPageFieldOrder() {
        StructuredPayloadPageMetadataPlan page = new StructuredPayloadPageMetadataPlan(List.of(
                StructuredPayloadPageMetadataPlan.Field.END_CURSOR,
                StructuredPayloadPageMetadataPlan.Field.START_CURSOR,
                StructuredPayloadPageMetadataPlan.Field.HAS_NEXT));
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(courseRows(null), 2);
        Map<String, Object> metadata = page.assemble(
                runtimeRows,
                List.of(row("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10)),
                Map.of(),
                OptionalInt.empty());

        assertEquals(List.of("endCursor", "startCursor", "hasNext"), List.copyOf(metadata.keySet()));
    }

    private static RowMaterializationPlan courseRows(QueryTemplatePlan.CursorWindow cursorWindow) {
        return new RowMaterializationPlan(
                table("courses", "c"),
                LIST,
                new RowMaterializationPlan.RowKey("c_identity", column("c", "id"), new TIntType()),
                List.of(
                        new RowMaterializationPlan.FieldBinding("id", column("c", "id"), new TIntType()),
                        new RowMaterializationPlan.FieldBinding(
                                "displayTitle",
                                column("c", "title"),
                                new TTextType())),
                List.of(),
                List.of(),
                List.of(new QueryTemplatePlan.OrderKey(column("c", "id"), SortDirection.ASC)),
                cursorWindow,
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    private static RowMaterializationPlan expressionCursorCourseRows() {
        QueryTemplatePlan.RowValue titleLength = new QueryTemplatePlan.FunctionValue(
                "CHAR_LENGTH",
                List.of(new QueryTemplatePlan.ColumnValue(column("c", "title"), new TTextType())),
                new TIntType());
        return new RowMaterializationPlan(
                table("courses", "c"),
                LIST,
                new RowMaterializationPlan.RowKey("c_identity", column("c", "id"), new TIntType()),
                List.of(
                        new RowMaterializationPlan.FieldBinding("id", column("c", "id"), new TIntType()),
                        new RowMaterializationPlan.FieldBinding(
                                "displayTitle",
                                column("c", "title"),
                                new TTextType())),
                List.of(),
                List.of(),
                List.of(new QueryTemplatePlan.OrderKey(column("c", "id"), SortDirection.ASC)),
                null,
                List.of(),
                List.of(),
                List.of(
                        new QueryTemplatePlan.ExpressionOrderKey(titleLength, SortDirection.ASC),
                        new QueryTemplatePlan.ExpressionOrderKey(
                                new QueryTemplatePlan.ColumnValue(column("c", "id"), new TIntType()),
                                SortDirection.ASC)),
                new QueryTemplatePlan.ExpressionCursorWindow(
                        titleLength,
                        GT,
                        new QueryTemplatePlan.RuntimeParameter("p_after_title_length", new TIntType()),
                        new QueryTemplatePlan.RuntimeParameter("p_after_course_id", new TIntType()),
                        2));
    }

    private static RowMaterializationPlan dashboardRows() {
        return new RowMaterializationPlan(
                table("dashboards", "d"),
                new RowMaterializationPlan.RowKey("d_identity", column("d", "id"), new TIntType()),
                List.of(new RowMaterializationPlan.FieldBinding(
                        "dashboardId",
                        column("d", "id"),
                        new TIntType())),
                List.of());
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
