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

class StructuredPayloadWrappedCollectionPlanTest {
    private final StructuredOutputRenderer renderer = new StructuredOutputRenderer();

    @Test
    void assemblesWrappedListItemsWithPageMetadataInRootOrder() {
        RowMaterializationPlan rows = courseRows(null);
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rows, 2);
        StructuredPayloadWrappedCollectionPlan collection = StructuredPayloadWrappedCollectionPlan.defaultItems();

        Map<String, Object> payload = collection.assemble(
                runtimeRows,
                List.of(
                        row("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10),
                        row("id", 20, "displayTitle", "Runtime SQL", "c_identity", 20)),
                courseItemPayload(),
                StructuredPayloadPageMetadataPlan.defaultWindow(),
                Map.of(),
                OptionalInt.of(3));

        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "items", List.of(
                                row(
                                        "cursor", 10,
                                        "item", row("id", 10, "displayTitle", "Structured Runtime")),
                                row(
                                        "cursor", 20,
                                        "item", row("id", 20, "displayTitle", "Runtime SQL"))),
                        "page", row(
                                "hasNext", true,
                                "hasPrevious", false,
                                "startCursor", 10,
                                "endCursor", 20,
                                "totalCount", 3)),
                payload);
    }

    @Test
    void supportsEmptyCollectionsWithNullPageCursors() {
        RowMaterializationPlan rows = courseRows(null);
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rows, 2);

        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "items", List.of(),
                        "page", row(
                                "hasNext", false,
                                "hasPrevious", false,
                                "startCursor", null,
                                "endCursor", null,
                                "totalCount", 0)),
                StructuredPayloadWrappedCollectionPlan.defaultItems().assemble(
                        runtimeRows,
                        List.of(),
                        courseItemPayload(),
                        StructuredPayloadPageMetadataPlan.defaultWindow(),
                        Map.of(),
                        OptionalInt.of(0)));
    }

    @Test
    void preservesConfiguredCollectionAndWrapperFieldOrder() {
        StructuredPayloadWrappedCollectionPlan collection = new StructuredPayloadWrappedCollectionPlan(
                key("entries"),
                key("position"),
                key("value"),
                key("window"),
                List.of(
                        StructuredPayloadWrappedCollectionPlan.Field.PAGE_METADATA,
                        StructuredPayloadWrappedCollectionPlan.Field.ITEMS),
                List.of(
                        StructuredPayloadWrappedCollectionPlan.ItemField.ITEM,
                        StructuredPayloadWrappedCollectionPlan.ItemField.CURSOR));
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(courseRows(null), 2);

        Map<String, Object> payload = collection.assemble(
                runtimeRows,
                List.of(row("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10)),
                courseItemPayload(),
                new StructuredPayloadPageMetadataPlan(List.of(StructuredPayloadPageMetadataPlan.Field.HAS_NEXT)),
                Map.of(),
                OptionalInt.empty());
        @SuppressWarnings("unchecked")
        Map<String, Object> wrapper = ((List<Map<String, Object>>) payload.get("entries")).getFirst();

        assertEquals(List.of("window", "entries"), List.copyOf(payload.keySet()));
        assertEquals(List.of("value", "position"), List.copyOf(wrapper.keySet()));
    }

    @Test
    void rendersWrappedCollectionShapeThroughPostgreSqlAndMySqlJsonProviders() {
        StructuredOutputPlan output = StructuredPayloadWrappedCollectionPlan.defaultItems()
                .toStructuredOutputPlan(
                        courseItemPayload(),
                        scalar("id", "c", "id", new TIntType()),
                        pageShape());

        assertEquals(
                "jsonb_build_object('items', jsonb_build_array(jsonb_build_object('cursor', to_jsonb(\"c\".\"id\"), "
                        + "'item', jsonb_build_object('id', to_jsonb(\"c\".\"id\"), 'displayTitle', "
                        + "to_jsonb(\"c\".\"title\")))), 'page', jsonb_build_object('hasNext', to_jsonb(false), "
                        + "'hasPrevious', to_jsonb(false), 'startCursor', to_jsonb(\"c\".\"id\"), "
                        + "'endCursor', to_jsonb(\"c\".\"id\"), 'totalCount', to_jsonb(2)))",
                renderer.renderPostgreSql(output));
        assertEquals(
                "JSON_OBJECT('items', JSON_ARRAY(JSON_OBJECT('cursor', `c`.`id`, 'item', "
                        + "JSON_OBJECT('id', `c`.`id`, 'displayTitle', `c`.`title`))), 'page', "
                        + "JSON_OBJECT('hasNext', FALSE, 'hasPrevious', FALSE, 'startCursor', `c`.`id`, "
                        + "'endCursor', `c`.`id`, 'totalCount', 2))",
                renderer.renderMySql(output));
    }

    @Test
    void rejectsWrappedItemCursorsWhenRowRuntimeHasNoMaterializedCursorAlias() {
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(
                expressionCursorCourseRows());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StructuredPayloadWrappedCollectionPlan.defaultItems().assemble(
                        runtimeRows,
                        List.of(row("id", 30, "displayTitle", "Cursor Runtime", "c_identity", 30)),
                        courseItemPayload(),
                        StructuredPayloadPageMetadataPlan.defaultWindow(),
                        Map.of("p_after_title_length", 12, "p_after_course_id", 20),
                        OptionalInt.empty()));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("cursor alias"));
    }

    @Test
    void rejectsWrappedItemPayloadFieldsMissingFromRootRows() {
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(courseRows(null), 2);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StructuredPayloadWrappedCollectionPlan.defaultItems().assemble(
                        runtimeRows,
                        List.of(row("id", 10, "c_identity", 10)),
                        courseItemPayload(),
                        StructuredPayloadPageMetadataPlan.defaultWindow(),
                        Map.of(),
                        OptionalInt.empty()));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("displayTitle"));
    }

    @Test
    void assemblesRelationBackedWrappedItemPayloads() {
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(courseLessonRows(), 2);
        StructuredOutputPlan.ObjectValue itemPayload =
                StructuredOutputPlan.fromRowMaterialization(courseLessonRows()).root();

        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "items", List.of(
                                row(
                                        "cursor", 10,
                                        "item", row(
                                                "id", 10,
                                                "displayTitle", "Structured Runtime",
                                                "lessons", List.of(
                                                        row("lessonTitle", "Intro"),
                                                        row("lessonTitle", "Windows")))),
                                row(
                                        "cursor", 20,
                                        "item", row(
                                                "id", 20,
                                                "displayTitle", "Runtime SQL",
                                                "lessons", List.of()))),
                        "page", row(
                                "hasNext", false,
                                "hasPrevious", false,
                                "startCursor", 10,
                                "endCursor", 20,
                                "totalCount", 2)),
                StructuredPayloadWrappedCollectionPlan.defaultItems().assemble(
                        runtimeRows,
                        List.of(
                                row("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10),
                                row("id", 20, "displayTitle", "Runtime SQL", "c_identity", 20)),
                        List.of(
                                row("lessonTitle", "Intro", "l_key", 10),
                                row("lessonTitle", "Windows", "l_key", 10)),
                        itemPayload,
                        StructuredPayloadPageMetadataPlan.defaultWindow(),
                        Map.of(),
                        OptionalInt.of(2)));
    }

    @Test
    void rejectsNestedCollectionItemPayloadsBeforeSqlEmission() {
        StructuredOutputPlan.ObjectValue itemWithRelationArray = new StructuredOutputPlan.ObjectValue(List.of(
                entry("id", scalar("id", "c", "id", new TIntType())),
                entry("lessons", new StructuredOutputPlan.ArrayValue(
                        new StructuredOutputPlan.ObjectValue(List.of(
                                entry("lessonTitle", scalar("lessonTitle", "l", "title", new TTextType()))))))));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StructuredPayloadWrappedCollectionPlan.defaultItems()
                        .toStructuredOutputPlan(itemWithRelationArray, scalar("id", "c", "id", new TIntType()),
                                pageShape()));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("nested collection arrays"));
    }

    @Test
    void rejectsObjectOrArrayItemCursorsBeforeSqlEmission() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StructuredPayloadWrappedCollectionPlan.defaultItems()
                        .toStructuredOutputPlan(
                                courseItemPayload(),
                                new StructuredOutputPlan.ObjectValue(List.of(
                                        entry("cursor", scalar("id", "c", "id", new TIntType())))),
                                pageShape()));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("item cursors"));
    }

    @Test
    void rejectsRuntimeSelectedWrapperKeysThroughOutputKeyContract() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StructuredOutputPlan.OutputKey.runtimeValue("items"));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("compiler-known"));
    }

    @Test
    void rejectsDuplicateOrIncompleteWrappedCollectionFieldOrders() {
        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredPayloadWrappedCollectionPlan(
                        key("items"),
                        key("cursor"),
                        key("item"),
                        key("page"),
                        List.of(
                                StructuredPayloadWrappedCollectionPlan.Field.ITEMS,
                                StructuredPayloadWrappedCollectionPlan.Field.ITEMS),
                        List.of(
                                StructuredPayloadWrappedCollectionPlan.ItemField.CURSOR,
                                StructuredPayloadWrappedCollectionPlan.ItemField.ITEM)));
        assertTrue(duplicate.getMessage().contains("TITAN-E001"));
        assertTrue(duplicate.getMessage().contains("unique"));

        IllegalArgumentException missingCursor = assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredPayloadWrappedCollectionPlan(
                        key("items"),
                        key("cursor"),
                        key("item"),
                        key("page"),
                        List.of(
                                StructuredPayloadWrappedCollectionPlan.Field.ITEMS,
                                StructuredPayloadWrappedCollectionPlan.Field.PAGE_METADATA),
                        List.of(StructuredPayloadWrappedCollectionPlan.ItemField.ITEM)));
        assertTrue(missingCursor.getMessage().contains("TITAN-E001"));
        assertTrue(missingCursor.getMessage().contains("cursor and item"));
    }

    private static StructuredOutputPlan.ObjectValue courseItemPayload() {
        return new StructuredOutputPlan.ObjectValue(List.of(
                entry("id", scalar("id", "c", "id", new TIntType())),
                entry("displayTitle", scalar("displayTitle", "c", "title", new TTextType()))));
    }

    private static StructuredOutputPlan.ObjectValue pageShape() {
        return new StructuredOutputPlan.ObjectValue(List.of(
                entry("hasNext", new StructuredOutputPlan.BooleanLiteralValue(false)),
                entry("hasPrevious", new StructuredOutputPlan.BooleanLiteralValue(false)),
                entry("startCursor", scalar("id", "c", "id", new TIntType())),
                entry("endCursor", scalar("id", "c", "id", new TIntType())),
                entry("totalCount", new StructuredOutputPlan.IntegerLiteralValue(2))));
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

    private static RowMaterializationPlan courseLessonRows() {
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
                List.of(new RowMaterializationPlan.RelationRows(
                        "lessons",
                        table("lessons", "l"),
                        new RowMaterializationPlan.RowKey("c_key", column("c", "id"), new TIntType()),
                        new RowMaterializationPlan.RowKey("l_key", column("l", "course_id"), new TIntType()),
                        QueryTemplatePlan.RelationCardinality.MANY,
                        List.of(new RowMaterializationPlan.FieldBinding(
                                "lessonTitle",
                                column("l", "title"),
                                new TTextType())))),
                List.of(),
                List.of(new QueryTemplatePlan.OrderKey(column("c", "id"), SortDirection.ASC)),
                null,
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    private static StructuredOutputPlan.Entry entry(String key, StructuredOutputPlan.Value value) {
        return new StructuredOutputPlan.Entry(StructuredOutputPlan.OutputKey.compilerKnown(key), value);
    }

    private static StructuredOutputPlan.OutputKey key(String key) {
        return StructuredOutputPlan.OutputKey.compilerKnown(key);
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
