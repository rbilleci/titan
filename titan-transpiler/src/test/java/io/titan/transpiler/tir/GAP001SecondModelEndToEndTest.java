package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.COLUMN;
import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.TABLE;
import static io.titan.transpiler.tir.QueryTemplatePlan.PredicateOperator.EQ;
import static io.titan.transpiler.tir.QueryTemplatePlan.PredicateOperator.GT;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.MANY;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.ZERO_OR_ONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GAP001SecondModelEndToEndTest {
    private final StructuredOutputRenderer outputRenderer = new StructuredOutputRenderer();

    @Test
    void validatesSecondModelThroughStructuredRuntimeLayers() {
        QueryTemplatePlan plan = coursePlan(
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("title", "courses", "c", "title", "displayTitle"),
                        field("summary", "lessons", "l", "summary", "lessonSummary")),
                List.of(
                        predicate(
                                "courses",
                                "c",
                                "id",
                                new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType())),
                        predicate(
                                "courses",
                                "c",
                                "visibility",
                                new QueryTemplatePlan.RuntimeParameter("p_viewer_role", new TTextType()))),
                List.of(relation(
                        "course_lessons",
                        "courses",
                        "c",
                        "id",
                        "lessons",
                        "course_id",
                        "l",
                        MANY)));
        RowMaterializationPlan rows = RowMaterializationPlan.fromQueryTemplate(
                plan,
                column("c", "id"),
                new TIntType(),
                Map.of(
                        column("c", "id"), new TIntType(),
                        column("c", "title"), new TTextType(),
                        column("c", "visibility"), new TTextType(),
                        column("l", "course_id"), new TIntType(),
                        column("l", "summary"), new TTextType()));
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.from(
                rows,
                new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType()));
        StructuredOutputPlan output = StructuredOutputPlan.fromRowMaterialization(rows);

        assertEquals("courses", plan.root().table().value());
        assertEquals(List.of("id", "displayTitle", "lessonSummary"), plan.projections().stream()
                .map(QueryTemplatePlan.Projection::outputKey)
                .toList());
        assertEquals(List.of("p_course_id", "p_viewer_role"), plan.predicates().stream()
                .map(predicate -> predicate.parameter().name())
                .toList());
        assertEquals(List.of("id", "displayTitle", "course_lessons"), output.root().entries().stream()
                .map(entry -> entry.key().value())
                .toList());
        assertTrue(output.root().entries().get(2).value() instanceof StructuredOutputPlan.ArrayValue);

        String postgresRoot = new PostgreSqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());
        String postgresLessons = new PostgreSqlEmitter().visitSelectSql(
                runtimeRows.relationQueries().getFirst().select());
        String mysqlRoot = new MySqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());
        String mysqlLessons = new MySqlEmitter().visitSelectSql(
                runtimeRows.relationQueries().getFirst().select());
        assertTrue(postgresRoot.contains(
                "SELECT \"courses\".\"id\" AS \"id\", \"courses\".\"title\" AS \"displayTitle\", \"courses\".\"id\" AS \"c_identity\" FROM \"courses\""));
        assertTrue(postgresRoot.contains("\"courses\".\"id\" = p_course_id"));
        assertTrue(postgresRoot.contains("\"courses\".\"visibility\" = p_viewer_role"));
        assertTrue(postgresLessons.contains(
                "SELECT \"lessons\".\"summary\" AS \"lessonSummary\", \"lessons\".\"course_id\" AS \"l_key\" FROM \"lessons\""));
        assertTrue(postgresLessons.contains("\"lessons\".\"course_id\" = p_course_id"));
        assertTrue(mysqlRoot.contains(
                "SELECT `courses`.`id` AS `id`, `courses`.`title` AS `displayTitle`, `courses`.`id` AS `c_identity` FROM `courses`"));
        assertTrue(mysqlRoot.contains("`courses`.`id` = p_course_id"));
        assertTrue(mysqlRoot.contains("`courses`.`visibility` = p_viewer_role"));
        assertTrue(mysqlLessons.contains(
                "SELECT `lessons`.`summary` AS `lessonSummary`, `lessons`.`course_id` AS `l_key` FROM `lessons`"));
        assertTrue(mysqlLessons.contains("`lessons`.`course_id` = p_course_id"));

        assertEquals(
                "jsonb_build_object('id', to_jsonb(\"c\".\"id\"), "
                        + "'displayTitle', to_jsonb(\"c\".\"title\"), "
                        + "'course_lessons', jsonb_build_array(jsonb_build_object("
                        + "'lessonSummary', to_jsonb(\"l\".\"summary\"))))",
                outputRenderer.renderPostgreSql(output));
        assertEquals(
                "JSON_OBJECT('id', `c`.`id`, 'displayTitle', `c`.`title`, "
                        + "'course_lessons', JSON_ARRAY(JSON_OBJECT('lessonSummary', `l`.`summary`)))",
                outputRenderer.renderMySql(output));
    }

    @Test
    void preservesContextPolicyPredicateIntoRuntimeSql() {
        QueryTemplatePlan plan = coursePlan(
                List.of(field("title", "courses", "c", "title", "displayTitle")),
                List.of(
                        predicate(
                                "courses",
                                "c",
                                "id",
                                new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType())),
                        predicate(
                                "courses",
                                "c",
                                "visibility",
                                new QueryTemplatePlan.RuntimeParameter("p_viewer_role", new TTextType()))),
                List.of());
        RowMaterializationPlan rows = RowMaterializationPlan.fromQueryTemplate(
                plan,
                column("c", "id"),
                new TIntType(),
                Map.of(
                        column("c", "id"), new TIntType(),
                        column("c", "title"), new TTextType(),
                        column("c", "visibility"), new TTextType()));
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.from(
                rows,
                new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType()));

        assertEquals(List.of("id", "visibility"), plan.predicates().stream()
                .map(predicate -> predicate.column().column().value())
                .toList());
        assertEquals(List.of("p_course_id", "p_viewer_role"), plan.predicates().stream()
                .map(predicate -> predicate.parameter().name())
                .toList());
        assertEquals(new TTextType(), plan.predicates().get(1).parameter().type());
        String postgresRoot = new PostgreSqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());
        String mysqlRoot = new MySqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());
        assertTrue(postgresRoot.contains("\"courses\".\"id\" = p_course_id"));
        assertTrue(postgresRoot.contains("\"courses\".\"visibility\" = p_viewer_role"));
        assertTrue(mysqlRoot.contains("`courses`.`id` = p_course_id"));
        assertTrue(mysqlRoot.contains("`courses`.`visibility` = p_viewer_role"));
    }

    @Test
    void recordsSecondModelListRootCursorWindowRuntimeBoundary() {
        QueryTemplatePlan plan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("title", "courses", "c", "title", "displayTitle")),
                List.of(predicate(
                        "courses",
                        "c",
                        "visibility",
                        new QueryTemplatePlan.RuntimeParameter("p_viewer_role", new TTextType()))),
                List.of(),
                List.of(
                        new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                table("courses"),
                                "c",
                                compilerKnown(COLUMN, "id"),
                                SortDirection.ASC),
                        new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                table("courses"),
                                "c",
                                compilerKnown(COLUMN, "title"),
                                SortDirection.ASC)),
                new QueryTemplateDescriptorPlanBuilder.CursorWindowDescriptor(
                        table("courses"),
                        "c",
                        compilerKnown(COLUMN, "id"),
                        GT,
                        new QueryTemplatePlan.RuntimeParameter("p_after_course_id", new TIntType()),
                        25));
        RowMaterializationPlan rows = RowMaterializationPlan.fromQueryTemplate(
                plan,
                column("c", "id"),
                new TIntType(),
                Map.of(
                        column("c", "id"), new TIntType(),
                        column("c", "title"), new TTextType(),
                        column("c", "visibility"), new TTextType()));
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rows);

        assertEquals(QueryTemplatePlan.RootKind.LIST, plan.rootKind());
        assertEquals(List.of("id", "title"), rows.rootOrderKeys().stream()
                .map(orderKey -> orderKey.column().column().value())
                .toList());
        assertEquals("p_after_course_id", rows.cursorWindow().cursorParameter().name());

        String postgresRoot = new PostgreSqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());
        String mysqlRoot = new MySqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());
        assertTrue(postgresRoot.contains(
                "SELECT \"courses\".\"id\" AS \"id\", \"courses\".\"title\" AS \"displayTitle\", \"courses\".\"id\" AS \"c_identity\" FROM \"courses\""));
        assertTrue(postgresRoot.contains("\"courses\".\"visibility\" = p_viewer_role"));
        assertTrue(postgresRoot.contains("\"courses\".\"id\" > p_after_course_id"));
        assertTrue(postgresRoot.contains("ORDER BY \"courses\".\"id\" ASC, \"courses\".\"title\" ASC"));
        assertTrue(postgresRoot.contains("LIMIT 25"));
        assertTrue(mysqlRoot.contains(
                "SELECT `courses`.`id` AS `id`, `courses`.`title` AS `displayTitle`, `courses`.`id` AS `c_identity` FROM `courses`"));
        assertTrue(mysqlRoot.contains("`courses`.`visibility` = p_viewer_role"));
        assertTrue(mysqlRoot.contains("`courses`.`id` > p_after_course_id"));
        assertTrue(mysqlRoot.contains("ORDER BY `courses`.`id` ASC, `courses`.`title` ASC"));
        assertTrue(mysqlRoot.contains("LIMIT 25"));
    }

    @Test
    void documentsSecondModelParentKeyCarrierRuntimeShape() {
        QueryTemplatePlan plan = coursePlan(
                List.of(
                        field("displayTitle", "courses", "c", "title", "title"),
                        field("instructorName", "instructors", "i", "name", "instructorName")),
                List.of(predicate(
                        "courses",
                        "c",
                        "id",
                        new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType()))),
                List.of(relation(
                        "course_instructor",
                        "courses",
                        "c",
                        "instructor_id",
                        "instructors",
                        "id",
                        "i",
                        ZERO_OR_ONE)));
        RowMaterializationPlan rows = RowMaterializationPlan.fromQueryTemplate(
                plan,
                column("c", "id"),
                new TIntType(),
                Map.of(
                        column("c", "id"), new TIntType(),
                        column("c", "title"), new TTextType(),
                        column("c", "instructor_id"), new TIntType(),
                        column("i", "id"), new TIntType(),
                        column("i", "name"), new TTextType()));

        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.from(
                rows,
                new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType()));
        RowMaterializationRuntimePlan.MaterializedRelationQuery instructor =
                runtimeRows.relationQueries().getFirst();
        String postgresRoot = new PostgreSqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());
        String postgresInstructor = new PostgreSqlEmitter().visitSelectSql(instructor.select());
        String mysqlRoot = new MySqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());
        String mysqlInstructor = new MySqlEmitter().visitSelectSql(instructor.select());

        assertEquals(new RowMaterializationRuntimePlan.ParentKeySource(
                        new RowMaterializationPlan.RowKey("c_key", column("c", "instructor_id"), new TIntType()),
                        RowMaterializationRuntimePlan.ParentKeySource.Kind.ROOT_HIDDEN_KEY),
                instructor.parentKeySource());
        assertEquals(new RowMaterializationRuntimePlan.ParentKeyCarrier(
                        "p_course_instructor_parent_key",
                        new TIntType()),
                instructor.parentKeyCarrier());
        assertTrue(postgresRoot.contains(
                "SELECT \"courses\".\"title\" AS \"title\", \"courses\".\"id\" AS \"c_identity\", \"courses\".\"instructor_id\" AS \"c_key\" FROM \"courses\""));
        assertTrue(postgresInstructor.contains("\"instructors\".\"id\" = p_course_instructor_parent_key"));
        assertTrue(mysqlRoot.contains(
                "SELECT `courses`.`title` AS `title`, `courses`.`id` AS `c_identity`, `courses`.`instructor_id` AS `c_key` FROM `courses`"));
        assertTrue(mysqlInstructor.contains("`instructors`.`id` = p_course_instructor_parent_key"));
    }

    @Test
    void assemblesSecondModelGroupedStructuredOutputRuntimeShape() {
        QueryTemplatePlan plan = coursePlan(
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("title", "courses", "c", "title", "displayTitle"),
                        field("summary", "lessons", "l", "summary", "lessonSummary")),
                List.of(
                        predicate(
                                "courses",
                                "c",
                                "id",
                                new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType())),
                        predicate(
                                "courses",
                                "c",
                                "visibility",
                                new QueryTemplatePlan.RuntimeParameter("p_viewer_role", new TTextType()))),
                List.of(relation(
                        "course_lessons",
                        "courses",
                        "c",
                        "id",
                        "lessons",
                        "course_id",
                        "l",
                        MANY)));
        RowMaterializationPlan rows = RowMaterializationPlan.fromQueryTemplate(
                plan,
                column("c", "id"),
                new TIntType(),
                Map.of(
                        column("c", "id"), new TIntType(),
                        column("c", "title"), new TTextType(),
                        column("c", "visibility"), new TTextType(),
                        column("l", "course_id"), new TIntType(),
                        column("l", "summary"), new TTextType()));
        StructuredOutputPlan output = StructuredOutputPlan.fromRowMaterialization(rows);
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.from(
                rows,
                new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType()));

        assertEquals(List.of("id", "displayTitle", "course_lessons"), output.root().entries().stream()
                .map(entry -> entry.key().value())
                .toList());
        assertTrue(output.root().entries().get(2).value() instanceof StructuredOutputPlan.ArrayValue);
        assertEquals(
                "jsonb_build_object('id', to_jsonb(\"c\".\"id\"), "
                        + "'displayTitle', to_jsonb(\"c\".\"title\"), "
                        + "'course_lessons', jsonb_build_array(jsonb_build_object("
                        + "'lessonSummary', to_jsonb(\"l\".\"summary\"))))",
                outputRenderer.renderPostgreSql(output));
        assertEquals(
                "JSON_OBJECT('id', `c`.`id`, 'displayTitle', `c`.`title`, "
                        + "'course_lessons', JSON_ARRAY(JSON_OBJECT('lessonSummary', `l`.`summary`)))",
                outputRenderer.renderMySql(output));

        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(output, runtimeRows);

        assertEquals(
                Map.of(
                        "id", 10,
                        "displayTitle", "Structured Runtime",
                        "course_lessons", List.of(
                                Map.of("lessonSummary", "Descriptor contracts"),
                                Map.of("lessonSummary", "Descriptor contracts"),
                                Map.of("lessonSummary", "Runtime SQL shapes"))),
                runtimeOutput.assemble(
                        Map.of("id", 10, "displayTitle", "Structured Runtime"),
                        List.of(
                                Map.of("lessonSummary", "Descriptor contracts", "l_key", 10),
                                Map.of("lessonSummary", "Descriptor contracts", "l_key", 10),
                                Map.of("lessonSummary", "Runtime SQL shapes", "l_key", 10))));
        assertEquals(
                Map.of(
                        "id", 10,
                        "displayTitle", "Structured Runtime",
                        "course_lessons", List.of()),
                runtimeOutput.assemble(
                        Map.of("id", 10, "displayTitle", "Structured Runtime"),
                        List.of()));
    }

    @Test
    void rejectsMismatchedSecondModelGroupedStructuredOutputRows() {
        QueryTemplatePlan plan = coursePlan(
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("title", "courses", "c", "title", "displayTitle"),
                        field("summary", "lessons", "l", "summary", "lessonSummary")),
                List.of(predicate(
                        "courses",
                        "c",
                        "id",
                        new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType()))),
                List.of(relation(
                        "course_lessons",
                        "courses",
                        "c",
                        "id",
                        "lessons",
                        "course_id",
                        "l",
                        MANY)));
        RowMaterializationPlan rows = RowMaterializationPlan.fromQueryTemplate(
                plan,
                column("c", "id"),
                new TIntType(),
                Map.of(
                        column("c", "id"), new TIntType(),
                        column("c", "title"), new TTextType(),
                        column("l", "course_id"), new TIntType(),
                        column("l", "summary"), new TTextType()));
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rows),
                RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType())));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeOutput.assemble(
                        Map.of("id", 10, "displayTitle", "Structured Runtime"),
                        List.of(Map.of("lessonSummary", "Other course", "l_key", 20))));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("course_lessons"));
        assertTrue(exception.getMessage().contains("parent key"));
    }

    @Test
    void preservesStructuralAndOutputGuardrailsForSecondModel() {
        IllegalArgumentException columnException = assertThrows(
                IllegalArgumentException.class,
                () -> field(
                        "selected",
                        "courses",
                        "c",
                        QueryTemplatePlan.StructuralIdentifier.runtimeValue(COLUMN, "p_requested_column"),
                        "selected"));
        assertTrue(columnException.getMessage().contains("TITAN-E001"));
        assertTrue(columnException.getMessage().contains("compiler-known"));

        IllegalArgumentException outputException = assertThrows(
                IllegalArgumentException.class,
                () -> StructuredOutputPlan.OutputKey.runtimeValue("p_selected_key"));
        assertTrue(outputException.getMessage().contains("TITAN-E001"));
        assertTrue(outputException.getMessage().contains("compiler-known"));

        IllegalArgumentException cursorException = assertThrows(
                IllegalArgumentException.class,
                () -> new QueryTemplateDescriptorPlanBuilder.CursorWindowDescriptor(
                        table("courses"),
                        "c",
                        QueryTemplatePlan.StructuralIdentifier.runtimeValue(COLUMN, "p_cursor_column"),
                        GT,
                        new QueryTemplatePlan.RuntimeParameter("p_after", new TIntType()),
                        10));
        assertTrue(cursorException.getMessage().contains("TITAN-E001"));
        assertTrue(cursorException.getMessage().contains("compiler-known"));
    }

    private static QueryTemplatePlan coursePlan(
            List<QueryTemplateDescriptorPlanBuilder.FieldDescriptor> fields,
            List<QueryTemplateDescriptorPlanBuilder.RuntimePredicate> predicates,
            List<QueryTemplateDescriptorPlanBuilder.RelationDescriptor> relations
    ) {
        return QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("course", table("courses"), "c", false),
                fields,
                predicates,
                relations);
    }

    private static QueryTemplateDescriptorPlanBuilder.FieldDescriptor field(
            String name,
            String table,
            String alias,
            String column,
            String outputKey
    ) {
        return field(name, table, alias, compilerKnown(COLUMN, column), outputKey);
    }

    private static QueryTemplateDescriptorPlanBuilder.FieldDescriptor field(
            String name,
            String table,
            String alias,
            QueryTemplatePlan.StructuralIdentifier column,
            String outputKey
    ) {
        return new QueryTemplateDescriptorPlanBuilder.FieldDescriptor(
                name,
                table(table),
                alias,
                column,
                outputKey);
    }

    private static QueryTemplateDescriptorPlanBuilder.RuntimePredicate predicate(
            String table,
            String alias,
            String column,
            QueryTemplatePlan.RuntimeParameter parameter
    ) {
        return new QueryTemplateDescriptorPlanBuilder.RuntimePredicate(
                table(table),
                alias,
                compilerKnown(COLUMN, column),
                EQ,
                parameter);
    }

    private static QueryTemplateDescriptorPlanBuilder.RelationDescriptor relation(
            String name,
            String fromTable,
            String fromAlias,
            String fromColumn,
            String toTable,
            String toColumn,
            String toAlias,
            QueryTemplatePlan.RelationCardinality cardinality
    ) {
        return new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                name,
                table(fromTable),
                fromAlias,
                compilerKnown(COLUMN, fromColumn),
                table(toTable),
                compilerKnown(COLUMN, toColumn),
                toAlias,
                cardinality);
    }

    private static QueryTemplatePlan.ColumnRef column(String alias, String column) {
        return new QueryTemplatePlan.ColumnRef(alias, compilerKnown(COLUMN, column));
    }

    private static QueryTemplatePlan.StructuralIdentifier table(String value) {
        return compilerKnown(TABLE, value);
    }

    private static QueryTemplatePlan.StructuralIdentifier compilerKnown(
            QueryTemplatePlan.IdentifierKind kind,
            String value
    ) {
        return QueryTemplatePlan.StructuralIdentifier.compilerKnown(kind, value);
    }
}
