package io.titan.transpiler.emit;

import java.util.Objects;

/**
 * Utility text buffer used by SQL emitters.
 */
public final class CodeBuffer {
    private static final String INDENT_UNIT = "    ";

    private final StringBuilder out = new StringBuilder();
    private int indentLevel;
    private int lineNumber = 1;

    public CodeBuffer indent() {
        indentLevel++;
        return this;
    }

    public CodeBuffer dedent() {
        if (indentLevel == 0) {
            throw new IllegalStateException("Cannot dedent below zero");
        }
        indentLevel--;
        return this;
    }

    public CodeBuffer line(String text) {
        writeIndent();
        out.append(text);
        newline();
        return this;
    }

    public CodeBuffer blankLine() {
        newline();
        return this;
    }

    public CodeBuffer source(String sourceFile, int sourceLine) {
        Objects.requireNonNull(sourceFile, "sourceFile");
        if (sourceLine <= 0) {
            throw new IllegalArgumentException("sourceLine must be > 0");
        }
        return line("-- titan:source:" + sourceFile + ":" + sourceLine);
    }

    public CodeBuffer appendRaw(String text) {
        if (text == null || text.isEmpty()) {
            return this;
        }
        out.append(text);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineNumber++;
            }
        }
        return this;
    }

    public int lineNumber() {
        return lineNumber;
    }

    @Override
    public String toString() {
        return out.toString();
    }

    private void writeIndent() {
        out.append(INDENT_UNIT.repeat(Math.max(0, indentLevel)));
    }

    private void newline() {
        out.append('\n');
        lineNumber++;
    }
}
