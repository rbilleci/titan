package io.titan.transpiler.tir.generative.conformance;

import java.util.List;

final class TranspilerInvalidCaseGenerator {

    GeneratedTranspilerCase generate(long seed) {
        InvalidCaseFamily family = InvalidCaseFamily.values()[(int) Math.floorMod(seed, InvalidCaseFamily.values().length)];
        String className = "GeneratedInvalidCase" + Long.toUnsignedString(seed);

        String source = switch (family) {
            case UNKNOWN_TABLE_REFERENCE -> """
                    import titan.dsl.*;
                    import static titan.dsl.DSL.*;

                    class %s {
                        static final AccountsTable ACCOUNTS = new AccountsTable();
                        static final AccountsTable USERS = new AccountsTable();

                        @StoredProcedure
                        static void run() {
                            select(USERS.EMAIL).from(ACCOUNTS).fetch();
                        }

                        static final class AccountsTable extends Table<Object> {
                            final Column<String> EMAIL = column("email", SQLType.VARCHAR, Nullability.NOT_NULL);

                            AccountsTable() {
                                super("accounts", "public");
                            }
                        }
                    }
                    """.formatted(className);
            case DYNAMIC_SQL_CONCAT -> """
                    import titan.dsl.StoredProcedure;

                    class %s {
                        @StoredProcedure
                        static void run(String tableName) {
                            exec("SELECT * FROM " + tableName);
                        }

                        static void exec(String sql) {
                        }
                    }
                    """.formatted(className);
        };

        return new GeneratedTranspilerCase(
                seed,
                "transpiler-invalid",
                family.id,
                className,
                source,
                "family=" + family.id,
                true,
                family.expectedFragments()
        );
    }

    private enum InvalidCaseFamily {
        UNKNOWN_TABLE_REFERENCE("unknown-table-reference", List.of("TITAN-E001", "USERS.EMAIL")),
        DYNAMIC_SQL_CONCAT("dynamic-sql-concat", List.of("TITAN-E004"));

        private final String id;
        private final List<String> expectedFragments;

        InvalidCaseFamily(String id, List<String> expectedFragments) {
            this.id = id;
            this.expectedFragments = expectedFragments;
        }

        List<String> expectedFragments() {
            return expectedFragments;
        }
    }
}
