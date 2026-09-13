package io.titan.transpiler.diagnostics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TitanDiagnosticsTest {

    @Test
    void entryPointSignatureDiagnosticsIncludeStableCodeSuggestionAndDocReference() {
        String rendered = TitanDiagnostics
                .storedProcedureMustReturnVoid("com.example.Accounts.run", "Accounts.java:42")
                .render();

        assertTrue(rendered.contains("TITAN-E002"));
        assertTrue(rendered.contains("Suggestion:"));
        assertTrue(rendered.contains("Doc: Section 8.1"));
    }

    @Test
    void triggerAndScheduledJobDiagnosticsExposeStructuredMetadata() {
        String trigger = TitanDiagnostics
                .triggerMustReturnVoid("com.example.Accounts.beforeInsert", "Accounts.java:15")
                .render();
        String scheduled = TitanDiagnostics
                .scheduledJobMustReturnVoid("com.example.Accounts.nightly", "Accounts.java:22")
                .render();

        assertTrue(trigger.contains("TITAN-E001"));
        assertTrue(trigger.contains("Doc: Section 8.3"));
        assertTrue(trigger.contains("Suggestion:"));

        assertTrue(scheduled.contains("TITAN-E001"));
        assertTrue(scheduled.contains("Doc: Section 8.4"));
        assertTrue(scheduled.contains("Suggestion:"));
    }
}
