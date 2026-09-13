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

echo "==> Phase A: DSL sanity + transpiler generative conformance"
bash scripts/validate-phasea.sh

echo "==> Phase B: bounded differential validation"
bash scripts/validate-phaseb-m4.sh

echo "==> Phase C: bounded equivalence validation"
bash scripts/validate-phasec.sh

echo "==> Phase D: bounded mutation validation"
bash scripts/validate-phased.sh

echo "==> Generative validation stack is green (A-D)"
