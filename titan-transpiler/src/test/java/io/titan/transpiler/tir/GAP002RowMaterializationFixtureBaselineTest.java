package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.COLUMN;
import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.TABLE;
import static io.titan.transpiler.tir.QueryTemplatePlan.PredicateOperator.EQ;
import static io.titan.transpiler.tir.QueryTemplatePlan.PredicateOperator.GT;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.MANY;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.ONE;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.ZERO_OR_ONE;
import static io.titan.transpiler.tir.RowMaterializationRuntimePlan.ParentKeySource.Kind.RELATION_HIDDEN_KEY;
import static io.titan.transpiler.tir.RowMaterializationRuntimePlan.ParentKeySource.Kind.ROOT_IDENTITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GAP002RowMaterializationFixtureBaselineTest {
    @Test
    void coversPointRootAndOneToManyRowMaterializationFixture() {
        RowMaterializationRuntimePlan runtimeRows = runtimeRows(courseLessonsPlan());

        assertEquals("c_identity", runtimeRows.rootQuery().rootKey().name());
        assertEquals("course_lessons", runtimeRows.relationQueries().getFirst().name());
        assertEquals(MANY, runtimeRows.relationQueries().getFirst().cardinality());

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
    }

    @Test
    void coversListRootCursorWindowWithoutRelationAssembly() {
        QueryTemplatePlan firstPagePlan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
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
                List.of(new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                        table("courses"),
                        "c",
                        compilerKnown(COLUMN, "id"),
                        SortDirection.ASC)),
                null);
        RowMaterializationRuntimePlan firstPageRows =
                RowMaterializationRuntimePlan.fromListRoot(rows(firstPagePlan), 2);
        QueryTemplatePlan afterCursorPlan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
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
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rows(afterCursorPlan));

        String postgresRoot = new PostgreSqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());
        String mysqlRoot = new MySqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());

        assertTrue(new PostgreSqlEmitter().visitSelectSql(firstPageRows.rootQuery().select()).contains("LIMIT 2"));
        assertEquals(
                List.of(
                        Map.of("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10),
                        Map.of("id", 20, "displayTitle", "Runtime SQL", "c_identity", 20)),
                RowMaterializationRuntimePlan.windowRootRows(
                        firstPageRows.rootQuery(),
                        rootWindowRows(),
                        Map.of("p_viewer_role", "member")));
        assertEquals(
                List.of(Map.of("id", 30, "displayTitle", "Cursor Runtime", "c_identity", 30)),
                RowMaterializationRuntimePlan.windowRootRows(
                        runtimeRows.rootQuery(),
                        rootWindowRows(),
                        Map.of("p_viewer_role", "member", "p_after_course_id", 20)));
        assertEquals(List.of(new RowMaterializationRuntimePlan.RowFieldProjection(
                        "c_identity",
                        column("c", "id"),
                        new TIntType(),
                        true)),
                runtimeRows.rootQuery().hiddenKeyProjections());
        assertTrue(postgresRoot.contains(
                "SELECT \"courses\".\"id\" AS \"id\", \"courses\".\"title\" AS \"displayTitle\", \"courses\".\"id\" AS \"c_identity\" FROM \"courses\""));
        assertTrue(postgresRoot.contains("\"courses\".\"visibility\" = p_viewer_role"));
        assertTrue(postgresRoot.contains("\"courses\".\"id\" = \"courses\".\"id\""));
        assertTrue(postgresRoot.contains("\"courses\".\"title\" = \"courses\".\"title\""));
        assertTrue(postgresRoot.contains("\"courses\".\"id\" > p_after_course_id"));
        assertTrue(postgresRoot.contains("ORDER BY \"courses\".\"id\" ASC, \"courses\".\"title\" ASC"));
        assertTrue(postgresRoot.contains("LIMIT 25"));
        assertTrue(mysqlRoot.contains(
                "SELECT `courses`.`id` AS `id`, `courses`.`title` AS `displayTitle`, `courses`.`id` AS `c_identity` FROM `courses`"));
        assertTrue(mysqlRoot.contains("`courses`.`visibility` = p_viewer_role"));
        assertTrue(mysqlRoot.contains("`courses`.`id` = `courses`.`id`"));
        assertTrue(mysqlRoot.contains("`courses`.`title` = `courses`.`title`"));
        assertTrue(mysqlRoot.contains("`courses`.`id` > p_after_course_id"));
        assertTrue(mysqlRoot.contains("ORDER BY `courses`.`id` ASC, `courses`.`title` ASC"));
        assertTrue(mysqlRoot.contains("LIMIT 25"));

        IllegalArgumentException missingCursor = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.windowRootRows(
                        runtimeRows.rootQuery(),
                        rootWindowRows(),
                        Map.of("p_viewer_role", "member")));
        assertTrue(missingCursor.getMessage().contains("TITAN-E001"));
        assertTrue(missingCursor.getMessage().contains("p_after_course_id"));

        IllegalArgumentException missingSourceColumn = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.windowRootRows(
                        runtimeRows.rootQuery(),
                        List.of(Map.of("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10)),
                        Map.of("p_viewer_role", "member", "p_after_course_id", 0)));
        assertTrue(missingSourceColumn.getMessage().contains("TITAN-E001"));
        assertTrue(missingSourceColumn.getMessage().contains("visibility"));

        IllegalArgumentException unstableWindow = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.fromListRoot(rows(QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                        new QueryTemplateDescriptorPlanBuilder.RootDescriptor(
                                "courses",
                                table("courses"),
                                "c",
                                true),
                        List.of(field("id", "courses", "c", "id", "id")),
                        List.of(),
                        List.of(),
                        List.of(),
                        null)), 2));
        assertTrue(unstableWindow.getMessage().contains("TITAN-E001"));
        assertTrue(unstableWindow.getMessage().contains("stable root ordering"));

        IllegalArgumentException missingIdentityOrder = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.fromListRoot(rows(QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                        new QueryTemplateDescriptorPlanBuilder.RootDescriptor(
                                "courses",
                                table("courses"),
                                "c",
                                true),
                        List.of(field("id", "courses", "c", "id", "id")),
                        List.of(),
                        List.of(),
                        List.of(new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                table("courses"),
                                "c",
                                compilerKnown(COLUMN, "title"),
                                SortDirection.ASC)),
                        null)), 2));
        assertTrue(missingIdentityOrder.getMessage().contains("TITAN-E001"));
        assertTrue(missingIdentityOrder.getMessage().contains("stable root identity key"));

        IllegalArgumentException misalignedCursor = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.fromListRoot(rows(QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                        new QueryTemplateDescriptorPlanBuilder.RootDescriptor(
                                "courses",
                                table("courses"),
                                "c",
                                true),
                        List.of(field("id", "courses", "c", "id", "id")),
                        List.of(),
                        List.of(),
                        List.of(
                                new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                        table("courses"),
                                        "c",
                                        compilerKnown(COLUMN, "title"),
                                        SortDirection.ASC),
                                new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                        table("courses"),
                                        "c",
                                        compilerKnown(COLUMN, "id"),
                                        SortDirection.ASC)),
                        new QueryTemplateDescriptorPlanBuilder.CursorWindowDescriptor(
                                table("courses"),
                                "c",
                                compilerKnown(COLUMN, "id"),
                                GT,
                                new QueryTemplatePlan.RuntimeParameter("p_after_course_id", new TIntType()),
                                25)))));
        assertTrue(misalignedCursor.getMessage().contains("TITAN-E001"));
        assertTrue(misalignedCursor.getMessage().contains("leading root order key"));

        IllegalArgumentException nonIdentityCursor = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.fromListRoot(rows(QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                        new QueryTemplateDescriptorPlanBuilder.RootDescriptor(
                                "courses",
                                table("courses"),
                                "c",
                                true),
                        List.of(field("id", "courses", "c", "id", "id")),
                        List.of(),
                        List.of(),
                        List.of(new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                table("courses"),
                                "c",
                                compilerKnown(COLUMN, "title"),
                                SortDirection.ASC)),
                        new QueryTemplateDescriptorPlanBuilder.CursorWindowDescriptor(
                                table("courses"),
                                "c",
                                compilerKnown(COLUMN, "title"),
                                GT,
                                new QueryTemplatePlan.RuntimeParameter("p_after_title", new TTextType()),
                                25)))));
        assertTrue(nonIdentityCursor.getMessage().contains("TITAN-E001"));
        assertTrue(nonIdentityCursor.getMessage().contains("stable root identity key"));

        assertEquals(
                List.of(Map.of("id", 30, "displayTitle", "Cursor Runtime", "c_identity", 30)),
                RowMaterializationRuntimePlan.windowRootRows(
                        runtimeRows.rootQuery(),
                        rootWindowRowsWithNullVisibility(),
                        Map.of("p_viewer_role", "member", "p_after_course_id", 20)));
        assertEquals(
                List.of(Map.of("id", 30, "displayTitle", "Cursor Runtime", "c_identity", 30)),
                RowMaterializationRuntimePlan.windowRootRows(
                        runtimeRows.rootQuery(),
                        rootWindowRowsWithNullSecondaryOrder(),
                        Map.of("p_viewer_role", "member", "p_after_course_id", 20)));
        assertEquals(
                List.of(),
                RowMaterializationRuntimePlan.windowRootRows(
                        runtimeRows.rootQuery(),
                        rootWindowRows(),
                        nullableMap("p_viewer_role", "member", "p_after_course_id", null)));
        assertEquals(
                List.of(Map.of("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10)),
                RowMaterializationRuntimePlan.windowRootRows(
                        firstPageRows.rootQuery(),
                        rootWindowRowsWithNullOrder(),
                        Map.of("p_viewer_role", "member")));
    }

    @Test
    void materializesDerivedRootFilterOrderAndCursorPaths() {
        QueryTemplatePlan.RowValue titleLength = new QueryTemplatePlan.FunctionValue(
                "CHAR_LENGTH",
                List.of(new QueryTemplatePlan.ColumnValue(column("a", "title"), new TTextType())),
                new TIntType());
        QueryTemplatePlan plan = articleAuthorDerivedRootPlan(
                titleLength,
                new QueryTemplatePlan.ExpressionCursorWindow(
                        titleLength,
                        GT,
                        new QueryTemplatePlan.RuntimeParameter("p_after_title_length", new TIntType()),
                        new QueryTemplatePlan.RuntimeParameter("p_after_article_id", new TIntType()),
                        2));
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(articleRows(plan));

        String postgresRoot = new PostgreSqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());
        String mysqlRoot = new MySqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());

        assertTrue(postgresRoot.contains("FROM \"articles\" JOIN \"users\" ON (\"articles\".\"author_id\" = \"users\".\"id\")"));
        assertTrue(postgresRoot.contains("CHAR_LENGTH(\"articles\".\"title\") = CHAR_LENGTH(\"articles\".\"title\")"));
        assertTrue(postgresRoot.contains("\"users\".\"name\" = p_author_name"));
        assertTrue(postgresRoot.contains("CHAR_LENGTH(\"articles\".\"title\") > p_after_title_length"));
        assertTrue(postgresRoot.contains("\"articles\".\"id\" > p_after_article_id"));
        assertTrue(postgresRoot.contains("ORDER BY CHAR_LENGTH(\"articles\".\"title\") ASC, \"articles\".\"id\" ASC"));
        assertTrue(postgresRoot.contains("LIMIT 2"));
        assertTrue(mysqlRoot.contains("FROM `articles` JOIN `users` ON (`articles`.`author_id` = `users`.`id`)"));
        assertTrue(mysqlRoot.contains("CHAR_LENGTH(`articles`.`title`) = CHAR_LENGTH(`articles`.`title`)"));
        assertTrue(mysqlRoot.contains("`users`.`name` = p_author_name"));
        assertTrue(mysqlRoot.contains("CHAR_LENGTH(`articles`.`title`) > p_after_title_length"));
        assertTrue(mysqlRoot.contains("`articles`.`id` > p_after_article_id"));
        assertTrue(mysqlRoot.contains("ORDER BY CHAR_LENGTH(`articles`.`title`) ASC, `articles`.`id` ASC"));
        assertTrue(mysqlRoot.contains("LIMIT 2"));

        assertEquals(
                List.of(
                        Map.of("id", 30, "title", "Cursor Runtime", "a_identity", 30),
                        Map.of("id", 40, "title", "Generated Runtime", "a_identity", 40)),
                RowMaterializationRuntimePlan.windowRootRows(
                        runtimeRows.rootQuery(),
                        articleAuthorRows(),
                        Map.of(
                                "p_author_name", "Ada",
                                "p_after_title_length", "Runtime SQL".length(),
                                "p_after_article_id", 20)));

        QueryTemplatePlan.RowValue authorName =
                new QueryTemplatePlan.ColumnValue(column("u", "name"), new TTextType());
        QueryTemplatePlan authorOrderPlan = articleAuthorDerivedRootPlan(
                authorName,
                new QueryTemplatePlan.ExpressionCursorWindow(
                        authorName,
                        GT,
                        new QueryTemplatePlan.RuntimeParameter("p_after_author_name", new TTextType()),
                        new QueryTemplatePlan.RuntimeParameter("p_after_article_id", new TIntType()),
                        3));
        RowMaterializationRuntimePlan authorOrderRows =
                RowMaterializationRuntimePlan.fromListRoot(articleRows(authorOrderPlan));
        assertTrue(new PostgreSqlEmitter().visitSelectSql(authorOrderRows.rootQuery().select())
                .contains("ORDER BY \"users\".\"name\" ASC, \"articles\".\"id\" ASC"));

        QueryTemplatePlan mismatchedCursor = articleAuthorDerivedRootPlan(
                titleLength,
                new QueryTemplatePlan.ExpressionCursorWindow(
                        authorName,
                        GT,
                        new QueryTemplatePlan.RuntimeParameter("p_after_author_name", new TTextType()),
                        new QueryTemplatePlan.RuntimeParameter("p_after_article_id", new TIntType()),
                        2));
        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.fromListRoot(articleRows(mismatchedCursor)));
        assertTrue(mismatch.getMessage().contains("TITAN-E001"));
        assertTrue(mismatch.getMessage().contains("leading root order key"));
    }

    @Test
    void assemblesManyRelationWithDuplicateRowsAndEmptyCollections() {
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rows(courseLessonsPlan())),
                runtimeRows(courseLessonsPlan()));

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
    void coversOneAndZeroOrOneCardinalityRuntimeBehavior() {
        StructuredOutputRuntimePlan requiredOwner = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rows(courseOwnerPlan(ONE))),
                runtimeRows(courseOwnerPlan(ONE)));
        StructuredOutputRuntimePlan optionalOwner = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rows(courseOwnerPlan(ZERO_OR_ONE))),
                runtimeRows(courseOwnerPlan(ZERO_OR_ONE)));

        assertEquals(
                Map.of("id", 10, "course_owner", Map.of("ownerName", "Ada")),
                requiredOwner.assemble(
                        Map.of("id", 10),
                        List.of(Map.of("ownerName", "Ada", "o_key", 10))));
        assertEquals(
                nullableMap("id", 10, "course_owner", null),
                optionalOwner.assemble(Map.of("id", 10), List.of()));

        IllegalArgumentException missingRequired = assertThrows(
                IllegalArgumentException.class,
                () -> requiredOwner.assemble(Map.of("id", 10), List.of()));
        assertTrue(missingRequired.getMessage().contains("TITAN-E001"));
        assertTrue(missingRequired.getMessage().contains("course_owner"));
        assertTrue(missingRequired.getMessage().contains("received 0"));

        IllegalArgumentException duplicateOptional = assertThrows(
                IllegalArgumentException.class,
                () -> optionalOwner.assemble(
                        Map.of("id", 10),
                        List.of(
                                Map.of("ownerName", "Ada", "o_key", 10),
                                Map.of("ownerName", "Grace", "o_key", 10))));
        assertTrue(duplicateOptional.getMessage().contains("TITAN-E001"));
        assertTrue(duplicateOptional.getMessage().contains("course_owner"));
        assertTrue(duplicateOptional.getMessage().contains("received 2"));
    }

    @Test
    void collectsNullableParentKeysForCourseInstructorRelation() {
        QueryTemplatePlan plan = coursePlan(
                List.of(
                        field("title", "courses", "c", "title", "displayTitle"),
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

        RowMaterializationRuntimePlan runtimeRows = runtimeRows(plan);
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
        assertEquals(
                new RowMaterializationRuntimePlan.CollectedParentKeys(
                        "course_instructor",
                        instructor.parentKeySource(),
                        Arrays.asList(7, null, 7, 9),
                        List.of(7, 9)),
                RowMaterializationRuntimePlan.collectParentKeys(
                        instructor,
                        List.of(
                                Map.of("displayTitle", "Runtime", "c_identity", 10, "c_key", 7),
                                nullableMap("displayTitle", "No Instructor", "c_identity", 11, "c_key", null),
                                Map.of("displayTitle", "Runtime Again", "c_identity", 12, "c_key", 7),
                                Map.of("displayTitle", "Compiler", "c_identity", 13, "c_key", 9))));
        assertTrue(postgresRoot.contains(
                "SELECT \"courses\".\"title\" AS \"displayTitle\", \"courses\".\"id\" AS \"c_identity\", \"courses\".\"instructor_id\" AS \"c_key\" FROM \"courses\""));
        assertTrue(postgresRoot.contains("\"courses\".\"id\" = p_course_id"));
        assertTrue(postgresInstructor.contains(
                "SELECT \"instructors\".\"name\" AS \"instructorName\", \"instructors\".\"id\" AS \"i_key\" FROM \"instructors\""));
        assertTrue(postgresInstructor.contains("\"instructors\".\"id\" = p_course_instructor_parent_key"));
        assertTrue(mysqlRoot.contains(
                "SELECT `courses`.`title` AS `displayTitle`, `courses`.`id` AS `c_identity`, `courses`.`instructor_id` AS `c_key` FROM `courses`"));
        assertTrue(mysqlRoot.contains("`courses`.`id` = p_course_id"));
        assertTrue(mysqlInstructor.contains(
                "SELECT `instructors`.`name` AS `instructorName`, `instructors`.`id` AS `i_key` FROM `instructors`"));
        assertTrue(mysqlInstructor.contains("`instructors`.`id` = p_course_instructor_parent_key"));
    }

    @Test
    void assemblesRelationRowsAgainstMaterializedParentKeySource() {
        QueryTemplatePlan plan = coursePlan(
                List.of(
                        field("title", "courses", "c", "title", "displayTitle"),
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
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rows(plan)),
                runtimeRows(plan));

        assertEquals(
                Map.of(
                        "displayTitle", "Runtime",
                        "course_instructor", Map.of("instructorName", "Ada")),
                runtimeOutput.assemble(
                        Map.of("displayTitle", "Runtime", "c_identity", 10, "c_key", 7),
                        List.of(Map.of("instructorName", "Ada", "i_key", 7))));
        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeOutput.assemble(
                        Map.of("displayTitle", "Runtime", "c_identity", 10, "c_key", 7),
                        List.of(Map.of("instructorName", "Grace", "i_key", 8))));
        assertTrue(mismatch.getMessage().contains("TITAN-E001"));
        assertTrue(mismatch.getMessage().contains("course_instructor"));
        assertTrue(mismatch.getMessage().contains("parent key"));
    }

    @Test
    void collectsListRootParentKeysBeforeGroupedRelationAssembly() {
        QueryTemplatePlan plan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("summary", "lessons", "l", "summary", "lessonSummary")),
                List.of(),
                List.of(relation(
                        "course_lessons",
                        "courses",
                        "c",
                        "id",
                        "lessons",
                        "course_id",
                        "l",
                        MANY)));

        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rows(plan));
        RowMaterializationRuntimePlan.MaterializedRelationQuery lessons =
                runtimeRows.relationQueries().getFirst();
        String postgresLessons = new PostgreSqlEmitter().visitSelectSql(lessons.select());
        String mysqlLessons = new MySqlEmitter().visitSelectSql(lessons.select());

        assertEquals(new RowMaterializationRuntimePlan.ParentKeySource(
                        new RowMaterializationPlan.RowKey("c_identity", column("c", "id"), new TIntType()),
                        RowMaterializationRuntimePlan.ParentKeySource.Kind.ROOT_IDENTITY),
                lessons.parentKeySource());
        assertEquals(new RowMaterializationRuntimePlan.ParentKeyCarrier(
                        "p_course_lessons_parent_key",
                        new TIntType()),
                lessons.parentKeyCarrier());
        assertEquals(
                new RowMaterializationRuntimePlan.CollectedParentKeys(
                        "course_lessons",
                        lessons.parentKeySource(),
                        List.of(10, 10, 20),
                        List.of(10, 20)),
                RowMaterializationRuntimePlan.collectParentKeys(
                        lessons,
                        List.of(
                                Map.of("id", 10, "c_identity", 10),
                                Map.of("id", 10, "c_identity", 10),
                                Map.of("id", 20, "c_identity", 20))));
        assertTrue(postgresLessons.contains("\"lessons\".\"course_id\" = ANY(p_course_lessons_parent_key)"));
        assertTrue(mysqlLessons.contains("`lessons`.`course_id` MEMBER OF(p_course_lessons_parent_key)"));
    }

    @Test
    void assemblesBatchedOneToManyRelationRowsForListRoots() {
        QueryTemplatePlan plan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("title", "courses", "c", "title", "displayTitle"),
                        field("summary", "lessons", "l", "summary", "lessonSummary")),
                List.of(),
                List.of(relation(
                        "course_lessons",
                        "courses",
                        "c",
                        "id",
                        "lessons",
                        "course_id",
                        "l",
                        MANY)));
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rows(plan)),
                RowMaterializationRuntimePlan.fromListRoot(rows(plan)));

        assertEquals(
                List.of(
                        Map.of(
                                "id", 10,
                                "displayTitle", "Structured Runtime",
                                "course_lessons", List.of(
                                        Map.of("lessonSummary", "Descriptor contracts"),
                                        Map.of("lessonSummary", "Descriptor contracts"))),
                        Map.of(
                                "id", 20,
                                "displayTitle", "Runtime SQL",
                                "course_lessons", List.of(Map.of("lessonSummary", "Batched reads"))),
                        Map.of(
                                "id", 30,
                                "displayTitle", "Empty Course",
                                "course_lessons", List.of())),
                runtimeOutput.assembleBatch(
                        List.of(
                                Map.of("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10),
                                Map.of("id", 20, "displayTitle", "Runtime SQL", "c_identity", 20),
                                Map.of("id", 30, "displayTitle", "Empty Course", "c_identity", 30)),
                        List.of(
                                Map.of("lessonSummary", "Batched reads", "l_key", 20),
                                Map.of("lessonSummary", "Descriptor contracts", "l_key", 10),
                                Map.of("lessonSummary", "Descriptor contracts", "l_key", 10))));

        IllegalArgumentException orphan = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeOutput.assembleBatch(
                        List.of(Map.of("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10)),
                        List.of(Map.of("lessonSummary", "Wrong course", "l_key", 99))));
        assertTrue(orphan.getMessage().contains("TITAN-E001"));
        assertTrue(orphan.getMessage().contains("course_lessons"));
        assertTrue(orphan.getMessage().contains("parent-key carrier"));
    }

    @Test
    void preservesOrderedRelationRowsForGroupedListRoots() {
        QueryTemplatePlan plan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("position", "lessons", "l", "position", "lessonPosition"),
                        field("summary", "lessons", "l", "summary", "lessonSummary")),
                List.of(),
                List.of(relation(
                        "course_lessons",
                        "courses",
                        "c",
                        "id",
                        "lessons",
                        "course_id",
                        "l",
                        MANY,
                        List.of(new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                table("lessons"),
                                "l",
                                compilerKnown(COLUMN, "position"),
                                SortDirection.ASC)))));
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rows(plan));
        RowMaterializationRuntimePlan.MaterializedRelationQuery lessons =
                runtimeRows.relationQueries().getFirst();
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rows(plan)),
                runtimeRows);

        assertEquals(List.of(new QueryTemplatePlan.OrderKey(column("l", "position"), SortDirection.ASC)),
                lessons.orderKeys());
        String postgresLessons = new PostgreSqlEmitter().visitSelectSql(lessons.select());
        String mysqlLessons = new MySqlEmitter().visitSelectSql(lessons.select());
        assertTrue(postgresLessons.contains("ORDER BY (\"lessons\".\"position\" IS NULL) ASC, \"lessons\".\"position\" ASC"));
        assertTrue(mysqlLessons.contains("ORDER BY (`lessons`.`position` IS NULL) ASC, `lessons`.`position` ASC"));
        assertEquals(
                List.of(Map.of(
                        "id", 10,
                        "course_lessons", List.of(
                                Map.of("lessonPosition", 1, "lessonSummary", "Intro"),
                                Map.of("lessonPosition", 2, "lessonSummary", "Runtime SQL")))),
                runtimeOutput.assembleBatch(
                        List.of(Map.of("id", 10, "c_identity", 10)),
                        List.of(
                                Map.of("lessonPosition", 1, "lessonSummary", "Intro", "l_key", 10),
                                Map.of("lessonPosition", 2, "lessonSummary", "Runtime SQL", "l_key", 10))));
    }

    @Test
    void materializesRelationCursorWindows() {
        QueryTemplatePlan plan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("position", "lessons", "l", "position", "lessonPosition"),
                        field("summary", "lessons", "l", "summary", "lessonSummary")),
                List.of(),
                List.of(relation(
                        "course_lessons",
                        "courses",
                        "c",
                        "id",
                        "lessons",
                        "course_id",
                        "l",
                        MANY,
                        List.of(
                                new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                        table("lessons"),
                                        "l",
                                        compilerKnown(COLUMN, "position"),
                                        SortDirection.ASC),
                                new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                        table("lessons"),
                                        "l",
                                        compilerKnown(COLUMN, "id"),
                                        SortDirection.ASC)),
                        new QueryTemplateDescriptorPlanBuilder.RelationCursorWindowDescriptor(
                                table("lessons"),
                                "l",
                                compilerKnown(COLUMN, "position"),
                                GT,
                                new QueryTemplatePlan.RuntimeParameter("p_after_lesson_position", new TIntType()),
                                table("lessons"),
                                "l",
                                compilerKnown(COLUMN, "id"),
                                new QueryTemplatePlan.RuntimeParameter("p_after_lesson_id", new TIntType()),
                                2))));
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rows(plan));
        RowMaterializationRuntimePlan.MaterializedRelationQuery lessons = runtimeRows.relationQueries().getFirst();
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rows(plan)),
                runtimeRows);

        assertEquals(new QueryTemplatePlan.RelationCursorWindow(
                        column("l", "position"),
                        GT,
                        new QueryTemplatePlan.RuntimeParameter("p_after_lesson_position", new TIntType()),
                        column("l", "id"),
                        new QueryTemplatePlan.RuntimeParameter("p_after_lesson_id", new TIntType()),
                        2),
                lessons.cursorWindow());
        assertEquals(List.of(new RowMaterializationRuntimePlan.RowFieldProjection(
                        "l_id_identity",
                        column("l", "id"),
                        new TIntType(),
                        true)),
                lessons.hiddenKeyProjections().stream()
                        .filter(field -> field.alias().equals("l_id_identity"))
                        .toList());

        List<Map<String, Object>> rootRows = List.of(
                Map.of("id", 10, "c_identity", 10),
                Map.of("id", 20, "c_identity", 20));
        List<Map<String, Object>> sourceRows = List.of(
                Map.of("lessonPosition", 1, "lessonSummary", "Intro", "l_key", 10, "l_id_identity", 100),
                Map.of("lessonPosition", 2, "lessonSummary", "Runtime SQL", "l_key", 10, "l_id_identity", 110),
                Map.of("lessonPosition", 2, "lessonSummary", "Runtime SQL follow-up", "l_key", 10,
                        "l_id_identity", 111),
                Map.of("lessonPosition", 3, "lessonSummary", "Windows", "l_key", 10, "l_id_identity", 120),
                Map.of("lessonPosition", 1, "lessonSummary", "Arrays", "l_key", 20, "l_id_identity", 200),
                Map.of("lessonPosition", 2, "lessonSummary", "Carriers", "l_key", 20, "l_id_identity", 210));
        List<Map<String, Object>> windowedRows = RowMaterializationRuntimePlan.windowRelationRows(
                lessons,
                rootRows,
                sourceRows,
                Map.of(
                        10,
                        Map.of("p_after_lesson_position", 2, "p_after_lesson_id", 110),
                        20,
                        Map.of()));

        assertEquals(List.of(
                        Map.of("lessonPosition", 2, "lessonSummary", "Runtime SQL follow-up", "l_key", 10,
                                "l_id_identity", 111),
                        Map.of("lessonPosition", 3, "lessonSummary", "Windows", "l_key", 10, "l_id_identity", 120),
                        Map.of("lessonPosition", 1, "lessonSummary", "Arrays", "l_key", 20, "l_id_identity", 200),
                        Map.of("lessonPosition", 2, "lessonSummary", "Carriers", "l_key", 20, "l_id_identity", 210)),
                windowedRows);
        Map<String, Object> firstPageCursorValues = new LinkedHashMap<>();
        firstPageCursorValues.put("p_after_lesson_position", null);
        firstPageCursorValues.put("p_after_lesson_id", null);
        assertEquals(
                sourceRows.subList(0, 2),
                RowMaterializationRuntimePlan.windowRelationRows(
                        lessons,
                        List.of(Map.of("id", 10, "c_identity", 10)),
                        sourceRows.subList(0, 4),
                        Map.of(10, firstPageCursorValues)));
        assertEquals(
                List.of(
                        Map.of(
                                "id", 10,
                                "course_lessons", List.of(
                                        Map.of("lessonPosition", 2, "lessonSummary", "Runtime SQL follow-up"),
                                        Map.of("lessonPosition", 3, "lessonSummary", "Windows"))),
                        Map.of(
                                "id", 20,
                                "course_lessons", List.of(
                                        Map.of("lessonPosition", 1, "lessonSummary", "Arrays"),
                                        Map.of("lessonPosition", 2, "lessonSummary", "Carriers")))),
                runtimeOutput.assembleBatch(rootRows, windowedRows));

        IllegalArgumentException missingIdentity = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.windowRelationRows(
                        lessons,
                        rootRows,
                        sourceRows,
                        Map.of(10, Map.of("p_after_lesson_position", 2))));
        assertTrue(missingIdentity.getMessage().contains("TITAN-E001"));
        assertTrue(missingIdentity.getMessage().contains("p_after_lesson_id"));

        IllegalArgumentException orphan = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.windowRelationRows(
                        lessons,
                        rootRows,
                        List.of(Map.of(
                                "lessonPosition", 1,
                                "lessonSummary", "Orphan",
                                "l_key", 99,
                                "l_id_identity", 990)),
                        Map.of()));
        assertTrue(orphan.getMessage().contains("TITAN-E001"));
        assertTrue(orphan.getMessage().contains("course_lessons"));
        assertTrue(orphan.getMessage().contains("parent-key carrier"));

        IllegalArgumentException unstableOrder = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.fromListRoot(rows(QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                        new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                        List.of(field("id", "courses", "c", "id", "id")),
                        List.of(),
                        List.of(relation(
                                "course_lessons",
                                "courses",
                                "c",
                                "id",
                                "lessons",
                                "course_id",
                                "l",
                                MANY,
                                List.of(new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                        table("lessons"),
                                        "l",
                                        compilerKnown(COLUMN, "position"),
                                        SortDirection.ASC)),
                                new QueryTemplateDescriptorPlanBuilder.RelationCursorWindowDescriptor(
                                        table("lessons"),
                                        "l",
                                        compilerKnown(COLUMN, "position"),
                                        GT,
                                        new QueryTemplatePlan.RuntimeParameter(
                                                "p_after_lesson_position",
                                                new TIntType()),
                                        table("lessons"),
                                        "l",
                                        compilerKnown(COLUMN, "id"),
                                        new QueryTemplatePlan.RuntimeParameter("p_after_lesson_id", new TIntType()),
                                        2)))))));
        assertTrue(unstableOrder.getMessage().contains("TITAN-E001"));
        assertTrue(unstableOrder.getMessage().contains("stable relation identity key"));

        IllegalArgumentException unsupportedIntermediateOrder = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.fromListRoot(rows(QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                        new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                        List.of(field("id", "courses", "c", "id", "id")),
                        List.of(),
                        List.of(relation(
                                "course_lessons",
                                "courses",
                                "c",
                                "id",
                                "lessons",
                                "course_id",
                                "l",
                                MANY,
                                List.of(
                                        new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                                table("lessons"),
                                                "l",
                                                compilerKnown(COLUMN, "position"),
                                                SortDirection.ASC),
                                        new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                                table("lessons"),
                                                "l",
                                                compilerKnown(COLUMN, "summary"),
                                                SortDirection.ASC),
                                        new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                                table("lessons"),
                                                "l",
                                                compilerKnown(COLUMN, "id"),
                                                SortDirection.ASC)),
                                new QueryTemplateDescriptorPlanBuilder.RelationCursorWindowDescriptor(
                                        table("lessons"),
                                        "l",
                                        compilerKnown(COLUMN, "position"),
                                        GT,
                                        new QueryTemplatePlan.RuntimeParameter(
                                                "p_after_lesson_position",
                                                new TIntType()),
                                        table("lessons"),
                                        "l",
                                        compilerKnown(COLUMN, "id"),
                                        new QueryTemplatePlan.RuntimeParameter("p_after_lesson_id", new TIntType()),
                                        2)))))));
        assertTrue(unsupportedIntermediateOrder.getMessage().contains("TITAN-E001"));
        assertTrue(unsupportedIntermediateOrder.getMessage().contains("immediately after the cursor key"));
    }

    @Test
    void materializesRelationCursorWindowWhenCursorColumnIsHidden() {
        QueryTemplatePlan plan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("summary", "lessons", "l", "summary", "lessonSummary")),
                List.of(),
                List.of(relation(
                        "course_lessons",
                        "courses",
                        "c",
                        "id",
                        "lessons",
                        "course_id",
                        "l",
                        MANY,
                        List.of(
                                new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                        table("lessons"),
                                        "l",
                                        compilerKnown(COLUMN, "position"),
                                        SortDirection.ASC),
                                new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                        table("lessons"),
                                        "l",
                                        compilerKnown(COLUMN, "id"),
                                        SortDirection.ASC)),
                        new QueryTemplateDescriptorPlanBuilder.RelationCursorWindowDescriptor(
                                table("lessons"),
                                "l",
                                compilerKnown(COLUMN, "position"),
                                GT,
                                new QueryTemplatePlan.RuntimeParameter("p_after_lesson_position", new TIntType()),
                                table("lessons"),
                                "l",
                                compilerKnown(COLUMN, "id"),
                                new QueryTemplatePlan.RuntimeParameter("p_after_lesson_id", new TIntType()),
                                2))));
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rows(plan));
        RowMaterializationRuntimePlan.MaterializedRelationQuery lessons = runtimeRows.relationQueries().getFirst();

        assertEquals(List.of(
                        new RowMaterializationRuntimePlan.RowFieldProjection(
                                "l_position_cursor",
                                column("l", "position"),
                                new TIntType(),
                                true),
                        new RowMaterializationRuntimePlan.RowFieldProjection(
                                "l_id_identity",
                                column("l", "id"),
                                new TIntType(),
                                true)),
                lessons.hiddenKeyProjections().stream()
                        .filter(field -> field.alias().equals("l_position_cursor")
                                || field.alias().equals("l_id_identity"))
                        .toList());

        List<Map<String, Object>> windowedRows = RowMaterializationRuntimePlan.windowRelationRows(
                lessons,
                List.of(Map.of("id", 10, "c_identity", 10)),
                List.of(
                        Map.of("lessonSummary", "Intro", "l_key", 10, "l_position_cursor", 1,
                                "l_id_identity", 100),
                        Map.of("lessonSummary", "Runtime SQL", "l_key", 10, "l_position_cursor", 2,
                                "l_id_identity", 110),
                        Map.of("lessonSummary", "Runtime SQL follow-up", "l_key", 10,
                                "l_position_cursor", 2, "l_id_identity", 111),
                        Map.of("lessonSummary", "Windows", "l_key", 10, "l_position_cursor", 3,
                                "l_id_identity", 120)),
                Map.of(10, Map.of("p_after_lesson_position", 2, "p_after_lesson_id", 110)));

        assertEquals(List.of(
                        Map.of("lessonSummary", "Runtime SQL follow-up", "l_key", 10,
                                "l_position_cursor", 2, "l_id_identity", 111),
                        Map.of("lessonSummary", "Windows", "l_key", 10, "l_position_cursor", 3,
                                "l_id_identity", 120)),
                windowedRows);
    }

    @Test
    void materializesBoundedNestedRelationGraphBaseline() {
        RowMaterializationPlan rows = rows(courseLessonResourcesPlan());
        StructuredOutputPlan output = StructuredOutputPlan.fromRowMaterialization(rows);
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rows);
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(output, runtimeRows);

        assertEquals(List.of("id", "displayTitle", "course_lessons"), outputKeys(output.root()));
        StructuredOutputPlan.ArrayValue lessonsOutput =
                (StructuredOutputPlan.ArrayValue) output.root().entries().get(2).value();
        StructuredOutputPlan.ObjectValue lessonOutput = (StructuredOutputPlan.ObjectValue) lessonsOutput.element();
        assertEquals(List.of("lessonSummary", "lesson_resources"), outputKeys(lessonOutput));
        assertEquals(List.of("course_lessons", "lesson_resources"),
                runtimeRows.relationQueries().stream()
                        .map(RowMaterializationRuntimePlan.MaterializedRelationQuery::name)
                        .toList());
        assertEquals(RowMaterializationRuntimePlan.ParentKeySource.Kind.RELATION_HIDDEN_KEY,
                runtimeRows.relationQueries().get(1).parentKeySource().kind());
        assertEquals(List.of(
                        new RowMaterializationRuntimePlan.RowFieldProjection(
                                "l_course_id_key",
                                column("l", "course_id"),
                                new TIntType(),
                                true),
                        new RowMaterializationRuntimePlan.RowFieldProjection(
                                "l_key",
                                column("l", "id"),
                                new TIntType(),
                                true)),
                runtimeRows.relationQueries().getFirst().hiddenKeyProjections());

        assertEquals(
                List.of(
                        Map.of(
                                "id", 10,
                                "displayTitle", "Structured Runtime",
                                "course_lessons", List.of(
                                        Map.of(
                                                "lessonSummary", "Intro",
                                                "lesson_resources", List.of(
                                                        Map.of("resourceUrl", "/intro.sql"),
                                                        Map.of("resourceUrl", "/intro.java"))),
                                        Map.of(
                                                "lessonSummary", "Windows",
                                                "lesson_resources", List.of()))),
                        Map.of(
                                "id", 20,
                                "displayTitle", "Runtime SQL",
                                "course_lessons", List.of(
                                        Map.of(
                                                "lessonSummary", "Arrays",
                                                "lesson_resources", List.of(Map.of("resourceUrl", "/arrays.sql")))))),
                runtimeOutput.assembleNestedBatch(
                        List.of(
                                Map.of("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10),
                                Map.of("id", 20, "displayTitle", "Runtime SQL", "c_identity", 20)),
                        Map.of(
                                "course_lessons",
                                List.of(
                                        Map.of("lessonSummary", "Intro", "l_course_id_key", 10, "l_key", 100),
                                        Map.of("lessonSummary", "Windows", "l_course_id_key", 10, "l_key", 110),
                                        Map.of("lessonSummary", "Arrays", "l_course_id_key", 20, "l_key", 200)),
                                "lesson_resources",
                                List.of(
                                        Map.of("resourceUrl", "/intro.sql", "r_key", 100),
                                        Map.of("resourceUrl", "/intro.java", "r_key", 100),
                                        Map.of("resourceUrl", "/arrays.sql", "r_key", 200)))));

        IllegalArgumentException orphanGrandchild = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeOutput.assembleNestedBatch(
                        List.of(Map.of("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10)),
                        Map.of(
                                "course_lessons",
                                List.of(Map.of("lessonSummary", "Intro", "l_course_id_key", 10, "l_key", 100)),
                                "lesson_resources",
                                List.of(Map.of("resourceUrl", "/orphan.sql", "r_key", 999)))));
        assertTrue(orphanGrandchild.getMessage().contains("TITAN-E001"));
        assertTrue(orphanGrandchild.getMessage().contains("lesson_resources"));
        assertTrue(orphanGrandchild.getMessage().contains("course_lessons.lesson_resources"));
        assertTrue(orphanGrandchild.getMessage().contains("parent-key carrier"));

        IllegalArgumentException pointApi = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeOutput.assemble(
                        Map.of("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10),
                        List.of()));
        assertTrue(pointApi.getMessage().contains("TITAN-E001"));
        assertTrue(pointApi.getMessage().contains("assembleNestedBatch"));

        IllegalArgumentException batchApi = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeOutput.assembleBatch(
                        List.of(Map.of("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10)),
                        List.of()));
        assertTrue(batchApi.getMessage().contains("TITAN-E001"));
        assertTrue(batchApi.getMessage().contains("assembleNestedBatch"));
    }

    @Test
    void keepsNestedRelationCursorWindowsBatchedAcrossCollectedParentKeys() {
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.from(
                rows(courseLessonResourcesPointWindowPlan()),
                new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType()));
        RowMaterializationRuntimePlan.MaterializedRelationQuery resources =
                runtimeRows.relationQueries().get(1);

        assertEquals("lesson_resources", resources.name());
        assertEquals(RowMaterializationRuntimePlan.ParentKeySource.Kind.RELATION_HIDDEN_KEY,
                resources.parentKeySource().kind());
        assertEquals("p_lesson_resources_parent_key", resources.parentKeyCarrier().parameterName());

        String postgresResources = new PostgreSqlEmitter().visitSelectSql(resources.select());
        String mysqlResources = new MySqlEmitter().visitSelectSql(resources.select());
        assertTrue(postgresResources.contains("\"resources\".\"lesson_id\" = ANY(p_lesson_resources_parent_key)"),
                postgresResources);
        assertTrue(mysqlResources.contains("`resources`.`lesson_id` MEMBER OF(p_lesson_resources_parent_key)"),
                mysqlResources);
        assertTrue(postgresResources.contains(
                "ORDER BY (\"resources\".\"url\" IS NULL) ASC, \"resources\".\"url\" ASC,"
                        + " (\"resources\".\"id\" IS NULL) ASC, \"resources\".\"id\" ASC"));
        assertTrue(mysqlResources.contains(
                "ORDER BY (`resources`.`url` IS NULL) ASC, `resources`.`url` ASC,"
                        + " (`resources`.`id` IS NULL) ASC, `resources`.`id` ASC"));
        assertTrue(!postgresResources.contains("LIMIT"), postgresResources);
        assertTrue(!mysqlResources.contains("LIMIT"), mysqlResources);
        assertTrue(!postgresResources.contains("p_after_resource_url"), postgresResources);
        assertTrue(!mysqlResources.contains("p_after_resource_url"), mysqlResources);

        IllegalArgumentException orphanResource = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.windowRelationRows(
                        resources,
                        "course_lessons.lesson_resources",
                        List.of(Map.of("lessonSummary", "Intro", "l_key", 100)),
                        List.of(Map.of("resourceUrl", "/orphan.sql", "r_key", 999)),
                        Map.of()));
        assertTrue(orphanResource.getMessage().contains("TITAN-E001"));
        assertTrue(orphanResource.getMessage().contains("lesson_resources"));
        assertTrue(orphanResource.getMessage().contains("course_lessons.lesson_resources"));
        assertTrue(orphanResource.getMessage().contains("parent-key carrier"));
    }

    @Test
    void enforcesBatchedRelationCardinalityForListRoots() {
        StructuredOutputRuntimePlan requiredOwner = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rows(listRootOwnerPlan(ONE))),
                RowMaterializationRuntimePlan.fromListRoot(rows(listRootOwnerPlan(ONE))));
        StructuredOutputRuntimePlan optionalOwner = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rows(listRootOwnerPlan(ZERO_OR_ONE))),
                RowMaterializationRuntimePlan.fromListRoot(rows(listRootOwnerPlan(ZERO_OR_ONE))));

        assertEquals(
                List.of(
                        Map.of("id", 10, "course_owner", Map.of("ownerName", "Ada")),
                        Map.of("id", 20, "course_owner", Map.of("ownerName", "Grace"))),
                requiredOwner.assembleBatch(
                        List.of(
                                Map.of("id", 10, "c_identity", 10, "c_key", 7),
                                Map.of("id", 20, "c_identity", 20, "c_key", 9)),
                        List.of(
                                Map.of("ownerName", "Grace", "o_key", 9),
                                Map.of("ownerName", "Ada", "o_key", 7))));
        assertEquals(
                List.of(
                        Map.of("id", 10, "course_owner", Map.of("ownerName", "Ada")),
                        nullableMap("id", 30, "course_owner", null)),
                optionalOwner.assembleBatch(
                        List.of(
                                Map.of("id", 10, "c_identity", 10, "c_key", 7),
                                nullableMap("id", 30, "c_identity", 30, "c_key", null)),
                        List.of(Map.of("ownerName", "Ada", "o_key", 7))));

        IllegalArgumentException missingRequired = assertThrows(
                IllegalArgumentException.class,
                () -> requiredOwner.assembleBatch(
                        List.of(Map.of("id", 30, "c_identity", 30, "c_key", 11)),
                        List.of()));
        assertTrue(missingRequired.getMessage().contains("TITAN-E001"));
        assertTrue(missingRequired.getMessage().contains("course_owner"));
        assertTrue(missingRequired.getMessage().contains("received 0"));

        IllegalArgumentException duplicateOptional = assertThrows(
                IllegalArgumentException.class,
                () -> optionalOwner.assembleBatch(
                        List.of(Map.of("id", 10, "c_identity", 10, "c_key", 7)),
                        List.of(
                                Map.of("ownerName", "Ada", "o_key", 7),
                                Map.of("ownerName", "Grace", "o_key", 7))));
        assertTrue(duplicateOptional.getMessage().contains("TITAN-E001"));
        assertTrue(duplicateOptional.getMessage().contains("course_owner"));
        assertTrue(duplicateOptional.getMessage().contains("received 2"));
    }

    @Test
    void documentsUnsupportedNestedGraphAndRelationCursorBoundaries() {
        RowMaterializationRuntimePlan.MaterializedRootQuery root = new RowMaterializationRuntimePlan
                .MaterializedRootQuery(
                new RowMaterializationPlan.RowKey("c_identity", column("c", "id"), new TIntType()),
                new SelectSql(List.of(), "courses", List.of(), null, List.of(), null, List.of(), null, null, null,
                        List.of()));
        RowMaterializationRuntimePlan.MaterializedRelationQuery lessons =
                relationQuery("course_lessons", "c", "id", ROOT_IDENTITY, "l", "course_id");
        RowMaterializationRuntimePlan.MaterializedRelationQuery quizzes =
                relationQuery("lesson_quizzes", "l", "id", RELATION_HIDDEN_KEY, "q", "lesson_id");
        RowMaterializationRuntimePlan.MaterializedRelationQuery resources =
                relationQuery("quiz_resources", "q", "id", RELATION_HIDDEN_KEY, "qr", "quiz_id");

        IllegalArgumentException nested = assertThrows(
                IllegalArgumentException.class,
                () -> StructuredOutputRuntimePlan.from(
                        StructuredOutputPlan.fromRowMaterialization(rows(courseLessonsPlan())),
                        new RowMaterializationRuntimePlan(root, List.of(lessons, quizzes, resources))));
        assertTrue(nested.getMessage().contains("TITAN-E001"));
        assertTrue(nested.getMessage().contains("quiz_resources"));
        assertTrue(nested.getMessage().contains("course_lessons.lesson_quizzes.quiz_resources"));
        assertTrue(nested.getMessage().contains("recursive relation graph assembly"));

        IllegalArgumentException relationOrderOnRootChannel = assertThrows(
                IllegalArgumentException.class,
                () -> QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                        new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                        List.of(
                                field("id", "courses", "c", "id", "id"),
                                field("summary", "lessons", "l", "summary", "lessonSummary")),
                        List.of(),
                        List.of(relation(
                                "course_lessons",
                                "courses",
                                "c",
                                "id",
                                "lessons",
                                "course_id",
                                "l",
                                MANY)),
                        List.of(new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                table("lessons"),
                                "l",
                                compilerKnown(COLUMN, "position"),
                                SortDirection.ASC)),
                        null));
        assertTrue(relationOrderOnRootChannel.getMessage().contains("TITAN-E001"));
        assertTrue(relationOrderOnRootChannel.getMessage().contains("order key alias"));
        assertTrue(relationOrderOnRootChannel.getMessage().contains("'c'"));
        assertTrue(relationOrderOnRootChannel.getMessage().contains("'l'"));

        IllegalArgumentException relationCursor = assertThrows(
                IllegalArgumentException.class,
                () -> QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                        new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                        List.of(
                                field("id", "courses", "c", "id", "id"),
                                field("summary", "lessons", "l", "summary", "lessonSummary")),
                        List.of(),
                        List.of(relation(
                                "course_lessons",
                                "courses",
                                "c",
                                "id",
                                "lessons",
                                "course_id",
                                "l",
                                MANY)),
                        List.of(),
                        new QueryTemplateDescriptorPlanBuilder.CursorWindowDescriptor(
                                table("lessons"),
                                "l",
                                compilerKnown(COLUMN, "position"),
                                GT,
                                new QueryTemplatePlan.RuntimeParameter("p_after_lesson_position", new TIntType()),
                                25)));
        assertTrue(relationCursor.getMessage().contains("TITAN-E001"));
        assertTrue(relationCursor.getMessage().contains("cursor window alias"));
        assertTrue(relationCursor.getMessage().contains("'c'"));
        assertTrue(relationCursor.getMessage().contains("'l'"));
    }

    private static QueryTemplatePlan courseLessonsPlan() {
        return coursePlan(
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
    }

    private static QueryTemplatePlan courseLessonResourcesPlan() {
        return QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("title", "courses", "c", "title", "displayTitle"),
                        field("summary", "lessons", "l", "summary", "lessonSummary"),
                        field("url", "resources", "r", "url", "resourceUrl")),
                List.of(),
                List.of(
                        relation(
                                "course_lessons",
                                "courses",
                                "c",
                                "id",
                                "lessons",
                                "course_id",
                                "l",
                                MANY),
                        relation(
                                "lesson_resources",
                                "lessons",
                                "l",
                                "id",
                                "resources",
                                "lesson_id",
                                "r",
                                MANY)));
    }

    private static QueryTemplatePlan courseLessonResourcesPointWindowPlan() {
        return QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", false),
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("title", "courses", "c", "title", "displayTitle"),
                        field("summary", "lessons", "l", "summary", "lessonSummary"),
                        field("url", "resources", "r", "url", "resourceUrl")),
                List.of(predicate(
                        "courses",
                        "c",
                        "id",
                        new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType()))),
                List.of(
                        relation(
                                "course_lessons",
                                "courses",
                                "c",
                                "id",
                                "lessons",
                                "course_id",
                                "l",
                                MANY),
                        relation(
                                "lesson_resources",
                                "lessons",
                                "l",
                                "id",
                                "resources",
                                "lesson_id",
                                "r",
                                MANY,
                                List.of(
                                        new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                                table("resources"),
                                                "r",
                                                compilerKnown(COLUMN, "url"),
                                                SortDirection.ASC),
                                        new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                                table("resources"),
                                                "r",
                                                compilerKnown(COLUMN, "id"),
                                                SortDirection.ASC)),
                                new QueryTemplateDescriptorPlanBuilder.RelationCursorWindowDescriptor(
                                        table("resources"),
                                        "r",
                                        compilerKnown(COLUMN, "url"),
                                        GT,
                                        new QueryTemplatePlan.RuntimeParameter(
                                                "p_after_resource_url",
                                                new TTextType()),
                                        table("resources"),
                                        "r",
                                        compilerKnown(COLUMN, "id"),
                                        new QueryTemplatePlan.RuntimeParameter("p_after_resource_id", new TIntType()),
                                        2))));
    }

    private static QueryTemplatePlan courseOwnerPlan(QueryTemplatePlan.RelationCardinality cardinality) {
        return coursePlan(
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("ownerName", "owners", "o", "name", "ownerName")),
                List.of(predicate(
                        "courses",
                        "c",
                        "id",
                        new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType()))),
                List.of(relation(
                        "course_owner",
                        "courses",
                        "c",
                        "id",
                        "owners",
                        "course_id",
                        "o",
                        cardinality)));
    }

    private static QueryTemplatePlan listRootOwnerPlan(QueryTemplatePlan.RelationCardinality cardinality) {
        return QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("ownerName", "owners", "o", "name", "ownerName")),
                List.of(),
                List.of(relation("course_owner", "courses", "c", "instructor_id", "owners", "id", "o", cardinality)));
    }

    private static RowMaterializationRuntimePlan runtimeRows(QueryTemplatePlan plan) {
        return RowMaterializationRuntimePlan.from(
                rows(plan),
                new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType()));
    }

    private static RowMaterializationPlan rows(QueryTemplatePlan plan) {
        return RowMaterializationPlan.fromQueryTemplate(
                plan,
                column("c", "id"),
                new TIntType(),
                Map.ofEntries(
                        Map.entry(column("c", "id"), new TIntType()),
                        Map.entry(column("c", "title"), new TTextType()),
                        Map.entry(column("c", "visibility"), new TTextType()),
                        Map.entry(column("c", "instructor_id"), new TIntType()),
                        Map.entry(column("l", "id"), new TIntType()),
                        Map.entry(column("l", "course_id"), new TIntType()),
                        Map.entry(column("l", "summary"), new TTextType()),
                        Map.entry(column("l", "position"), new TIntType()),
                        Map.entry(column("r", "id"), new TIntType()),
                        Map.entry(column("r", "lesson_id"), new TIntType()),
                        Map.entry(column("r", "url"), new TTextType()),
                        Map.entry(column("i", "id"), new TIntType()),
                        Map.entry(column("i", "name"), new TTextType()),
                        Map.entry(column("o", "id"), new TIntType()),
                        Map.entry(column("o", "course_id"), new TIntType()),
                        Map.entry(column("o", "name"), new TTextType())));
    }

    private static QueryTemplatePlan articleAuthorDerivedRootPlan(
            QueryTemplatePlan.RowValue orderValue,
            QueryTemplatePlan.ExpressionCursorWindow cursorWindow
    ) {
        QueryTemplatePlan.TableRef articles = new QueryTemplatePlan.TableRef(null, table("articles"), "a");
        QueryTemplatePlan.TableRef users = new QueryTemplatePlan.TableRef(null, table("users"), "u");
        return new QueryTemplatePlan(
                articles,
                QueryTemplatePlan.RootKind.LIST,
                List.of(
                        new QueryTemplatePlan.Projection(column("a", "id"), "id"),
                        new QueryTemplatePlan.Projection(column("a", "title"), "title")),
                List.of(),
                List.of(),
                List.of(new QueryTemplatePlan.OrderKey(column("a", "id"), SortDirection.ASC)),
                null,
                List.of(new QueryTemplatePlan.RootJoin(
                        articles,
                        column("a", "author_id"),
                        users,
                        column("u", "id"))),
                List.of(new QueryTemplatePlan.ExpressionPredicate(
                        new QueryTemplatePlan.ColumnValue(column("u", "name"), new TTextType()),
                        EQ,
                        new QueryTemplatePlan.RuntimeParameter("p_author_name", new TTextType()))),
                List.of(new QueryTemplatePlan.ExpressionOrderKey(orderValue, SortDirection.ASC)),
                cursorWindow);
    }

    private static RowMaterializationPlan articleRows(QueryTemplatePlan plan) {
        return RowMaterializationPlan.fromQueryTemplate(
                plan,
                column("a", "id"),
                new TIntType(),
                Map.ofEntries(
                        Map.entry(column("a", "id"), new TIntType()),
                        Map.entry(column("a", "title"), new TTextType()),
                        Map.entry(column("a", "author_id"), new TIntType()),
                        Map.entry(column("u", "id"), new TIntType()),
                        Map.entry(column("u", "name"), new TTextType())));
    }

    private static List<Map<String, Object>> articleAuthorRows() {
        return List.of(
                Map.of("id", 20, "title", "Runtime SQL", "author_id", 100, "users.name", "Ada"),
                Map.of("id", 40, "title", "Generated Runtime", "author_id", 100, "users.name", "Ada"),
                Map.of("id", 10, "title", "Structured Runtime", "author_id", 200, "users.name", "Grace"),
                Map.of("id", 30, "title", "Cursor Runtime", "author_id", 100, "users.name", "Ada"));
    }

    private static RowMaterializationRuntimePlan.MaterializedRelationQuery relationQuery(
            String name,
            String parentAlias,
            String parentColumn,
            RowMaterializationRuntimePlan.ParentKeySource.Kind parentKind,
            String childAlias,
            String childColumn
    ) {
        RowMaterializationPlan.RowKey parentKey =
                new RowMaterializationPlan.RowKey(
                        parentAlias + "_key",
                        column(parentAlias, parentColumn),
                        new TIntType());
        RowMaterializationPlan.RowKey childKey =
                new RowMaterializationPlan.RowKey(
                        childAlias + "_key",
                        column(childAlias, childColumn),
                        new TIntType());
        return new RowMaterializationRuntimePlan.MaterializedRelationQuery(
                name,
                new RowMaterializationRuntimePlan.ParentKeySource(parentKey, parentKind),
                new RowMaterializationRuntimePlan.ParentKeyCarrier("p_" + name + "_parent_key", new TIntType()),
                childKey,
                List.of(),
                List.of(),
                List.of(),
                null,
                MANY,
                new SelectSql(List.of(), "lessons", List.of(), null, List.of(), null, List.of(), null, null, null,
                        List.of()));
    }

    private static List<String> outputKeys(StructuredOutputPlan.ObjectValue object) {
        return object.entries().stream()
                .map(entry -> entry.key().value())
                .toList();
    }

    private static List<Map<String, Object>> rootWindowRows() {
        return List.of(
                Map.of("id", 20, "title", "Runtime SQL", "visibility", "member"),
                Map.of("id", 40, "title", "Admin Runtime", "visibility", "admin"),
                Map.of("id", 10, "title", "Structured Runtime", "visibility", "member"),
                Map.of("id", 30, "title", "Cursor Runtime", "visibility", "member"));
    }

    private static List<Map<String, Object>> rootWindowRowsWithNullVisibility() {
        return List.of(
                Map.of("id", 20, "title", "Runtime SQL", "visibility", "member"),
                nullableMap("id", 25, "title", "Unknown Runtime", "visibility", null),
                Map.of("id", 30, "title", "Cursor Runtime", "visibility", "member"));
    }

    private static List<Map<String, Object>> rootWindowRowsWithNullOrder() {
        return List.of(
                nullableMap("id", null, "title", "Unknown Runtime", "visibility", "member"),
                Map.of("id", 10, "title", "Structured Runtime", "visibility", "member"));
    }

    private static List<Map<String, Object>> rootWindowRowsWithNullSecondaryOrder() {
        return List.of(
                nullableMap("id", 25, "title", null, "visibility", "member"),
                Map.of("id", 30, "title", "Cursor Runtime", "visibility", "member"));
    }

    private static Map<String, Object> nullableMap(
            String firstKey,
            Object firstValue,
            String secondKey,
            Object secondValue
    ) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put(firstKey, firstValue);
        map.put(secondKey, secondValue);
        return map;
    }

    private static Map<String, Object> nullableMap(
            String firstKey,
            Object firstValue,
            String secondKey,
            Object secondValue,
            String thirdKey,
            Object thirdValue
    ) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put(firstKey, firstValue);
        map.put(secondKey, secondValue);
        map.put(thirdKey, thirdValue);
        return map;
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
        return new QueryTemplateDescriptorPlanBuilder.FieldDescriptor(
                name,
                table(table),
                alias,
                compilerKnown(COLUMN, column),
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
        return relation(name, fromTable, fromAlias, fromColumn, toTable, toColumn, toAlias, cardinality, List.of());
    }

    private static QueryTemplateDescriptorPlanBuilder.RelationDescriptor relation(
            String name,
            String fromTable,
            String fromAlias,
            String fromColumn,
            String toTable,
            String toColumn,
            String toAlias,
            QueryTemplatePlan.RelationCardinality cardinality,
            List<QueryTemplateDescriptorPlanBuilder.OrderDescriptor> orderKeys
    ) {
        return relation(name, fromTable, fromAlias, fromColumn, toTable, toColumn, toAlias, cardinality, orderKeys,
                null);
    }

    private static QueryTemplateDescriptorPlanBuilder.RelationDescriptor relation(
            String name,
            String fromTable,
            String fromAlias,
            String fromColumn,
            String toTable,
            String toColumn,
            String toAlias,
            QueryTemplatePlan.RelationCardinality cardinality,
            List<QueryTemplateDescriptorPlanBuilder.OrderDescriptor> orderKeys,
            QueryTemplateDescriptorPlanBuilder.RelationCursorWindowDescriptor cursorWindow
    ) {
        return new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                name,
                table(fromTable),
                fromAlias,
                compilerKnown(COLUMN, fromColumn),
                table(toTable),
                compilerKnown(COLUMN, toColumn),
                toAlias,
                cardinality,
                orderKeys,
                cursorWindow);
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
