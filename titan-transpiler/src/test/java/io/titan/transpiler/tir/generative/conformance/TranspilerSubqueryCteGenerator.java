package io.titan.transpiler.tir.generative.conformance;

import java.util.List;

final class TranspilerSubqueryCteGenerator {

    GeneratedTranspilerCase generate(long seed) {
        ValidCaseFamily family = ValidCaseFamily.values()[(int) Math.floorMod(seed, ValidCaseFamily.values().length)];
        String className = "GeneratedSubqueryCteCase" + Long.toUnsignedString(seed);

        String source = switch (family) {
            case SCALAR_SUBQUERY -> """
                    import titan.dsl.*;
                    import static titan.dsl.DSL.*;

                    class %s {
                        static final AccountsTable ACCOUNTS = new AccountsTable();

                        @StoredProcedure
                        static void run() {
                            Column<Long> firstAccountId = scalar(
                                    select(ACCOUNTS.ID)
                                            .from(ACCOUNTS)
                                            .where(ACCOUNTS.ACTIVE.eq(true))
                                            .limit(1),
                                    SQLType.BIGINT);

                            select(ACCOUNTS.EMAIL)
                                    .from(ACCOUNTS)
                                    .where(ACCOUNTS.ID.eqColumn(firstAccountId))
                                    .fetch();
                        }

                        static final class AccountsTable extends Table<Object> {
                            final Column<Long> ID = column("id", SQLType.BIGINT, Nullability.NOT_NULL);
                            final Column<String> EMAIL = column("email", SQLType.VARCHAR, Nullability.NULLABLE);
                            final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NOT_NULL);

                            AccountsTable() {
                                super("accounts", "public");
                            }
                        }
                    }
                    """.formatted(className);
        };

        return new GeneratedTranspilerCase(
                seed,
                "transpiler-subquery-cte",
                family.id,
                className,
                source,
                "family=" + family.id,
                false,
                family.expectedFragments
        );
    }

    private enum ValidCaseFamily {
        SCALAR_SUBQUERY("scalar-subquery", List.of("select", "accounts"));

        private final String id;
        private final List<String> expectedFragments;

        ValidCaseFamily(String id, List<String> expectedFragments) {
            this.id = id;
            this.expectedFragments = expectedFragments;
        }
    }
}
