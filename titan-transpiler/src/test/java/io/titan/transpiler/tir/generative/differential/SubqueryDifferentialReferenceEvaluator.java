package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ResultRow;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class SubqueryDifferentialReferenceEvaluator {

    ReferenceResult evaluate(
            SubqueryDifferentialCaseModel.Case subqueryCase,
            FixtureCatalog.FixtureTable fixtureTable
    ) {
        Objects.requireNonNull(subqueryCase, "subqueryCase");
        Objects.requireNonNull(fixtureTable, "fixtureTable");

        List<ResultRow> rows = new ArrayList<>();
        switch (subqueryCase.family()) {
            case EXISTS_ENTERPRISE -> {
                boolean existsEnterprise = fixtureTable.rows().stream()
                        .anyMatch(row -> "enterprise".equals(row.values().get("plan_code")));
                if (existsEnterprise) {
                    for (FixtureCatalog.FixtureRow row : fixtureTable.rows()) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("id", row.values().get("id"));
                        values.put("plan_code", row.values().get("plan_code"));
                        rows.add(new ResultRow(values));
                    }
                }
            }
            case NOT_EXISTS_VIP_ACTIVE -> {
                boolean existsVip = fixtureTable.rows().stream()
                        .anyMatch(row -> "vip".equals(row.values().get("plan_code")));
                if (!existsVip) {
                    for (FixtureCatalog.FixtureRow row : fixtureTable.rows()) {
                        if (Boolean.TRUE.equals(row.values().get("active"))) {
                            Map<String, Object> values = new LinkedHashMap<>();
                            values.put("id", row.values().get("id"));
                            values.put("email", row.values().get("email"));
                            rows.add(new ResultRow(values));
                        }
                    }
                }
            }
            case CORRELATED_EXISTS_ACTIVE_PLAN -> {
                for (FixtureCatalog.FixtureRow row : fixtureTable.rows()) {
                    if (!Boolean.TRUE.equals(row.values().get("active"))) {
                        continue;
                    }
                    Object outerPlanCode = row.values().get("plan_code");
                    boolean matchingPlanExists = fixtureTable.rows().stream()
                            .anyMatch(inner -> Objects.equals(inner.values().get("plan_code"), outerPlanCode)
                                    && outerPlanCode != null);
                    if (matchingPlanExists) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("id", row.values().get("id"));
                        values.put("email", row.values().get("email"));
                        rows.add(new ResultRow(values));
                    }
                }
            }
            case CORRELATED_NOT_EXISTS_NULL_PLAN -> {
                for (FixtureCatalog.FixtureRow row : fixtureTable.rows()) {
                    if (!Boolean.TRUE.equals(row.values().get("active"))) {
                        continue;
                    }
                    Object outerPlanCode = row.values().get("plan_code");
                    boolean matchingPlanExists = fixtureTable.rows().stream()
                            .anyMatch(inner -> Objects.equals(inner.values().get("plan_code"), outerPlanCode)
                                    && outerPlanCode != null);
                    if (!matchingPlanExists) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("id", row.values().get("id"));
                        values.put("email", row.values().get("email"));
                        rows.add(new ResultRow(values));
                    }
                }
            }
            case CORRELATED_SCALAR_EMAIL_BY_PLAN -> {
                for (FixtureCatalog.FixtureRow row : fixtureTable.rows()) {
                    if (!Boolean.TRUE.equals(row.values().get("active"))) {
                        continue;
                    }
                    Object outerPlanCode = row.values().get("plan_code");
                    String scalarEmail = fixtureTable.rows().stream()
                            .filter(inner -> Objects.equals(inner.values().get("plan_code"), outerPlanCode)
                                    && outerPlanCode != null)
                            .map(inner -> (String) inner.values().get("email"))
                            .findFirst()
                            .orElse(null);
                    if (scalarEmail != null && Objects.equals(row.values().get("email"), scalarEmail)) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("id", row.values().get("id"));
                        values.put("email", row.values().get("email"));
                        rows.add(new ResultRow(values));
                    }
                }
            }
            case CORRELATED_SCALAR_NULL_OR_ABSENT_EMAIL_BY_PLAN -> {
                for (FixtureCatalog.FixtureRow row : fixtureTable.rows()) {
                    if (!Boolean.TRUE.equals(row.values().get("active"))) {
                        continue;
                    }
                    Object outerPlanCode = row.values().get("plan_code");
                    if (outerPlanCode == null) {
                        continue;
                    }
                    Object scalarEmail = fixtureTable.rows().stream()
                            .filter(inner -> Objects.equals(inner.values().get("plan_code"), outerPlanCode))
                            .filter(inner -> inner.values().get("email") == null)
                            .findFirst()
                            .map(FixtureCatalog.FixtureRow::values)
                            .map(values -> values.get("email"))
                            .orElse(null);
                    if (scalarEmail == null) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("id", row.values().get("id"));
                        values.put("email", row.values().get("email"));
                        rows.add(new ResultRow(values));
                    }
                }
            }
            case SCALAR_EMAIL_LOOKUP -> {
                String emailLookup = fixtureTable.rows().stream()
                        .filter(row -> Integer.valueOf(1).equals(row.values().get("id")))
                        .map(row -> (String) row.values().get("email"))
                        .findFirst()
                        .orElse(null);
                for (FixtureCatalog.FixtureRow row : fixtureTable.rows()) {
                    if (Objects.equals(row.values().get("email"), emailLookup)) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("id", row.values().get("id"));
                        values.put("email", row.values().get("email"));
                        rows.add(new ResultRow(values));
                    }
                }
            }
            case SCALAR_NULL_EMAIL_LOOKUP -> {
                FixtureCatalog.FixtureRow lookupRow = fixtureTable.rows().stream()
                        .filter(row -> Integer.valueOf(3).equals(row.values().get("id")))
                        .findFirst()
                        .orElseThrow();
                Object emailLookup = lookupRow.values().get("email");
                if (emailLookup != null) {
                    for (FixtureCatalog.FixtureRow row : fixtureTable.rows()) {
                        if (Objects.equals(row.values().get("email"), emailLookup)) {
                            Map<String, Object> values = new LinkedHashMap<>();
                            values.put("id", row.values().get("id"));
                            values.put("email", row.values().get("email"));
                            rows.add(new ResultRow(values));
                        }
                    }
                }
            }
            case CTE_ACTIVE_ROWS -> {
                for (FixtureCatalog.FixtureRow row : fixtureTable.rows()) {
                    if (Boolean.TRUE.equals(row.values().get("active"))) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("id", row.values().get("id"));
                        values.put("email", row.values().get("email"));
                        rows.add(new ResultRow(values));
                    }
                }
            }
        }
        rows.sort(Comparator.comparing(ResultRow::toStableJson));
        return new ReferenceResult(List.copyOf(rows));
    }
}
