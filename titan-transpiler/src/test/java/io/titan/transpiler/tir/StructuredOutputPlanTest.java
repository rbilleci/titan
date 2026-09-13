package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.COLUMN;
import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.TABLE;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.MANY;
import static io.titan.transpiler.tir.QueryTemplatePlan.RelationCardinality.ONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuredOutputPlanTest {

    @Test
    void modelsDeterministicObjectKeysScalarsNullsNestedObjectsAndArrays() {
        StructuredOutputPlan.ObjectValue author = new StructuredOutputPlan.ObjectValue(List.of(
                new StructuredOutputPlan.Entry(
                        StructuredOutputPlan.OutputKey.compilerKnown("name"),
                        new StructuredOutputPlan.ScalarValue("name", column("a", "name"), new TTextType())),
                new StructuredOutputPlan.Entry(
                        StructuredOutputPlan.OutputKey.compilerKnown("verified"),
                        new StructuredOutputPlan.NullValue(new TBooleanType()))));
        StructuredOutputPlan.ObjectValue review = new StructuredOutputPlan.ObjectValue(List.of(
                new StructuredOutputPlan.Entry(
                        StructuredOutputPlan.OutputKey.compilerKnown("body"),
                        new StructuredOutputPlan.ScalarValue("body", column("r", "body"), new TTextType()))));
        StructuredOutputPlan plan = new StructuredOutputPlan(new StructuredOutputPlan.ObjectValue(List.of(
                new StructuredOutputPlan.Entry(
                        StructuredOutputPlan.OutputKey.compilerKnown("title"),
                        new StructuredOutputPlan.ScalarValue("title", column("b", "title"), new TTextType())),
                new StructuredOutputPlan.Entry(StructuredOutputPlan.OutputKey.compilerKnown("author"), author),
                new StructuredOutputPlan.Entry(
                        StructuredOutputPlan.OutputKey.compilerKnown("reviews"),
                        new StructuredOutputPlan.ArrayValue(review)))));

        assertEquals(List.of("title", "author", "reviews"), keys(plan.root()));
        assertEquals(List.of("name", "verified"), keys(author));
        assertTrue(plan.root().entries().get(1).value() instanceof StructuredOutputPlan.ObjectValue);
        assertTrue(plan.root().entries().get(2).value() instanceof StructuredOutputPlan.ArrayValue);
    }

    @Test
    void derivesOutputShapeFromRowMaterializationMetadata() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier reviews = compilerKnown(TABLE, "reviews");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                new RowMaterializationPlan.RowKey("b_identity", column("b", "id"), new TIntType()),
                List.of(
                        new RowMaterializationPlan.FieldBinding("id", column("b", "id"), new TIntType()),
                        new RowMaterializationPlan.FieldBinding("title", column("b", "title"), new TTextType())),
                List.of(new RowMaterializationPlan.RelationRows(
                        "reviews",
                        new QueryTemplatePlan.TableRef(null, reviews, "r"),
                        new RowMaterializationPlan.RowKey("b_key", column("b", "id"), new TIntType()),
                        new RowMaterializationPlan.RowKey("r_key", column("r", "book_id"), new TIntType()),
                        MANY,
                        List.of(new RowMaterializationPlan.FieldBinding(
                                "body",
                                column("r", "body"),
                                new TTextType())))));

        StructuredOutputPlan plan = StructuredOutputPlan.fromRowMaterialization(rows);

        assertEquals(List.of("id", "title", "reviews"), keys(plan.root()));
        StructuredOutputPlan.ArrayValue reviewsOutput =
                (StructuredOutputPlan.ArrayValue) plan.root().entries().get(2).value();
        StructuredOutputPlan.ObjectValue reviewObject = (StructuredOutputPlan.ObjectValue) reviewsOutput.element();
        assertEquals(List.of("body"), keys(reviewObject));
    }

    @Test
    void runtimePlanAcceptsRootOnlyStructuredOutput() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                new RowMaterializationPlan.RowKey("b_identity", column("b", "id"), new TIntType()),
                List.of(new RowMaterializationPlan.FieldBinding("title", column("b", "title"), new TTextType())),
                List.of());
        StructuredOutputPlan output = StructuredOutputPlan.fromRowMaterialization(rows);
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.from(
                rows,
                new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType()));

        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(output, runtimeRows);

        assertEquals(output, runtimeOutput.output());
        assertEquals(runtimeRows, runtimeOutput.rows());
    }

    @Test
    void runtimePlanAssemblesNestedRootOnlyObjectsFromRootRows() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                new RowMaterializationPlan.RowKey("b_identity", column("b", "id"), new TIntType()),
                List.of(new RowMaterializationPlan.FieldBinding("title", column("b", "title"), new TTextType())),
                List.of());
        StructuredOutputPlan output = new StructuredOutputPlan(new StructuredOutputPlan.ObjectValue(List.of(
                new StructuredOutputPlan.Entry(
                        StructuredOutputPlan.OutputKey.compilerKnown("book"),
                        new StructuredOutputPlan.ObjectValue(List.of(new StructuredOutputPlan.Entry(
                                StructuredOutputPlan.OutputKey.compilerKnown("title"),
                                new StructuredOutputPlan.ScalarValue(
                                        "title",
                                        column("b", "title"),
                                        new TTextType()))))))));
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                output,
                RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType())));

        assertEquals(
                Map.of("book", Map.of("title", "Titan")),
                runtimeOutput.assemble(Map.of("title", "Titan"), List.of()));
    }

    @Test
    void runtimePlanAssemblesGroupedManyRelationRows() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier reviews = compilerKnown(TABLE, "reviews");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                new RowMaterializationPlan.RowKey("b_identity", column("b", "id"), new TIntType()),
                List.of(new RowMaterializationPlan.FieldBinding("title", column("b", "title"), new TTextType())),
                List.of(new RowMaterializationPlan.RelationRows(
                        "book_reviews",
                        new QueryTemplatePlan.TableRef(null, reviews, "r"),
                        new RowMaterializationPlan.RowKey("b_key", column("b", "id"), new TIntType()),
                        new RowMaterializationPlan.RowKey("r_key", column("r", "book_id"), new TIntType()),
                        MANY,
                        List.of(new RowMaterializationPlan.FieldBinding(
                                "body",
                                column("r", "body"),
                                new TTextType())))));
        StructuredOutputPlan output = StructuredOutputPlan.fromRowMaterialization(rows);
        RowMaterializationRuntimePlan runtimeRows = RowMaterializationRuntimePlan.from(
                rows,
                new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType()));

        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(output, runtimeRows);

        assertEquals(
                Map.of(
                        "title", "Titan",
                        "book_reviews", List.of(
                                Map.of("body", "first"),
                                Map.of("body", "first"))),
                runtimeOutput.assemble(
                        Map.of("title", "Titan", "b_identity", 10),
                        List.of(
                                Map.of("body", "first", "r_key", 10),
                                Map.of("body", "first", "r_key", 10))));
        assertEquals(
                Map.of("title", "Titan", "book_reviews", List.of()),
                runtimeOutput.assemble(Map.of("title", "Titan", "b_identity", 10), List.of()));
    }

    @Test
    void runtimePlanRejectsMismatchedGroupedRelationRows() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier reviews = compilerKnown(TABLE, "reviews");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                new RowMaterializationPlan.RowKey("b_identity", column("b", "id"), new TIntType()),
                List.of(new RowMaterializationPlan.FieldBinding("id", column("b", "id"), new TIntType())),
                List.of(new RowMaterializationPlan.RelationRows(
                        "book_reviews",
                        new QueryTemplatePlan.TableRef(null, reviews, "r"),
                        new RowMaterializationPlan.RowKey("b_key", column("b", "id"), new TIntType()),
                        new RowMaterializationPlan.RowKey("r_key", column("r", "book_id"), new TIntType()),
                        MANY,
                        List.of(new RowMaterializationPlan.FieldBinding(
                                "body",
                                column("r", "body"),
                                new TTextType())))));
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rows),
                RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType())));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeOutput.assemble(
                        Map.of("id", 10),
                        List.of(Map.of("body", "other", "r_key", 20))));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("book_reviews"));
        assertTrue(exception.getMessage().contains("parent key"));
    }

    @Test
    void runtimePlanEnforcesSingleRelationCardinality() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier authors = compilerKnown(TABLE, "authors");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                new RowMaterializationPlan.RowKey("b_identity", column("b", "id"), new TIntType()),
                List.of(new RowMaterializationPlan.FieldBinding("id", column("b", "id"), new TIntType())),
                List.of(new RowMaterializationPlan.RelationRows(
                        "author",
                        new QueryTemplatePlan.TableRef(null, authors, "a"),
                        new RowMaterializationPlan.RowKey("b_key", column("b", "id"), new TIntType()),
                        new RowMaterializationPlan.RowKey("a_key", column("a", "book_id"), new TIntType()),
                        ONE,
                        List.of(new RowMaterializationPlan.FieldBinding(
                                "name",
                                column("a", "name"),
                                new TTextType())))));
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                StructuredOutputPlan.fromRowMaterialization(rows),
                RowMaterializationRuntimePlan.from(
                        rows,
                        new QueryTemplatePlan.RuntimeParameter("p_book_id", new TIntType())));

        assertEquals(
                Map.of("id", 10, "author", Map.of("name", "Ada")),
                runtimeOutput.assemble(
                        Map.of("id", 10),
                        List.of(Map.of("name", "Ada", "a_key", 10))));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeOutput.assemble(Map.of("id", 10), List.of()));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("cardinality ONE"));
    }

    @Test
    void oneCardinalityRelationsBecomeNestedObjectsInsteadOfArrays() {
        QueryTemplatePlan.StructuralIdentifier books = compilerKnown(TABLE, "books");
        QueryTemplatePlan.StructuralIdentifier authors = compilerKnown(TABLE, "authors");
        RowMaterializationPlan rows = new RowMaterializationPlan(
                new QueryTemplatePlan.TableRef(null, books, "b"),
                new RowMaterializationPlan.RowKey("b_identity", column("b", "id"), new TIntType()),
                List.of(),
                List.of(new RowMaterializationPlan.RelationRows(
                        "author",
                        new QueryTemplatePlan.TableRef(null, authors, "a"),
                        new RowMaterializationPlan.RowKey("b_author_key", column("b", "author_id"), new TIntType()),
                        new RowMaterializationPlan.RowKey("a_key", column("a", "id"), new TIntType()),
                        ONE,
                        List.of(new RowMaterializationPlan.FieldBinding("name", column("a", "name"), new TTextType())))));

        StructuredOutputPlan plan = StructuredOutputPlan.fromRowMaterialization(rows);

        assertEquals("author", plan.root().entries().getFirst().key().value());
        assertTrue(plan.root().entries().getFirst().value() instanceof StructuredOutputPlan.ObjectValue);
    }

    @Test
    void rejectsRuntimeSelectedOutputKeys() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StructuredOutputPlan.OutputKey.runtimeValue("p_selected_key"));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("compiler-known"));
    }

    @Test
    void rejectsDuplicateObjectKeys() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredOutputPlan.ObjectValue(List.of(
                        new StructuredOutputPlan.Entry(
                                StructuredOutputPlan.OutputKey.compilerKnown("title"),
                                new StructuredOutputPlan.ScalarValue("title", column("b", "title"), new TTextType())),
                        new StructuredOutputPlan.Entry(
                                StructuredOutputPlan.OutputKey.compilerKnown("title"),
                                new StructuredOutputPlan.ScalarValue("title", column("b", "subtitle"), new TTextType())))));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("unique"));
    }

    @Test
    void rejectsUnsupportedNestedOutputShapes() {
        StructuredOutputPlan.ArrayValue nested = new StructuredOutputPlan.ArrayValue(
                new StructuredOutputPlan.ObjectValue(List.of()));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredOutputPlan.ArrayValue(nested));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("nested arrays"));
    }

    @Test
    void rejectsUnsupportedScalarFieldTypes() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredOutputPlan.ScalarValue(
                        "payload",
                        column("b", "payload"),
                        new TJsonType()));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("not supported yet"));
    }

    @Test
    void snapshotsOutputEntryCollectionsAtConstruction() {
        ArrayList<StructuredOutputPlan.Entry> entries = new ArrayList<>();
        entries.add(new StructuredOutputPlan.Entry(
                StructuredOutputPlan.OutputKey.compilerKnown("title"),
                new StructuredOutputPlan.ScalarValue("title", column("b", "title"), new TTextType())));

        StructuredOutputPlan.ObjectValue object = new StructuredOutputPlan.ObjectValue(entries);
        entries.clear();

        assertEquals(1, object.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> object.entries().clear());
    }

    private static List<String> keys(StructuredOutputPlan.ObjectValue object) {
        return object.entries().stream()
                .map(entry -> entry.key().value())
                .toList();
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
}
