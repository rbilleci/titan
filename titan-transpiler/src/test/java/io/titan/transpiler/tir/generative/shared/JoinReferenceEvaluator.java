package io.titan.transpiler.tir.generative.shared;

import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ResultRow;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JoinReferenceEvaluator {

    public ReferenceResult evaluate(
            JoinCaseModel.JoinCase joinCase,
            FixtureCatalog.FixtureTable accounts,
            FixtureCatalog.FixtureTable plans,
            FixtureCatalog.FixtureTable planFamilies
    ) {
        Objects.requireNonNull(joinCase, "joinCase");
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(plans, "plans");
        Objects.requireNonNull(planFamilies, "planFamilies");

        List<ResultRow> rows = new ArrayList<>();
        for (FixtureCatalog.FixtureRow account : accounts.rows()) {
            if (!includeAccount(joinCase.family(), account)) {
                continue;
            }
            Object accountPlanCode = account.values().get("plan_code");
            boolean matched = false;
            for (FixtureCatalog.FixtureRow plan : plans.rows()) {
                if (!Objects.equals(accountPlanCode, plan.values().get("code"))) {
                    continue;
                }
                matched = true;
                if (!includePlan(joinCase.family(), plan)) {
                    continue;
                }
                if (joinCase.family() == JoinCaseModel.Family.TWO_JOIN_ACTIVE_PLAN_FAMILY
                        || joinCase.family() == JoinCaseModel.Family.TWO_JOIN_DUPLICATE_SECOND_HOP_MULTIPLICATION
                        || joinCase.family() == JoinCaseModel.Family.TWO_JOIN_SECOND_HOP_FILTER
                        || joinCase.family() == JoinCaseModel.Family.LEFT_TWO_JOIN_NULL_EXTENSION
                        || joinCase.family() == JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_FILTER
                        || joinCase.family() == JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION) {
                    Object familyCode = plan.values().get("family_code");
                    boolean familyMatched = false;
                    for (FixtureCatalog.FixtureRow planFamily : planFamilies.rows()) {
                        if (!Objects.equals(familyCode, planFamily.values().get("code"))) {
                            continue;
                        }
                        if ((joinCase.family() == JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_FILTER
                                || joinCase.family() == JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION
                                || joinCase.family() == JoinCaseModel.Family.TWO_JOIN_SECOND_HOP_FILTER)
                                && !Objects.equals("Growth", planFamily.values().get("label"))) {
                            continue;
                        }
                        familyMatched = true;
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("account_id", account.values().get("id"));
                        values.put("plan_name", planFamily.values().get("label"));
                        rows.add(new ResultRow(values));
                    }
                    if (!familyMatched && (joinCase.family() == JoinCaseModel.Family.LEFT_TWO_JOIN_NULL_EXTENSION
                            || joinCase.family() == JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_FILTER)) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("account_id", account.values().get("id"));
                        values.put("plan_name", null);
                        rows.add(new ResultRow(values));
                    }
                    continue;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("account_id", account.values().get("id"));
                values.put("plan_name", plan.values().get("name"));
                rows.add(new ResultRow(values));
            }
            if (!matched && preservesNullExtension(joinCase.family())) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("account_id", account.values().get("id"));
                values.put("plan_name", null);
                rows.add(new ResultRow(values));
            } else if (matched && joinCase.family() == JoinCaseModel.Family.LEFT_JOIN_PAID_FILTER_PRESERVES_NULLS) {
                boolean anyIncluded = plans.rows().stream()
                        .filter(plan -> Objects.equals(accountPlanCode, plan.values().get("code")))
                        .anyMatch(plan -> includePlan(joinCase.family(), plan));
                if (!anyIncluded) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("account_id", account.values().get("id"));
                    values.put("plan_name", null);
                    rows.add(new ResultRow(values));
                }
            }
        }
        rows.sort(Comparator.comparing(ResultRow::toStableJson));
        return new ReferenceResult(List.copyOf(rows));
    }

    private static boolean includeAccount(
            JoinCaseModel.Family family,
            FixtureCatalog.FixtureRow account
    ) {
        return switch (family) {
            case INNER_JOIN_ACTIVE_ACCOUNTS,
                 INNER_JOIN_DUPLICATE_PLAN_MATCHES,
                 LEFT_JOIN_ACTIVE_NULL_EXTENSION,
                 LEFT_JOIN_PAID_FILTER_PRESERVES_NULLS,
                 LEFT_JOIN_PAID_WHERE_COLLAPSES_NULL_EXTENSION,
                 TWO_JOIN_ACTIVE_PLAN_FAMILY,
                 TWO_JOIN_DUPLICATE_SECOND_HOP_MULTIPLICATION,
                 TWO_JOIN_SECOND_HOP_FILTER,
                 LEFT_JOIN_DUPLICATE_NULL_EXTENSION,
                 LEFT_TWO_JOIN_NULL_EXTENSION,
                 LEFT_TWO_JOIN_SECOND_HOP_FILTER,
                 LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION -> Boolean.TRUE.equals(account.values().get("active"));
            case INNER_JOIN_PAID_PLANS -> true;
        };
    }

    private static boolean includePlan(
            JoinCaseModel.Family family,
            FixtureCatalog.FixtureRow plan
    ) {
        return switch (family) {
            case INNER_JOIN_PAID_PLANS,
                 LEFT_JOIN_PAID_FILTER_PRESERVES_NULLS,
                 LEFT_JOIN_PAID_WHERE_COLLAPSES_NULL_EXTENSION -> Boolean.TRUE.equals(plan.values().get("paid"));
            case INNER_JOIN_ACTIVE_ACCOUNTS,
                 INNER_JOIN_DUPLICATE_PLAN_MATCHES,
                 LEFT_JOIN_ACTIVE_NULL_EXTENSION,
                 TWO_JOIN_ACTIVE_PLAN_FAMILY,
                 TWO_JOIN_DUPLICATE_SECOND_HOP_MULTIPLICATION,
                 TWO_JOIN_SECOND_HOP_FILTER,
                 LEFT_JOIN_DUPLICATE_NULL_EXTENSION,
                 LEFT_TWO_JOIN_NULL_EXTENSION,
                 LEFT_TWO_JOIN_SECOND_HOP_FILTER,
                 LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION -> true;
        };
    }

    private static boolean preservesNullExtension(JoinCaseModel.Family family) {
        return switch (family) {
            case LEFT_JOIN_ACTIVE_NULL_EXTENSION,
                 LEFT_JOIN_PAID_FILTER_PRESERVES_NULLS,
                 LEFT_JOIN_DUPLICATE_NULL_EXTENSION,
                 LEFT_TWO_JOIN_NULL_EXTENSION,
                 LEFT_TWO_JOIN_SECOND_HOP_FILTER -> true;
            case INNER_JOIN_ACTIVE_ACCOUNTS,
                 INNER_JOIN_PAID_PLANS,
                 INNER_JOIN_DUPLICATE_PLAN_MATCHES,
                 LEFT_JOIN_PAID_WHERE_COLLAPSES_NULL_EXTENSION,
                 TWO_JOIN_ACTIVE_PLAN_FAMILY,
                 TWO_JOIN_DUPLICATE_SECOND_HOP_MULTIPLICATION,
                 TWO_JOIN_SECOND_HOP_FILTER,
                 LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION -> false;
        };
    }
}
