#!/usr/bin/env bash
# ホスト g++ で native-cpp Move 契約を検証
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAT="$ROOT/native-cpp"
command -v g++ >/dev/null || { echo "g++ required"; exit 1; }
test -f "$NAT/src/magi_native.cpp" || { echo "missing $NAT/src/magi_native.cpp"; exit 1; }
test -f "$NAT/test/test_main.cpp" || { echo "missing $NAT/test/test_main.cpp"; exit 1; }

g++ -std=c++17 -O2 -fPIC -shared -I"$NAT/include" -o /tmp/libmagi_native.so "$NAT/src/magi_native.cpp"
g++ -std=c++17 -O2 -I"$NAT/include" -o /tmp/magi_native_test "$NAT/test/test_main.cpp" /tmp/libmagi_native.so -Wl,-rpath,/tmp
/tmp/magi_native_test
echo "verify-native-host PASS"
