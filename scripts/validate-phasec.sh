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

# Docker-free lowering/seed-corpus checks run in the plain test task; the
# container-backed conformance/replay checks run via integrationTest
# (@Tag("docker") split, plan 4.5). Coverage is identical to the pre-split list.
./gradlew :titan-transpiler:test \
  --tests io.titan.transpiler.tir.generative.equivalence.PredicateReorderEquivalenceSeedCorpusTest \
  --tests io.titan.transpiler.tir.JavaToTirLowererSelectFromTest \
  --tests io.titan.transpiler.tir.JavaToTirLowererJoinOnColumnsTest \
  --tests io.titan.transpiler.tir.generative.equivalence.FormEquivalenceSeedCorpusTest \
  --tests io.titan.transpiler.tir.generative.equivalence.composition.CompositionEquivalenceSeedCorpusTest \
  --tests io.titan.transpiler.tir.generative.equivalence.projection.ProjectionEquivalenceSeedCorpusTest \
  --tests io.titan.transpiler.tir.generative.equivalence.join.JoinEquivalenceSeedCorpusTest \
  --tests io.titan.transpiler.tir.generative.equivalence.outerjoin.OuterJoinEquivalenceSeedCorpusTest \
  --tests io.titan.transpiler.tir.generative.equivalence.subquery.SubqueryEquivalenceSeedCorpusTest \
  --no-daemon

./gradlew :titan-transpiler:integrationTest \
  --tests io.titan.transpiler.tir.generative.equivalence.PredicateReorderEquivalenceConformanceTest \
  --tests io.titan.transpiler.tir.generative.equivalence.PredicateReorderEquivalenceReplayTest \
  --tests io.titan.transpiler.tir.generative.equivalence.FormEquivalenceConformanceTest \
  --tests io.titan.transpiler.tir.generative.equivalence.FormEquivalenceReplayTest \
  --tests io.titan.transpiler.tir.generative.equivalence.composition.CompositionEquivalenceConformanceTest \
  --tests io.titan.transpiler.tir.generative.equivalence.composition.CompositionEquivalenceReplayTest \
  --tests io.titan.transpiler.tir.generative.equivalence.projection.ProjectionEquivalenceConformanceTest \
  --tests io.titan.transpiler.tir.generative.equivalence.projection.ProjectionEquivalenceReplayTest \
  --tests io.titan.transpiler.tir.generative.equivalence.join.JoinEquivalenceConformanceTest \
  --tests io.titan.transpiler.tir.generative.equivalence.join.JoinEquivalenceReplayTest \
  --tests io.titan.transpiler.tir.generative.equivalence.outerjoin.OuterJoinEquivalenceConformanceTest \
  --tests io.titan.transpiler.tir.generative.equivalence.outerjoin.OuterJoinEquivalenceReplayTest \
  --tests io.titan.transpiler.tir.generative.equivalence.subquery.SubqueryEquivalenceConformanceTest \
  --tests io.titan.transpiler.tir.generative.equivalence.subquery.SubqueryEquivalenceReplayTest \
  --no-daemon
