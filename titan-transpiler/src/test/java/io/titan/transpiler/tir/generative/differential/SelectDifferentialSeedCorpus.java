package io.titan.transpiler.tir.generative.differential;

import java.util.List;

final class SelectDifferentialSeedCorpus {

    private SelectDifferentialSeedCorpus() {
    }

    static List<Long> curatedBasicSelectSeeds() {
        return curatedCorpus().stream().map(SelectDifferentialCuratedSeed::seed).toList();
    }

    // Seeds map to generator families via floorMod(seed, familyCount). The 16 consecutive
    // seeds cover every family exactly once; the rationale strings track the current mapping
    // (familyCount grew from 14 to 16 when the plan-5.3 visible-NULL ordering families landed,
    // which remapped every seed).
    static List<SelectDifferentialCuratedSeed> curatedCorpus() {
        return List.of(
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1001L, "arithmetic add projection coverage"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1002L, "arithmetic subtract projection coverage"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1003L, "arithmetic multiply projection coverage"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1004L, "nullable arithmetic add projection coverage"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1005L, "nullable arithmetic multiply projection coverage"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1006L, "visible-NULL ascending ordering with explicit NULLS LAST (MySQL emulation coverage)"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1007L, "visible-NULL descending ordering with explicit NULLS FIRST (MySQL emulation coverage)"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1008L, "active=true plus email-not-null ordered projection"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1009L, "plan_code null-or-id equality disjunction"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1010L, "ordered bounded-inequality filtering coverage"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1011L, "email null check projection"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1012L, "active false-or-id branch coverage"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1013L, "plan_code not-null plus bounded id disjunction coverage"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1014L, "nullable login_count less-than coverage"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1015L, "text not-equal coverage"),
                new SelectDifferentialCuratedSeed(SelectDifferentialProfile.BASIC_SELECT, 1016L, "logical not predicate coverage")
        );
    }
}
