#!/bin/bash
# JVM unit tests for the merged tree, suite A (Agent A: BlastGate,
# DndSchedule, RamVerdict, EventLog, DndGuard, RamKiller/DndCulprit;
# v1.3: DndEnforce, Elimination, RamList, NotifStore).
# Total: 197 checks (10 tests), 0 failures.
# No JUnit: the Gradle test dependency cannot be added without editing
# app/build.gradle.kts, so tests are plain `main()` runners compiled
# with the Kotlin compiler from the Gradle cache and run on the JDK 17
# toolchain. Android sources compile against android.jar (stubs); only
# pure functions are exercised — no Android class is ever instantiated.
#
# MERGED: CompanionService now references the real Net/DNS/Bridge
# modules and Prefs now imports NetMode/SignalGrade, so this suite
# compiles the whole app source set (minus the real MainActivity,
# replaced by the MainActivityStub, plus the R stub for generated
# resources) instead of Agent A's original fixed file list.
set -u
BASE=~/workspace/blastbuster-v3
SRC=$BASE/app/src/main/java
KOTLINC_JAR=$BASE/jvm-tests/lib/kotlin-compiler-embeddable-1.9.22.jar
STDLIB_JAR=$BASE/jvm-tests/lib/kotlin-stdlib-1.9.22.jar
# Fallback to the Gradle cache if the lib jars are missing (the jars-9
# copies are Gradle-instrumented and cannot run the compiler standalone).
[ -f "$KOTLINC_JAR" ] || KOTLINC_JAR=$(ls ~/.gradle/caches/jars-9/*/kotlin-compiler-embeddable-1.9.22.jar | head -1)
[ -f "$STDLIB_JAR" ] || STDLIB_JAR=$(ls ~/.gradle/caches/jars-9/*/kotlin-stdlib-1.9.20.jar | head -1)
ANDROID_JAR=~/Android/Sdk/platforms/android-34/android.jar
JAVA=~/jdk17/bin/java
OUT=$BASE/jvm-tests/out-a
rm -rf "$OUT"
mkdir -p "$OUT"
echo "kotlinc: $KOTLINC_JAR"
echo "stdlib: $STDLIB_JAR"

# All app sources except the real MainActivity (stubbed), plus stubs + tests.
APP_SOURCES=$(find "$SRC" -name '*.kt' ! -name 'MainActivity.kt' | sort)
# shellcheck disable=SC2086
"$JAVA" -cp "$KOTLINC_JAR:$STDLIB_JAR:$BASE/jvm-tests/lib/trove4j-20160824.jar:$BASE/jvm-tests/lib/annotations-13.0.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -cp "$ANDROID_JAR:$STDLIB_JAR" -d "$OUT" \
  $APP_SOURCES \
  "$BASE/jvm-tests/stubs/MainActivityStub.kt" \
  "$BASE/jvm-tests/stubs/RStub.kt" \
  "$BASE/jvm-tests/BlastGateTest.kt" \
  "$BASE/jvm-tests/DndScheduleTest.kt" \
  "$BASE/jvm-tests/RamVerdictTest.kt" \
  "$BASE/jvm-tests/EventLogTest.kt" \
  "$BASE/jvm-tests/DndGuardTest.kt" \
  "$BASE/jvm-tests/RamKillerDndCulpritTest.kt" \
  "$BASE/jvm-tests/DndEnforceTest.kt" \
  "$BASE/jvm-tests/EliminationTest.kt" \
  "$BASE/jvm-tests/RamListTest.kt" \
  "$BASE/jvm-tests/NotifStoreTest.kt" || { echo "COMPILE FAILED"; exit 1; }
echo "compile OK"

TOTAL_PASS=0
TOTAL_FAIL=0
for T in BlastGateTest DndScheduleTest RamVerdictTest EventLogTest DndGuardTest RamKillerDndCulpritTest DndEnforceTest EliminationTest RamListTest NotifStoreTest; do
  echo "=== $T ==="
  OUT_TXT=$("$JAVA" -cp "$OUT:$STDLIB_JAR:$ANDROID_JAR" "${T}Kt" 2>&1)
  echo "$OUT_TXT"
  P=$(echo "$OUT_TXT" | grep -oE '[0-9]+ passed' | grep -oE '[0-9]+' | head -1)
  F=$(echo "$OUT_TXT" | grep -oE '[0-9]+ failed' | grep -oE '[0-9]+' | head -1)
  TOTAL_PASS=$((TOTAL_PASS + ${P:-0}))
  TOTAL_FAIL=$((TOTAL_FAIL + ${F:-0}))
done
echo "TOTAL: $TOTAL_PASS passed, $TOTAL_FAIL failed"
[ "$TOTAL_FAIL" -eq 0 ]
