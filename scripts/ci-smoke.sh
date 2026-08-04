#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
KOTLINC="${KOTLINC:-/home/workdir/tools/bin/kotlinc}"
STDLIB="${STDLIB:-/home/workdir/tools/kotlin/kotlin-stdlib-1.9.25.jar}"
ANN="${ANN:-/home/workdir/tools/kotlin/annotations-24.1.0.jar}"
mkdir -p out
find src -name '*.kt' | sort > /tmp/magi-srcs.txt
"$KOTLINC" -cp "$STDLIB:$ANN" -d out @/tmp/magi-srcs.txt
export MAGI_BUDGET_MS="${MAGI_BUDGET_MS:-2000}"
export MAGI_SEEDS="${MAGI_SEEDS:-3}"
java -cp "out:$STDLIB" com.magi.app.v6.engine.CiSmokeKt
