package io.titan.transpiler.tir.generative.conformance;

import java.util.List;

final class TranspilerInvalidCompositionGenerator {

    GeneratedTranspilerCase generate(long seed) {
        InvalidCompositionFamily family = InvalidCompositionFamily.values()[(int) Math.floorMod(seed, InvalidCompositionFamily.values().length)];
        String className = "GeneratedInvalidCompositionCase" + Long.toUnsignedString(seed);

        String source = switch (family) {
            case NON_BOOLEAN_JOIN_ON -> """
                    import titan.dsl.*;
                    import static titan.dsl.DSL.*;

                    class %s {
                        static final AccountsTable ACCOUNTS = new AccountsTable();
                        static final PlansTable PLANS = new PlansTable();

                        @StoredProcedure
                        static void run() {
                            select(ACCOUNTS.ID, PLANS.NAME)
                                    .from(ACCOUNTS)
                                    .join(PLANS).on(ACCOUNTS.ACTIVE)
                                    .fetch();
                        }

                        static final class AccountsTable extends Table<Object> {
                            final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                            final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NOT_NULL);
                            final Column<Integer> PLAN_ID = column("plan_id", SQLType.INTEGER, Nullability.NULLABLE);

                            AccountsTable() {
                                super("accounts", "public");
                            }
                        }

                        static final class PlansTable extends Table<Object> {
                            final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                            final Column<String> NAME = column("name", SQLType.VARCHAR, Nullability.NOT_NULL);

                            PlansTable() {
                                super("plans", "public");
                            }
                        }
                    }
                    """.formatted(className);
            case UNKNOWN_TABLE_IN_JOINED_WHERE -> """
                    import titan.dsl.*;
                    import static titan.dsl.DSL.*;

                    class %s {
                        static final AccountsTable ACCOUNTS = new AccountsTable();
                        static final PlansTable PLANS = new PlansTable();
                        static final UsersTable USERS = new UsersTable();

                        @StoredProcedure
                        static void run() {
                            select(ACCOUNTS.ID, PLANS.NAME)
                                    .from(ACCOUNTS)
                                    .join(PLANS).on(ACCOUNTS.PLAN_ID.eqColumn(PLANS.ID))
                                    .where(USERS.EMAIL.eq("x"))
                                    .fetch();
                        }

                        static final class AccountsTable extends Table<Object> {
                            final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                            final Column<Integer> PLAN_ID = column("plan_id", SQLType.INTEGER, Nullability.NULLABLE);

                            AccountsTable() {
                                super("accounts", "public");
                            }
                        }

                        static final class PlansTable extends Table<Object> {
                            final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                            final Column<String> NAME = column("name", SQLType.VARCHAR, Nullability.NOT_NULL);

                            PlansTable() {
                                super("plans", "public");
                            }
                        }

                        static final class UsersTable extends Table<Object> {
                            final Column<String> EMAIL = column("email", SQLType.VARCHAR, Nullability.NOT_NULL);

                            UsersTable() {
                                super("users", "public");
                            }
                        }
                    }
                    """.formatted(className);
            case FETCH_INTO_MISSING_COMPONENT_ON_JOIN -> """
                    import titan.dsl.*;
                    import static titan.dsl.DSL.*;

                    class %s {
                        static final AccountsTable ACCOUNTS = new AccountsTable();
                        static final PlansTable PLANS = new PlansTable();

                        record AccountPlanProjection(Integer id, String missingField) {}

                        @StoredProcedure
                        static void run() {
                            select(ACCOUNTS.ID, PLANS.NAME)
                                    .from(ACCOUNTS)
                                    .join(PLANS).on(ACCOUNTS.PLAN_ID.eqColumn(PLANS.ID))
                                    .fetchInto(AccountPlanProjection.class);
                        }

                        static final class AccountsTable extends Table<Object> {
                            final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                            final Column<Integer> PLAN_ID = column("plan_id", SQLType.INTEGER, Nullability.NULLABLE);

                            AccountsTable() {
                                super("accounts", "public");
                            }
                        }

                        static final class PlansTable extends Table<Object> {
                            final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                            final Column<String> NAME = column("name", SQLType.VARCHAR, Nullability.NOT_NULL);

                            PlansTable() {
                                super("plans", "public");
                            }
                        }
                    }
                    """.formatted(className);
            case FETCH_INTO_PROJECTION_COUNT_MISMATCH_ON_JOIN -> """
                    import titan.dsl.*;
                    import static titan.dsl.DSL.*;

                    class %s {
                        static final AccountsTable ACCOUNTS = new AccountsTable();
                        static final PlansTable PLANS = new PlansTable();

                        record AccountPlanProjection(Integer id, String name) {}

                        @StoredProcedure
                        static void run() {
                            select(ACCOUNTS.ID, PLANS.NAME, ACCOUNTS.ACTIVE)
                                    .from(ACCOUNTS)
                                    .join(PLANS).on(ACCOUNTS.PLAN_ID.eqColumn(PLANS.ID))
                                    .fetchInto(AccountPlanProjection.class);
                        }

                        static final class AccountsTable extends Table<Object> {
                            final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                            final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NOT_NULL);
                            final Column<Integer> PLAN_ID = column("plan_id", SQLType.INTEGER, Nullability.NULLABLE);

                            AccountsTable() {
                                super("accounts", "public");
                            }
                        }

                        static final class PlansTable extends Table<Object> {
                            final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                            final Column<String> NAME = column("name", SQLType.VARCHAR, Nullability.NOT_NULL);

                            PlansTable() {
                                super("plans", "public");
                            }
                        }
                    }
                    """.formatted(className);
            case FETCH_INTO_NON_IDENTIFIER_PROJECTION -> """
                    import titan.dsl.*;
                    import static titan.dsl.DSL.*;

                    class %s {
                        static final AccountsTable ACCOUNTS = new AccountsTable();

                        record CountProjection(Long count) {}

                        @StoredProcedure
                        static void run() {
                            select(count())
                                    .from(ACCOUNTS)
                                    .fetchInto(CountProjection.class);
                        }

                        static final class AccountsTable extends Table<Object> {
                            final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                            AccountsTable() {
                                super("accounts", "public");
                            }
                        }
                    }
                    """.formatted(className);
            case FETCH_INTO_AMBIGUOUS_NORMALIZED_PROJECTION -> """
                    import titan.dsl.*;
                    import static titan.dsl.DSL.*;

                    class %s {
                        static final AccountsTable ACCOUNTS = new AccountsTable();

                        record AmbiguousPlanProjection(Integer planId, Integer planid) {}

                        @StoredProcedure
                        static void run() {
                            select(ACCOUNTS.PLAN_ID, ACCOUNTS.PLANID)
                                    .from(ACCOUNTS)
                                    .fetchInto(AmbiguousPlanProjection.class);
                        }

                        static final class AccountsTable extends Table<Object> {
                            final Column<Integer> PLAN_ID = column("plan_id", SQLType.INTEGER, Nullability.NOT_NULL);
                            final Column<Integer> PLANID = column("planid", SQLType.INTEGER, Nullability.NOT_NULL);

                            AccountsTable() {
                                super("accounts", "public");
                            }
                        }
                    }
                    """.formatted(className);
            case FOR_EACH_CALLBACK_ARITY_MISMATCH -> """
                    import titan.dsl.*;
                    import static titan.dsl.DSL.*;

                    class %s {
                        static final AccountsTable ACCOUNTS = new AccountsTable();

                        @StoredProcedure
                        static void run() {
                            select(ACCOUNTS.ID)
                                    .from(ACCOUNTS)
                                    .forEach(new TwoArgConsumer());
                        }

                        static final class TwoArgConsumer implements java.util.function.BiConsumer<Object, Object> {
                            @Override
                            public void accept(Object id, Object email) {
                            }
                        }

                        static final class AccountsTable extends Table<Object> {
                            final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                            AccountsTable() {
                                super("accounts", "public");
                            }
                        }
                    }
                    """.formatted(className);
        };

        return new GeneratedTranspilerCase(
                seed,
                "transpiler-invalid-composition",
                family.id,
                className,
                source,
                "family=" + family.id,
                true,
                family.expectedFragments
        );
    }

    private enum InvalidCompositionFamily {
        NON_BOOLEAN_JOIN_ON("non-boolean-join-on", List.of("TITAN-E001", "Java sources must compile before transpilation", "no suitable method found for on(")),
        UNKNOWN_TABLE_IN_JOINED_WHERE("unknown-table-in-joined-where", List.of("TITAN-E001", "USERS.EMAIL")),
        FETCH_INTO_MISSING_COMPONENT_ON_JOIN("fetch-into-missing-component-on-join", List.of("TITAN-E001", "fetchInto(AccountPlanProjection.class)", "missingField")),
        FETCH_INTO_PROJECTION_COUNT_MISMATCH_ON_JOIN(
                "fetch-into-projection-count-mismatch-on-join",
                List.of("TITAN-E001", "fetchInto(AccountPlanProjection.class)", "projected column count to match record component count")),
        FETCH_INTO_NON_IDENTIFIER_PROJECTION(
                "fetch-into-non-identifier-projection",
                List.of("TITAN-E001", "fetchInto(CountProjection.class)", "identifier-shaped")),
        FETCH_INTO_AMBIGUOUS_NORMALIZED_PROJECTION(
                "fetch-into-ambiguous-normalized-projection",
                List.of("TITAN-E001", "fetchInto(AmbiguousPlanProjection.class)", "multiple projected columns normalize to the same identifier")),
        FOR_EACH_CALLBACK_ARITY_MISMATCH(
                "for-each-callback-arity-mismatch",
                List.of("TITAN-E001", "object or record construction in transpiled code"));

        private final String id;
        private final List<String> expectedFragments;

        InvalidCompositionFamily(String id, List<String> expectedFragments) {
            this.id = id;
            this.expectedFragments = expectedFragments;
        }
    }
}
