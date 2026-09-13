package io.titan.transpiler.tir.generative.differential;

final class SubqueryDifferentialGenerator {

    SubqueryDifferentialCaseModel.Case generate(long seed) {
        SubqueryDifferentialCaseModel.Family family = switch ((int) Math.floorMod(seed, 9)) {
            case 0 -> SubqueryDifferentialCaseModel.Family.EXISTS_ENTERPRISE;
            case 1 -> SubqueryDifferentialCaseModel.Family.NOT_EXISTS_VIP_ACTIVE;
            case 2 -> SubqueryDifferentialCaseModel.Family.CORRELATED_EXISTS_ACTIVE_PLAN;
            case 3 -> SubqueryDifferentialCaseModel.Family.CORRELATED_NOT_EXISTS_NULL_PLAN;
            case 4 -> SubqueryDifferentialCaseModel.Family.CORRELATED_SCALAR_EMAIL_BY_PLAN;
            case 5 -> SubqueryDifferentialCaseModel.Family.CORRELATED_SCALAR_NULL_OR_ABSENT_EMAIL_BY_PLAN;
            case 6 -> SubqueryDifferentialCaseModel.Family.SCALAR_EMAIL_LOOKUP;
            case 7 -> SubqueryDifferentialCaseModel.Family.SCALAR_NULL_EMAIL_LOOKUP;
            default -> SubqueryDifferentialCaseModel.Family.CTE_ACTIVE_ROWS;
        };
        return SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), family);
    }
}
