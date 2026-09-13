package io.titan.transpiler;

public record ScheduledJobDefinition(
        String cron,
        String name
) {
}
