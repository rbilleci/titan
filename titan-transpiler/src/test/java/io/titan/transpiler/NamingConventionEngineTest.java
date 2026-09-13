package io.titan.transpiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NamingConventionEngineTest {

    @Test
    void mapsCamelCaseToSnakeCase() {
        NamingConventionEngine engine = new NamingConventionEngine();

        assertEquals("customer_id", engine.toSqlIdentifier("customerId"));
        assertEquals("http_server_port", engine.toSqlIdentifier("HTTPServerPort"));
        assertEquals("already_snake", engine.toSqlIdentifier("already_snake"));
    }

    @Test
    void appliesDefaultPrefixesForVariableKinds() {
        NamingConventionEngine engine = new NamingConventionEngine();

        assertEquals("p_account_id", engine.parameterName("accountId"));
        assertEquals("v_running_total", engine.localVariableName("runningTotal"));
        assertEquals("c_max_retries", engine.constantName("MAX_RETRIES"));
    }

    @Test
    void supportsCustomPrefixes() {
        NamingConventionEngine engine = new NamingConventionEngine("arg", "tmp", "const");

        assertEquals("arg_order_id", engine.parameterName("orderId"));
        assertEquals("tmp_batch_size", engine.localVariableName("batchSize"));
        assertEquals("const_limit", engine.constantName("limit"));
    }

    @Test
    void normalizesUnsafeIdentifiers() {
        NamingConventionEngine engine = new NamingConventionEngine();

        assertEquals("v_123abc", engine.toSqlIdentifier("123Abc"));
        assertEquals("value", engine.toSqlIdentifier("---"));
    }

    @Test
    void rejectsBlankIdentifier() {
        NamingConventionEngine engine = new NamingConventionEngine();

        assertThrows(IllegalArgumentException.class, () -> engine.toSqlIdentifier("  "));
    }
}
