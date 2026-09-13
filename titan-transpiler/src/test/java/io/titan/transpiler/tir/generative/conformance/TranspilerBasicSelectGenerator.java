package io.titan.transpiler.tir.generative.conformance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

final class TranspilerBasicSelectGenerator {

    GeneratedTranspilerCase generate(long seed) {
        Random random = new Random(seed);
        String className = "GeneratedSelectCase" + Long.toUnsignedString(seed);
        CaseFamily family = CaseFamily.values()[(int) Math.floorMod(seed, CaseFamily.values().length)];

        List<String> projections = chooseProjections(random, family);
        String query = switch (family) {
            case SIMPLE_FILTER -> buildSimpleFilterQuery(random, projections);
            case BOOLEAN_FILTER -> buildBooleanFilterQuery(random, projections);
            case JOINED_SELECT -> buildJoinQuery(random, projections);
            case GROUPED_AGGREGATE -> buildGroupedAggregateQuery(random);
        };

        String source = """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class %s {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final PlansTable PLANS = new PlansTable();

                    @StoredProcedure
                    static void run(long accountId, String email, boolean active) {
                        %s
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Long> ID = column("id", SQLType.BIGINT, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, Nullability.NULLABLE);
                        final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NOT_NULL);
                        final Column<Long> PLAN_ID = column("plan_id", SQLType.BIGINT, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class PlansTable extends Table<Object> {
                        final Column<Long> ID = column("id", SQLType.BIGINT, Nullability.NOT_NULL);
                        final Column<String> NAME = column("name", SQLType.VARCHAR, Nullability.NOT_NULL);

                        PlansTable() {
                            super("plans", "public");
                        }
                    }
                }
                """.formatted(className, query);

        String summary = "family=" + family.id + ", projections=" + projections + ", query=" + query;
        return new GeneratedTranspilerCase(seed, "transpiler-basic-select", family.id, className, source, summary, false, family.expectedSqlFragments());
    }

    private static String buildSimpleFilterQuery(Random random, List<String> projections) {
        List<String> predicates = chooseBasePredicates(random);
        String select = "select(" + String.join(", ", projections) + ")";
        String where = ".where(" + chainPredicates(predicates, ".and") + ")";
        String orderByClause = random.nextBoolean() ? ".orderBy(" + chooseOrderColumn(random) + ")" : "";
        String limitClause = random.nextBoolean() ? ".limit(" + (1 + random.nextInt(5)) + ")" : "";
        String offsetClause = limitClause.isEmpty() || !random.nextBoolean() ? "" : ".offset(" + random.nextInt(3) + ")";
        String fetchTerminal = random.nextBoolean() ? ".fetch()" : ".fetchOne()";
        return select + ".from(ACCOUNTS)" + where + orderByClause + limitClause + offsetClause + fetchTerminal + ";";
    }

    private static String buildBooleanFilterQuery(Random random, List<String> projections) {
        List<String> predicates = chooseBasePredicates(random);
        String select = "select(" + String.join(", ", projections) + ")";
        String boolExpression = chainPredicates(predicates, random.nextBoolean() ? ".and" : ".or");
        return select + ".from(ACCOUNTS).where(" + boolExpression + ").fetch();";
    }

    private static String buildJoinQuery(Random random, List<String> projections) {
        List<String> joinProjections = new ArrayList<>(projections);
        if (random.nextBoolean()) {
            joinProjections.add("PLANS.NAME");
        }
        String select = "select(" + String.join(", ", dedupe(joinProjections)) + ")";
        String joinKeyword = random.nextBoolean() ? ".join(PLANS)" : ".leftJoin(PLANS)";
        String where = random.nextBoolean() ? ".where(ACCOUNTS.ACTIVE.eq(active))" : "";
        return select
                + ".from(ACCOUNTS)"
                + joinKeyword
                + ".on(ACCOUNTS.PLAN_ID.eqColumn(PLANS.ID))"
                + where
                + ".fetch();";
    }

    private static String buildGroupedAggregateQuery(Random random) {
        String having = random.nextBoolean() ? ".having(count().gt(0L))" : ".having(ACCOUNTS.ACTIVE.isNotNull())";
        String orderBy = random.nextBoolean() ? ".orderBy(count().desc())" : "";
        return "select(ACCOUNTS.ACTIVE, count())"
                + ".from(ACCOUNTS)"
                + ".groupBy(ACCOUNTS.ACTIVE)"
                + having
                + orderBy
                + ".fetch();";
    }

    private static List<String> chooseProjections(Random random, CaseFamily family) {
        String[] columns = switch (family) {
            case JOINED_SELECT -> new String[]{"ACCOUNTS.ID", "ACCOUNTS.EMAIL", "ACCOUNTS.ACTIVE", "PLANS.NAME"};
            default -> new String[]{"ACCOUNTS.ID", "ACCOUNTS.EMAIL", "ACCOUNTS.ACTIVE"};
        };
        Set<String> chosen = new LinkedHashSet<>();
        int count = 1 + random.nextInt(Math.min(3, columns.length));
        while (chosen.size() < count) {
            chosen.add(columns[random.nextInt(columns.length)]);
        }
        return List.copyOf(chosen);
    }

    private static List<String> chooseBasePredicates(Random random) {
        List<String> predicates = new ArrayList<>();
        if (random.nextBoolean()) {
            predicates.add("ACCOUNTS.ID.eq(accountId)");
        }
        if (random.nextBoolean()) {
            predicates.add("ACCOUNTS.EMAIL.eq(email)");
        }
        if (random.nextBoolean()) {
            predicates.add("ACCOUNTS.ACTIVE.eq(active)");
        }
        if (predicates.isEmpty()) {
            predicates.add("ACCOUNTS.ID.eq(accountId)");
            predicates.add("ACCOUNTS.ACTIVE.eq(active)");
        }
        return List.copyOf(predicates);
    }

    private static String chainPredicates(List<String> predicates, String connector) {
        String expression = predicates.getFirst();
        for (int i = 1; i < predicates.size(); i++) {
            expression = expression + connector + "(" + predicates.get(i) + ")";
        }
        return expression;
    }

    private static List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static String chooseOrderColumn(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> "ACCOUNTS.ID.asc()";
            case 1 -> "ACCOUNTS.EMAIL.asc()";
            default -> "ACCOUNTS.ACTIVE.asc()";
        };
    }

    private enum CaseFamily {
        SIMPLE_FILTER("simple-filter", List.of("select", "from app.accounts")),
        BOOLEAN_FILTER("boolean-filter", List.of("where")),
        JOINED_SELECT("joined-select", List.of("join", "plans")),
        GROUPED_AGGREGATE("grouped-aggregate", List.of("group by", "count("));

        private final String id;
        private final List<String> expectedSqlFragments;

        CaseFamily(String id, List<String> expectedSqlFragments) {
            this.id = id;
            this.expectedSqlFragments = expectedSqlFragments;
        }

        List<String> expectedSqlFragments() {
            return expectedSqlFragments;
        }
    }
}
