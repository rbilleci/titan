#!/usr/bin/env bash
set -eo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# sdkman-init.sh is not nounset-safe in all environments, and not all
# environments have sdkman at all - fall back to the ambient JAVA_HOME.
if [ -f ~/.sdkman/bin/sdkman-init.sh ]; then
  source ~/.sdkman/bin/sdkman-init.sh
fi
set -u

# Docker-free model/reducer/seed-corpus checks run in the plain test task; the
# container-backed conformance/replay checks run via integrationTest
# (@Tag("docker") split, plan 4.5). Coverage is identical to the pre-split list.
./gradlew :titan-transpiler:test \
  --tests io.titan.transpiler.tir.generative.mutation.MutationCaseModelTest \
  --tests io.titan.transpiler.tir.generative.mutation.BasicMutationReducerTest \
  --tests io.titan.transpiler.tir.generative.mutation.BasicMutationSeedCorpusTest \
  --tests io.titan.transpiler.tir.generative.mutation.JoinMutationCaseModelTest \
  --tests io.titan.transpiler.tir.generative.mutation.JoinMutationReducerTest \
  --tests io.titan.transpiler.tir.generative.mutation.JoinMutationSeedCorpusTest \
  --tests io.titan.transpiler.tir.generative.mutation.OuterJoinMutationCaseModelTest \
  --tests io.titan.transpiler.tir.generative.mutation.OuterJoinMutationReducerTest \
  --tests io.titan.transpiler.tir.generative.mutation.OuterJoinMutationSeedCorpusTest \
  --tests io.titan.transpiler.tir.generative.mutation.TwoHopJoinMutationCaseModelTest \
  --tests io.titan.transpiler.tir.generative.mutation.TwoHopJoinMutationReducerTest \
  --tests io.titan.transpiler.tir.generative.mutation.TwoHopJoinMutationMappingTest \
  --tests io.titan.transpiler.tir.generative.mutation.TwoHopJoinMutationSeedCorpusTest \
  --no-daemon

./gradlew :titan-transpiler:integrationTest \
  --tests io.titan.transpiler.tir.generative.mutation.BasicMutationConformanceTest \
  --tests io.titan.transpiler.tir.generative.mutation.JoinMutationConformanceTest \
  --tests io.titan.transpiler.tir.generative.mutation.OuterJoinMutationConformanceTest \
  --tests io.titan.transpiler.tir.generative.mutation.TwoHopJoinMutationConformanceTest \
  --no-daemon

for spec in "8100 add-safe-conjunct" "8101 add-safe-disjunct" "8102 duplicate-projection"; do
  set -- $spec
  ./gradlew :titan-transpiler:integrationTest \
    --tests io.titan.transpiler.tir.generative.mutation.BasicMutationReplayTest \
    -Dtitan.phased.profile=transpiler-mutation-basic \
    -Dtitan.phased.seed="$1" \
    -Dtitan.phased.mutator="$2" \
    --rerun-tasks \
    --no-daemon
done

for seed in 8500 8501; do
  ./gradlew :titan-transpiler:integrationTest \
    --tests io.titan.transpiler.tir.generative.mutation.JoinMutationReplayTest \
    -Dtitan.phased.profile=transpiler-mutation-joins \
    -Dtitan.phased.seed="$seed" \
    --rerun-tasks \
    --no-daemon
done

for seed in 8600 8601; do
  ./gradlew :titan-transpiler:integrationTest \
    --tests io.titan.transpiler.tir.generative.mutation.OuterJoinMutationReplayTest \
    -Dtitan.phased.profile=transpiler-mutation-outer-joins \
    -Dtitan.phased.seed="$seed" \
    --rerun-tasks \
    --no-daemon
done

for seed in 8700 8701 8702 8703; do
  ./gradlew :titan-transpiler:integrationTest \
    --tests io.titan.transpiler.tir.generative.mutation.TwoHopJoinMutationReplayTest \
    -Dtitan.phased.profile=transpiler-mutation-two-hop-joins \
    -Dtitan.phased.seed="$seed" \
    --rerun-tasks \
    --no-daemon
done
