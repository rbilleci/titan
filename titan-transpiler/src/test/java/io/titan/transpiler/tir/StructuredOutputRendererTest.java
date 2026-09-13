package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.COLUMN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredOutputRendererTest {
    private final StructuredOutputRenderer renderer = new StructuredOutputRenderer();

    @Test
    void rendersDeterministicPostgreSqlStructuredOutputExpression() {
        StructuredOutputPlan plan = contractPlan();

        String sql = renderer.renderPostgreSql(plan);

        assertEquals(
                "jsonb_build_object('title', to_jsonb(\"b\".\"title\"), "
                        + "'author', jsonb_build_object('name', to_jsonb(\"a\".\"name\"), "
                        + "'verified', to_jsonb(CAST(NULL AS BOOLEAN))), "
                        + "'reviews', jsonb_build_array(jsonb_build_object('body', to_jsonb(\"r\".\"body\"))))",
                sql);
    }

    @Test
    void rendersDeterministicMySqlStructuredOutputExpression() {
        StructuredOutputPlan plan = contractPlan();

        String sql = renderer.renderMySql(plan);

        assertEquals(
                "JSON_OBJECT('title', `b`.`title`, "
                        + "'author', JSON_OBJECT('name', `a`.`name`, 'verified', CAST(NULL AS SIGNED)), "
                        + "'reviews', JSON_ARRAY(JSON_OBJECT('body', `r`.`body`)))",
                sql);
    }

    @Test
    void escapesCompilerKnownOutputKeysAsSqlLiterals() {
        StructuredOutputPlan plan = new StructuredOutputPlan(new StructuredOutputPlan.ObjectValue(List.of(
                new StructuredOutputPlan.Entry(
                        StructuredOutputPlan.OutputKey.compilerKnown("reader's title"),
                        new StructuredOutputPlan.ScalarValue("title", column("b", "title"), new TTextType())))));

        assertEquals(
                "jsonb_build_object('reader''s title', to_jsonb(\"b\".\"title\"))",
                renderer.renderPostgreSql(plan));
        assertEquals(
                "JSON_OBJECT('reader''s title', `b`.`title`)",
                renderer.renderMySql(plan));
    }

    @Test
    void quotesCompilerKnownColumnIdentifiersPerDialect() {
        StructuredOutputPlan plan = new StructuredOutputPlan(new StructuredOutputPlan.ObjectValue(List.of(
                new StructuredOutputPlan.Entry(
                        StructuredOutputPlan.OutputKey.compilerKnown("order"),
                        new StructuredOutputPlan.ScalarValue("order", column("select", "order"), new TIntType())))));

        assertEquals(
                "jsonb_build_object('order', to_jsonb(\"select\".\"order\"))",
                renderer.renderPostgreSql(plan));
        assertEquals(
                "JSON_OBJECT('order', `select`.`order`)",
                renderer.renderMySql(plan));
    }

    @Test
    void unsupportedDynamicKeysStillFailBeforeEmission() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StructuredOutputPlan.OutputKey.runtimeValue("p_selected_key"));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("compiler-known"));
    }

    @Test
    void unsupportedNestedArraysStillFailBeforeEmission() {
        StructuredOutputPlan.ArrayValue nested = new StructuredOutputPlan.ArrayValue(
                new StructuredOutputPlan.ObjectValue(List.of()));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredOutputPlan.ArrayValue(nested));

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("nested arrays"));
    }

    private static StructuredOutputPlan contractPlan() {
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
        return new StructuredOutputPlan(new StructuredOutputPlan.ObjectValue(List.of(
                new StructuredOutputPlan.Entry(
                        StructuredOutputPlan.OutputKey.compilerKnown("title"),
                        new StructuredOutputPlan.ScalarValue("title", column("b", "title"), new TTextType())),
                new StructuredOutputPlan.Entry(StructuredOutputPlan.OutputKey.compilerKnown("author"), author),
                new StructuredOutputPlan.Entry(
                        StructuredOutputPlan.OutputKey.compilerKnown("reviews"),
                        new StructuredOutputPlan.ArrayValue(review)))));
    }

    private static QueryTemplatePlan.ColumnRef column(String alias, String column) {
        return new QueryTemplatePlan.ColumnRef(
                alias,
                QueryTemplatePlan.StructuralIdentifier.compilerKnown(COLUMN, column));
    }
}
