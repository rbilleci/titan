package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.COLUMN;
import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.TABLE;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.MANY;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.ZERO_OR_ONE;
import static io.titan.transpiler.tir.QueryTemplatePlan.RootKind.LIST;
import static io.titan.transpiler.tir.SemanticJsonAssertions.assertJsonSemanticallyEqualsOrderSensitive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuredPayloadRuntimePlanTest {
    private final StructuredOutputRenderer renderer = new StructuredOutputRenderer();

    @Test
    void assemblesRootOnlyPayloadEnvelopeFromMaterializedRows() {
        RowMaterializationPlan rows = dashboardRows();
        StructuredPayloadRuntimePlan payload = StructuredPayloadRuntimePlan.success(
                StructuredOutputPlan.fromRowMaterialization(rows).root(),
                RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_dashboard_id", new TIntType())));

        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "data", row("dashboardId", 10, "title", "Operations"),
                        "errors", List.of()),
                payload.assemble(row("dashboardId", 10, "title", "Operations"), List.of()));
    }

    @Test
    void assemblesSecondModelNestedPayloadEnvelopeFromMaterializedRowGroups() {
        RowMaterializationPlan rows = courseLessonResourceRows();
        StructuredPayloadRuntimePlan payload = StructuredPayloadRuntimePlan.success(
                StructuredOutputPlan.fromRowMaterialization(rows).root(),
                RowMaterializationRuntimePlan.fromListRoot(rows));

        assertTrue(renderer.renderPostgreSql(payload.toStructuredOutputPlan())
                .contains("jsonb_build_object('data', jsonb_build_object("));
        assertTrue(renderer.renderMySql(payload.toStructuredOutputPlan())
                .contains("JSON_OBJECT('data', JSON_OBJECT("));
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
                payload.assembleNestedBatch(
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
    void assemblesZeroOrOneRelationPayloadsAndNullsFromGroupedRows() {
        RowMaterializationPlan rows = courseInstructorRows();
        StructuredPayloadRuntimePlan payload = StructuredPayloadRuntimePlan.success(
                StructuredOutputPlan.fromRowMaterialization(rows).root(),
                RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType())));

        assertJsonSemanticallyEqualsOrderSensitive(
                List.of(
                        row(
                                "data", row(
                                        "title", "Structured Runtime",
                                        "course_instructor", row("instructorName", "Ada")),
                                "errors", List.of()),
                        row(
                                "data", row(
                                        "title", "Runtime SQL",
                                        "course_instructor", null),
                                "errors", List.of())),
                payload.assembleBatch(
                        List.of(
                                row("title", "Structured Runtime", "c_identity", 10, "c_key", 7),
                                row("title", "Runtime SQL", "c_identity", 20, "c_key", null)),
                        List.of(row("instructorName", "Ada", "i_key", 7))));
    }

    @Test
    void convertsRelationCardinalityViolationsIntoStructuredErrorEnvelopesWhenRequested() {
        RowMaterializationPlan rows = courseInstructorRows();
        StructuredPayloadRuntimePlan payload = StructuredPayloadRuntimePlan.success(
                StructuredOutputPlan.fromRowMaterialization(rows).root(),
                RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType())));

        Map<String, Object> assembled = payload.assembleOrCardinalityError(
                row("title", "Structured Runtime", "c_identity", 10, "c_key", 7),
                List.of(
                        row("instructorName", "Ada", "i_key", 7),
                        row("instructorName", "Grace", "i_key", 7)),
                StructuredPayloadRuntimePlan.CardinalityErrorContract.defaultForPath("course_instructor"));

        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "data", null,
                        "errors", List.of(row(
                                "code", "CARDINALITY_VIOLATION",
                                "message",
                                "relation 'course_instructor' expected ZERO_OR_ONE cardinality but received 2 row(s)",
                        "path", "course_instructor"))),
                assembled);
    }

    @Test
    void convertsBatchRelationCardinalityViolationsIntoStructuredErrorEnvelopesWhenRequested() {
        RowMaterializationPlan rows = courseInstructorRows();
        StructuredPayloadRuntimePlan payload = StructuredPayloadRuntimePlan.success(
                StructuredOutputPlan.fromRowMaterialization(rows).root(),
                RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType())));

        List<Map<String, Object>> assembled = payload.assembleBatchOrCardinalityError(
                List.of(row("title", "Structured Runtime", "c_identity", 10, "c_key", 7)),
                List.of(
                        row("instructorName", "Ada", "i_key", 7),
                        row("instructorName", "Grace", "i_key", 7)),
                StructuredPayloadRuntimePlan.CardinalityErrorContract.defaultForPath("course_instructor"));

        assertJsonSemanticallyEqualsOrderSensitive(
                List.of(row(
                        "data", null,
                        "errors", List.of(row(
                                "code", "CARDINALITY_VIOLATION",
                                "message",
                                "relation 'course_instructor' expected ZERO_OR_ONE cardinality but received 2 row(s)",
                                "path", "course_instructor")))),
                assembled);
    }

    @Test
    void convertsNestedRelationCardinalityViolationsIntoStructuredErrorEnvelopesWhenRequested() {
        RowMaterializationPlan rows = courseLessonDetailRows();
        StructuredPayloadRuntimePlan payload = StructuredPayloadRuntimePlan.success(
                StructuredOutputPlan.fromRowMaterialization(rows).root(),
                RowMaterializationRuntimePlan.fromListRoot(rows));

        StructuredOutputRuntimePlan.RelationCardinalityViolationException exception = assertThrows(
                StructuredOutputRuntimePlan.RelationCardinalityViolationException.class,
                () -> payload.assembleNestedBatch(
                        List.of(row("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10)),
                        Map.of(
                                "course_lessons",
                                List.of(row("lessonSummary", "Intro", "l_course_id_key", 10, "l_key", 100)),
                                "lesson_detail",
                                List.of(
                                        row("detailText", "first", "d_key", 100),
                                        row("detailText", "duplicate", "d_key", 100)))));
        assertEquals("lesson_detail", exception.relationName());
        assertEquals("course_lessons.lesson_detail", exception.relationPath());
        assertTrue(exception.getMessage().contains("course_lessons.lesson_detail"));

        List<Map<String, Object>> assembled = payload.assembleNestedBatchOrCardinalityError(
                List.of(row("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10)),
                Map.of(
                        "course_lessons",
                        List.of(row("lessonSummary", "Intro", "l_course_id_key", 10, "l_key", 100)),
                        "lesson_detail",
                        List.of(
                                row("detailText", "first", "d_key", 100),
                                row("detailText", "duplicate", "d_key", 100))),
                StructuredPayloadRuntimePlan.CardinalityErrorContract.defaultForPath("course_lessons.lesson_detail"));

        assertJsonSemanticallyEqualsOrderSensitive(
                List.of(row(
                        "data", null,
                        "errors", List.of(row(
                                "code", "CARDINALITY_VIOLATION",
                                "message",
                                "relation 'lesson_detail' expected ZERO_OR_ONE cardinality but received 2 row(s)",
                                "path", "course_lessons.lesson_detail")))),
                assembled);
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

    private static RowMaterializationPlan courseInstructorRows() {
        return new RowMaterializationPlan(
                table("courses", "c"),
                new RowMaterializationPlan.RowKey("c_identity", column("c", "id"), new TIntType()),
                List.of(new RowMaterializationPlan.FieldBinding("title", column("c", "title"), new TTextType())),
                List.of(new RowMaterializationPlan.RelationRows(
                        "course_instructor",
                        table("instructors", "i"),
                        new RowMaterializationPlan.RowKey("c_key", column("c", "instructor_id"), new TIntType()),
                        new RowMaterializationPlan.RowKey("i_key", column("i", "id"), new TIntType()),
                        ZERO_OR_ONE,
                        List.of(new RowMaterializationPlan.FieldBinding(
                                "instructorName",
                                column("i", "name"),
                                new TTextType())))));
    }

    private static RowMaterializationPlan courseLessonResourceRows() {
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

    private static RowMaterializationPlan courseLessonDetailRows() {
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
                                "lesson_detail",
                                table("lesson_details", "d"),
                                new RowMaterializationPlan.RowKey("l_key", column("l", "id"), new TIntType()),
                                new RowMaterializationPlan.RowKey("d_key", column("d", "lesson_id"), new TIntType()),
                                ZERO_OR_ONE,
                                List.of(new RowMaterializationPlan.FieldBinding(
                                        "detailText",
                                        column("d", "detail_text"),
                                        new TTextType())))),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                null);
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
        assertEquals(0, pairs.length % 2, "row requires key/value pairs");
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            row.put((String) pairs[index], pairs[index + 1]);
        }
        return row;
    }
}
