package io.titan.transpiler;

import java.util.List;

public record DiscoveredEntryPoint(
        EntryPointKind kind,
        String className,
        String methodName,
        List<String> parameterNames,
        List<String> parameterTypes,
        String returnType,
        String sourceFile,
        long line,
        TriggerDefinition triggerDefinition,
        ScheduledJobDefinition scheduledJobDefinition,
        boolean securityDefiner,
        SecurityPolicy securityPolicy,
        boolean internalHelper
) {
    public DiscoveredEntryPoint {
        securityPolicy = securityPolicy == null ? SecurityPolicy.NONE : securityPolicy;
    }

    public DiscoveredEntryPoint(
            EntryPointKind kind,
            String className,
            String methodName,
            List<String> parameterNames,
            List<String> parameterTypes,
            String sourceFile,
            long line
    ) {
        this(kind, className, methodName, parameterNames, parameterTypes, "void", sourceFile, line, null, null, false, SecurityPolicy.NONE, false);
    }

    public DiscoveredEntryPoint(
            EntryPointKind kind,
            String className,
            String methodName,
            List<String> parameterNames,
            List<String> parameterTypes,
            String sourceFile,
            long line,
            TriggerDefinition triggerDefinition,
            ScheduledJobDefinition scheduledJobDefinition,
            boolean securityDefiner
    ) {
        this(kind, className, methodName, parameterNames, parameterTypes, "void", sourceFile, line, triggerDefinition, scheduledJobDefinition, securityDefiner, SecurityPolicy.NONE, false);
    }

    public DiscoveredEntryPoint(
            EntryPointKind kind,
            String className,
            String methodName,
            List<String> parameterNames,
            List<String> parameterTypes,
            String returnType,
            String sourceFile,
            long line,
            TriggerDefinition triggerDefinition,
            ScheduledJobDefinition scheduledJobDefinition,
            boolean securityDefiner,
            SecurityPolicy securityPolicy
    ) {
        this(kind, className, methodName, parameterNames, parameterTypes, returnType, sourceFile, line, triggerDefinition, scheduledJobDefinition, securityDefiner, securityPolicy, false);
    }

    public String methodSignatureKey() {
        return MethodSignatureKeys.of(className, methodName, parameterTypes);
    }
}
