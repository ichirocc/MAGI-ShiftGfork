#!/usr/bin/env bash
# 一括: native 検証 + JVM CiSmoke
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
echo "=== 1/2 native-cpp ==="
bash "$ROOT/scripts/verify-native-host.sh"
echo "=== 2/2 JVM CiSmoke ==="
bash "$ROOT/scripts/ci-smoke.sh"
echo "=== ALL PASS — 実行可能 ==="
