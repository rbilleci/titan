package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.COLUMN;
import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.SCHEMA;
import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.TABLE;
import static io.titan.transpiler.tir.QueryTemplatePlan.PredicateOperator.EQ;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.MANY;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.ONE;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.ZERO_OR_ONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowMaterializationPlanTest {

    @Test
    void derivesRootAndRelationRowFieldsFromQueryTemplateMetadata() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier reviews = compilerKnown(TABLE, "reviews");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");
        QueryTemplatePlan.StructuralIdentifier body = compilerKnown(COLUMN, "body");
        QueryTemplatePlan.StructuralIdentifier bookId = compilerKnown(COLUMN, "book_id");
        QueryTemplatePlan plan = new QueryTemplatePlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                List.of(
                        new QueryTemplatePlan.Projection(new QueryTemplatePlan.ColumnRef("b", title), "title"),
                        new QueryTemplatePlan.Projection(new QueryTemplatePlan.ColumnRef("r", body), "reviewBody")),
                List.of(new QueryTemplatePlan.ParameterPredicate(
                        new QueryTemplatePlan.ColumnRef("b", id),
                        EQ,
                        new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType()))),
                List.of(new QueryTemplatePlan.RelationEdge(
                        "book_reviews",
                        new QueryTemplatePlan.TableRef(null, books, "b"),
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new QueryTemplatePlan.TableRef(null, reviews, "r"),
                        new QueryTemplatePlan.ColumnRef("r", bookId),
                        MANY)));

        RowMaterializationPlan rows = RowMaterializationPlan.fromQueryTemplate(
                plan,
                new QueryTemplatePlan.ColumnRef("b", id),
                new TIntType(),
                Map.of(
                        new QueryTemplatePlan.ColumnRef("b", id), new TIntType(),
                        new QueryTemplatePlan.ColumnRef("b", title), new TTextType(),
                        new QueryTemplatePlan.ColumnRef("r", body), new TTextType(),
                        new QueryTemplatePlan.ColumnRef("r", bookId), new TIntType()));

        assertEquals("books", rows.root().table().value());
        assertEquals(new RowMaterializationPlan.RowKey(
                        "b_identity",
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType()),
                rows.rootKey());
        assertEquals(List.of(new RowMaterializationPlan.FieldBinding(
                        "title",
                        new QueryTemplatePlan.ColumnRef("b", title),
                        new TTextType())),
                rows.rootFields());
        RowMaterializationPlan.RelationRows relation = rows.relations().getFirst();
        assertEquals("book_reviews", relation.name());
        assertEquals(MANY, relation.cardinality());
        assertEquals(new RowMaterializationPlan.RowKey(
                        "b_key",
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType()),
                relation.parentKey());
        assertEquals(new RowMaterializationPlan.RowKey(
                        "r_key",
                        new QueryTemplatePlan.ColumnRef("r", bookId),
                        new TIntType()),
                relation.childKey());
        assertEquals(List.of(new RowMaterializationPlan.FieldBinding(
                        "reviewBody",
                        new QueryTemplatePlan.ColumnRef("r", body),
                        new TTextType())),
                relation.fields());
    }

    @Test
    void preservesZeroOneAndRequiredOneRelationCardinalityMetadata() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier authors = compilerKnown(TABLE, "authors");
        QueryTemplatePlan.StructuralIdentifier editions = compilerKnown(TABLE, "editions");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier authorId = compilerKnown(COLUMN, "author_id");
        QueryTemplatePlan.StructuralIdentifier bookId = compilerKnown(COLUMN, "book_id");
        QueryTemplatePlan plan = new QueryTemplatePlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                List.of(),
                List.of(),
                List.of(
                        new QueryTemplatePlan.RelationEdge(
                                "book_author",
                                new QueryTemplatePlan.TableRef(null, books, "b"),
                                new QueryTemplatePlan.ColumnRef("b", authorId),
                                new QueryTemplatePlan.TableRef(null, authors, "a"),
                                new QueryTemplatePlan.ColumnRef("a", id),
                                ONE),
                        new QueryTemplatePlan.RelationEdge(
                                "book_primary_edition",
                                new QueryTemplatePlan.TableRef(null, books, "b"),
                                new QueryTemplatePlan.ColumnRef("b", id),
                                new QueryTemplatePlan.TableRef(null, editions, "e"),
                                new QueryTemplatePlan.ColumnRef("e", bookId),
                                ZERO_OR_ONE)));

        RowMaterializationPlan rows = RowMaterializationPlan.fromQueryTemplate(
                plan,
                new QueryTemplatePlan.ColumnRef("b", id),
                new TIntType(),
                Map.of(
                        new QueryTemplatePlan.ColumnRef("b", id), new TIntType(),
                        new QueryTemplatePlan.ColumnRef("b", authorId), new TIntType(),
                        new QueryTemplatePlan.ColumnRef("a", id), new TIntType(),
                        new QueryTemplatePlan.ColumnRef("e", bookId), new TIntType()));

        assertEquals(ONE, rows.relations().get(0).cardinality());
        assertEquals(ZERO_OR_ONE, rows.relations().get(1).cardinality());
    }

    @Test
    void recordsFetchIntoCompatibleRootAndChildRowShapesForOneRelation() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier reviews = compilerKnown(TABLE, "reviews");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");
        QueryTemplatePlan.StructuralIdentifier body = compilerKnown(COLUMN, "body");
        QueryTemplatePlan.StructuralIdentifier bookId = compilerKnown(COLUMN, "book_id");
        QueryTemplatePlan plan = new QueryTemplatePlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                List.of(
                        new QueryTemplatePlan.Projection(new QueryTemplatePlan.ColumnRef("b", id), "id"),
                        new QueryTemplatePlan.Projection(new QueryTemplatePlan.ColumnRef("b", title), "title"),
                        new QueryTemplatePlan.Projection(new QueryTemplatePlan.ColumnRef("r", bookId), "bookId"),
                        new QueryTemplatePlan.Projection(new QueryTemplatePlan.ColumnRef("r", body), "body")),
                List.of(new QueryTemplatePlan.ParameterPredicate(
                        new QueryTemplatePlan.ColumnRef("b", id),
                        EQ,
                        new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType()))),
                List.of(new QueryTemplatePlan.RelationEdge(
                        "book_reviews",
                        new QueryTemplatePlan.TableRef(null, books, "b"),
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new QueryTemplatePlan.TableRef(null, reviews, "r"),
                        new QueryTemplatePlan.ColumnRef("r", bookId),
                        MANY)));

        RowMaterializationPlan rows = RowMaterializationPlan.fromQueryTemplate(
                plan,
                new QueryTemplatePlan.ColumnRef("b", id),
                new TIntType(),
                Map.of(
                        new QueryTemplatePlan.ColumnRef("b", id), new TIntType(),
                        new QueryTemplatePlan.ColumnRef("b", title), new TTextType(),
                        new QueryTemplatePlan.ColumnRef("r", bookId), new TIntType(),
                        new QueryTemplatePlan.ColumnRef("r", body), new TTextType()));

        assertEquals(List.of(
                        new RowMaterializationPlan.FieldBinding(
                                "id",
                                new QueryTemplatePlan.ColumnRef("b", id),
                                new TIntType()),
                        new RowMaterializationPlan.FieldBinding(
                                "title",
                                new QueryTemplatePlan.ColumnRef("b", title),
                                new TTextType())),
                rows.rootFields());
        RowMaterializationPlan.RelationRows reviewsRows = rows.relations().getFirst();
        assertEquals(MANY, reviewsRows.cardinality());
        assertEquals(new RowMaterializationPlan.RowKey(
                        "b_key",
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType()),
                reviewsRows.parentKey());
        assertEquals(new RowMaterializationPlan.RowKey(
                        "r_key",
                        new QueryTemplatePlan.ColumnRef("r", bookId),
                        new TIntType()),
                reviewsRows.childKey());
        assertEquals(List.of(
                        new RowMaterializationPlan.FieldBinding(
                                "bookId",
                                new QueryTemplatePlan.ColumnRef("r", bookId),
                                new TIntType()),
                        new RowMaterializationPlan.FieldBinding(
                                "body",
                                new QueryTemplatePlan.ColumnRef("r", body),
                                new TTextType())),
                reviewsRows.fields());

        RowMaterializationRuntimePlan runtimePlan = RowMaterializationRuntimePlan.from(
                rows,
                new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType()));
        RowMaterializationRuntimePlan.MaterializedRootQuery rootQuery = runtimePlan.rootQuery();
        RowMaterializationRuntimePlan.MaterializedRelationQuery relationQuery =
                runtimePlan.relationQueries().getFirst();
        String postgresRoot = new PostgreSqlEmitter().visitSelectSql(runtimePlan.rootQuery().select());
        String postgresReviews = new PostgreSqlEmitter().visitSelectSql(
                runtimePlan.relationQueries().getFirst().select());
        String mysqlRoot = new MySqlEmitter().visitSelectSql(runtimePlan.rootQuery().select());
        String mysqlReviews = new MySqlEmitter().visitSelectSql(
                runtimePlan.relationQueries().getFirst().select());

        assertEquals(List.of(
                        new RowMaterializationRuntimePlan.RowFieldProjection(
                                "id",
                                new QueryTemplatePlan.ColumnRef("b", id),
                                new TIntType(),
                                false),
                        new RowMaterializationRuntimePlan.RowFieldProjection(
                                "title",
                                new QueryTemplatePlan.ColumnRef("b", title),
                                new TTextType(),
                                false)),
                rootQuery.fields());
        assertEquals(List.of(new RowMaterializationRuntimePlan.RowFieldProjection(
                        "b_identity",
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType(),
                        true)),
                rootQuery.hiddenKeyProjections());
        assertEquals(new RowMaterializationRuntimePlan.ParentKeySource(
                        new RowMaterializationPlan.RowKey(
                                "b_identity",
                                new QueryTemplatePlan.ColumnRef("b", id),
                                new TIntType()),
                        RowMaterializationRuntimePlan.ParentKeySource.Kind.ROOT_IDENTITY),
                relationQuery.parentKeySource());
        assertEquals(new RowMaterializationPlan.RowKey(
                        "r_key",
                        new QueryTemplatePlan.ColumnRef("r", bookId),
                        new TIntType()),
                relationQuery.childPredicateKey());
        assertEquals(List.of(
                        new RowMaterializationRuntimePlan.RowFieldProjection(
                                "bookId",
                                new QueryTemplatePlan.ColumnRef("r", bookId),
                                new TIntType(),
                                false),
                        new RowMaterializationRuntimePlan.RowFieldProjection(
                                "body",
                                new QueryTemplatePlan.ColumnRef("r", body),
                                new TTextType(),
                                false)),
                relationQuery.fields());
        assertEquals(List.of(new RowMaterializationRuntimePlan.RowFieldProjection(
                        "r_key",
                        new QueryTemplatePlan.ColumnRef("r", bookId),
                        new TIntType(),
                        true)),
                relationQuery.hiddenKeyProjections());
        assertTrue(postgresRoot.contains(
                "SELECT \"books\".\"id\" AS \"id\", \"books\".\"title\" AS \"title\", \"books\".\"id\" AS \"b_identity\" FROM \"books\""));
        assertTrue(postgresRoot.contains("\"books\".\"id\" = p_book_id"));
        assertTrue(postgresReviews.contains(
                "SELECT \"reviews\".\"book_id\" AS \"bookId\", \"reviews\".\"body\" AS \"body\", \"reviews\".\"book_id\" AS \"r_key\" FROM \"reviews\""));
        assertTrue(postgresReviews.contains("\"reviews\".\"book_id\" = p_book_id"));
        assertTrue(mysqlRoot.contains(
                "SELECT `books`.`id` AS `id`, `books`.`title` AS `title`, `books`.`id` AS `b_identity` FROM `books`"));
        assertTrue(mysqlRoot.contains("`books`.`id` = p_book_id"));
        assertTrue(mysqlReviews.contains(
                "SELECT `reviews`.`book_id` AS `bookId`, `reviews`.`body` AS `body`, `reviews`.`book_id` AS `r_key` FROM `reviews`"));
        assertTrue(mysqlReviews.contains("`reviews`.`book_id` = p_book_id"));
    }

    @Test
    void runtimeShapePreservesRootPolicyPredicates() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");
        QueryTemplatePlan.StructuralIdentifier visibility = compilerKnown(COLUMN, "visibility");
        QueryTemplatePlan plan = new QueryTemplatePlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                List.of(new QueryTemplatePlan.Projection(new QueryTemplatePlan.ColumnRef("b", title), "title")),
                List.of(
                        new QueryTemplatePlan.ParameterPredicate(
                                new QueryTemplatePlan.ColumnRef("b", id),
                                EQ,
                                new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType())),
                        new QueryTemplatePlan.ParameterPredicate(
                                new QueryTemplatePlan.ColumnRef("b", visibility),
                                EQ,
                                new QueryTemplatePlan.RuntimeParameter("p_viewer_role", new TTextType()))),
                List.of());
        RowMaterializationPlan rows = RowMaterializationPlan.fromQueryTemplate(
                plan,
                new QueryTemplatePlan.ColumnRef("b", id),
                new TIntType(),
                Map.of(
                        new QueryTemplatePlan.ColumnRef("b", id), new TIntType(),
                        new QueryTemplatePlan.ColumnRef("b", title), new TTextType(),
                        new QueryTemplatePlan.ColumnRef("b", visibility), new TTextType()));

        RowMaterializationRuntimePlan runtimePlan = RowMaterializationRuntimePlan.from(
                rows,
                new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType()));

        String postgresRoot = new PostgreSqlEmitter().visitSelectSql(runtimePlan.rootQuery().select());
        String mysqlRoot = new MySqlEmitter().visitSelectSql(runtimePlan.rootQuery().select());
        assertTrue(postgresRoot.contains("\"books\".\"id\" = p_book_id"));
        assertTrue(postgresRoot.contains("\"books\".\"visibility\" = p_viewer_role"));
        assertTrue(mysqlRoot.contains("`books`.`id` = p_book_id"));
        assertTrue(mysqlRoot.contains("`books`.`visibility` = p_viewer_role"));
    }

    @Test
    void runtimeShapePreservesCompilerKnownSchemas() {
        QueryTemplatePlan.StructuralIdentifier app = compilerKnown(SCHEMA, "app");
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(app, books, "b"),
                new RowMaterializationPlan.RowKey(
                        "b_identity",
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType()),
                List.of(new RowMaterializationPlan.FieldBinding(
                        "title",
                        new QueryTemplatePlan.ColumnRef("b", title),
                        new TTextType())),
                List.of());

        RowMaterializationRuntimePlan runtimePlan = RowMaterializationRuntimePlan.from(
                rows,
                new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType()));

        String postgresRoot = new PostgreSqlEmitter().visitSelectSql(runtimePlan.rootQuery().select());
        assertTrue(postgresRoot.contains(
                "SELECT \"app\".\"books\".\"title\" AS \"title\", \"app\".\"books\".\"id\" AS \"b_identity\" FROM \"app\".\"books\""));
        assertTrue(postgresRoot.contains("\"app\".\"books\".\"id\" = p_book_id"));
    }

    @Test
    void runtimeShapeRejectsGeneratedRowKeyAliasCollision() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                new RowMaterializationPlan.RowKey(
                        "b_identity",
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType()),
                List.of(
                        new RowMaterializationPlan.FieldBinding(
                                "id",
                                new QueryTemplatePlan.ColumnRef("b", id),
                                new TIntType()),
                        new RowMaterializationPlan.FieldBinding(
                                "b_identity",
                                new QueryTemplatePlan.ColumnRef("b", title),
                                new TTextType())),
                List.of());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType())));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("row-key alias 'b_identity'"));
        assertTrue(exception.getMessage().contains("conflicts"));
    }

    @Test
    void runtimeShapeRejectsDuplicateProjectedAliasesBeforeSqlEmission() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");
        QueryTemplatePlan.StructuralIdentifier slug = compilerKnown(COLUMN, "slug");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                new RowMaterializationPlan.RowKey(
                        "b_identity",
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType()),
                List.of(
                        new RowMaterializationPlan.FieldBinding(
                                "label",
                                new QueryTemplatePlan.ColumnRef("b", title),
                                new TTextType()),
                        new RowMaterializationPlan.FieldBinding(
                                "label",
                                new QueryTemplatePlan.ColumnRef("b", slug),
                                new TTextType())),
                List.of());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType())));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("field alias 'label'"));
        assertTrue(exception.getMessage().contains("not unique"));
    }

    @Test
    void runtimeShapeSupportsRelationThatUsesMaterializedRootFieldAsParentKey() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier authors = compilerKnown(TABLE, "authors");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier authorId = compilerKnown(COLUMN, "author_id");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                new RowMaterializationPlan.RowKey(
                        "b_identity",
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType()),
                List.of(),
                List.of(new RowMaterializationPlan.RelationRows(
                        "book_author",
                        new QueryTemplatePlan.TableRef(null, authors, "a"),
                        new RowMaterializationPlan.RowKey(
                                "b_author_key",
                                new QueryTemplatePlan.ColumnRef("b", authorId),
                                new TIntType()),
                        new RowMaterializationPlan.RowKey(
                                "a_key",
                                new QueryTemplatePlan.ColumnRef("a", id),
                                new TIntType()),
                        ONE,
                        List.of())));

        RowMaterializationRuntimePlan runtimePlan = RowMaterializationRuntimePlan.from(
                rows,
                new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType()));
        RowMaterializationRuntimePlan.MaterializedRelationQuery relation =
                runtimePlan.relationQueries().getFirst();
        String postgresRoot = new PostgreSqlEmitter().visitSelectSql(runtimePlan.rootQuery().select());
        String postgresAuthor = new PostgreSqlEmitter().visitSelectSql(relation.select());
        String mysqlRoot = new MySqlEmitter().visitSelectSql(runtimePlan.rootQuery().select());
        String mysqlAuthor = new MySqlEmitter().visitSelectSql(relation.select());

        assertEquals(new RowMaterializationRuntimePlan.ParentKeySource(
                        new RowMaterializationPlan.RowKey(
                                "b_author_key",
                                new QueryTemplatePlan.ColumnRef("b", authorId),
                                new TIntType()),
                        RowMaterializationRuntimePlan.ParentKeySource.Kind.ROOT_HIDDEN_KEY),
                relation.parentKeySource());
        assertEquals(new RowMaterializationRuntimePlan.ParentKeyCarrier(
                        "p_book_author_parent_key",
                        new TIntType()),
                relation.parentKeyCarrier());
        assertEquals(List.of(
                        new RowMaterializationRuntimePlan.RowFieldProjection(
                                "b_identity",
                                new QueryTemplatePlan.ColumnRef("b", id),
                                new TIntType(),
                                true),
                        new RowMaterializationRuntimePlan.RowFieldProjection(
                                "b_author_key",
                                new QueryTemplatePlan.ColumnRef("b", authorId),
                                new TIntType(),
                                true)),
                runtimePlan.rootQuery().hiddenKeyProjections());
        assertEquals(
                new RowMaterializationRuntimePlan.CollectedParentKeys(
                        "book_author",
                        relation.parentKeySource(),
                        Arrays.asList(11, null, 11, 12),
                        List.of(11, 12)),
                RowMaterializationRuntimePlan.collectParentKeys(
                        relation,
                        List.of(
                                Map.of("b_author_key", 11),
                                nullableMap("b_author_key", null),
                                Map.of("b_author_key", 11),
                                Map.of("b_author_key", 12))));
        assertTrue(postgresRoot.contains(
                "SELECT \"books\".\"id\" AS \"b_identity\", \"books\".\"author_id\" AS \"b_author_key\" FROM \"books\""));
        assertTrue(postgresAuthor.contains("SELECT \"authors\".\"id\" AS \"a_key\" FROM \"authors\""));
        assertTrue(postgresAuthor.contains("\"authors\".\"id\" = p_book_author_parent_key"));
        assertTrue(mysqlRoot.contains(
                "SELECT `books`.`id` AS `b_identity`, `books`.`author_id` AS `b_author_key` FROM `books`"));
        assertTrue(mysqlAuthor.contains("SELECT `authors`.`id` AS `a_key` FROM `authors`"));
        assertTrue(mysqlAuthor.contains("`authors`.`id` = p_book_author_parent_key"));
    }

    @Test
    void parentKeyCollectionRejectsMissingRootProjection() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier authors = compilerKnown(TABLE, "authors");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier authorId = compilerKnown(COLUMN, "author_id");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                new RowMaterializationPlan.RowKey(
                        "b_identity",
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType()),
                List.of(),
                List.of(new RowMaterializationPlan.RelationRows(
                        "book_author",
                        new QueryTemplatePlan.TableRef(null, authors, "a"),
                        new RowMaterializationPlan.RowKey(
                                "b_author_key",
                                new QueryTemplatePlan.ColumnRef("b", authorId),
                                new TIntType()),
                        new RowMaterializationPlan.RowKey(
                                "a_key",
                                new QueryTemplatePlan.ColumnRef("a", id),
                                new TIntType()),
                        ONE,
                        List.of())));
        RowMaterializationRuntimePlan runtimePlan = RowMaterializationRuntimePlan.from(
                rows,
                new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType()));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.collectParentKeys(
                        runtimePlan.relationQueries().getFirst(),
                        List.of(Map.of("b_identity", 1))));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("parent-key projection 'b_author_key'"));
        assertTrue(exception.getMessage().contains("book_author"));
    }

    @Test
    void runtimeShapeRejectsHiddenParentKeyAliasCollision() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier authors = compilerKnown(TABLE, "authors");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier authorId = compilerKnown(COLUMN, "author_id");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                new RowMaterializationPlan.RowKey(
                        "b_key",
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType()),
                List.of(),
                List.of(new RowMaterializationPlan.RelationRows(
                        "book_author",
                        new QueryTemplatePlan.TableRef(null, authors, "a"),
                        new RowMaterializationPlan.RowKey(
                                "b_key",
                                new QueryTemplatePlan.ColumnRef("b", authorId),
                                new TIntType()),
                        new RowMaterializationPlan.RowKey(
                                "a_key",
                                new QueryTemplatePlan.ColumnRef("a", id),
                                new TIntType()),
                        ONE,
                        List.of())));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType())));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("row-key alias 'b_key'"));
        assertTrue(exception.getMessage().contains("conflicts with hidden key projection"));
    }

    @Test
    void runtimeShapeRejectsRootParameterTypeMismatch() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                new RowMaterializationPlan.RowKey(
                        "b_identity",
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType()),
                List.of(),
                List.of());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_book_id", new TTextType())));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("root parameter type"));
    }

    @Test
    void rowMaterializationRejectsNonRootRuntimePredicates() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier reviews = compilerKnown(TABLE, "reviews");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier body = compilerKnown(COLUMN, "body");
        QueryTemplatePlan plan = new QueryTemplatePlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                List.of(),
                List.of(new QueryTemplatePlan.ParameterPredicate(
                        new QueryTemplatePlan.ColumnRef("r", body),
                        EQ,
                        new QueryTemplatePlan.RuntimeParameter("p_body", new TTextType()))),
                List.of(new QueryTemplatePlan.RelationEdge(
                        "book_reviews",
                        new QueryTemplatePlan.TableRef(null, books, "b"),
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new QueryTemplatePlan.TableRef(null, reviews, "r"),
                        new QueryTemplatePlan.ColumnRef("r", id),
                        MANY)));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationPlan.fromQueryTemplate(
                        plan,
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType(),
                        Map.of(
                                new QueryTemplatePlan.ColumnRef("b", id), new TIntType(),
                                new QueryTemplatePlan.ColumnRef("r", id), new TIntType(),
                                new QueryTemplatePlan.ColumnRef("r", body), new TTextType())));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("predicate alias"));
        assertTrue(exception.getMessage().contains("root alias"));
    }

    @Test
    void rowMaterializationRejectsRuntimePredicateTypeMismatch() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier visibility = compilerKnown(COLUMN, "visibility");
        QueryTemplatePlan plan = new QueryTemplatePlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                List.of(),
                List.of(new QueryTemplatePlan.ParameterPredicate(
                        new QueryTemplatePlan.ColumnRef("b", visibility),
                        EQ,
                        new QueryTemplatePlan.RuntimeParameter("p_viewer_role", new TIntType()))),
                List.of());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationPlan.fromQueryTemplate(
                        plan,
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType(),
                        Map.of(
                                new QueryTemplatePlan.ColumnRef("b", id), new TIntType(),
                                new QueryTemplatePlan.ColumnRef("b", visibility), new TTextType())));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("predicate parameter type"));
    }

    @Test
    void snapshotsFieldCollectionsAtConstruction() {
        ArrayList<RowMaterializationPlan.FieldBinding> fields = new ArrayList<>();
        fields.add(new RowMaterializationPlan.FieldBinding(
                "title",
                new QueryTemplatePlan.ColumnRef("b", compilerKnown(COLUMN, "title")),
                new TTextType()));

        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, compilerKnown(TABLE, "books"), "b"),
                new RowMaterializationPlan.RowKey(
                        "b_identity",
                        new QueryTemplatePlan.ColumnRef("b", compilerKnown(COLUMN, "id")),
                        new TIntType()),
                fields,
                List.of());
        fields.clear();

        assertEquals(1, rows.rootFields().size());
        assertThrows(UnsupportedOperationException.class, () -> rows.rootFields().clear());
    }

    @Test
    void rejectsProjectionAliasesOutsideRootOrDeclaredRelations() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier name = compilerKnown(COLUMN, "name");
        QueryTemplatePlan plan = new QueryTemplatePlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                List.of(new QueryTemplatePlan.Projection(new QueryTemplatePlan.ColumnRef("a", name), "author")),
                List.of(),
                List.of());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationPlan.fromQueryTemplate(
                        plan,
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType(),
                        Map.of(
                                new QueryTemplatePlan.ColumnRef("b", id), new TIntType(),
                                new QueryTemplatePlan.ColumnRef("a", name), new TTextType())));

        assertTrue(exception.getMessage().contains("root or a declared relation"));
    }

    @Test
    void rejectsRuntimeStructuralIdentifiersThroughQueryTemplateBoundary() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new QueryTemplatePlan.ColumnRef(
                        "b",
                        QueryTemplatePlan.StructuralIdentifier.runtimeValue(COLUMN, "p_requested_column")));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("compiler-known"));
    }

    @Test
    void rejectsMismatchedRelationKeyAliases() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier reviews = compilerKnown(TABLE, "reviews");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier bookId = compilerKnown(COLUMN, "book_id");
        QueryTemplatePlan plan = new QueryTemplatePlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                List.of(),
                List.of(),
                List.of(new QueryTemplatePlan.RelationEdge(
                        "book_reviews",
                        new QueryTemplatePlan.TableRef(null, books, "b"),
                        new QueryTemplatePlan.ColumnRef("wrong", id),
                        new QueryTemplatePlan.TableRef(null, reviews, "r"),
                        new QueryTemplatePlan.ColumnRef("r", bookId),
                        MANY)));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationPlan.fromQueryTemplate(
                        plan,
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType(),
                        Map.of(
                                new QueryTemplatePlan.ColumnRef("b", id), new TIntType(),
                                new QueryTemplatePlan.ColumnRef("wrong", id), new TIntType(),
                                new QueryTemplatePlan.ColumnRef("r", bookId), new TIntType())));

        assertTrue(exception.getMessage().contains("relation parent key alias"));
    }

    @Test
    void rejectsMaterializedFieldsWithoutTypes() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");
        QueryTemplatePlan plan = new QueryTemplatePlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                List.of(new QueryTemplatePlan.Projection(new QueryTemplatePlan.ColumnRef("b", title), "title")),
                List.of(),
                List.of());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationPlan.fromQueryTemplate(
                        plan,
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType(),
                        Map.of(new QueryTemplatePlan.ColumnRef("b", id), new TIntType())));

        assertTrue(exception.getMessage().contains("must have a row field type"));
    }

    @Test
    void rejectsRelationParentAliasesOutsideRootOrEarlierRelations() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier reviews = compilerKnown(TABLE, "reviews");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier bookId = compilerKnown(COLUMN, "book_id");
        QueryTemplatePlan plan = new QueryTemplatePlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                List.of(),
                List.of(),
                List.of(new QueryTemplatePlan.RelationEdge(
                        "book_reviews",
                        new QueryTemplatePlan.TableRef(null, books, "missing_parent"),
                        new QueryTemplatePlan.ColumnRef("missing_parent", id),
                        new QueryTemplatePlan.TableRef(null, reviews, "r"),
                        new QueryTemplatePlan.ColumnRef("r", bookId),
                        MANY)));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RowMaterializationPlan.fromQueryTemplate(
                        plan,
                        new QueryTemplatePlan.ColumnRef("b", id),
                        new TIntType(),
                        Map.of(
                                new QueryTemplatePlan.ColumnRef("b", id), new TIntType(),
                                new QueryTemplatePlan.ColumnRef("missing_parent", id), new TIntType(),
                                new QueryTemplatePlan.ColumnRef("r", bookId), new TIntType())));

        assertTrue(exception.getMessage().contains("root or an earlier relation"));
    }

    @Test
    void directConstructionRejectsRelationParentAliasesOutsideRootOrEarlierRelations() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier reviews = compilerKnown(TABLE, "reviews");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier bookId = compilerKnown(COLUMN, "book_id");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new RowMaterializationPlan(
                        new QueryTemplatePlan.TableRef(null, books, "b"),
                        new RowMaterializationPlan.RowKey(
                                "b_identity",
                                new QueryTemplatePlan.ColumnRef("b", id),
                                new TIntType()),
                        List.of(),
                        List.of(new RowMaterializationPlan.RelationRows(
                                "book_reviews",
                                new QueryTemplatePlan.TableRef(null, reviews, "r"),
                                new RowMaterializationPlan.RowKey(
                                        "unknown_key",
                                        new QueryTemplatePlan.ColumnRef("unknown", id),
                                        new TIntType()),
                                new RowMaterializationPlan.RowKey(
                                        "r_key",
                                        new QueryTemplatePlan.ColumnRef("r", bookId),
                                        new TIntType()),
                                MANY,
                                List.of()))));

        assertTrue(exception.getMessage().contains("root or an earlier relation"));
    }

    @Test
    void rejectsRelationKeyTypeMismatch() {
        QueryTemplatePlan.StructuralIdentifier reviews = compilerKnown(TABLE, "reviews");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier bookId = compilerKnown(COLUMN, "book_id");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new RowMaterializationPlan.RelationRows(
                        "book_reviews",
                        new QueryTemplatePlan.TableRef(null, reviews, "r"),
                        new RowMaterializationPlan.RowKey(
                                "b_key",
                                new QueryTemplatePlan.ColumnRef("b", id),
                                new TIntType()),
                        new RowMaterializationPlan.RowKey(
                                "r_key",
                                new QueryTemplatePlan.ColumnRef("r", bookId),
                                new TTextType()),
                        MANY,
                        List.of()));

        assertTrue(exception.getMessage().contains("relation key types must match"));
    }

    private static QueryTemplatePlan.StructuralIdentifier compilerKnown(
            QueryTemplatePlan.IdentifierKind kind,
            String value
    ) {
        return QueryTemplatePlan.StructuralIdentifier.compilerKnown(kind, value);
    }

    private static Map<String, Object> nullableMap(String key, Object value) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
