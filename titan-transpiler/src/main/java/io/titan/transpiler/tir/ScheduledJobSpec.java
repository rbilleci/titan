package io.titan.transpiler.tir;

public record ScheduledJobSpec(
        String cron,
        String name
) {
}
