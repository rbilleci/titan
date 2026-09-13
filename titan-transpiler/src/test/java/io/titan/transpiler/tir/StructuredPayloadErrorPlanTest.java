package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.COLUMN;
import static io.titan.transpiler.tir.QueryTemplatePlan.IdentifierKind.TABLE;
import static io.titan.transpiler.tir.SemanticJsonAssertions.assertJsonSemanticallyEqualsOrderSensitive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StructuredPayloadErrorPlanTest {
    private final StructuredOutputRenderer renderer = new StructuredOutputRenderer();

    @Test
    void lowersDeterministicValidationErrorThroughEnvelopeWithoutJsonStringConcatenation() {
        StructuredPayloadErrorPlan error = new StructuredPayloadErrorPlan(
                new StructuredOutputPlan.TextLiteralValue("VALIDATION_FAILED"),
                new StructuredOutputPlan.TextLiteralValue("title 'must' use C:\\drafts before \"publish\""),
                Optional.of(new StructuredOutputPlan.ArrayLiteralValue(List.of(
                        new StructuredOutputPlan.TextLiteralValue("payload"),
                        new StructuredOutputPlan.TextLiteralValue("title")))),
                Optional.of(new StructuredOutputPlan.ObjectValue(List.of(
                        entry("line", new StructuredOutputPlan.IntegerLiteralValue(12)),
                        entry("column", new StructuredOutputPlan.IntegerLiteralValue(5))))),
                Optional.of(new StructuredOutputPlan.ObjectValue(List.of(
                        entry("rule", new StructuredOutputPlan.TextLiteralValue("required"))))),
                List.of(
                        StructuredPayloadErrorPlan.Field.CODE,
                        StructuredPayloadErrorPlan.Field.MESSAGE,
                        StructuredPayloadErrorPlan.Field.PATH,
                        StructuredPayloadErrorPlan.Field.LOCATION,
                        StructuredPayloadErrorPlan.Field.METADATA));
        StructuredPayloadEnvelopePlan envelope = StructuredPayloadEnvelopePlan.failureFromErrors(List.of(error));
        StructuredOutputPlan output = envelope.toStructuredOutputPlan();
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                output,
                RowMaterializationRuntimePlan.from(
                        validationRows(),
                        new QueryTemplatePlan.RuntimeParameter("p_request_id", new TIntType())));

        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "data", null,
                        "errors", List.of(row(
                                "code", "VALIDATION_FAILED",
                                "message", "title 'must' use C:\\drafts before \"publish\"",
                                "path", List.of("payload", "title"),
                                "location", row("line", 12, "column", 5),
                                "metadata", row("rule", "required")))),
                runtimeOutput.assemble(row("requestId", 99), List.of()));
        assertEquals(
                "jsonb_build_object('data', 'null'::jsonb, "
                        + "'errors', jsonb_build_array(jsonb_build_object("
                        + "'code', to_jsonb(CAST('VALIDATION_FAILED' AS TEXT)), "
                        + "'message', to_jsonb(CAST('title ''must'' use C:\\drafts before \"publish\"' AS TEXT)), "
                        + "'path', jsonb_build_array(to_jsonb(CAST('payload' AS TEXT)), "
                        + "to_jsonb(CAST('title' AS TEXT))), "
                        + "'location', jsonb_build_object('line', to_jsonb(12), 'column', to_jsonb(5)), "
                        + "'metadata', jsonb_build_object('rule', to_jsonb(CAST('required' AS TEXT))))))",
                renderer.renderPostgreSql(output));
        assertEquals(
                "JSON_OBJECT('data', CAST(NULL AS CHAR), "
                        + "'errors', JSON_ARRAY(JSON_OBJECT("
                        + "'code', 'VALIDATION_FAILED', "
                        + "'message', 'title ''must'' use C:\\\\drafts before \"publish\"', "
                        + "'path', JSON_ARRAY('payload', 'title'), "
                        + "'location', JSON_OBJECT('line', 12, 'column', 5), "
                        + "'metadata', JSON_OBJECT('rule', 'required'))))",
                renderer.renderMySql(output));
    }

    @Test
    void preservesSuppliedOrderingForMultipleErrors() {
        StructuredPayloadEnvelopePlan envelope = StructuredPayloadEnvelopePlan.failureFromErrors(List.of(
                StructuredPayloadErrorPlan.validation("FIRST", "first message", "payload.first"),
                StructuredPayloadErrorPlan.validation("SECOND", "second message", "payload.second")));
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                envelope.toStructuredOutputPlan(),
                RowMaterializationRuntimePlan.from(
                        validationRows(),
                        new QueryTemplatePlan.RuntimeParameter("p_request_id", new TIntType())));

        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "data", null,
                        "errors", List.of(
                                row("code", "FIRST", "message", "first message", "path", "payload.first"),
                                row("code", "SECOND", "message", "second message", "path", "payload.second"))),
                runtimeOutput.assemble(row("requestId", 7), List.of()));
    }

    @Test
    void supportsFieldBackedRuntimeErrorMetadataWhereAvailable() {
        StructuredPayloadErrorPlan error = new StructuredPayloadErrorPlan(
                scalar("code", "e", "code", new TTextType()),
                scalar("message", "e", "message", new TTextType()),
                Optional.of(scalar("path", "e", "path", new TTextType())),
                Optional.empty(),
                Optional.of(new StructuredOutputPlan.ObjectValue(List.of(
                        entry("traceId", scalar("traceId", "e", "trace_id", new TTextType()))))),
                List.of(
                        StructuredPayloadErrorPlan.Field.CODE,
                        StructuredPayloadErrorPlan.Field.MESSAGE,
                        StructuredPayloadErrorPlan.Field.PATH,
                        StructuredPayloadErrorPlan.Field.METADATA));
        StructuredPayloadEnvelopePlan envelope = StructuredPayloadEnvelopePlan.failureFromErrors(List.of(error));
        StructuredOutputRuntimePlan runtimeOutput = StructuredOutputRuntimePlan.from(
                envelope.toStructuredOutputPlan(),
                RowMaterializationRuntimePlan.from(
                        validationRows(),
                        new QueryTemplatePlan.RuntimeParameter("p_request_id", new TIntType())));

        assertJsonSemanticallyEqualsOrderSensitive(
                row(
                        "data", null,
                        "errors", List.of(row(
                                "code", "RUNTIME_CARDINALITY",
                                "message", "expected one child row",
                                "path", "payload.items",
                                "metadata", row("traceId", "trace-9")))),
                runtimeOutput.assemble(row(
                        "code", "RUNTIME_CARDINALITY",
                        "message", "expected one child row",
                        "path", "payload.items",
                        "traceId", "trace-9"), List.of()));
    }

    @Test
    void rejectsErrorFieldOrdersThatOmitRequiredOrSuppliedFields() {
        IllegalArgumentException missingMessage = assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredPayloadErrorPlan(
                        new StructuredOutputPlan.TextLiteralValue("VALIDATION_FAILED"),
                        new StructuredOutputPlan.TextLiteralValue("message"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(StructuredPayloadErrorPlan.Field.CODE)));
        assertTrue(missingMessage.getMessage().contains("TITAN-E001"));
        assertTrue(missingMessage.getMessage().contains("code and message"));

        IllegalArgumentException missingMetadataOrder = assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredPayloadErrorPlan(
                        new StructuredOutputPlan.TextLiteralValue("VALIDATION_FAILED"),
                        new StructuredOutputPlan.TextLiteralValue("message"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new StructuredOutputPlan.ObjectValue(List.of())),
                        List.of(
                                StructuredPayloadErrorPlan.Field.CODE,
                                StructuredPayloadErrorPlan.Field.MESSAGE)));
        assertTrue(missingMetadataOrder.getMessage().contains("TITAN-E001"));
        assertTrue(missingMetadataOrder.getMessage().contains("metadata"));
    }

    private static RowMaterializationPlan validationRows() {
        return new RowMaterializationPlan(
                table("validation_events", "e"),
                new RowMaterializationPlan.RowKey("e_identity", column("e", "id"), new TIntType()),
                List.of(
                        new RowMaterializationPlan.FieldBinding("requestId", column("e", "id"), new TIntType()),
                        new RowMaterializationPlan.FieldBinding("code", column("e", "code"), new TTextType()),
                        new RowMaterializationPlan.FieldBinding("message", column("e", "message"), new TTextType()),
                        new RowMaterializationPlan.FieldBinding("path", column("e", "path"), new TTextType()),
                        new RowMaterializationPlan.FieldBinding("traceId", column("e", "trace_id"), new TTextType())),
                List.of());
    }

    private static StructuredOutputPlan.Entry entry(String key, StructuredOutputPlan.Value value) {
        return new StructuredOutputPlan.Entry(StructuredOutputPlan.OutputKey.compilerKnown(key), value);
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
