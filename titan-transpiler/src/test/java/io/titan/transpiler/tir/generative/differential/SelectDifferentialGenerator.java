package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.List;

public final class SelectDifferentialGenerator {

    public SelectCaseModel.SelectCase generate(long seed) {
        Family family = Family.values()[(int) Math.floorMod(seed, Family.values().length)];
        return switch (family) {
            case ACTIVE_EMAIL_ORDERED -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection("email", SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT))),
                    SelectCaseModel.and(
                            SelectCaseModel.compare(
                                    SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                    SelectCaseModel.ComparisonOperator.EQ,
                                    SelectCaseModel.boolLiteral(true)),
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                                    SelectCaseModel.NullCheckKind.IS_NOT_NULL)),
                    List.of(SelectCaseModel.orderBy(
                            "id",
                            SelectCaseModel.ValueType.INTEGER,
                            SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            case PLAN_NULL_OR_ID_EQ -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection("plan_code", SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT))),
                    SelectCaseModel.or(
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT),
                                    SelectCaseModel.NullCheckKind.IS_NULL),
                            SelectCaseModel.compare(
                                    SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                    SelectCaseModel.ComparisonOperator.EQ,
                                    SelectCaseModel.intLiteral(2))),
                    List.of(),
                    SelectCaseModel.ResultShape.ROW_SET);
            case LOGIN_COUNT_THRESHOLD_DESC -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection("login_count", SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER))),
                    SelectCaseModel.and(
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                    SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                            SelectCaseModel.and(
                                    SelectCaseModel.compare(
                                            SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                            SelectCaseModel.ComparisonOperator.GT,
                                            SelectCaseModel.intLiteral(1)),
                                    SelectCaseModel.compare(
                                            SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                            SelectCaseModel.ComparisonOperator.LE,
                                            SelectCaseModel.intLiteral(5)))),
                    List.of(SelectCaseModel.orderBy(
                            "id",
                            SelectCaseModel.ValueType.INTEGER,
                            SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            case EMAIL_IS_NULL -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection("email", SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT))),
                    SelectCaseModel.nullCheck(
                            SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                            SelectCaseModel.NullCheckKind.IS_NULL),
                    List.of(SelectCaseModel.orderBy(
                            "id",
                            SelectCaseModel.ValueType.INTEGER,
                            SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            case ACTIVE_FALSE_OR_ID_ONE -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection("active", SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN))),
                    SelectCaseModel.or(
                            SelectCaseModel.compare(
                                    SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                    SelectCaseModel.ComparisonOperator.EQ,
                                    SelectCaseModel.boolLiteral(false)),
                            SelectCaseModel.compare(
                                    SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                    SelectCaseModel.ComparisonOperator.EQ,
                                    SelectCaseModel.intLiteral(1))),
                    List.of(SelectCaseModel.orderBy(
                            "id",
                            SelectCaseModel.ValueType.INTEGER,
                            SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            case PLAN_NOT_NULL_AND_ID_GE -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection("plan_code", SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT))),
                    SelectCaseModel.and(
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT),
                                    SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                            SelectCaseModel.or(
                                    SelectCaseModel.compare(
                                            SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                            SelectCaseModel.ComparisonOperator.EQ,
                                            SelectCaseModel.intLiteral(4)),
                                    SelectCaseModel.compare(
                                            SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                            SelectCaseModel.ComparisonOperator.EQ,
                                            SelectCaseModel.intLiteral(5)))),
                    List.of(SelectCaseModel.orderBy(
                            "id",
                            SelectCaseModel.ValueType.INTEGER,
                            SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            case NULLABLE_LOGIN_LT -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection("login_count", SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER))),
                    SelectCaseModel.and(
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                    SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                            SelectCaseModel.compare(
                                    SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                    SelectCaseModel.ComparisonOperator.LT,
                                    SelectCaseModel.intLiteral(10))),
                    List.of(SelectCaseModel.orderBy(
                            "login_count",
                            SelectCaseModel.ValueType.INTEGER,
                            SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            case PLAN_CODE_NE -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection("plan_code", SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT))),
                    SelectCaseModel.and(
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT),
                                    SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                            SelectCaseModel.compare(
                                    SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT),
                                    SelectCaseModel.ComparisonOperator.NE,
                                    SelectCaseModel.textLiteral("free"))),
                    List.of(SelectCaseModel.orderBy(
                            "id",
                            SelectCaseModel.ValueType.INTEGER,
                            SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            case NOT_ACTIVE_TRUE -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection("active", SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN))),
                    SelectCaseModel.not(
                            SelectCaseModel.compare(
                                    SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                    SelectCaseModel.ComparisonOperator.EQ,
                                    SelectCaseModel.boolLiteral(true))),
                    List.of(SelectCaseModel.orderBy(
                            "id",
                            SelectCaseModel.ValueType.INTEGER,
                            SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            case ARITHMETIC_LOGIN_PLUS_ONE -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection(
                                    "login_plus_one",
                                    SelectCaseModel.add(
                                            SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                            SelectCaseModel.intLiteral(1)))),
                    SelectCaseModel.and(
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                    SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                            SelectCaseModel.compare(
                                    SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                    SelectCaseModel.ComparisonOperator.GT,
                                    SelectCaseModel.intLiteral(1))),
                    List.of(SelectCaseModel.orderBy(
                            "id",
                            SelectCaseModel.ValueType.INTEGER,
                            SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            case ARITHMETIC_LOGIN_MINUS_ONE -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection(
                                    "login_minus_one",
                                    SelectCaseModel.subtract(
                                            SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                            SelectCaseModel.intLiteral(1)))),
                    SelectCaseModel.and(
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                    SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                            SelectCaseModel.compare(
                                    SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                    SelectCaseModel.ComparisonOperator.GT,
                                    SelectCaseModel.intLiteral(1))),
                    List.of(SelectCaseModel.orderBy(
                            "id",
                            SelectCaseModel.ValueType.INTEGER,
                            SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            case ARITHMETIC_LOGIN_TIMES_TWO -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection(
                                    "login_times_two",
                                    SelectCaseModel.multiply(
                                            SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                            SelectCaseModel.intLiteral(2)))),
                    SelectCaseModel.and(
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                    SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                            SelectCaseModel.compare(
                                    SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                    SelectCaseModel.ComparisonOperator.GT,
                                    SelectCaseModel.intLiteral(1))),
                    List.of(SelectCaseModel.orderBy(
                            "id",
                            SelectCaseModel.ValueType.INTEGER,
                            SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            case NULLABLE_ARITHMETIC_PLUS_ONE -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection(
                                    "login_plus_one",
                                    SelectCaseModel.add(
                                            SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                            SelectCaseModel.intLiteral(1)))),
                    SelectCaseModel.compare(
                            SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                            SelectCaseModel.ComparisonOperator.GE,
                            SelectCaseModel.intLiteral(4)),
                    List.of(SelectCaseModel.orderBy(
                            "id",
                            SelectCaseModel.ValueType.INTEGER,
                            SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            case NULLABLE_ARITHMETIC_TIMES_TWO -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection(
                                    "login_times_two",
                                    SelectCaseModel.multiply(
                                            SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                            SelectCaseModel.intLiteral(2)))),
                    SelectCaseModel.compare(
                            SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                            SelectCaseModel.ComparisonOperator.GE,
                            SelectCaseModel.intLiteral(4)),
                    List.of(SelectCaseModel.orderBy(
                            "id",
                            SelectCaseModel.ValueType.INTEGER,
                            SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            // Plan 5.3, dialect-divergence-sensitive: a NULL login_count row stays visible in
            // the result and the ordering pins its placement explicitly. PostgreSQL emits
            // native NULLS LAST; MySQL (no NULLS syntax) emits the (expr IS NULL) ASC
            // leading-sort-key emulation — both must agree with the reference comparator.
            case NULLS_LAST_VISIBLE_ASC -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection("login_count", SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER))),
                    SelectCaseModel.compare(
                            SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                            SelectCaseModel.ComparisonOperator.GE,
                            SelectCaseModel.intLiteral(1)),
                    List.of(
                            SelectCaseModel.orderBy(
                                    "login_count",
                                    SelectCaseModel.ValueType.INTEGER,
                                    SelectCaseModel.SortDirection.ASC,
                                    SelectCaseModel.NullsPlacement.LAST),
                            SelectCaseModel.orderBy(
                                    "id",
                                    SelectCaseModel.ValueType.INTEGER,
                                    SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
            // Plan 5.3, dialect-divergence-sensitive: DESC with NULLS FIRST — PostgreSQL
            // native, MySQL emulated with (expr IS NULL) DESC ahead of the DESC value key.
            case NULLS_FIRST_VISIBLE_DESC -> SelectCaseModel.selectCase(
                    SelectDifferentialProfile.BASIC_SELECT.id(),
                    SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                    List.of(
                            SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                            SelectCaseModel.projection("login_count", SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER))),
                    SelectCaseModel.compare(
                            SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                            SelectCaseModel.ComparisonOperator.GE,
                            SelectCaseModel.intLiteral(1)),
                    List.of(
                            SelectCaseModel.orderBy(
                                    "login_count",
                                    SelectCaseModel.ValueType.INTEGER,
                                    SelectCaseModel.SortDirection.DESC,
                                    SelectCaseModel.NullsPlacement.FIRST),
                            SelectCaseModel.orderBy(
                                    "id",
                                    SelectCaseModel.ValueType.INTEGER,
                                    SelectCaseModel.SortDirection.ASC)),
                    SelectCaseModel.ResultShape.ROW_SET);
        };
    }

    private enum Family {
        ACTIVE_EMAIL_ORDERED,
        PLAN_NULL_OR_ID_EQ,
        LOGIN_COUNT_THRESHOLD_DESC,
        EMAIL_IS_NULL,
        ACTIVE_FALSE_OR_ID_ONE,
        PLAN_NOT_NULL_AND_ID_GE,
        NULLABLE_LOGIN_LT,
        PLAN_CODE_NE,
        NOT_ACTIVE_TRUE,
        ARITHMETIC_LOGIN_PLUS_ONE,
        ARITHMETIC_LOGIN_MINUS_ONE,
        ARITHMETIC_LOGIN_TIMES_TWO,
        NULLABLE_ARITHMETIC_PLUS_ONE,
        NULLABLE_ARITHMETIC_TIMES_TWO,
        NULLS_LAST_VISIBLE_ASC,
        NULLS_FIRST_VISIBLE_DESC
    }
}
