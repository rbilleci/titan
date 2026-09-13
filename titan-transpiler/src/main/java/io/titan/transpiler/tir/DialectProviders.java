package io.titan.transpiler.tir;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class DialectProviders {
    private final Map<DialectId, DialectProvider> providers;

    public DialectProviders() {
        this(defaultProviders());
    }

    public DialectProviders(Map<DialectId, DialectProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        this.providers = new EnumMap<>(providers);
    }

    public DialectProvider require(DialectId dialectId) {
        DialectProvider provider = providers.get(dialectId);
        if (provider == null) {
            throw new IllegalArgumentException("No dialect provider registered for " + dialectId);
        }
        return provider;
    }

    private static Map<DialectId, DialectProvider> defaultProviders() {
        DialectProvider postgres = new PostgreSqlDialectProvider();
        DialectProvider mysql = new MySqlDialectProvider();
        return Map.of(postgres.id(), postgres, mysql.id(), mysql);
    }
}
