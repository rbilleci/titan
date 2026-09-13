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

# Docker-free reference/seed-corpus checks run in the plain test task; the
# container-backed conformance/SQL-path/replay checks run via integrationTest
# (@Tag("docker") split, plan 4.5). Coverage is identical to the pre-split list.
./gradlew :titan-transpiler:test \
  --tests io.titan.transpiler.tir.generative.shared.SelectReferenceEvaluatorTest \
  --tests io.titan.transpiler.tir.generative.shared.ReferenceResultComparisonTest \
  --tests io.titan.transpiler.tir.generative.differential.AggregationDifferentialReferenceEvaluatorTest \
  --tests io.titan.transpiler.tir.generative.differential.AggregationDifferentialSeedCorpusTest \
  --tests io.titan.transpiler.tir.generative.differential.SubqueryDifferentialReferenceEvaluatorTest \
  --tests io.titan.transpiler.tir.generative.differential.SubqueryDifferentialSeedCorpusTest \
  --tests io.titan.transpiler.tir.generative.differential.JoinReferenceEvaluatorTest \
  --tests io.titan.transpiler.tir.generative.differential.JoinDifferentialSeedCorpusTest \
  --rerun-tasks \
  --no-daemon

./gradlew :titan-transpiler:integrationTest \
  --tests io.titan.transpiler.tir.generative.differential.SelectDifferentialPostgresSqlPathIT \
  --tests io.titan.transpiler.tir.generative.differential.SelectDifferentialConformanceTest \
  --tests io.titan.transpiler.tir.generative.differential.SelectDifferentialSeedCorpusTest \
  --tests io.titan.transpiler.tir.generative.differential.AggregationDifferentialPostgresSqlPathIT \
  --tests io.titan.transpiler.tir.generative.differential.AggregationDifferentialConformanceTest \
  --tests io.titan.transpiler.tir.generative.differential.SubqueryDifferentialPostgresSqlPathIT \
  --tests io.titan.transpiler.tir.generative.differential.SubqueryDifferentialConformanceTest \
  --tests io.titan.transpiler.tir.generative.differential.JoinDifferentialPostgresSqlPathIT \
  --tests io.titan.transpiler.tir.generative.differential.JoinDifferentialConformanceTest \
  --rerun-tasks \
  --no-daemon

for seed in 1001 1010 1014; do
  ./gradlew :titan-transpiler:integrationTest \
    --tests io.titan.transpiler.tir.generative.differential.SelectDifferentialReplayTest \
    -Dtitan.phaseb.profile=transpiler-diff-basic-select \
    -Dtitan.phaseb.seed="$seed" \
    --rerun-tasks \
    --no-daemon
done

for seed in 2001 2004 2007; do
  ./gradlew :titan-transpiler:integrationTest \
    --tests io.titan.transpiler.tir.generative.differential.AggregationDifferentialReplayTest \
    -Dtitan.phaseb.profile=transpiler-diff-aggregation \
    -Dtitan.phaseb.seed="$seed" \
    --rerun-tasks \
    --no-daemon
done

for seed in 3000 3004 3006; do
  ./gradlew :titan-transpiler:integrationTest \
    --tests io.titan.transpiler.tir.generative.differential.SubqueryDifferentialReplayTest \
    -Dtitan.phaseb.profile=transpiler-diff-subquery-cte \
    -Dtitan.phaseb.seed="$seed" \
    --rerun-tasks \
    --no-daemon
done

for seed in 6000 6004 6006 6009; do
  ./gradlew :titan-transpiler:integrationTest \
    --tests io.titan.transpiler.tir.generative.differential.JoinDifferentialReplayTest \
    -Dtitan.phaseb.profile=transpiler-diff-joins \
    -Dtitan.phaseb.seed="$seed" \
    --rerun-tasks \
    --no-daemon
done

# --- MySQL differential leg (plan 5.3) ---------------------------------------
# Same generated corpus, transpiled for MySQL and executed on the singleton
# MySQL container against the same Java reference evaluators. Curated seed
# conformance first, then the same replay seeds the PostgreSQL loops above use,
# so every seed is exercised on both dialects.
echo "==> Phase B MySQL differential leg: curated conformance"
./gradlew :titan-transpiler:integrationTest \
  --tests io.titan.transpiler.tir.generative.differential.SelectDifferentialMySqlConformanceTest \
  --tests io.titan.transpiler.tir.generative.differential.AggregationDifferentialMySqlConformanceTest \
  --tests io.titan.transpiler.tir.generative.differential.SubqueryDifferentialMySqlConformanceTest \
  --tests io.titan.transpiler.tir.generative.differential.JoinDifferentialMySqlConformanceTest \
  --rerun-tasks \
  --no-daemon

echo "==> Phase B MySQL differential leg: replay seeds"
for seed in 1001 1010 1014; do
  ./gradlew :titan-transpiler:integrationTest \
    --tests io.titan.transpiler.tir.generative.differential.SelectDifferentialMySqlReplayTest \
    -Dtitan.phaseb.profile=transpiler-diff-basic-select \
    -Dtitan.phaseb.seed="$seed" \
    --rerun-tasks \
    --no-daemon
done

for seed in 2001 2004 2007; do
  ./gradlew :titan-transpiler:integrationTest \
    --tests io.titan.transpiler.tir.generative.differential.AggregationDifferentialMySqlReplayTest \
    -Dtitan.phaseb.profile=transpiler-diff-aggregation \
    -Dtitan.phaseb.seed="$seed" \
    --rerun-tasks \
    --no-daemon
done

for seed in 3000 3004 3006; do
  ./gradlew :titan-transpiler:integrationTest \
    --tests io.titan.transpiler.tir.generative.differential.SubqueryDifferentialMySqlReplayTest \
    -Dtitan.phaseb.profile=transpiler-diff-subquery-cte \
    -Dtitan.phaseb.seed="$seed" \
    --rerun-tasks \
    --no-daemon
done

for seed in 6000 6004 6006 6009; do
  ./gradlew :titan-transpiler:integrationTest \
    --tests io.titan.transpiler.tir.generative.differential.JoinDifferentialMySqlReplayTest \
    -Dtitan.phaseb.profile=transpiler-diff-joins \
    -Dtitan.phaseb.seed="$seed" \
    --rerun-tasks \
    --no-daemon
done
