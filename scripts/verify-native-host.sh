#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAT="$ROOT/native-cpp"
g++ -std=c++17 -O2 -fPIC -shared -I"$NAT/include" -o /tmp/libmagi_native.so "$NAT/src/magi_native.cpp"
g++ -std=c++17 -O2 -I"$NAT/include" -o /tmp/magi_native_test "$NAT/test/test_main.cpp" /tmp/libmagi_native.so -Wl,-rpath,/tmp
/tmp/magi_native_test
echo "verify-native-host PASS"
