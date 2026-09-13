package io.titan.transpiler.tir;

import java.util.List;

public record TriggerSpec(
        String table,
        String timing,
        List<String> events,
        String forEach
) {
}
