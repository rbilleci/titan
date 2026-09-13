package io.titan.transpiler.tir.generative.conformance;

import java.util.List;

final class TranspilerAggregationGenerator {

    GeneratedTranspilerCase generate(long seed) {
        AggregationFamily family = AggregationFamily.values()[(int) Math.floorMod(seed, AggregationFamily.values().length)];
        String className = "GeneratedAggregationCase" + Long.toUnsignedString(seed);

        String query = switch (family) {
            case COUNT_GROUP_HAVING -> "select(ACCOUNTS.ACTIVE, count())"
                    + ".from(ACCOUNTS)"
                    + ".groupBy(ACCOUNTS.ACTIVE)"
                    + ".having(count().gt(0L))"
                    + ".orderBy(count().desc())"
                    + ".fetch();";
            case SUM_GROUP_HAVING -> "select(ACCOUNTS.ACTIVE, sum(ACCOUNTS.BALANCE))"
                    + ".from(ACCOUNTS)"
                    + ".groupBy(ACCOUNTS.ACTIVE)"
                    + ".having(sum(ACCOUNTS.BALANCE).gt(0L))"
                    + ".fetch();";
            case AVG_GROUP_ORDER -> "select(ACCOUNTS.ACTIVE, avg(ACCOUNTS.BALANCE))"
                    + ".from(ACCOUNTS)"
                    + ".groupBy(ACCOUNTS.ACTIVE)"
                    + ".orderBy(avg(ACCOUNTS.BALANCE).desc())"
                    + ".fetch();";
            case MULTI_GROUP_COUNT -> "select(ACCOUNTS.ACTIVE, ACCOUNTS.STATUS, count())"
                    + ".from(ACCOUNTS)"
                    + ".groupBy(ACCOUNTS.ACTIVE, ACCOUNTS.STATUS)"
                    + ".having(ACCOUNTS.STATUS.isNotNull())"
                    + ".fetch();";
        };

        String source = """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class %s {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    static void run() {
                        %s
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Long> ID = column("id", SQLType.BIGINT, Nullability.NOT_NULL);
                        final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NOT_NULL);
                        final Column<String> STATUS = column("status", SQLType.VARCHAR, Nullability.NULLABLE);
                        final Column<Long> BALANCE = column("balance", SQLType.BIGINT, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """.formatted(className, query);

        return new GeneratedTranspilerCase(
                seed,
                "transpiler-aggregation",
                family.id,
                className,
                source,
                "family=" + family.id + ", query=" + query,
                false,
                family.expectedFragments
        );
    }

    private enum AggregationFamily {
        COUNT_GROUP_HAVING("count-group-having", List.of("group by", "count(", "having")),
        SUM_GROUP_HAVING("sum-group-having", List.of("group by", "sum(", "having")),
        AVG_GROUP_ORDER("avg-group-order", List.of("group by", "avg(", "order by")),
        MULTI_GROUP_COUNT("multi-group-count", List.of("group by", "count(", "status"));

        private final String id;
        private final List<String> expectedFragments;

        AggregationFamily(String id, List<String> expectedFragments) {
            this.id = id;
            this.expectedFragments = expectedFragments;
        }
    }
}
