package io.titan.transpiler;

import java.util.List;

public record TriggerDefinition(
        String table,
        String timing,
        List<String> events,
        String forEach
) {
}
