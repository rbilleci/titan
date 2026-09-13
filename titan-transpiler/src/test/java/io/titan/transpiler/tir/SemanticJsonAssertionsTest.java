package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.SemanticJsonAssertions.assertJsonTextSemanticallyEquals;
import static io.titan.transpiler.tir.SemanticJsonAssertions.assertJsonTextSemanticallyEqualsOrderSensitive;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SemanticJsonAssertionsTest {
    @Test
    void comparesParsedJsonTextWithoutDependingOnObjectFormattingOrder() {
        assertJsonTextSemanticallyEquals(
                """
                {
                  "data": {
                    "id": 10,
                    "title": "Operations",
                    "ratio": 1.0,
                    "active": true,
                    "missing": null
                  },
                  "errors": []
                }
                """,
                """
                {"errors":[],"data":{"missing":null,"active":true,"ratio":1,"title":"Operations","id":10}}
                """);
    }

    @Test
    void canRequireObjectOrderWhenThePayloadContractRequiresIt() {
        AssertionError error = assertThrows(
                AssertionError.class,
                () -> assertJsonTextSemanticallyEqualsOrderSensitive(
                        "{\"data\":{},\"errors\":[]}",
                        "{\"errors\":[],\"data\":{}}"));

        org.junit.jupiter.api.Assertions.assertTrue(
                error.getMessage().contains("$ expected object keys [data, errors] but was [errors, data]"));
    }

    @Test
    void reportsNestedMismatchPathsForParsedJsonText() {
        AssertionError error = assertThrows(
                AssertionError.class,
                () -> assertJsonTextSemanticallyEquals(
                        "{\"data\":{\"items\":[{\"id\":1}]}}",
                        "{\"data\":{\"items\":[{\"id\":2}]}}"));

        org.junit.jupiter.api.Assertions.assertTrue(
                error.getMessage().contains("$.data.items[0].id expected number <1> but was <2>"));
    }

    @Test
    void rejectsInvalidJsonTextBeforeComparison() {
        AssertionError error = assertThrows(
                AssertionError.class,
                () -> assertJsonTextSemanticallyEquals("{\"data\":{}} trailing", "{\"data\":{}}"));

        org.junit.jupiter.api.Assertions.assertTrue(
                error.getMessage().contains("Invalid JSON text: unexpected trailing content"));
    }
}
