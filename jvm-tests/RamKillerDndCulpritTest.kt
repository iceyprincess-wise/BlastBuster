import com.splendor.blastbuster.dnd.DndCulprit
import com.splendor.blastbuster.ram.RamKiller

// RamKiller denylist + cooldown, and DndCulprit tier resolution.
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("PASS $name") }
        else { failed++; println("FAIL $name") }
    }
    val self = "com.splendor.blastbuster"

    // Denylist.
    check("self denied", RamKiller.denialReason(self, self, emptyList(), null, null, null) != null)
    check(
        "systemui denied",
        RamKiller.denialReason(RamKiller.SYSTEM_UI_PKG, self, emptyList(), null, null, null) != null
    )
    check(
        "dialer denied",
        RamKiller.denialReason("com.android.dialer", self, emptyList(), "com.android.dialer", null, null) != null
    )
    check(
        "sms denied",
        RamKiller.denialReason("com.android.messaging", self, emptyList(), null, "com.android.messaging", null) != null
    )
    check(
        "launcher denied",
        RamKiller.denialReason("com.miui.home", self, emptyList(), null, null, "com.miui.home") != null
    )
    check(
        "protected list denied",
        RamKiller.denialReason("com.example.keep", self, listOf("com.example.keep"), null, null, null) != null
    )
    check(
        "ordinary app allowed",
        RamKiller.denialReason("com.example.game", self, listOf("com.example.keep"), "com.android.dialer", "com.android.messaging", "com.miui.home") == null
    )
    check(
        "empty package denied",
        RamKiller.denialReason("", self, emptyList(), null, null, null) != null
    )

    // Cooldown: 1 hour.
    check("never killed -> no cooldown", RamKiller.cooldownRemainingMs(0L, 10_000L) == 0L)
    check(
        "killed 10 min ago -> 50 min left",
        RamKiller.cooldownRemainingMs(0L, 10 * 60_000L) == 0L &&
            RamKiller.cooldownRemainingMs(1_000L, 1_000L + 10 * 60_000L) == 50 * 60_000L
    )
    check(
        "killed 61 min ago -> free",
        RamKiller.cooldownRemainingMs(1_000L, 1_000L + 61 * 60_000L) == 0L
    )

    // Culprit resolution order: logcat > foreground > unknown.
    val logcat = DndCulprit.resolve("com.evil.app", "com.front.app", DndCulprit.Tier.LISTENER)
    check(
        "logcat beats foreground",
        logcat.packageName == "com.evil.app" && logcat.tier == DndCulprit.Tier.LOGCAT &&
            logcat.label.contains("system log")
    )
    val listener = DndCulprit.resolve(null, "com.front.app", DndCulprit.Tier.LISTENER)
    check(
        "listener tier labelled inference",
        listener.packageName == "com.front.app" && listener.tier == DndCulprit.Tier.LISTENER &&
            listener.label.contains("inferred")
    )
    val polling = DndCulprit.resolve(null, "com.front.app", DndCulprit.Tier.POLLING)
    check(
        "polling tier labelled 60s",
        polling.tier == DndCulprit.Tier.POLLING && polling.label.contains("60 s")
    )
    val unknown = DndCulprit.resolve(null, null, DndCulprit.Tier.LISTENER)
    check(
        "no evidence is unknown",
        unknown.packageName == null && unknown.tier == DndCulprit.Tier.UNKNOWN &&
            unknown.label.contains("unknown")
    )
    // Logcat line scan: newest matching line wins, non-DND lines ignored.
    val log = """
        01-01 00:00:01.000  1000  1000 I Foo: something happened pkg=com.nope.app
        01-01 00:00:02.000  1000  1000 I ZenModeHelper: setInterruptionFilter callingPkg=com.first.app
        01-01 00:00:03.000  1000  1000 I ZenModeHelper: setInterruptionFilter callingPkg=com.second.app
    """.trimIndent()
    check("logcat newest wins", DndCulprit.findCulpritInLogcat(log) == "com.second.app")
    check("logcat none found", DndCulprit.findCulpritInLogcat("nothing here") == null)

    println("RamKillerDndCulpritTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
