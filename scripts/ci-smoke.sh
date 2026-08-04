#!/usr/bin/env bash
# JVM エンジン: コンパイル → CiSmoke（決定性・golden・並列）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

need_java() {
  command -v java >/dev/null || { echo "Java 17+ required"; exit 1; }
}

ensure_kotlinc() {
  if command -v kotlinc >/dev/null 2>&1; then
    KOTLINC="$(command -v kotlinc)"
    # stdlib near kotlinc
    local home
    home="$(dirname "$(dirname "$KOTLINC")")"
    STDLIB="$(find "$home" -name 'kotlin-stdlib.jar' 2>/dev/null | head -1 || true)"
    if [ -z "${STDLIB:-}" ]; then
      STDLIB="$(find "$HOME" -name 'kotlin-stdlib.jar' 2>/dev/null | head -1 || true)"
    fi
    return 0
  fi
  # install local copy under .tools
  local ver=1.9.25
  local tools="$ROOT/.tools"
  mkdir -p "$tools"
  if [ ! -x "$tools/kotlinc/bin/kotlinc" ]; then
    echo "Downloading Kotlin $ver ..."
    curl -sL "https://github.com/JetBrains/kotlin/releases/download/v${ver}/kotlin-compiler-${ver}.zip" -o /tmp/kotlin-compiler.zip
    unzip -q -o /tmp/kotlin-compiler.zip -d "$tools"
  fi
  KOTLINC="$tools/kotlinc/bin/kotlinc"
  STDLIB="$(find "$tools/kotlinc" -name 'kotlin-stdlib.jar' | head -1)"
  export PATH="$tools/kotlinc/bin:$PATH"
}

need_java
ensure_kotlinc
if [ -z "${STDLIB:-}" ] || [ ! -f "$STDLIB" ]; then
  echo "kotlin-stdlib.jar not found"; exit 1
fi

echo "kotlinc=$KOTLINC"
echo "stdlib=$STDLIB"

mkdir -p out
find src -name '*.kt' | sort > /tmp/magi-srcs.txt
echo "sources=$(wc -l < /tmp/magi-srcs.txt)"
"$KOTLINC" -cp "$STDLIB" -d out @/tmp/magi-srcs.txt

export MAGI_BUDGET_MS="${MAGI_BUDGET_MS:-2000}"
export MAGI_SEEDS="${MAGI_SEEDS:-3}"
echo "Running CiSmoke budgetMs=$MAGI_BUDGET_MS seeds=$MAGI_SEEDS"
java -cp "out:$STDLIB" com.magi.app.v6.engine.CiSmokeKt
echo "ci-smoke PASS"
