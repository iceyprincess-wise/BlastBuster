#!/bin/bash
# JVM unit tests for the Blast Buster v1.2 Network/DNS module (Agent B).
#
# DESIGN.md §7.1 names src/test/ JUnit tests, but only if the JUnit
# dependency is available offline — and adding testImplementation would
# mean editing app/build.gradle.kts, which Agent C owns (§4.7). So the
# pure helpers are tested here instead, as standalone Kotlin files with
# plain main() runners, compiled with the Gradle-bundled Kotlin 1.9.20
# compiler (no downloads, no new dependencies).
#
# Usage: ./run-tests.sh
# Exit code: 0 when every test file passes, 1 otherwise.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="${JAVA_HOME:-$HOME/jdk17}/bin/java"
KOTLINC_JAR="$HOME/gradle-8.5/lib/kotlin-compiler-embeddable-1.9.20.jar"
STDLIB="$HOME/gradle-8.5/lib/kotlin-stdlib-1.9.20.jar"
SRC="$ROOT/app/src/main/java/com/splendor/blastbuster"
TESTS="$ROOT/jvm-tests"
OUT="/tmp/bb-jvm-tests/classes"
BUNDLED="$ROOT/app/src/main/res/raw/blocklist_hosts.txt"

set -u
rm -rf "$OUT"
mkdir -p "$OUT"

echo "== compiling pure sources + tests =="
COMPILER_CP="$(ls "$HOME"/gradle-8.5/lib/*.jar | tr '\n' ':')$STDLIB"
"$JAVA_BIN" -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -cp "$STDLIB" -d "$OUT" \
    "$SRC/net/SignalGrade.kt" \
    "$SRC/net/NetMode.kt" \
    "$SRC/net/EventSink.kt" \
    "$SRC/dns/DnsParser.kt" \
    "$SRC/dns/Blocklist.kt" \
    "$SRC/dns/DnsPipe.kt" \
    "$TESTS/SignalGradeTest.kt" \
    "$TESTS/DnsParserTest.kt" \
    "$TESTS/BlocklistTest.kt" \
    "$TESTS/DnsPipePacketsTest.kt" \
    "$TESTS/NetModeTest.kt" || { echo "COMPILE FAILED"; exit 1; }
echo "compile OK"
echo

overall=0
run_one() {
    local name="$1"; shift
    echo "== $name =="
    if "$JAVA_BIN" -cp "$OUT:$STDLIB" "${name}Kt" "$@"; then
        echo "$name: PASS"
    else
        echo "$name: FAIL"
        overall=1
    fi
    echo
}

run_one SignalGradeTest
run_one DnsParserTest
run_one BlocklistTest "$BUNDLED"
run_one DnsPipePacketsTest
run_one NetModeTest

if [ "$overall" -eq 0 ]; then
    echo "ALL JVM TESTS PASSED"
else
    echo "SOME JVM TESTS FAILED"
fi
exit "$overall"
