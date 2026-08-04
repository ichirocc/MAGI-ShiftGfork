#!/usr/bin/env bash
# フォーク CI から呼ばれる。エンジン再構築パッケージが sibling なら委譲。
set -euo pipefail
if [ -d "../magi-engine-rebuild" ]; then
  export MAGI_BUDGET_MS="${MAGI_BUDGET_MS:-2000}"
  export MAGI_SEEDS="${MAGI_SEEDS:-3}"
  bash ../magi-engine-rebuild/scripts/ci-smoke.sh
elif [ -d "magi-engine-rebuild" ]; then
  bash magi-engine-rebuild/scripts/ci-smoke.sh
else
  echo "magi-engine-rebuild not found — run host native verify only"
  exit 0
fi
