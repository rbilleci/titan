package io.titan.transpiler.diagnostics;

/**
 * Central diagnostic factory for transpiler checks.
 */
public final class TitanDiagnostics {

    private TitanDiagnostics() {
    }

    public static TitanDiagnostic unsupportedFeature(String feature, String location) {
        return new TitanDiagnostic(
                TitanErrorCode.E001,
                "Unsupported feature '" + feature + "'",
                location,
                "Limit code to the Titan P0 subset or refactor this construct out of the transpiled entry point",
                "Section 12.1"
        );
    }

    public static TitanDiagnostic entryPointMustBeStatic(String methodQualifiedName, String location) {
        return new TitanDiagnostic(
                TitanErrorCode.E002,
                "Entry point method must be static: " + methodQualifiedName,
                location,
                "Mark the method static or remove Titan entry-point annotation",
                "Section 8.1 / Section 8.2"
        );
    }

    public static TitanDiagnostic storedProcedureMustReturnVoid(String methodQualifiedName, String location) {
        return new TitanDiagnostic(
                TitanErrorCode.E002,
                "@StoredProcedure method must return void: " + methodQualifiedName,
                location,
                "Change return type to void or convert annotation to @StoredFunction",
                "Section 8.1"
        );
    }

    public static TitanDiagnostic storedFunctionMustReturnValue(String methodQualifiedName, String location) {
        return new TitanDiagnostic(
                TitanErrorCode.E002,
                "@StoredFunction method must return a value: " + methodQualifiedName,
                location,
                "Provide a non-void return type or convert annotation to @StoredProcedure",
                "Section 8.2"
        );
    }

    public static TitanDiagnostic entryPointMustDeclareExactlyOneAnnotation(String methodQualifiedName, String location) {
        return new TitanDiagnostic(
                TitanErrorCode.E001,
                "Entry point method must declare exactly one of @StoredProcedure, @StoredFunction, @Trigger, @ScheduledJob: "
                        + methodQualifiedName,
                location,
                "Keep exactly one Titan entry-point annotation on the method",
                "Section 8.1 / Section 8.2 / Section 8.3 / Section 8.4"
        );
    }

    public static TitanDiagnostic triggerMustReturnVoid(String methodQualifiedName, String location) {
        return new TitanDiagnostic(
                TitanErrorCode.E001,
                "@Trigger method must return void: " + methodQualifiedName,
                location,
                "Change return type to void for trigger entry points",
                "Section 8.3"
        );
    }

    public static TitanDiagnostic triggerMissingTableAttribute(String methodQualifiedName, String location) {
        return new TitanDiagnostic(
                TitanErrorCode.E001,
                "@Trigger is missing required table attribute: " + methodQualifiedName,
                location,
                "Set @Trigger(table = \"...\") to the physical target table name",
                "Section 8.3"
        );
    }

    public static TitanDiagnostic triggerMissingTimingAttribute(String methodQualifiedName, String location) {
        return new TitanDiagnostic(
                TitanErrorCode.E001,
                "@Trigger is missing required timing attribute: " + methodQualifiedName,
                location,
                "Set @Trigger(timing = TriggerTiming.BEFORE|AFTER) explicitly",
                "Section 8.3"
        );
    }

    public static TitanDiagnostic triggerMissingEventAttribute(String methodQualifiedName, String location) {
        return new TitanDiagnostic(
                TitanErrorCode.E001,
                "@Trigger is missing required event attribute: " + methodQualifiedName,
                location,
                "Set @Trigger(event = { TriggerEvent.INSERT | UPDATE | DELETE }) explicitly",
                "Section 8.3"
        );
    }

    public static TitanDiagnostic scheduledJobMustReturnVoid(String methodQualifiedName, String location) {
        return new TitanDiagnostic(
                TitanErrorCode.E001,
                "@ScheduledJob method must return void: " + methodQualifiedName,
                location,
                "Change return type to void for scheduled-job entry points",
                "Section 8.4"
        );
    }

    public static TitanDiagnostic scheduledJobMissingCronAttribute(String methodQualifiedName, String location) {
        return new TitanDiagnostic(
                TitanErrorCode.E001,
                "@ScheduledJob is missing required cron attribute: " + methodQualifiedName,
                location,
                "Set @ScheduledJob(cron = \"m h dom mon dow\") with a valid 5-field cron expression",
                "Section 8.4"
        );
    }

    public static TitanDiagnostic scheduledJobInvalidCronExpression(String methodQualifiedName, String location, String cron) {
        return new TitanDiagnostic(
                TitanErrorCode.E001,
                "@ScheduledJob has invalid cron expression '" + cron + "': " + methodQualifiedName,
                location,
                "Use a valid 5-field cron expression such as \"15 4 * * *\"",
                "Section 8.4"
        );
    }
}
