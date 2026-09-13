package io.titan.transpiler.tir.generative.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class BasicMutationSeedCorpusTest {

    @Test
    void curatedSeedsCoverAllActiveMutationFamilies() {
        EnumSet<MutationCaseModel.MutatorId> covered = EnumSet.noneOf(MutationCaseModel.MutatorId.class);
        for (BasicMutationSeedCorpus.CuratedMutationSeed seedCase : BasicMutationSeedCorpus.curatedSeeds()) {
            covered.add(seedCase.mutatorId());
        }
        assertEquals(
                EnumSet.of(
                        MutationCaseModel.MutatorId.ADD_SAFE_CONJUNCT,
                        MutationCaseModel.MutatorId.ADD_SAFE_DISJUNCT,
                        MutationCaseModel.MutatorId.DUPLICATE_PROJECTION),
                covered);
    }

    @Test
    void curatedSeedsCarryProvenanceNotes() {
        for (BasicMutationSeedCorpus.CuratedMutationSeed seedCase : BasicMutationSeedCorpus.curatedSeeds()) {
            assertFalse(seedCase.note().isBlank(), "Seed note must not be blank for " + seedCase.seed());
        }
    }
}
