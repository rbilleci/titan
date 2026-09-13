package io.titan.transpiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntryPointDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversStoredProceduresAndFunctions() throws Exception {
        Path sourceFile = tempDir.resolve("DemoEntryPoints.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;
                import titan.dsl.SecurityDefiner;
                import titan.dsl.StoredProcedure;

                class DemoEntryPoints {
                    @StoredProcedure
                    @SecurityDefiner
                    public static void syncAccounts() {}

                    @StoredFunction
                    public static int totalAccounts() { return 42; }

                    static void helper() {}
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        EntryPointDiscovery discovery = new EntryPointDiscovery();
        List<DiscoveredEntryPoint> entryPoints = discovery.discover(parsed);

        assertEquals(2, entryPoints.size());
        List<DiscoveredEntryPoint> sorted = entryPoints.stream()
                .sorted(Comparator.comparing(DiscoveredEntryPoint::methodName))
                .toList();

        DiscoveredEntryPoint syncAccounts = sorted.get(0);
        assertEquals(EntryPointKind.STORED_PROCEDURE, syncAccounts.kind());
        assertEquals("DemoEntryPoints", syncAccounts.className());
        assertEquals("syncAccounts", syncAccounts.methodName());
        assertTrue(syncAccounts.securityDefiner());

        DiscoveredEntryPoint totalAccounts = sorted.get(1);
        assertEquals(EntryPointKind.STORED_FUNCTION, totalAccounts.kind());
        assertEquals("totalAccounts", totalAccounts.methodName());
    }

    @Test
    void discoversTriggerMetadata() throws Exception {
        Path sourceFile = tempDir.resolve("TriggerEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.Trigger;
                import titan.dsl.TriggerEvent;
                import titan.dsl.TriggerTiming;

                class TriggerEntryPoint {
                    @Trigger(table = "accounts", timing = TriggerTiming.BEFORE, event = { TriggerEvent.INSERT, TriggerEvent.UPDATE })
                    public static void beforeUpsert() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        assertEquals(1, entryPoints.size());
        DiscoveredEntryPoint trigger = entryPoints.getFirst();
        assertEquals(EntryPointKind.TRIGGER, trigger.kind());
        assertEquals("accounts", trigger.triggerDefinition().table());
        assertEquals("BEFORE", trigger.triggerDefinition().timing());
        assertEquals(List.of("INSERT", "UPDATE"), trigger.triggerDefinition().events());
        assertEquals("ROW", trigger.triggerDefinition().forEach());
    }

    @Test
    void discoversScheduledJobMetadata() throws Exception {
        Path sourceFile = tempDir.resolve("ScheduledJobEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.ScheduledJob;

                class ScheduledJobEntryPoint {
                    @ScheduledJob(cron = "15 4 * * *", name = "nightly_refresh")
                    public static void refreshNightly() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        assertEquals(1, entryPoints.size());
        DiscoveredEntryPoint job = entryPoints.getFirst();
        assertEquals(EntryPointKind.SCHEDULED_JOB, job.kind());
        assertEquals("15 4 * * *", job.scheduledJobDefinition().cron());
        assertEquals("nightly_refresh", job.scheduledJobDefinition().name());
    }

    @Test
    void rejectsInvalidScheduledJobCronExpressions() throws Exception {
        Path sourceFile = tempDir.resolve("InvalidScheduledJobEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.ScheduledJob;

                class InvalidScheduledJobEntryPoint {
                    @ScheduledJob(cron = "60 4 * * *")
                    public static void badMinute() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new EntryPointDiscovery().discover(parsed));

        assertTrue(exception.getMessage().contains("invalid cron expression '60 4 * * *'"));
        assertTrue(exception.getMessage().contains("Suggestion:"));
        assertTrue(exception.getMessage().contains("Doc: Section 8.4"));
    }

    @Test
    void rejectsCronExpressionsWithStepLargerThanFieldSpan() throws Exception {
        Path sourceFile = tempDir.resolve("OversizedStepScheduledJobEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.ScheduledJob;

                class OversizedStepScheduledJobEntryPoint {
                    @ScheduledJob(cron = "*/61 * * * *")
                    public static void badMinuteStep() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new EntryPointDiscovery().discover(parsed));

        assertTrue(exception.getMessage().contains("invalid cron expression '*/61 * * * *'"));
        assertTrue(exception.getMessage().contains("Suggestion:"));
        assertTrue(exception.getMessage().contains("Doc: Section 8.4"));
    }

    @Test
    void acceptsCronExpressionsWithRangesListsAndSteps() throws Exception {
        Path sourceFile = tempDir.resolve("ValidScheduledJobEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.ScheduledJob;

                class ValidScheduledJobEntryPoint {
                    @ScheduledJob(cron = "0,15,30 9-17/2 * * 1-5")
                    public static void businessHours() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        assertEquals(1, entryPoints.size());
        assertEquals("0,15,30 9-17/2 * * 1-5", entryPoints.getFirst().scheduledJobDefinition().cron());
    }

    @Test
    void acceptsCronExpressionsWithNamedMonthsAndWeekdays() throws Exception {
        Path sourceFile = tempDir.resolve("NamedCronScheduledJobEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.ScheduledJob;

                class NamedCronScheduledJobEntryPoint {
                    @ScheduledJob(cron = "0 9 * JAN,MAR MON-FRI")
                    public static void weekdayQuarterOpen() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        assertEquals(1, entryPoints.size());
        assertEquals("0 9 * JAN,MAR MON-FRI", entryPoints.getFirst().scheduledJobDefinition().cron());
    }

    @Test
    void acceptsSundayAsSevenInCronDayOfWeekField() throws Exception {
        Path sourceFile = tempDir.resolve("SundayCronScheduledJobEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.ScheduledJob;

                class SundayCronScheduledJobEntryPoint {
                    @ScheduledJob(cron = "0 8 * * 7")
                    public static void sundayMorning() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        assertEquals(1, entryPoints.size());
        assertEquals("0 8 * * 7", entryPoints.getFirst().scheduledJobDefinition().cron());
    }

    @Test
    void rejectsTriggerMissingRequiredAttributesWithStructuredDiagnostic() throws Exception {
        Path sourceFile = tempDir.resolve("InvalidTriggerEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.Trigger;

                class InvalidTriggerEntryPoint {
                    @Trigger
                    static void broken() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new EntryPointDiscovery().discover(parsed));

        assertTrue(exception.getMessage().contains("missing required table attribute"));
        assertTrue(exception.getMessage().contains("Suggestion:"));
        assertTrue(exception.getMessage().contains("Doc: Section 8.3"));
    }

    @Test
    void rejectsScheduledJobMissingCronWithStructuredDiagnostic() throws Exception {
        Path sourceFile = tempDir.resolve("MissingCronScheduledJobEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.ScheduledJob;

                class MissingCronScheduledJobEntryPoint {
                    @ScheduledJob
                    static void broken() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new EntryPointDiscovery().discover(parsed));

        assertTrue(exception.getMessage().contains("missing required cron attribute"));
        assertTrue(exception.getMessage().contains("Suggestion:"));
        assertTrue(exception.getMessage().contains("Doc: Section 8.4"));
    }

    @Test
    void rejectsMethodsAnnotatedWithMultipleEntryPointTypes() throws Exception {
        Path sourceFile = tempDir.resolve("AmbiguousEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;
                import titan.dsl.StoredProcedure;

                class AmbiguousEntryPoint {
                    @StoredProcedure
                    @StoredFunction
                    static int conflicted() { return 1; }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new EntryPointDiscovery().discover(parsed));

        assertTrue(exception.getMessage().contains("must declare exactly one of @StoredProcedure, @StoredFunction, @Trigger, @ScheduledJob"));
        assertTrue(exception.getMessage().contains("Suggestion:"));
        assertTrue(exception.getMessage().contains("Doc: Section 8.1 / Section 8.2 / Section 8.3 / Section 8.4"));
    }

    @Test
    void rejectsNonStaticAndInvalidReturnTypes() throws Exception {
        Path sourceFile = tempDir.resolve("InvalidEntryPoints.java");
        Files.writeString(sourceFile, """
                import titan.dsl.StoredFunction;
                import titan.dsl.StoredProcedure;

                class InvalidEntryPoints {
                    @StoredProcedure
                    int badProcedure() { return 1; }

                    @StoredFunction
                    static void badFunction() {}
                }
                """);

        JavaSourceParser parser = new JavaSourceParser();
        ParsedSources parsed = parser.parse(List.of(sourceFile), List.of(), "21", false);

        EntryPointDiscovery discovery = new EntryPointDiscovery();
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> discovery.discover(parsed));

        String message = exception.getMessage();
        assertTrue(message.contains("must be static"));
        assertTrue(message.contains("@StoredProcedure method must return void"));
        assertTrue(message.contains("@StoredFunction method must return a value"));
    }
}
