package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.Objects;

final class AggregationDifferentialCaseModel {

    enum GroupKey {
        PLAN_CODE("plan_code", SelectCaseModel.ValueType.TEXT),
        ACTIVE("active", SelectCaseModel.ValueType.BOOLEAN);

        private final String columnName;
        private final SelectCaseModel.ValueType valueType;

        GroupKey(String columnName, SelectCaseModel.ValueType valueType) {
            this.columnName = columnName;
            this.valueType = valueType;
        }

        String columnName() {
            return columnName;
        }

        SelectCaseModel.ValueType valueType() {
            return valueType;
        }
    }

    enum AggregateKind {
        COUNT_ALL("count-all"),
        SUM_LOGIN_COUNT("sum-login-count"),
        MIN_LOGIN_COUNT("min-login-count"),
        MAX_LOGIN_COUNT("max-login-count");

        private final String id;

        AggregateKind(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    enum HavingKind {
        NONE("none", null),
        COUNT_GT_ONE("count-gt-one", 1L),
        SUM_GT_TEN("sum-gt-ten", 10L);

        private final String id;
        private final Long threshold;

        HavingKind(String id, Long threshold) {
            this.id = id;
            this.threshold = threshold;
        }

        String id() {
            return id;
        }

        Long threshold() {
            return threshold;
        }
    }

    record AggregationCase(
            String profileId,
            GroupKey groupKey,
            AggregateKind aggregateKind,
            HavingKind havingKind,
            String groupAlias,
            String aggregateAlias
    ) {
        AggregationCase {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            Objects.requireNonNull(groupKey, "groupKey");
            Objects.requireNonNull(aggregateKind, "aggregateKind");
            Objects.requireNonNull(havingKind, "havingKind");
            if (groupAlias == null || groupAlias.isBlank()) {
                throw new IllegalArgumentException("groupAlias must not be blank");
            }
            if (aggregateAlias == null || aggregateAlias.isBlank()) {
                throw new IllegalArgumentException("aggregateAlias must not be blank");
            }
        }

        String toStableJson() {
            return """
                    {
                      "profileId": "%s",
                      "groupKey": "%s",
                      "aggregateKind": "%s",
                      "havingKind": "%s",
                      "groupAlias": "%s",
                      "aggregateAlias": "%s"
                    }
                    """.formatted(profileId, groupKey.columnName(), aggregateKind.id(), havingKind.id(), groupAlias, aggregateAlias);
        }
    }

    static AggregationCase aggregationCase(
            String profileId,
            GroupKey groupKey,
            AggregateKind aggregateKind,
            HavingKind havingKind,
            String groupAlias,
            String aggregateAlias
    ) {
        return new AggregationCase(profileId, groupKey, aggregateKind, havingKind, groupAlias, aggregateAlias);
    }
}
