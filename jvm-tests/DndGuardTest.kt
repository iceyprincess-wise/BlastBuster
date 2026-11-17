import com.splendor.blastbuster.dnd.DndAction
import com.splendor.blastbuster.dnd.DndMode
import com.splendor.blastbuster.dnd.DndReapplyLimiter
import com.splendor.blastbuster.dnd.decideDndAction

// DndGuard pure logic: drift decision + re-apply limiter.
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("PASS $name") }
        else { failed++; println("FAIL $name") }
    }

    // Drift decisions.
    check("want ON, is OFF -> APPLY_ON", decideDndAction(DndMode.ON, false) == DndAction.APPLY_ON)
    check("want ON, is ON -> NONE", decideDndAction(DndMode.ON, true) == DndAction.NONE)
    check("want OFF, is ON -> APPLY_OFF", decideDndAction(DndMode.OFF, true) == DndAction.APPLY_OFF)
    check("want OFF, is OFF -> NONE", decideDndAction(DndMode.OFF, false) == DndAction.NONE)
    check("no opinion -> NONE", decideDndAction(null, true) == DndAction.NONE)
    check("no opinion off -> NONE", decideDndAction(null, false) == DndAction.NONE)

    // Limiter: first apply allowed.
    val min = 60_000L
    val lim = DndReapplyLimiter()
    check("first apply allowed", lim.allow(0L, DndAction.APPLY_ON))
    lim.record(0L, DndAction.APPLY_ON)
    // Same direction within 5 min blocked.
    check("same direction <5min blocked", !lim.allow(4 * min, DndAction.APPLY_ON))
    // Opposite direction within 5 min is a different fight: allowed (per-direction cooldown).
    check("opposite direction allowed", lim.allow(4 * min, DndAction.APPLY_OFF))
    // Same direction after 5 min allowed.
    check("same direction >5min allowed", lim.allow(6 * min, DndAction.APPLY_ON))
    lim.record(6 * min, DndAction.APPLY_ON)
    lim.record(12 * min, DndAction.APPLY_OFF)
    // 3 applies in the hour -> fight, further applies blocked.
    check("fight detected at 3 in hour", lim.isFight(13 * min))
    check("fourth apply blocked in fight", !lim.allow(13 * min, DndAction.APPLY_ON))
    // After the hour window passes, the fight clears.
    check("fight clears after an hour", !lim.isFight(70 * min))
    check("apply allowed after window", lim.allow(70 * min, DndAction.APPLY_ON))
    // NONE direction is never allowed.
    check("NONE never allowed", !DndReapplyLimiter().allow(0L, DndAction.NONE))

    println("DndGuardTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
