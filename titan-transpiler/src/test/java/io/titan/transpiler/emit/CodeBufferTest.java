package io.titan.transpiler.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CodeBufferTest {

    @Test
    void appliesIndentationAndTracksLineNumbers() {
        var buffer = new CodeBuffer();

        buffer.line("CREATE FUNCTION f()")
                .line("RETURNS VOID")
                .line("BEGIN")
                .indent()
                .line("SET x = 1;")
                .dedent()
                .line("END;");

        assertEquals(
                """
                CREATE FUNCTION f()
                RETURNS VOID
                BEGIN
                    SET x = 1;
                END;
                """,
                buffer.toString());
        assertEquals(6, buffer.lineNumber());
    }

    @Test
    void emitsSourceMappingComment() {
        var buffer = new CodeBuffer();

        buffer.source("AccountService.java", 42)
                .line("IF v_plan_id IS NULL THEN")
                .indent()
                .line("RAISE EXCEPTION 'npe';")
                .dedent()
                .line("END IF;");

        assertEquals(
                """
                -- titan:source:AccountService.java:42
                IF v_plan_id IS NULL THEN
                    RAISE EXCEPTION 'npe';
                END IF;
                """,
                buffer.toString());
    }

    @Test
    void rawAppendUpdatesLineCount() {
        var buffer = new CodeBuffer();

        buffer.appendRaw("SELECT 1;\nSELECT 2;\n");

        assertEquals(3, buffer.lineNumber());
    }

    @Test
    void rejectsInvalidDedentAndInvalidSourceLine() {
        var buffer = new CodeBuffer();

        assertThrows(IllegalStateException.class, buffer::dedent);
        assertThrows(IllegalArgumentException.class, () -> buffer.source("Any.java", 0));
    }
}
