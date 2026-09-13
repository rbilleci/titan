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

echo "==> Phase A: DSL sanity / rendering coverage"
./gradlew -p ../titan-dsl :titan-dsl:test \
  --tests titan.dsl.SelectBuilderTest \
  --no-daemon

echo "==> Phase A: transpiler generative conformance"
./gradlew :titan-transpiler:test \
  --tests io.titan.transpiler.tir.generative.conformance.TranspilerGenerativeConformanceTest \
  --no-daemon

echo "==> Phase A validation is green"
