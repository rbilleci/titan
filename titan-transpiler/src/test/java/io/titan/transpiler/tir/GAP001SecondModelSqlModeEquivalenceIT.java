package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.COLUMN;
import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.TABLE;
import static io.titan.transpiler.tir.QueryTemplatePlan.PredicateOperator.EQ;
import static io.titan.transpiler.tir.QueryTemplatePlan.PredicateOperator.GT;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.MANY;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.ONE;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.ZERO_OR_ONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.test.TestContainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class GAP001SecondModelSqlModeEquivalenceIT {
    static final PostgreSQLContainer<?> POSTGRES = TestContainers.postgres();

    static final MySQLContainer<?> MYSQL = TestContainers.mysql();

    @Test
    void supportedSecondModelRuntimeSelectsMatchJavaSemanticsInPostgresAndMySql() throws Exception {
        RowMaterializationRuntimePlan runtimeRows = runtimeRows();

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            recreateSecondModelTables(postgres);
            recreateSecondModelTables(mysql);

            String postgresRootSql = sqlWithJdbcParameters(
                    new PostgreSqlEmitter().visitSelectSql(runtimeRows.rootQuery().select()));
            String postgresLessonsSql = sqlWithJdbcParameters(
                    new PostgreSqlEmitter().visitSelectSql(runtimeRows.relationQueries().getFirst().select()));
            String mysqlRootSql = sqlWithJdbcParameters(
                    new MySqlEmitter().visitSelectSql(runtimeRows.rootQuery().select()));
            String mysqlLessonsSql = sqlWithJdbcParameters(
                    new MySqlEmitter().visitSelectSql(runtimeRows.relationQueries().getFirst().select()));

            assertTrue(postgresRootSql.contains("\"courses\".\"id\" = ?"));
            assertTrue(postgresRootSql.contains("\"courses\".\"visibility\" = ?"));
            assertTrue(mysqlRootSql.contains("`courses`.`id` = ?"));
            assertTrue(mysqlRootSql.contains("`courses`.`visibility` = ?"));

            assertEquals(
                    javaRoot(10, "member"),
                    fetchCourseRows(postgres, postgresRootSql, 10, "member"));
            assertEquals(
                    javaLessons(10),
                    fetchLessonRows(postgres, postgresLessonsSql, 10));
            assertEquals(
                    javaRoot(10, "member"),
                    fetchCourseRows(mysql, mysqlRootSql, 10, "member"));
            assertEquals(
                    javaLessons(10),
                    fetchLessonRows(mysql, mysqlLessonsSql, 10));

            assertEquals(List.of(), fetchCourseRows(postgres, postgresRootSql, 10, "guest"));
            assertEquals(List.of(), fetchCourseRows(mysql, mysqlRootSql, 10, "guest"));
        }
    }

    @Test
    void batchedListRootRelationRowsGroupLikeJavaSemanticsInPostgresAndMySql() throws Exception {
        QueryTemplatePlan plan = listRootPlan();
        RowMaterializationPlan rowPlan = rowPlan(plan);
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rowPlan);
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rowPlan),
                runtimeRows);

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            recreateSecondModelTables(postgres);
            recreateSecondModelTables(mysql);

            String postgresLessonsSql = new PostgreSqlEmitter()
                    .visitSelectSql(runtimeRows.relationQueries().getFirst().select())
                    .replace("p_course_lessons_parent_key", "ARRAY[10, 20]");
            String mysqlLessonsSql = new MySqlEmitter()
                    .visitSelectSql(runtimeRows.relationQueries().getFirst().select())
                    .replace("p_course_lessons_parent_key", "JSON_ARRAY(10, 20)");

            assertTrue(postgresLessonsSql.contains("\"lessons\".\"course_id\" = ANY(ARRAY[10, 20])"));
            assertTrue(mysqlLessonsSql.contains("`lessons`.`course_id` MEMBER OF(JSON_ARRAY(10, 20))"));
            assertEquals(
                    javaBatchedOutput(),
                    runtimeOutput.assembleBatch(javaRootRows(), fetchLessonMaps(postgres, postgresLessonsSql)));
            assertEquals(
                    javaBatchedOutput(),
                    runtimeOutput.assembleBatch(javaRootRows(), fetchLessonMaps(mysql, mysqlLessonsSql)));
        }
    }

    @Test
    void orderedRelationRowsMatchJavaSemanticsInPostgresAndMySql() throws Exception {
        QueryTemplatePlan plan = orderedListRootPlan();
        RowMaterializationPlan rowPlan = rowPlan(plan);
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rowPlan);
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rowPlan),
                runtimeRows);

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            recreateSecondModelTables(postgres);
            recreateSecondModelTables(mysql);
            insertNullPositionLesson(postgres);
            insertNullPositionLesson(mysql);

            String postgresLessonsSql = new PostgreSqlEmitter()
                    .visitSelectSql(runtimeRows.relationQueries().getFirst().select())
                    .replace("p_course_lessons_parent_key", "ARRAY[10, 20]");
            String mysqlLessonsSql = new MySqlEmitter()
                    .visitSelectSql(runtimeRows.relationQueries().getFirst().select())
                    .replace("p_course_lessons_parent_key", "JSON_ARRAY(10, 20)");

            assertTrue(postgresLessonsSql.contains("ORDER BY (\"lessons\".\"position\" IS NULL) ASC, \"lessons\".\"position\" ASC"));
            assertTrue(mysqlLessonsSql.contains("ORDER BY (`lessons`.`position` IS NULL) ASC, `lessons`.`position` ASC"));
            assertEquals(
                    javaOrderedBatchedOutput(),
                    runtimeOutput.assembleBatch(javaRootRows(), fetchOrderedLessonMaps(postgres, postgresLessonsSql)));
            assertEquals(
                    javaOrderedBatchedOutput(),
                    runtimeOutput.assembleBatch(javaRootRows(), fetchOrderedLessonMaps(mysql, mysqlLessonsSql)));
        }
    }

    @Test
    void nestedRelationGraphRowsMatchJavaSemanticsInPostgresAndMySql() throws Exception {
        QueryTemplatePlan plan = courseLessonResourcesPlan();
        RowMaterializationPlan rowPlan = rowPlan(plan);
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.fromListRoot(rowPlan);
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rowPlan),
                runtimeRows);

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            recreateSecondModelTables(postgres);
            recreateSecondModelTables(mysql);

            String postgresRootSql = new PostgreSqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());
            String mysqlRootSql = new MySqlEmitter().visitSelectSql(runtimeRows.rootQuery().select());
            String postgresLessonsSql = new PostgreSqlEmitter()
                    .visitSelectSql(runtimeRows.relationQueries().getFirst().select())
                    .replace("p_course_lessons_parent_key", "ARRAY[10, 20, 30]");
            String mysqlLessonsSql = new MySqlEmitter()
                    .visitSelectSql(runtimeRows.relationQueries().getFirst().select())
                    .replace("p_course_lessons_parent_key", "JSON_ARRAY(10, 20, 30)");
            String postgresResourcesSql = new PostgreSqlEmitter()
                    .visitSelectSql(runtimeRows.relationQueries().get(1).select())
                    .replace("p_lesson_resources_parent_key", "ARRAY[1, 2, 3]");
            String mysqlResourcesSql = new MySqlEmitter()
                    .visitSelectSql(runtimeRows.relationQueries().get(1).select())
                    .replace("p_lesson_resources_parent_key", "JSON_ARRAY(1, 2, 3)");

            assertTrue(postgresLessonsSql.contains("\"lessons\".\"course_id\" = ANY(ARRAY[10, 20, 30])"));
            assertTrue(mysqlLessonsSql.contains("`lessons`.`course_id` MEMBER OF(JSON_ARRAY(10, 20, 30))"));
            assertTrue(postgresResourcesSql.contains("\"resources\".\"lesson_id\" = ANY(ARRAY[1, 2, 3])"));
            assertTrue(mysqlResourcesSql.contains("`resources`.`lesson_id` MEMBER OF(JSON_ARRAY(1, 2, 3))"));
            List<Map<String, Object>> postgresRootRows = fetchRootWindowMaps(postgres, postgresRootSql);
            List<Map<String, Object>> mysqlRootRows = fetchRootWindowMaps(mysql, mysqlRootSql);
            assertEquals(javaRootRows(), postgresRootRows);
            assertEquals(javaRootRows(), mysqlRootRows);
            assertEquals(
                    javaNestedOutput(),
                    runtimeOutput.assembleNestedBatch(
                            postgresRootRows,
                            Map.of(
                                    "course_lessons", fetchNestedLessonMaps(postgres, postgresLessonsSql),
                                    "lesson_resources", fetchResourceMaps(postgres, postgresResourcesSql))));
            assertEquals(
                    javaNestedOutput(),
                    runtimeOutput.assembleNestedBatch(
                            mysqlRootRows,
                            Map.of(
                                    "course_lessons", fetchNestedLessonMaps(mysql, mysqlLessonsSql),
                                    "lesson_resources", fetchResourceMaps(mysql, mysqlResourcesSql))));
        }
    }

    @Test
    void rootListCursorWindowsMatchJavaSemanticsInPostgresAndMySql() throws Exception {
        RowMaterializationRuntimePlan firstPageRuntime =
                RowMaterializationRuntimePlan.fromListRoot(rowPlan(listRootWindowPlan(null)), 1);
        RowMaterializationRuntimePlan afterCursorRuntime =
                RowMaterializationRuntimePlan.fromListRoot(rowPlan(listRootWindowPlan(
                        new QueryTemplateDescriptorPlanBuilder.CursorWindowDescriptor(
                                table("courses"),
                                "c",
                                compilerKnown(COLUMN, "id"),
                                GT,
                                new QueryTemplatePlan.RuntimeParameter("p_after_course_id", new TIntType()),
                                2))));

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            recreateSecondModelTables(postgres);
            recreateSecondModelTables(mysql);

            String postgresFirstPageSql = sqlWithJdbcParameters(
                    new PostgreSqlEmitter().visitSelectSql(firstPageRuntime.rootQuery().select()));
            String mysqlFirstPageSql = sqlWithJdbcParameters(
                    new MySqlEmitter().visitSelectSql(firstPageRuntime.rootQuery().select()));
            String postgresAfterCursorSql = sqlWithJdbcParameters(
                    new PostgreSqlEmitter().visitSelectSql(afterCursorRuntime.rootQuery().select()));
            String mysqlAfterCursorSql = sqlWithJdbcParameters(
                    new MySqlEmitter().visitSelectSql(afterCursorRuntime.rootQuery().select()));

            assertTrue(postgresFirstPageSql.contains("\"courses\".\"visibility\" = ?"));
            assertTrue(postgresFirstPageSql.contains("\"courses\".\"id\" = \"courses\".\"id\""));
            assertTrue(postgresFirstPageSql.contains("ORDER BY \"courses\".\"id\" ASC"));
            assertTrue(postgresFirstPageSql.contains("LIMIT 1"));
            assertTrue(mysqlFirstPageSql.contains("`courses`.`visibility` = ?"));
            assertTrue(mysqlFirstPageSql.contains("`courses`.`id` = `courses`.`id`"));
            assertTrue(mysqlFirstPageSql.contains("ORDER BY `courses`.`id` ASC"));
            assertTrue(mysqlFirstPageSql.contains("LIMIT 1"));
            assertTrue(postgresAfterCursorSql.contains("\"courses\".\"id\" = \"courses\".\"id\""));
            assertTrue(postgresAfterCursorSql.contains("\"courses\".\"id\" > ?"));
            assertTrue(postgresAfterCursorSql.contains("ORDER BY \"courses\".\"id\" ASC"));
            assertTrue(postgresAfterCursorSql.contains("LIMIT 2"));
            assertTrue(mysqlAfterCursorSql.contains("`courses`.`id` = `courses`.`id`"));
            assertTrue(mysqlAfterCursorSql.contains("`courses`.`id` > ?"));
            assertTrue(mysqlAfterCursorSql.contains("ORDER BY `courses`.`id` ASC"));
            assertTrue(mysqlAfterCursorSql.contains("LIMIT 2"));

            List<Map<String, Object>> javaFirstPage = RowMaterializationRuntimePlan.windowRootRows(
                    firstPageRuntime.rootQuery(),
                    javaRootWindowSourceRows(),
                    Map.of("p_viewer_role", "member"));
            List<Map<String, Object>> javaAfterCursor = RowMaterializationRuntimePlan.windowRootRows(
                    afterCursorRuntime.rootQuery(),
                    javaRootWindowSourceRows(),
                    Map.of("p_viewer_role", "member", "p_after_course_id", 10));

            assertEquals(javaFirstPage, fetchRootWindowMaps(postgres, postgresFirstPageSql, "member"));
            assertEquals(javaFirstPage, fetchRootWindowMaps(mysql, mysqlFirstPageSql, "member"));
            assertEquals(javaAfterCursor, fetchRootWindowMaps(postgres, postgresAfterCursorSql, "member", 10));
            assertEquals(javaAfterCursor, fetchRootWindowMaps(mysql, mysqlAfterCursorSql, "member", 10));
        }
    }

    @Test
    void derivedRootFilterOrderAndCursorRowsMatchJavaSemanticsInPostgresAndMySql() throws Exception {
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

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            recreateSecondModelTables(postgres);
            recreateSecondModelTables(mysql);

            String postgresSql = sqlWithJdbcParameters(
                    new PostgreSqlEmitter().visitSelectSql(runtimeRows.rootQuery().select()));
            String mysqlSql = sqlWithJdbcParameters(
                    new MySqlEmitter().visitSelectSql(runtimeRows.rootQuery().select()));

            assertTrue(postgresSql.contains("JOIN \"users\" ON (\"articles\".\"author_id\" = \"users\".\"id\")"));
            assertTrue(mysqlSql.contains("JOIN `users` ON (`articles`.`author_id` = `users`.`id`)"));
            assertTrue(postgresSql.contains("ORDER BY CHAR_LENGTH(\"articles\".\"title\") ASC, \"articles\".\"id\" ASC"));
            assertTrue(mysqlSql.contains("ORDER BY CHAR_LENGTH(`articles`.`title`) ASC, `articles`.`id` ASC"));

            List<Map<String, Object>> javaRows = RowMaterializationRuntimePlan.windowRootRows(
                    runtimeRows.rootQuery(),
                    javaArticleAuthorRows(),
                    Map.of(
                            "p_author_name", "Ada",
                            "p_after_title_length", "Runtime SQL".length(),
                            "p_after_article_id", 20));
            assertEquals(
                    javaRows,
                    fetchArticleWindowMaps(postgres, postgresSql, "Ada", "Runtime SQL".length(), "Runtime SQL".length(), 20));
            assertEquals(
                    javaRows,
                    fetchArticleWindowMaps(mysql, mysqlSql, "Ada", "Runtime SQL".length(), "Runtime SQL".length(), 20));
        }
    }

    @Test
    void batchedArticleCommentRelationWindowsMatchJavaSemanticsInPostgresAndMySql() throws Exception {
        RowMaterializationRuntimePlan runtimeRows =
                RowMaterializationRuntimePlan.fromListRoot(articleCommentsRows(articleCommentsPlan()));
        RowMaterializationRuntimePlan.MaterializedRelationQuery comments =
                runtimeRows.relationQueries().getFirst();

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            recreateSecondModelTables(postgres);
            recreateSecondModelTables(mysql);

            String postgresCommentsSql = new PostgreSqlEmitter()
                    .visitSelectSql(comments.select())
                    .replace("p_article_comments_parent_key", "ARRAY[20, 30]");
            String mysqlCommentsSql = new MySqlEmitter()
                    .visitSelectSql(comments.select())
                    .replace("p_article_comments_parent_key", "JSON_ARRAY(20, 30)");

            assertTrue(
                    postgresCommentsSql.contains("\"comments\".\"article_id\" = ANY(ARRAY[20, 30])"),
                    postgresCommentsSql);
            assertTrue(
                    mysqlCommentsSql.contains("`comments`.`article_id` MEMBER OF(JSON_ARRAY(20, 30))"),
                    mysqlCommentsSql);
            assertTrue(postgresCommentsSql.contains(
                    "ORDER BY (\"comments\".\"position\" IS NULL) ASC, \"comments\".\"position\" ASC,"
                            + " (\"comments\".\"id\" IS NULL) ASC, \"comments\".\"id\" ASC"));
            assertTrue(mysqlCommentsSql.contains(
                    "ORDER BY (`comments`.`position` IS NULL) ASC, `comments`.`position` ASC,"
                            + " (`comments`.`id` IS NULL) ASC, `comments`.`id` ASC"));

            Map<Object, Map<String, Object>> cursorValues = Map.of(
                    20,
                    Map.of("p_after_comment_position", 2, "p_after_comment_id", 2),
                    30,
                    Map.of());
            List<Map<String, Object>> javaRows = RowMaterializationRuntimePlan.windowRelationRows(
                    comments,
                    javaArticleRootRows(),
                    javaCommentRows(),
                    cursorValues);
            assertEquals(
                    javaRows,
                    RowMaterializationRuntimePlan.windowRelationRows(
                            comments,
                            javaArticleRootRows(),
                            fetchCommentMaps(postgres, postgresCommentsSql),
                            cursorValues));
            assertEquals(
                    javaRows,
                    RowMaterializationRuntimePlan.windowRelationRows(
                            comments,
                            javaArticleRootRows(),
                            fetchCommentMaps(mysql, mysqlCommentsSql),
                            cursorValues));
        }
    }

    @Test
    void secondModelCardinalityMatchesJavaSemanticsInPostgresAndMySql() throws Exception {
        QueryTemplatePlan optionalPlan = listRootInstructorPlan(ZERO_OR_ONE);
        RowMaterializationPlan optionalRowPlan = rowPlan(optionalPlan);
        RowMaterializationRuntimePlan optionalRuntimeRows = RowMaterializationRuntimePlan.fromListRoot(optionalRowPlan);
        StructuredOutputRuntimePlan optionalOutput = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(optionalRowPlan),
                optionalRuntimeRows);
        QueryTemplatePlan requiredPlan = listRootInstructorPlan(ONE);
        RowMaterializationPlan requiredRowPlan = rowPlan(requiredPlan);
        RowMaterializationRuntimePlan requiredRuntimeRows = RowMaterializationRuntimePlan.fromListRoot(requiredRowPlan);
        StructuredOutputRuntimePlan requiredOutput = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(requiredRowPlan),
                requiredRuntimeRows);

        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            recreateSecondModelTables(postgres);
            recreateSecondModelTables(mysql);

            String postgresOptionalSql = new PostgreSqlEmitter()
                    .visitSelectSql(optionalRuntimeRows.relationQueries().getFirst().select())
                    .replace("p_course_instructor_parent_key", "ARRAY[100, 200]");
            String mysqlOptionalSql = new MySqlEmitter()
                    .visitSelectSql(optionalRuntimeRows.relationQueries().getFirst().select())
                    .replace("p_course_instructor_parent_key", "JSON_ARRAY(100, 200)");
            assertTrue(postgresOptionalSql.contains("\"instructors\".\"id\" = ANY(ARRAY[100, 200])"));
            assertTrue(mysqlOptionalSql.contains("`instructors`.`id` MEMBER OF(JSON_ARRAY(100, 200))"));

            assertEquals(
                    javaOptionalInstructorOutput(),
                    optionalOutput.assembleBatch(javaInstructorRootRows(), fetchInstructorMaps(postgres, postgresOptionalSql)));
            assertEquals(
                    javaOptionalInstructorOutput(),
                    optionalOutput.assembleBatch(javaInstructorRootRows(), fetchInstructorMaps(mysql, mysqlOptionalSql)));

            assertEquals(
                    javaRequiredInstructorOutput(),
                    requiredOutput.assembleBatch(
                            javaRequiredInstructorRootRows(),
                            fetchInstructorMaps(postgres, postgresOptionalSql)));
            assertEquals(
                    javaRequiredInstructorOutput(),
                    requiredOutput.assembleBatch(
                            javaRequiredInstructorRootRows(),
                            fetchInstructorMaps(mysql, mysqlOptionalSql)));

            String postgresMissingRequiredSql = new PostgreSqlEmitter()
                    .visitSelectSql(requiredRuntimeRows.relationQueries().getFirst().select())
                    .replace("p_course_instructor_parent_key", "ARRAY[300]");
            String mysqlMissingRequiredSql = new MySqlEmitter()
                    .visitSelectSql(requiredRuntimeRows.relationQueries().getFirst().select())
                    .replace("p_course_instructor_parent_key", "JSON_ARRAY(300)");
            assertCardinalityViolationMatchesJava(
                    requiredOutput,
                    javaMissingRequiredInstructorRootRows(),
                    List.of(),
                    fetchInstructorMaps(postgres, postgresMissingRequiredSql),
                    "received 0");
            assertCardinalityViolationMatchesJava(
                    requiredOutput,
                    javaMissingRequiredInstructorRootRows(),
                    List.of(),
                    fetchInstructorMaps(mysql, mysqlMissingRequiredSql),
                    "received 0");

            insertDuplicateInstructor(postgres);
            insertDuplicateInstructor(mysql);
            String postgresDuplicateSql = new PostgreSqlEmitter()
                    .visitSelectSql(optionalRuntimeRows.relationQueries().getFirst().select())
                    .replace("p_course_instructor_parent_key", "ARRAY[100]");
            String mysqlDuplicateSql = new MySqlEmitter()
                    .visitSelectSql(optionalRuntimeRows.relationQueries().getFirst().select())
                    .replace("p_course_instructor_parent_key", "JSON_ARRAY(100)");
            assertCardinalityViolationMatchesJava(
                    optionalOutput,
                    javaDuplicateInstructorRootRows(),
                    javaDuplicateInstructorRows(),
                    fetchInstructorMaps(postgres, postgresDuplicateSql),
                    "received 2");
            assertCardinalityViolationMatchesJava(
                    optionalOutput,
                    javaDuplicateInstructorRootRows(),
                    javaDuplicateInstructorRows(),
                    fetchInstructorMaps(mysql, mysqlDuplicateSql),
                    "received 2");
        }
    }

    private static RowMaterializationRuntimePlan runtimeRows() {
        QueryTemplatePlan plan = pointRootPlan();
        return RowMaterializationRuntimePlan.from(
                rowPlan(plan),
                new QueryTemplatePlan.RuntimeParameter("p_course_id", new TIntType()));
    }

    private static QueryTemplatePlan pointRootPlan() {
        return QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("course", table("courses"), "c", false),
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
                List.of(new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                        "course_lessons",
                        table("courses"),
                        "c",
                        compilerKnown(COLUMN, "id"),
                        table("lessons"),
                        compilerKnown(COLUMN, "course_id"),
                        "l",
                        MANY)));
    }

    private static QueryTemplatePlan listRootPlan() {
        return QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("title", "courses", "c", "title", "displayTitle"),
                        field("summary", "lessons", "l", "summary", "lessonSummary")),
                List.of(),
                List.of(new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                        "course_lessons",
                        table("courses"),
                        "c",
                        compilerKnown(COLUMN, "id"),
                        table("lessons"),
                        compilerKnown(COLUMN, "course_id"),
                        "l",
                        MANY)));
    }

    private static QueryTemplatePlan orderedListRootPlan() {
        return QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("title", "courses", "c", "title", "displayTitle"),
                        field("position", "lessons", "l", "position", "lessonPosition"),
                        field("summary", "lessons", "l", "summary", "lessonSummary")),
                List.of(),
                List.of(new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                        "course_lessons",
                        table("courses"),
                        "c",
                        compilerKnown(COLUMN, "id"),
                        table("lessons"),
                        compilerKnown(COLUMN, "course_id"),
                        "l",
                        MANY,
                        List.of(new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                table("lessons"),
                                "l",
                                compilerKnown(COLUMN, "position"),
                                SortDirection.ASC)))));
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
                        new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                                "course_lessons",
                                table("courses"),
                                "c",
                                compilerKnown(COLUMN, "id"),
                                table("lessons"),
                                compilerKnown(COLUMN, "course_id"),
                                "l",
                                MANY,
                                List.of(new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                        table("lessons"),
                                        "l",
                                        compilerKnown(COLUMN, "id"),
                                        SortDirection.ASC))),
                        new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                                "lesson_resources",
                                table("lessons"),
                                "l",
                                compilerKnown(COLUMN, "id"),
                                table("resources"),
                                compilerKnown(COLUMN, "lesson_id"),
                                "r",
                                MANY,
                                List.of(new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                        table("resources"),
                                        "r",
                                        compilerKnown(COLUMN, "url"),
                                        SortDirection.ASC)))),
                List.of(new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                        table("courses"),
                        "c",
                        compilerKnown(COLUMN, "id"),
                        SortDirection.ASC)),
                null);
    }

    private static QueryTemplatePlan listRootInstructorPlan(QueryTemplatePlan.RelationCardinality cardinality) {
        return QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("courses", table("courses"), "c", true),
                List.of(
                        field("id", "courses", "c", "id", "id"),
                        field("title", "courses", "c", "title", "displayTitle"),
                        field("name", "instructors", "i", "name", "instructorName")),
                List.of(),
                List.of(new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                        "course_instructor",
                        table("courses"),
                        "c",
                        compilerKnown(COLUMN, "instructor_id"),
                        table("instructors"),
                        compilerKnown(COLUMN, "id"),
                        "i",
                        cardinality)));
    }

    private static QueryTemplatePlan listRootWindowPlan(
            QueryTemplateDescriptorPlanBuilder.CursorWindowDescriptor cursorWindow
    ) {
        return QueryTemplateDescriptorPlanBuilder.buildRootPlan(
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
                cursorWindow);
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

    private static QueryTemplatePlan articleCommentsPlan() {
        return QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("articles", table("articles"), "a", true),
                List.of(
                        field("id", "articles", "a", "id", "id"),
                        field("title", "articles", "a", "title", "title"),
                        field("position", "comments", "cm", "position", "commentPosition"),
                        field("body", "comments", "cm", "body", "commentBody")),
                List.of(),
                List.of(new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                        "article_comments",
                        table("articles"),
                        "a",
                        compilerKnown(COLUMN, "id"),
                        table("comments"),
                        compilerKnown(COLUMN, "article_id"),
                        "cm",
                        MANY,
                        List.of(
                                new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                        table("comments"),
                                        "cm",
                                        compilerKnown(COLUMN, "position"),
                                        SortDirection.ASC),
                                new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                        table("comments"),
                                        "cm",
                                        compilerKnown(COLUMN, "id"),
                                        SortDirection.ASC)),
                        new QueryTemplateDescriptorPlanBuilder.RelationCursorWindowDescriptor(
                                table("comments"),
                                "cm",
                                compilerKnown(COLUMN, "position"),
                                GT,
                                new QueryTemplatePlan.RuntimeParameter("p_after_comment_position", new TIntType()),
                                table("comments"),
                                "cm",
                                compilerKnown(COLUMN, "id"),
                                new QueryTemplatePlan.RuntimeParameter("p_after_comment_id", new TIntType()),
                                2))));
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

    private static RowMaterializationPlan articleCommentsRows(QueryTemplatePlan plan) {
        return RowMaterializationPlan.fromQueryTemplate(
                plan,
                column("a", "id"),
                new TIntType(),
                Map.ofEntries(
                        Map.entry(column("a", "id"), new TIntType()),
                        Map.entry(column("a", "title"), new TTextType()),
                        Map.entry(column("cm", "id"), new TIntType()),
                        Map.entry(column("cm", "article_id"), new TIntType()),
                        Map.entry(column("cm", "position"), new TIntType()),
                        Map.entry(column("cm", "body"), new TTextType())));
    }

    private static RowMaterializationPlan rowPlan(QueryTemplatePlan plan) {
        return RowMaterializationPlan.fromQueryTemplate(
                plan,
                column("c", "id"),
                new TIntType(),
                Map.ofEntries(
                        Map.entry(column("c", "id"), new TIntType()),
                        Map.entry(column("c", "title"), new TTextType()),
                        Map.entry(column("c", "instructor_id"), new TIntType()),
                        Map.entry(column("c", "visibility"), new TTextType()),
                        Map.entry(column("i", "id"), new TIntType()),
                        Map.entry(column("i", "name"), new TTextType()),
                        Map.entry(column("l", "id"), new TIntType()),
                        Map.entry(column("l", "course_id"), new TIntType()),
                        Map.entry(column("l", "position"), new TIntType()),
                        Map.entry(column("l", "summary"), new TTextType()),
                        Map.entry(column("r", "lesson_id"), new TIntType()),
                        Map.entry(column("r", "url"), new TTextType())));
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

    private static String sqlWithJdbcParameters(String sql) {
        return sql.replace("p_course_id", "?")
                .replace("p_viewer_role", "?")
                .replace("p_after_course_id", "?")
                .replace("p_author_name", "?")
                .replace("p_after_title_length", "?")
                .replace("p_after_article_id", "?");
    }

    private static void recreateSecondModelTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS resources");
            statement.execute("DROP TABLE IF EXISTS lessons");
            statement.execute("DROP TABLE IF EXISTS instructors");
            statement.execute("DROP TABLE IF EXISTS courses");
            statement.execute("DROP TABLE IF EXISTS comments");
            statement.execute("DROP TABLE IF EXISTS articles");
            statement.execute("DROP TABLE IF EXISTS users");
            statement.execute("""
                    CREATE TABLE courses(
                        id INT PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        instructor_id INT,
                        visibility VARCHAR(32) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE instructors(
                        id INT NOT NULL,
                        name VARCHAR(255) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE lessons(
                        id INT PRIMARY KEY,
                        course_id INT NOT NULL,
                        position INT,
                        summary VARCHAR(255) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE resources(
                        id INT PRIMARY KEY,
                        lesson_id INT NOT NULL,
                        url VARCHAR(255) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE users(
                        id INT PRIMARY KEY,
                        name VARCHAR(255) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE articles(
                        id INT PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        author_id INT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE comments(
                        id INT PRIMARY KEY,
                        article_id INT NOT NULL,
                        position INT NOT NULL,
                        body VARCHAR(255) NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO courses(id, title, instructor_id, visibility) "
                    + "VALUES (10, 'Structured Runtime', 100, 'member')");
            statement.execute("INSERT INTO courses(id, title, instructor_id, visibility) "
                    + "VALUES (20, 'Compiler Boundaries', 200, 'admin')");
            statement.execute("INSERT INTO courses(id, title, instructor_id, visibility) "
                    + "VALUES (30, 'No Lessons', NULL, 'member')");
            statement.execute("INSERT INTO instructors(id, name) VALUES (100, 'Ada')");
            statement.execute("INSERT INTO instructors(id, name) VALUES (200, 'Grace')");
            statement.execute("INSERT INTO lessons(id, course_id, position, summary) "
                    + "VALUES (1, 10, 1, 'Descriptor contracts')");
            statement.execute("INSERT INTO lessons(id, course_id, position, summary) "
                    + "VALUES (2, 10, 2, 'Runtime SQL shapes')");
            statement.execute("INSERT INTO lessons(id, course_id, position, summary) "
                    + "VALUES (3, 20, 1, 'Unsupported grouped execution')");
            statement.execute("INSERT INTO resources(id, lesson_id, url) VALUES (1, 1, '/descriptor.sql')");
            statement.execute("INSERT INTO resources(id, lesson_id, url) VALUES (2, 1, '/descriptor.java')");
            statement.execute("INSERT INTO resources(id, lesson_id, url) VALUES (3, 3, '/grouped.sql')");
            statement.execute("INSERT INTO users(id, name) VALUES (100, 'Ada')");
            statement.execute("INSERT INTO users(id, name) VALUES (200, 'Grace')");
            statement.execute("INSERT INTO articles(id, title, author_id) VALUES (20, 'Runtime SQL', 100)");
            statement.execute("INSERT INTO articles(id, title, author_id) VALUES (40, 'Generated Runtime', 100)");
            statement.execute("INSERT INTO articles(id, title, author_id) VALUES (10, 'Structured Runtime', 200)");
            statement.execute("INSERT INTO articles(id, title, author_id) VALUES (30, 'Cursor Runtime', 100)");
            statement.execute("INSERT INTO comments(id, article_id, position, body) "
                    + "VALUES (1, 20, 1, 'First runtime note')");
            statement.execute("INSERT INTO comments(id, article_id, position, body) "
                    + "VALUES (2, 20, 2, 'Second runtime note')");
            statement.execute("INSERT INTO comments(id, article_id, position, body) "
                    + "VALUES (3, 20, 2, 'Tie-break runtime note')");
            statement.execute("INSERT INTO comments(id, article_id, position, body) "
                    + "VALUES (4, 20, 3, 'Window runtime note')");
            statement.execute("INSERT INTO comments(id, article_id, position, body) "
                    + "VALUES (5, 30, 1, 'Cursor note')");
            statement.execute("INSERT INTO comments(id, article_id, position, body) "
                    + "VALUES (6, 30, 2, 'Cursor follow-up')");
        }
    }

    private static void insertDuplicateInstructor(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO instructors(id, name) VALUES (100, 'Ada Duplicate')");
        }
    }

    private static void insertNullPositionLesson(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO lessons(id, course_id, position, summary) "
                    + "VALUES (4, 10, NULL, 'Null-position appendix')");
        }
    }

    private static List<CourseRow> fetchCourseRows(
            Connection connection,
            String sql,
            int courseId,
            String viewerRole
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, courseId);
            statement.setString(2, viewerRole);
            try (var resultSet = statement.executeQuery()) {
                List<CourseRow> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(new CourseRow(resultSet.getInt("id"), resultSet.getString("displayTitle")));
                }
                return rows;
            }
        }
    }

    private static List<LessonRow> fetchLessonRows(
            Connection connection,
            String sql,
            int courseId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, courseId);
            try (var resultSet = statement.executeQuery()) {
                List<LessonRow> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(new LessonRow(resultSet.getString("lessonSummary")));
                }
                rows.sort(Comparator.comparing(LessonRow::lessonSummary));
                return rows;
            }
        }
    }

    private static List<Map<String, Object>> fetchLessonMaps(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             var resultSet = statement.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(Map.of(
                        "lessonSummary", resultSet.getString("lessonSummary"),
                        "l_key", resultSet.getInt("l_key")));
            }
            return rows;
        }
    }

    private static List<Map<String, Object>> fetchOrderedLessonMaps(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             var resultSet = statement.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(orderedLessonMap(
                        resultSet.getObject("lessonPosition"),
                        resultSet.getString("lessonSummary"),
                        resultSet.getInt("l_key")));
            }
            return rows;
        }
    }

    private static List<Map<String, Object>> fetchNestedLessonMaps(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             var resultSet = statement.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(Map.of(
                        "lessonSummary", resultSet.getString("lessonSummary"),
                        "l_course_id_key", resultSet.getInt("l_course_id_key"),
                        "l_key", resultSet.getInt("l_key")));
            }
            return rows;
        }
    }

    private static List<Map<String, Object>> fetchResourceMaps(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             var resultSet = statement.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(Map.of(
                        "resourceUrl", resultSet.getString("resourceUrl"),
                        "r_key", resultSet.getInt("r_key")));
            }
            return rows;
        }
    }

    private static List<Map<String, Object>> fetchInstructorMaps(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             var resultSet = statement.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(Map.of(
                        "instructorName", resultSet.getString("instructorName"),
                        "i_key", resultSet.getInt("i_key")));
            }
            return rows;
        }
    }

    private static List<Map<String, Object>> fetchCommentMaps(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             var resultSet = statement.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(Map.of(
                        "commentPosition", resultSet.getInt("commentPosition"),
                        "commentBody", resultSet.getString("commentBody"),
                        "cm_key", resultSet.getInt("cm_key"),
                        "cm_id_identity", resultSet.getInt("cm_id_identity")));
            }
            return rows;
        }
    }

    private static List<Map<String, Object>> fetchRootWindowMaps(
            Connection connection,
            String sql,
            Object... parameters
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var resultSet = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(Map.of(
                            "id", resultSet.getInt("id"),
                            "displayTitle", resultSet.getString("displayTitle"),
                            "c_identity", resultSet.getInt("c_identity")));
                }
                return rows;
            }
        }
    }

    private static List<Map<String, Object>> fetchArticleWindowMaps(
            Connection connection,
            String sql,
            Object... parameters
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var resultSet = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(Map.of(
                            "id", resultSet.getInt("id"),
                            "title", resultSet.getString("title"),
                            "a_identity", resultSet.getInt("a_identity")));
                }
                return rows;
            }
        }
    }

    private static List<CourseRow> javaRoot(int courseId, String viewerRole) {
        return courses().stream()
                .filter(course -> course.id() == courseId)
                .filter(course -> course.visibility().equals(viewerRole))
                .map(course -> new CourseRow(course.id(), course.title()))
                .toList();
    }

    private static List<LessonRow> javaLessons(int courseId) {
        return lessons().stream()
                .filter(lesson -> lesson.courseId() == courseId)
                .map(lesson -> new LessonRow(lesson.summary()))
                .sorted(Comparator.comparing(LessonRow::lessonSummary))
                .toList();
    }

    private static List<Map<String, Object>> javaRootRows() {
        return List.of(
                Map.of("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10),
                Map.of("id", 20, "displayTitle", "Compiler Boundaries", "c_identity", 20),
                Map.of("id", 30, "displayTitle", "No Lessons", "c_identity", 30));
    }

    private static List<Map<String, Object>> javaRootWindowSourceRows() {
        return List.of(
                Map.of("id", 20, "title", "Compiler Boundaries", "visibility", "admin"),
                Map.of("id", 30, "title", "No Lessons", "visibility", "member"),
                Map.of("id", 10, "title", "Structured Runtime", "visibility", "member"));
    }

    private static List<Map<String, Object>> javaArticleAuthorRows() {
        return List.of(
                Map.of("id", 20, "title", "Runtime SQL", "author_id", 100, "users.name", "Ada"),
                Map.of("id", 40, "title", "Generated Runtime", "author_id", 100, "users.name", "Ada"),
                Map.of("id", 10, "title", "Structured Runtime", "author_id", 200, "users.name", "Grace"),
                Map.of("id", 30, "title", "Cursor Runtime", "author_id", 100, "users.name", "Ada"));
    }

    private static List<Map<String, Object>> javaArticleRootRows() {
        return List.of(
                Map.of("id", 20, "title", "Runtime SQL", "a_identity", 20),
                Map.of("id", 30, "title", "Cursor Runtime", "a_identity", 30));
    }

    private static List<Map<String, Object>> javaCommentRows() {
        return List.of(
                Map.of("commentPosition", 1, "commentBody", "First runtime note", "cm_key", 20,
                        "cm_id_identity", 1),
                Map.of("commentPosition", 2, "commentBody", "Second runtime note", "cm_key", 20,
                        "cm_id_identity", 2),
                Map.of("commentPosition", 2, "commentBody", "Tie-break runtime note", "cm_key", 20,
                        "cm_id_identity", 3),
                Map.of("commentPosition", 3, "commentBody", "Window runtime note", "cm_key", 20,
                        "cm_id_identity", 4),
                Map.of("commentPosition", 1, "commentBody", "Cursor note", "cm_key", 30,
                        "cm_id_identity", 5),
                Map.of("commentPosition", 2, "commentBody", "Cursor follow-up", "cm_key", 30,
                        "cm_id_identity", 6));
    }

    private static List<Map<String, Object>> javaBatchedOutput() {
        return List.of(
                Map.of(
                        "id", 10,
                        "displayTitle", "Structured Runtime",
                        "course_lessons", List.of(
                                Map.of("lessonSummary", "Descriptor contracts"),
                                Map.of("lessonSummary", "Runtime SQL shapes"))),
                Map.of(
                        "id", 20,
                        "displayTitle", "Compiler Boundaries",
                        "course_lessons", List.of(Map.of("lessonSummary", "Unsupported grouped execution"))),
                Map.of(
                        "id", 30,
                        "displayTitle", "No Lessons",
                        "course_lessons", List.of()));
    }

    private static List<Map<String, Object>> javaOrderedBatchedOutput() {
        return List.of(
                Map.of(
                        "id", 10,
                        "displayTitle", "Structured Runtime",
                        "course_lessons", List.of(
                                Map.of("lessonPosition", 1, "lessonSummary", "Descriptor contracts"),
                                Map.of("lessonPosition", 2, "lessonSummary", "Runtime SQL shapes"),
                                orderedLessonOutput(null, "Null-position appendix"))),
                Map.of(
                        "id", 20,
                        "displayTitle", "Compiler Boundaries",
                        "course_lessons", List.of(Map.of(
                                "lessonPosition", 1,
                                "lessonSummary", "Unsupported grouped execution"))),
                Map.of(
                        "id", 30,
                        "displayTitle", "No Lessons",
                        "course_lessons", List.of()));
    }

    private static List<Map<String, Object>> javaNestedOutput() {
        return List.of(
                Map.of(
                        "id", 10,
                        "displayTitle", "Structured Runtime",
                        "course_lessons", List.of(
                                Map.of(
                                        "lessonSummary", "Descriptor contracts",
                                        "lesson_resources", List.of(
                                                Map.of("resourceUrl", "/descriptor.java"),
                                                Map.of("resourceUrl", "/descriptor.sql"))),
                                Map.of(
                                        "lessonSummary", "Runtime SQL shapes",
                                        "lesson_resources", List.of()))),
                Map.of(
                        "id", 20,
                        "displayTitle", "Compiler Boundaries",
                        "course_lessons", List.of(Map.of(
                                "lessonSummary", "Unsupported grouped execution",
                                "lesson_resources", List.of(Map.of("resourceUrl", "/grouped.sql"))))),
                Map.of(
                        "id", 30,
                        "displayTitle", "No Lessons",
                        "course_lessons", List.of()));
    }

    private static List<Map<String, Object>> javaInstructorRootRows() {
        return List.of(
                Map.of("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10, "c_key", 100),
                Map.of("id", 20, "displayTitle", "Compiler Boundaries", "c_identity", 20, "c_key", 200),
                nullableMap("id", 30, "displayTitle", "No Lessons", "c_identity", 30, "c_key", null));
    }

    private static List<Map<String, Object>> javaRequiredInstructorRootRows() {
        return List.of(
                Map.of("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10, "c_key", 100),
                Map.of("id", 20, "displayTitle", "Compiler Boundaries", "c_identity", 20, "c_key", 200));
    }

    private static List<Map<String, Object>> javaMissingRequiredInstructorRootRows() {
        return List.of(nullableMap(
                "id", 30,
                "displayTitle", "No Lessons",
                "c_identity", 30,
                "c_key", null));
    }

    private static List<Map<String, Object>> javaDuplicateInstructorRootRows() {
        return List.of(Map.of("id", 10, "displayTitle", "Structured Runtime", "c_identity", 10, "c_key", 100));
    }

    private static List<Map<String, Object>> javaDuplicateInstructorRows() {
        return List.of(
                Map.of("instructorName", "Ada", "i_key", 100),
                Map.of("instructorName", "Ada Duplicate", "i_key", 100));
    }

    private static List<Map<String, Object>> javaOptionalInstructorOutput() {
        return List.of(
                Map.of(
                        "id", 10,
                        "displayTitle", "Structured Runtime",
                        "course_instructor", Map.of("instructorName", "Ada")),
                Map.of(
                        "id", 20,
                        "displayTitle", "Compiler Boundaries",
                        "course_instructor", Map.of("instructorName", "Grace")),
                nullableMap("id", 30, "displayTitle", "No Lessons", "course_instructor", null));
    }

    private static List<Map<String, Object>> javaRequiredInstructorOutput() {
        return List.of(
                Map.of(
                        "id", 10,
                        "displayTitle", "Structured Runtime",
                        "course_instructor", Map.of("instructorName", "Ada")),
                Map.of(
                        "id", 20,
                        "displayTitle", "Compiler Boundaries",
                        "course_instructor", Map.of("instructorName", "Grace")));
    }

    private static void assertCardinalityViolationMatchesJava(
            StructuredOutputRuntimePlan runtimeOutput,
            List<Map<String, Object>> rootRows,
            List<Map<String, Object>> javaRows,
            List<Map<String, Object>> sqlRows,
            String expectedCount
    ) {
        IllegalArgumentException javaException = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeOutput.assembleBatch(rootRows, javaRows));
        IllegalArgumentException sqlException = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeOutput.assembleBatch(rootRows, sqlRows));

        assertTrue(javaException.getMessage().contains("TITAN-E001"));
        assertTrue(javaException.getMessage().contains("course_instructor"));
        assertTrue(javaException.getMessage().contains(expectedCount));
        assertEquals(javaException.getMessage(), sqlException.getMessage());
    }

    private static List<Course> courses() {
        return List.of(
                new Course(10, "Structured Runtime", "member"),
                new Course(20, "Compiler Boundaries", "admin"),
                new Course(30, "No Lessons", "member"));
    }

    private static List<Lesson> lessons() {
        return List.of(
                new Lesson(1, 10, "Descriptor contracts"),
                new Lesson(2, 10, "Runtime SQL shapes"),
                new Lesson(3, 20, "Unsupported grouped execution"));
    }

    private record Course(int id, String title, String visibility) {
    }

    private record Lesson(int id, int courseId, String summary) {
    }

    private record CourseRow(int id, String displayTitle) {
    }

    private record LessonRow(String lessonSummary) {
    }

    private static Map<String, Object> nullableMap(
            String firstKey,
            Object firstValue,
            String secondKey,
            Object secondValue,
            String thirdKey,
            Object thirdValue
    ) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put(firstKey, firstValue);
        map.put(secondKey, secondValue);
        map.put(thirdKey, thirdValue);
        return map;
    }

    private static Map<String, Object> nullableMap(
            String firstKey,
            Object firstValue,
            String secondKey,
            Object secondValue,
            String thirdKey,
            Object thirdValue,
            String fourthKey,
            Object fourthValue
    ) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put(firstKey, firstValue);
        map.put(secondKey, secondValue);
        map.put(thirdKey, thirdValue);
        map.put(fourthKey, fourthValue);
        return map;
    }

    private static Map<String, Object> orderedLessonMap(Object position, String summary, int key) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("lessonPosition", position);
        map.put("lessonSummary", summary);
        map.put("l_key", key);
        return map;
    }

    private static Map<String, Object> orderedLessonOutput(Object position, String summary) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("lessonPosition", position);
        map.put("lessonSummary", summary);
        return map;
    }
}
