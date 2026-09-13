package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.COLUMN;
import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.SCHEMA;
import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.TABLE;
import static io.titan.transpiler.tir.QueryTemplatePlan.PredicateOperator.EQ;
import static io.titan.transpiler.tir.QueryTemplatePlan.PredicateOperator.GT;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.MANY;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.ONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryTemplatePlanTest {

    @Test
    void modelsCompilerKnownStructureAndParameterizedRuntimeValues() {
        QueryTemplatePlan.TableRef books = new QueryTemplatePlan.TableRef(
                compilerKnown(SCHEMA, "public"),
                compilerKnown(TABLE, "books"),
                "b");
        QueryTemplatePlan.ColumnRef id = new QueryTemplatePlan.ColumnRef("b", compilerKnown(COLUMN, "id"));
        QueryTemplatePlan.ColumnRef title = new QueryTemplatePlan.ColumnRef("b", compilerKnown(COLUMN, "title"));
        QueryTemplatePlan.RuntimeParameter bookId = new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType());

        QueryTemplatePlan plan = new QueryTemplatePlan(
                books,
                List.of(new QueryTemplatePlan.Projection(title, "title")),
                List.of(new QueryTemplatePlan.ParameterPredicate(
                        id,
                        QueryTemplatePlan.PredicateOperator.EQ,
                        bookId)),
                List.of());

        assertEquals("books", plan.root().table().value());
        assertEquals("title", plan.projections().getFirst().column().column().value());
        assertEquals("p_book_id", plan.predicates().getFirst().parameter().name());
        assertEquals(new TIntType(), plan.predicates().getFirst().parameter().type());
    }

    @Test
    void modelsRelationEdgesWithCompilerKnownColumns() {
        QueryTemplatePlan.TableRef books = new QueryTemplatePlan.TableRef(
                null,
                compilerKnown(TABLE, "books"),
                "b");
        QueryTemplatePlan.TableRef reviews = new QueryTemplatePlan.TableRef(
                null,
                compilerKnown(TABLE, "reviews"),
                "r");
        QueryTemplatePlan.RelationEdge edge = new QueryTemplatePlan.RelationEdge(
                "book_reviews",
                books,
                new QueryTemplatePlan.ColumnRef("b", compilerKnown(COLUMN, "id")),
                reviews,
                new QueryTemplatePlan.ColumnRef("r", compilerKnown(COLUMN, "book_id")),
                QueryTemplatePlan.RelationCardinality.MANY);

        assertEquals("book_reviews", edge.name());
        assertEquals(QueryTemplatePlan.RelationCardinality.MANY, edge.cardinality());
        assertEquals("book_id", edge.toColumn().column().value());
    }

    @Test
    void rejectsRuntimeSelectedTableIdentifiers() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> QueryTemplatePlan.StructuralIdentifier.runtimeValue(TABLE, "p_table_name"));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("table"));
        assertTrue(exception.getMessage().contains("compiler-known"));
    }

    @Test
    void rejectsRuntimeSelectedColumnIdentifiers() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new QueryTemplatePlan.ColumnRef(
                        "b",
                        QueryTemplatePlan.StructuralIdentifier.runtimeValue(COLUMN, "p_column_name")));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("column"));
        assertTrue(exception.getMessage().contains("compiler-known"));
    }

    @Test
    void doesNotAllowParameterValuesToMasqueradeAsStructuralReferences() {
        QueryTemplatePlan.RuntimeParameter requestedColumn =
                new QueryTemplatePlan.RuntimeParameter("p_requested_column", new TTextType());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> QueryTemplatePlan.StructuralIdentifier.runtimeValue(
                        COLUMN,
                        requestedColumn.name()));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
    }

    @Test
    void snapshotsChildCollectionsAtConstruction() {
        ArrayList<QueryTemplatePlan.Projection> projections = new ArrayList<>();
        projections.add(new QueryTemplatePlan.Projection(
                new QueryTemplatePlan.ColumnRef("b", compilerKnown(COLUMN, "title")),
                "title"));

        QueryTemplatePlan plan = new QueryTemplatePlan(
                new QueryTemplatePlan.TableRef(null, compilerKnown(TABLE, "books"), "b"),
                projections,
                List.of(),
                List.of());
        projections.clear();

        assertEquals(1, plan.projections().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.projections().clear());
    }

    @Test
    void structuralIdentifiersUseValueEqualityInsidePlanRecords() {
        QueryTemplatePlan first = new QueryTemplatePlan(
                new QueryTemplatePlan.TableRef(null, compilerKnown(TABLE, "books"), "b"),
                List.of(new QueryTemplatePlan.Projection(
                        new QueryTemplatePlan.ColumnRef("b", compilerKnown(COLUMN, "title")),
                        "title")),
                List.of(),
                List.of());
        QueryTemplatePlan second = new QueryTemplatePlan(
                new QueryTemplatePlan.TableRef(null, compilerKnown(TABLE, "books"), "b"),
                List.of(new QueryTemplatePlan.Projection(
                        new QueryTemplatePlan.ColumnRef("b", compilerKnown(COLUMN, "title")),
                        "title")),
                List.of(),
                List.of());

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void rejectsIdentifierKindMismatch() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new QueryTemplatePlan.ColumnRef("b", compilerKnown(TABLE, "books")));

        assertTrue(exception.getMessage().contains("Expected query-template column identifier"));
    }

    @Test
    void buildsPointRootPlanFromSpecializedDescriptorRecords() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier reviews = compilerKnown(TABLE, "reviews");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");
        QueryTemplatePlan.StructuralIdentifier body = compilerKnown(COLUMN, "body");
        QueryTemplatePlan.StructuralIdentifier bookId = compilerKnown(COLUMN, "book_id");
        QueryTemplateDescriptorPlanBuilder.RootDescriptor root =
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("book", books, "b", false);

        QueryTemplatePlan plan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                root,
                List.of(
                        new QueryTemplateDescriptorPlanBuilder.FieldDescriptor("title", books, "b", title, "title"),
                        new QueryTemplateDescriptorPlanBuilder.FieldDescriptor("reviews", reviews, "r", body, "reviews")),
                List.of(new QueryTemplateDescriptorPlanBuilder.RuntimePredicate(
                        books,
                        "b",
                        id,
                        EQ,
                        new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType()))),
                List.of(new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                        "book_reviews",
                        books,
                        "b",
                        id,
                        reviews,
                        bookId,
                        "r",
                        MANY)));

        assertEquals("books", plan.root().table().value());
        assertEquals("b", plan.root().alias());
        assertEquals(List.of(
                        new QueryTemplatePlan.Projection(new QueryTemplatePlan.ColumnRef("b", title), "title"),
                        new QueryTemplatePlan.Projection(new QueryTemplatePlan.ColumnRef("r", body), "reviews")),
                plan.projections());
        assertEquals(new QueryTemplatePlan.ParameterPredicate(
                        new QueryTemplatePlan.ColumnRef("b", id),
                        EQ,
                        new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType())),
                plan.predicates().getFirst());
        assertEquals(MANY, plan.relationEdges().getFirst().cardinality());
        assertEquals(new QueryTemplatePlan.ColumnRef("b", id), plan.relationEdges().getFirst().fromColumn());
        assertEquals(new QueryTemplatePlan.ColumnRef("r", bookId), plan.relationEdges().getFirst().toColumn());
    }

    @Test
    void buildsRelationLocalOrderMetadataFromDescriptorRecords() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier reviews = compilerKnown(TABLE, "reviews");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier bookId = compilerKnown(COLUMN, "book_id");
        QueryTemplatePlan.StructuralIdentifier body = compilerKnown(COLUMN, "body");
        QueryTemplatePlan.StructuralIdentifier position = compilerKnown(COLUMN, "position");

        QueryTemplatePlan plan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("books", books, "b", true),
                List.of(new QueryTemplateDescriptorPlanBuilder.FieldDescriptor("reviews", reviews, "r", body, "body")),
                List.of(),
                List.of(new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                        "book_reviews",
                        books,
                        "b",
                        id,
                        reviews,
                        bookId,
                        "r",
                        MANY,
                        List.of(new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                reviews,
                                "r",
                                position,
                                SortDirection.ASC)))));

        assertEquals(List.of(new QueryTemplatePlan.OrderKey(
                        new QueryTemplatePlan.ColumnRef("r", position),
                        SortDirection.ASC)),
                plan.relationEdges().getFirst().orderKeys());

        IllegalArgumentException wrongAlias = assertThrows(
                IllegalArgumentException.class,
                () -> QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                        new QueryTemplateDescriptorPlanBuilder.RootDescriptor("books", books, "b", true),
                        List.of(new QueryTemplateDescriptorPlanBuilder.FieldDescriptor("reviews", reviews, "r", body, "body")),
                        List.of(),
                        List.of(new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                                "book_reviews",
                                books,
                                "b",
                                id,
                                reviews,
                                bookId,
                                "r",
                                MANY,
                                List.of(new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                        books,
                                        "b",
                                        id,
                                        SortDirection.ASC))))));
        assertTrue(wrongAlias.getMessage().contains("TITAN-E001"));
        assertTrue(wrongAlias.getMessage().contains("relation order key alias"));
        assertTrue(wrongAlias.getMessage().contains("book_reviews"));
    }

    @Test
    void buildsListRootPlanWithoutPointLookupPredicate() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");
        QueryTemplateDescriptorPlanBuilder.RootDescriptor root =
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("books", books, "b", true);

        QueryTemplatePlan plan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                root,
                List.of(new QueryTemplateDescriptorPlanBuilder.FieldDescriptor("title", books, "b", title, "title")),
                List.of(),
                List.of());

        assertTrue(plan.root().table().origin() == QueryTemplatePlan.IdentifierOrigin.COMPILER_KNOWN);
        assertEquals(QueryTemplatePlan.RootKind.LIST, plan.rootKind());
        assertTrue(plan.predicates().isEmpty());
        assertEquals("title", plan.projections().getFirst().outputKey());
    }

    @Test
    void buildsListRootPlanWithOrderAndCursorWindowMetadata() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");
        QueryTemplatePlan.StructuralIdentifier visibility = compilerKnown(COLUMN, "visibility");
        QueryTemplateDescriptorPlanBuilder.RootDescriptor root =
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("books", books, "b", true);

        QueryTemplatePlan plan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                root,
                List.of(new QueryTemplateDescriptorPlanBuilder.FieldDescriptor("title", books, "b", title, "title")),
                List.of(new QueryTemplateDescriptorPlanBuilder.RuntimePredicate(
                        books,
                        "b",
                        visibility,
                        EQ,
                        new QueryTemplatePlan.RuntimeParameter("p_viewer_role", new TTextType()))),
                List.of(),
                List.of(
                        new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(books, "b", title, SortDirection.ASC),
                        new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(books, "b", id, SortDirection.ASC)),
                new QueryTemplateDescriptorPlanBuilder.CursorWindowDescriptor(
                        books,
                        "b",
                        id,
                        GT,
                        new QueryTemplatePlan.RuntimeParameter("p_after_course_id", new TIntType()),
                        20));

        assertEquals(QueryTemplatePlan.RootKind.LIST, plan.rootKind());
        assertEquals("visibility", plan.predicates().getFirst().column().column().value());
        assertEquals(List.of("title", "id"), plan.orderKeys().stream()
                .map(orderKey -> orderKey.column().column().value())
                .toList());
        assertEquals(SortDirection.ASC, plan.orderKeys().getFirst().direction());
        assertEquals("p_after_course_id", plan.cursorWindow().cursorParameter().name());
        assertEquals(20, plan.cursorWindow().limit());
    }

    @Test
    void rejectsRuntimeSelectedDescriptorIdentifiersBeforePlanConstruction() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new QueryTemplateDescriptorPlanBuilder.FieldDescriptor(
                        "selected",
                        compilerKnown(TABLE, "books"),
                        "b",
                        QueryTemplatePlan.StructuralIdentifier.runtimeValue(COLUMN, "p_requested_column"),
                        "selected"));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("compiler-known"));
    }

    @Test
    void rejectsProjectionTablesThatAreNotRootOrDeclaredRelations() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier authors = compilerKnown(TABLE, "authors");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier name = compilerKnown(COLUMN, "name");
        QueryTemplateDescriptorPlanBuilder.RootDescriptor root =
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("book", books, "b", false);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                        root,
                        List.of(new QueryTemplateDescriptorPlanBuilder.FieldDescriptor(
                                "author",
                                authors,
                                "a",
                                name,
                                "author")),
                        List.of(new QueryTemplateDescriptorPlanBuilder.RuntimePredicate(
                                books,
                                "b",
                                id,
                                EQ,
                                new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType()))),
                        List.of()));

        assertTrue(exception.getMessage().contains("root table or a declared relation table"));
    }

    @Test
    void preservesRequiredOneRelationCardinality() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier authors = compilerKnown(TABLE, "authors");
        QueryTemplatePlan.StructuralIdentifier authorId = compilerKnown(COLUMN, "author_id");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier name = compilerKnown(COLUMN, "name");
        QueryTemplateDescriptorPlanBuilder.RootDescriptor root =
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("book", books, "b", false);

        QueryTemplatePlan plan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                root,
                List.of(new QueryTemplateDescriptorPlanBuilder.FieldDescriptor("author", authors, "a", name, "author")),
                List.of(new QueryTemplateDescriptorPlanBuilder.RuntimePredicate(
                        books,
                        "b",
                        id,
                        EQ,
                        new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType()))),
                List.of(new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                        "book_author",
                        books,
                        "b",
                        authorId,
                        authors,
                        id,
                        "a",
                        ONE)));

        assertEquals(ONE, plan.relationEdges().getFirst().cardinality());
        assertEquals(new QueryTemplatePlan.ColumnRef("a", name), plan.projections().getFirst().column());
    }

    @Test
    void disambiguatesRepeatedTablesByCompilerKnownAliases() {
        QueryTemplatePlan.StructuralIdentifier people = compilerKnown(TABLE, "people");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier managerId = compilerKnown(COLUMN, "manager_id");
        QueryTemplatePlan.StructuralIdentifier name = compilerKnown(COLUMN, "name");
        QueryTemplateDescriptorPlanBuilder.RootDescriptor root =
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("employee", people, "employee", false);

        QueryTemplatePlan plan = QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                root,
                List.of(new QueryTemplateDescriptorPlanBuilder.FieldDescriptor(
                        "manager",
                        people,
                        "manager",
                        name,
                        "manager")),
                List.of(new QueryTemplateDescriptorPlanBuilder.RuntimePredicate(
                        people,
                        "employee",
                        id,
                        EQ,
                        new QueryTemplatePlan.RuntimeParameter("p_employee_id", new TIntType()))),
                List.of(new QueryTemplateDescriptorPlanBuilder.RelationDescriptor(
                        "employee_manager",
                        people,
                        "employee",
                        managerId,
                        people,
                        id,
                        "manager",
                        ONE)));

        assertEquals(new QueryTemplatePlan.ColumnRef("manager", name), plan.projections().getFirst().column());
        assertEquals(new QueryTemplatePlan.ColumnRef("employee", managerId), plan.relationEdges().getFirst().fromColumn());
        assertEquals(new QueryTemplatePlan.ColumnRef("manager", id), plan.relationEdges().getFirst().toColumn());
    }

    @Test
    void rejectsUnsafeDescriptorAliases() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new QueryTemplateDescriptorPlanBuilder.RootDescriptor("book", books, "b;drop", false));

        assertTrue(exception.getMessage().contains("compiler-known SQL alias"));
    }

    @Test
    void rejectsPointRootWithoutLookupPredicate() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");
        QueryTemplateDescriptorPlanBuilder.RootDescriptor root =
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("book", books, "b", false);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                        root,
                        List.of(new QueryTemplateDescriptorPlanBuilder.FieldDescriptor("title", books, "b", title, "title")),
                        List.of(),
                        List.of()));

        assertTrue(exception.getMessage().contains("point root must carry a lookup predicate"));
    }

    @Test
    void rejectsOrderAndWindowMetadataOnPointRoots() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");
        QueryTemplateDescriptorPlanBuilder.RootDescriptor root =
                new QueryTemplateDescriptorPlanBuilder.RootDescriptor("book", books, "b", false);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> QueryTemplateDescriptorPlanBuilder.buildRootPlan(
                        root,
                        List.of(new QueryTemplateDescriptorPlanBuilder.FieldDescriptor("title", books, "b", title, "title")),
                        List.of(new QueryTemplateDescriptorPlanBuilder.RuntimePredicate(
                                books,
                                "b",
                                id,
                                EQ,
                                new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType()))),
                        List.of(),
                        List.of(new QueryTemplateDescriptorPlanBuilder.OrderDescriptor(
                                books,
                                "b",
                                title,
                                SortDirection.ASC)),
                        null));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("list root"));
    }

    @Test
    void rejectsDirectPointRootOrderMetadata() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new QueryTemplatePlan(
                        new QueryTemplatePlan.TableRef(null, books, "b"),
                        QueryTemplatePlan.RootKind.POINT,
                        List.of(new QueryTemplatePlan.Projection(
                                new QueryTemplatePlan.ColumnRef("b", title),
                                "title")),
                        List.of(),
                        List.of(),
                        List.of(new QueryTemplatePlan.OrderKey(
                                new QueryTemplatePlan.ColumnRef("b", title),
                                SortDirection.ASC)),
                        null));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("order metadata"));
        assertTrue(exception.getMessage().contains("list root"));
    }

    @Test
    void rejectsRuntimeSelectedCursorStructuralIdentifier() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new QueryTemplateDescriptorPlanBuilder.CursorWindowDescriptor(
                        books,
                        "b",
                        QueryTemplatePlan.StructuralIdentifier.runtimeValue(COLUMN, "p_sort_column"),
                        GT,
                        new QueryTemplatePlan.RuntimeParameter("p_after", new TIntType()),
                        10));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("compiler-known"));
    }

    @Test
    void rejectsUnsupportedRootExpressionFunctionsBeforeRuntimePlanning() {
        QueryTemplatePlan.StructuralIdentifier title = compilerKnown(COLUMN, "title");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new QueryTemplatePlan.FunctionValue(
                        "runtime_sort_path",
                        List.of(new QueryTemplatePlan.ColumnValue(
                                new QueryTemplatePlan.ColumnRef("b", title),
                                new TTextType())),
                        new TTextType()));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("unsupported"));
    }

    @Test
    void rejectsAmbiguousRootExpressionRelationPaths() {
        QueryTemplatePlan.StructuralIdentifier articles = compilerKnown(TABLE, "articles");
        QueryTemplatePlan.StructuralIdentifier users = compilerKnown(TABLE, "users");
        QueryTemplatePlan.StructuralIdentifier id = compilerKnown(COLUMN, "id");
        QueryTemplatePlan.StructuralIdentifier authorId = compilerKnown(COLUMN, "author_id");
        QueryTemplatePlan.TableRef root = new QueryTemplatePlan.TableRef(null, articles, "a");
        QueryTemplatePlan.TableRef author = new QueryTemplatePlan.TableRef(null, users, "author");
        QueryTemplatePlan.TableRef editor = new QueryTemplatePlan.TableRef(null, users, "editor");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new QueryTemplatePlan(
                        root,
                        QueryTemplatePlan.RootKind.LIST,
                        List.of(new QueryTemplatePlan.Projection(new QueryTemplatePlan.ColumnRef("a", id), "id")),
                        List.of(),
                        List.of(),
                        List.of(new QueryTemplatePlan.OrderKey(new QueryTemplatePlan.ColumnRef("a", id), SortDirection.ASC)),
                        null,
                        List.of(
                                new QueryTemplatePlan.RootJoin(
                                        root,
                                        new QueryTemplatePlan.ColumnRef("a", authorId),
                                        author,
                                        new QueryTemplatePlan.ColumnRef("author", id)),
                                new QueryTemplatePlan.RootJoin(
                                        root,
                                        new QueryTemplatePlan.ColumnRef("a", authorId),
                                        editor,
                                        new QueryTemplatePlan.ColumnRef("editor", id))),
                        List.of(new QueryTemplatePlan.ExpressionPredicate(
                                new QueryTemplatePlan.ColumnValue(
                                        new QueryTemplatePlan.ColumnRef("author", id),
                                        new TIntType()),
                                EQ,
                                new QueryTemplatePlan.RuntimeParameter("p_author_id", new TIntType()))),
                        List.of(),
                        null));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("ambiguous"));
    }

    private static QueryTemplatePlan.StructuralIdentifier compilerKnown(
            QueryTemplatePlan.IdentifierKind kind,
            String value
    ) {
        return QueryTemplatePlan.StructuralIdentifier.compilerKnown(kind, value);
    }
}
