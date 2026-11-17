import com.splendor.blastbuster.net.NetMode
import com.splendor.blastbuster.net.NetModeValues

// JVM unit tests for NetMode mapping (mode parsing — DESIGN.md §7.1
// "mode parsing" under the network tools).
// Standalone main() runner — no JUnit dependency (see run-tests.sh).

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
        println("  PASS: $name")
    } else {
        failed++
        println("  FAIL: $name")
    }
}

fun main() {
    println("NetModeTest")

    // --- setting value round-trip for every mode ---
    for (mode in NetMode.entries) {
        val value = NetModeValues.settingValue(mode)
        check("round-trip $mode ($value)", NetModeValues.fromSettingValue(value) == mode)
    }

    // --- exact RIL values (AOSP RILConstants) ---
    check("AUTO -> 23", NetModeValues.settingValue(NetMode.AUTO) == 23)
    check("LTE_ONLY -> 11", NetModeValues.settingValue(NetMode.LTE_ONLY) == 11)
    check("NR_ONLY -> 21", NetModeValues.settingValue(NetMode.NR_ONLY) == 21)
    check("UMTS_ONLY -> 2", NetModeValues.settingValue(NetMode.UMTS_ONLY) == 2)
    check("unknown value 99 -> null", NetModeValues.fromSettingValue(99) == null)
    check("unknown value 0 -> null", NetModeValues.fromSettingValue(0) == null)

    // --- parse: bridge short forms (DESIGN.md §2.3: 4g/5g/auto) ---
    check("parse 4g", NetModeValues.parse("4g") == NetMode.LTE_ONLY)
    check("parse 5g", NetModeValues.parse("5g") == NetMode.NR_ONLY)
    check("parse auto", NetModeValues.parse("auto") == NetMode.AUTO)
    check("parse 3g", NetModeValues.parse("3g") == NetMode.UMTS_ONLY)
    check("parse lte", NetModeValues.parse("lte") == NetMode.LTE_ONLY)
    check("parse nr", NetModeValues.parse("nr") == NetMode.NR_ONLY)
    check("parse umts", NetModeValues.parse("umts") == NetMode.UMTS_ONLY)
    check("parse LTE_ONLY enum name", NetModeValues.parse("lte_only") == NetMode.LTE_ONLY)
    check("parse with spaces/case", NetModeValues.parse("  AUTO ") == NetMode.AUTO)
    check("parse bogus -> null", NetModeValues.parse("bogus") == null)
    check("parse empty -> null", NetModeValues.parse("") == null)
    check("parse null -> null", NetModeValues.parse(null) == null)

    // --- expected live network type (TelephonyManager NETWORK_TYPE_*) ---
    check("LTE_ONLY expects 13 (LTE)", NetModeValues.expectedNetworkType(NetMode.LTE_ONLY) == 13)
    check("NR_ONLY expects 20 (NR)", NetModeValues.expectedNetworkType(NetMode.NR_ONLY) == 20)
    check("UMTS_ONLY expects 3 (UMTS)", NetModeValues.expectedNetworkType(NetMode.UMTS_ONLY) == 3)
    check("AUTO expects any (null)", NetModeValues.expectedNetworkType(NetMode.AUTO) == null)

    // --- storeKey round-trip ---
    for (mode in NetMode.entries) {
        check("storeKey round-trip $mode",
            NetModeValues.parse(NetModeValues.storeKey(mode)) == mode)
    }

    // --- display names are generic (no carrier, no personal data) ---
    check("display names generic", NetMode.entries.all {
        !it.displayName.contains("Airtel", ignoreCase = true) &&
            !it.displayName.contains("MTN", ignoreCase = true)
    })

    println("RESULT NetModeTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
