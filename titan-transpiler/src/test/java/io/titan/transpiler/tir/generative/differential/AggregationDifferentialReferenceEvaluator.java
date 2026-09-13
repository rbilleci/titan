package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ResultRow;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class AggregationDifferentialReferenceEvaluator {

    ReferenceResult evaluate(
            AggregationDifferentialCaseModel.AggregationCase aggregationCase,
            FixtureCatalog.FixtureTable fixtureTable
    ) {
        Objects.requireNonNull(aggregationCase, "aggregationCase");
        Objects.requireNonNull(fixtureTable, "fixtureTable");

        Map<Object, AggregateState> grouped = new LinkedHashMap<>();
        for (FixtureCatalog.FixtureRow row : fixtureTable.rows()) {
            Object key = row.values().get(aggregationCase.groupKey().columnName());
            AggregateState state = grouped.computeIfAbsent(key, ignored -> new AggregateState());
            state.rowCount++;
            Object loginCount = row.values().get("login_count");
            if (loginCount instanceof Number number) {
                int value = number.intValue();
                state.sumLoginCount = (state.sumLoginCount == null ? 0 : state.sumLoginCount) + value;
                state.minLoginCount = state.minLoginCount == null ? value : Math.min(state.minLoginCount, value);
                state.maxLoginCount = state.maxLoginCount == null ? value : Math.max(state.maxLoginCount, value);
            }
        }

        List<ResultRow> rows = new ArrayList<>();
        for (Map.Entry<Object, AggregateState> entry : grouped.entrySet()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(aggregationCase.groupAlias(), entry.getKey());
            Object aggregateValue = switch (aggregationCase.aggregateKind()) {
                case COUNT_ALL -> (long) entry.getValue().rowCount;
                case SUM_LOGIN_COUNT -> entry.getValue().sumLoginCount;
                case MIN_LOGIN_COUNT -> entry.getValue().minLoginCount;
                case MAX_LOGIN_COUNT -> entry.getValue().maxLoginCount;
            };
            values.put(aggregationCase.aggregateAlias(), aggregateValue);
            if (passesHaving(aggregationCase.havingKind(), aggregateValue)) {
                rows.add(new ResultRow(values));
            }
        }
        rows.sort((left, right) -> compareGroupKeys(
                left.values().get(aggregationCase.groupAlias()),
                right.values().get(aggregationCase.groupAlias())));
        return new ReferenceResult(List.copyOf(rows));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compareGroupKeys(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        if (left instanceof Comparable comparableLeft && right instanceof Comparable comparableRight) {
            return comparableLeft.compareTo(comparableRight);
        }
        return left.toString().compareTo(right.toString());
    }

    private static boolean passesHaving(AggregationDifferentialCaseModel.HavingKind havingKind, Object aggregateValue) {
        return switch (havingKind) {
            case NONE -> true;
            case COUNT_GT_ONE -> aggregateValue instanceof Number number && number.longValue() > 1L;
            case SUM_GT_TEN -> aggregateValue instanceof Number number && number.longValue() > 10L;
        };
    }

    private static final class AggregateState {
        private int rowCount;
        private Integer sumLoginCount;
        private Integer minLoginCount;
        private Integer maxLoginCount;
    }
}
