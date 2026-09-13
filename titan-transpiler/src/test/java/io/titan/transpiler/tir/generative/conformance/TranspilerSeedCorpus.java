package io.titan.transpiler.tir.generative.conformance;

import java.util.List;

final class TranspilerSeedCorpus {

    private TranspilerSeedCorpus() {
    }

    static List<Long> curatedBasicSelectSeeds() {
        return List.of(7L, 11L, 19L, 23L, 29L, 31L, 37L, 41L, 43L, 47L, 53L, 59L);
    }

    static List<Long> curatedInvalidSeeds() {
        return List.of(101L, 103L, 107L, 109L);
    }

    static List<Long> curatedSubqueryCteSeeds() {
        return List.of(201L, 202L, 203L, 204L, 205L, 206L);
    }

    static List<Long> curatedAggregationSeeds() {
        return List.of(301L, 302L, 303L, 304L, 305L, 306L, 307L, 308L);
    }

    static List<Long> curatedJoinCompositionSeeds() {
        return List.of(401L, 402L, 403L, 404L, 405L, 406L, 407L, 408L);
    }

    static List<Long> curatedInvalidCompositionSeeds() {
        return List.of(504L, 505L, 506L, 507L, 508L, 509L, 510L);
    }

    static List<CuratedSeedCase> curatedCorpus() {
        return List.of(
                new CuratedSeedCase(TranspilerGenerativeProfile.BASIC_SELECT, 7L, "baseline simple filter coverage"),
                new CuratedSeedCase(TranspilerGenerativeProfile.BASIC_SELECT, 23L, "join-oriented select shape coverage"),
                new CuratedSeedCase(TranspilerGenerativeProfile.BASIC_SELECT, 41L, "grouped aggregate branch in the basic-select profile"),
                new CuratedSeedCase(TranspilerGenerativeProfile.INVALID, 101L, "unknown table reference diagnostic regression"),
                new CuratedSeedCase(TranspilerGenerativeProfile.INVALID, 103L, "dynamic SQL concat rejection regression"),
                new CuratedSeedCase(TranspilerGenerativeProfile.SUBQUERY_CTE, 201L, "scalar subquery compositional coverage"),
                new CuratedSeedCase(TranspilerGenerativeProfile.AGGREGATION, 301L, "count/group-by/having coverage"),
                new CuratedSeedCase(TranspilerGenerativeProfile.AGGREGATION, 304L, "multi-column aggregation coverage"),
                new CuratedSeedCase(TranspilerGenerativeProfile.JOIN_COMPOSITION, 401L, "join + filter + ordering coverage"),
                new CuratedSeedCase(TranspilerGenerativeProfile.JOIN_COMPOSITION, 404L, "right join + grouped composition coverage"),
                new CuratedSeedCase(TranspilerGenerativeProfile.INVALID_COMPOSITION, 504L, "non-boolean join predicate rejection"),
                new CuratedSeedCase(TranspilerGenerativeProfile.INVALID_COMPOSITION, 505L, "unknown joined-table reference rejection"),
                new CuratedSeedCase(TranspilerGenerativeProfile.INVALID_COMPOSITION, 506L, "fetchInto joined missing-component rejection"),
                new CuratedSeedCase(TranspilerGenerativeProfile.INVALID_COMPOSITION, 507L, "fetchInto joined projection-count rejection"),
                new CuratedSeedCase(TranspilerGenerativeProfile.INVALID_COMPOSITION, 508L, "fetchInto non-identifier projection rejection"),
                new CuratedSeedCase(TranspilerGenerativeProfile.INVALID_COMPOSITION, 509L, "fetchInto ambiguous normalized projection rejection"),
                new CuratedSeedCase(TranspilerGenerativeProfile.INVALID_COMPOSITION, 510L, "forEach callback arity rejection")
        );
    }
}
