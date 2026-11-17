#!/bin/bash
# Merged JVM test runner for Blast Buster v1.3 (DESIGN.md §7.1).
# Runs all three agents' suites against the merged tree:
#   A (Agent A): BlastGate, DndSchedule, RamVerdict, EventLog,
#      DndGuard, RamKiller/DndCulprit — 90 checks; plus v1.3
#      DndEnforce, Elimination, RamList, NotifStore — 107 checks.
#      Suite A total: 197 checks, 0 failures.
#   B (Agent B): SignalGrade, DnsParser, Blocklist, DnsPipePackets,
#      NetMode — 174 checks passed, 3 skipped (environmental:
#      the sandbox blocks Java UDP send; see DnsPipePacketsTest);
#      plus v1.3 DnsFix, NetAdb — 64 checks. Suite B total:
#      238 checks passed, 0 failures, 3 skipped.
#   C (Agent C): MiniJson, BridgeHttp, BridgeAuthCore,
#      BridgeCommands — 65 checks; plus v1.3 BridgeRebind,
#      BridgeLogExport — 33 checks. Suite C total: 98 checks.
# Total: 533 checks passed, 0 failures, 3 skipped (environmental).
# Usage: bash jvm-tests/run-tests.sh   (from the repo root)
# Exit code: 0 when every suite passes, 1 otherwise.
set -u
cd "$(dirname "$0")/.."

overall=0
echo "########## Suite A (companion/RAM/DND) ##########"
bash jvm-tests/run-tests-a.sh || overall=1
echo
echo "########## Suite B (network/DNS) ##########"
bash jvm-tests/run-tests-b.sh || overall=1
echo
echo "########## Suite C (bridge/JSON) ##########"
bash jvm-tests/run-tests-c.sh || overall=1
echo
if [ "$overall" -eq 0 ]; then
  echo "ALL MERGED JVM TEST SUITES PASSED"
else
  echo "SOME MERGED JVM TEST SUITE FAILED"
fi
exit "$overall"
