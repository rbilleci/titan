package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.JoinProfile;
import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
final class JoinDifferentialGenerator {

    JoinCaseModel.JoinCase generate(long seed) {
        JoinCaseModel.Family family = switch ((int) Math.floorMod(seed, 13)) {
            case 0 -> JoinCaseModel.Family.INNER_JOIN_ACTIVE_ACCOUNTS;
            case 1 -> JoinCaseModel.Family.INNER_JOIN_PAID_PLANS;
            case 2 -> JoinCaseModel.Family.INNER_JOIN_DUPLICATE_PLAN_MATCHES;
            case 3 -> JoinCaseModel.Family.LEFT_JOIN_ACTIVE_NULL_EXTENSION;
            case 4 -> JoinCaseModel.Family.LEFT_JOIN_PAID_FILTER_PRESERVES_NULLS;
            case 5 -> JoinCaseModel.Family.LEFT_JOIN_PAID_WHERE_COLLAPSES_NULL_EXTENSION;
            case 6 -> JoinCaseModel.Family.TWO_JOIN_ACTIVE_PLAN_FAMILY;
            case 7 -> JoinCaseModel.Family.TWO_JOIN_DUPLICATE_SECOND_HOP_MULTIPLICATION;
            case 8 -> JoinCaseModel.Family.TWO_JOIN_SECOND_HOP_FILTER;
            case 9 -> JoinCaseModel.Family.LEFT_JOIN_DUPLICATE_NULL_EXTENSION;
            case 10 -> JoinCaseModel.Family.LEFT_TWO_JOIN_NULL_EXTENSION;
            case 11 -> JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_FILTER;
            default -> JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION;
        };
        return JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), family);
    }
}
