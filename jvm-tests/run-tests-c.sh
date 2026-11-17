#!/bin/bash
# JVM tests for Agent C's pure helpers (DESIGN.md §7.1).
# Total: 98 checks (6 tests), 0 failures.
# v1.3 additions: BridgeRebindTest (19), BridgeLogExportTest (14).
# No JUnit in the offline sandbox: each test file has a plain main()
# runner. Compiles the pure sources + tests with the offline Kotlin
# compiler (kotlin-compiler-embeddable 1.9.22) and runs each main.
# Usage: bash jvm-tests/run-tests.sh   (from the repo copy root)
set -u
cd "$(dirname "$0")/.."

KOTLINC_JAR="$HOME/maven-repo/org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.22/kotlin-compiler-embeddable-1.9.22.jar"
STDLIB_JAR="$HOME/maven-repo/org/jetbrains/kotlin/kotlin-stdlib/1.9.22/kotlin-stdlib-1.9.22.jar"
# The embeddable compiler also needs trove4j + annotations on its classpath.
TROVE_JAR="$HOME/maven-repo/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar"
ANNOT_JAR="$HOME/maven-repo/org/jetbrains/annotations/13.0/annotations-13.0.jar"
SCRIPT_RT_JAR="$HOME/maven-repo/org/jetbrains/kotlin/kotlin-script-runtime/1.9.22/kotlin-script-runtime-1.9.22.jar"
OUT_DIR="/tmp/bb-c-jvm-tests"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

SOURCES=(
  app/src/main/java/com/splendor/blastbuster/companion/MiniJson.kt
  app/src/main/java/com/splendor/blastbuster/bridge/BridgeHttp.kt
  app/src/main/java/com/splendor/blastbuster/bridge/BridgeAuthCore.kt
  app/src/main/java/com/splendor/blastbuster/bridge/BridgeCommands.kt
  app/src/main/java/com/splendor/blastbuster/bridge/BridgeRebind.kt
  app/src/main/java/com/splendor/blastbuster/bridge/LogExportGate.kt
  jvm-tests/MiniJsonTest.kt
  jvm-tests/BridgeHttpTest.kt
  jvm-tests/BridgeAuthCoreTest.kt
  jvm-tests/BridgeCommandsTest.kt
  jvm-tests/BridgeRebindTest.kt
  jvm-tests/BridgeLogExportTest.kt
)

echo "== compiling ${#SOURCES[@]} Kotlin files =="
"$HOME/jdk17/bin/java" -cp "$KOTLINC_JAR:$STDLIB_JAR:$TROVE_JAR:$ANNOT_JAR:$SCRIPT_RT_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -cp "$STDLIB_JAR" -d "$OUT_DIR/classes" "${SOURCES[@]}" 2>&1
if [ $? -ne 0 ]; then
  echo "COMPILE FAILED"
  exit 1
fi

TOTAL_FAIL=0
for TEST in \
  com.splendor.blastbuster.companion.MiniJsonTestKt \
  com.splendor.blastbuster.bridge.BridgeHttpTestKt \
  com.splendor.blastbuster.bridge.BridgeAuthCoreTestKt \
  com.splendor.blastbuster.bridge.BridgeCommandsTestKt \
  com.splendor.blastbuster.bridge.BridgeRebindTestKt \
  com.splendor.blastbuster.bridge.BridgeLogExportTestKt ; do
  echo "== $TEST =="
  "$HOME/jdk17/bin/java" -cp "$OUT_DIR/classes:$STDLIB_JAR" "$TEST"
  if [ $? -ne 0 ]; then TOTAL_FAIL=1; fi
done

if [ "$TOTAL_FAIL" -eq 0 ]; then
  echo "ALL JVM TESTS PASSED"
else
  echo "SOME JVM TESTS FAILED"
  exit 1
fi
