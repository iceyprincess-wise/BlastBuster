import com.splendor.blastbuster.notif.NotifReply
import com.splendor.blastbuster.notif.NotifStore
import com.splendor.blastbuster.notif.looksSensitive
import com.splendor.blastbuster.notif.notifSummaryForLog

// NotifStore / NotifReply — v1.3 notification module.
// Pure-logic checks: cap eviction, ordering, package filtering,
// reply-action detection, reply gating, log-summary privacy.
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("PASS $name") }
        else { failed++; println("FAIL $name") }
    }

    fun entry(
        pkg: String, title: String = "Hi", text: String = "body",
        reply: Boolean = false, plainAction: Boolean = false
    ): NotifStore.CapturedNotif {
        val actions = mutableListOf<NotifStore.NotifAction>()
        if (reply) actions.add(NotifStore.NotifAction(actions.size, "Reply", true))
        if (plainAction) actions.add(NotifStore.NotifAction(actions.size, "Mark read", false))
        return NotifStore.CapturedNotif(
            id = 0L, key = "key-$pkg-$title", packageName = pkg,
            appLabel = pkg.substringAfterLast('.'), title = title,
            text = text, postedTimeMs = 1000L, actions = actions
        )
    }

    // 1. Ring starts empty.
    val ring = NotifStore.NotifRing()
    check("empty ring size 0", ring.size() == 0)

    // 2. add assigns monotonic ids starting at 1.
    val first = ring.add(entry("com.whatsapp", "Alice"))
    val second = ring.add(entry("com.whatsapp", "Bob"))
    check("ids monotonic from 1", first.id == 1L && second.id == 2L)

    // 3. Snapshot is newest first.
    val snap = ring.snapshot()
    check(
        "newest first",
        snap.size == 2 && snap[0].id == 2L && snap[1].id == 1L
    )

    // 4. find returns the entry, missing id returns null.
    check("find by id", ring.find(1L)?.title == "Alice")
    check("find missing is null", ring.find(999L) == null)

    // 5. Cap: 55 adds -> 50 kept, oldest 5 evicted.
    val big = NotifStore.NotifRing()
    for (n in 1..55) big.add(entry("com.app", "N$n"))
    check("cap holds 50", big.size() == 50)
    check("oldest evicted", big.find(1L) == null && big.find(5L) == null)
    check("newest kept", big.find(55L)?.title == "N55")
    check("oldest kept is id 6", big.snapshot().last().id == 6L)

    // 6. Exactly 50 adds -> no eviction.
    val exact = NotifStore.NotifRing()
    for (n in 1..50) exact.add(entry("com.app", "N$n"))
    check("exactly 50 keeps all", exact.size() == 50 && exact.find(1L) != null)

    // 7. Filter by package, newest first.
    val mixed = NotifStore.NotifRing()
    mixed.add(entry("com.whatsapp", "W1"))
    mixed.add(entry("com.telegram", "T1"))
    mixed.add(entry("com.whatsapp", "W2"))
    val wa = mixed.byPackage("com.whatsapp")
    check(
        "filter by package newest first",
        wa.size == 2 && wa[0].title == "W2" && wa[1].title == "W1"
    )
    check("filter unknown package empty", mixed.byPackage("com.none").isEmpty())

    // 8. clear empties the ring.
    mixed.clear()
    check("clear empties", mixed.size() == 0 && mixed.snapshot().isEmpty())

    // 9. Reply-action detection.
    val withReply = entry("com.whatsapp", reply = true, plainAction = true)
    val noReply = entry("com.bank", plainAction = true)
    val noActions = entry("com.bank")
    check("hasReplyAction true", withReply.hasReplyAction())
    check("hasReplyAction false for plain only", !noReply.hasReplyAction())
    check("hasReplyAction false for none", !noActions.hasReplyAction())
    check(
        "replyActions only remote-input ones",
        withReply.replyActions().size == 1 &&
            withReply.replyActions()[0].title == "Reply"
    )
    check("replyActions empty when none", noReply.replyActions().isEmpty())

    // 10. Reply gate: allowed when a reply action exists and no blast.
    check(
        "gate allows reply",
        NotifReply.decide(hasReplyAction = true, isBlasting = false) ==
            NotifReply.ReplyDecision.ALLOWED
    )
    // 11. Reply gate: blocked with no reply action.
    check(
        "gate blocks no reply action",
        NotifReply.decide(hasReplyAction = false, isBlasting = false) ==
            NotifReply.ReplyDecision.BLOCKED_NO_REPLY_ACTION
    )
    // 12. Reply gate: blast blocks even with a reply action.
    check(
        "gate blocks during blast",
        NotifReply.decide(hasReplyAction = true, isBlasting = true) ==
            NotifReply.ReplyDecision.BLOCKED_BLAST_ACTIVE
    )
    // 13. Reply gate: blast wins over the no-action case.
    check(
        "gate blast wins over no-action",
        NotifReply.decide(hasReplyAction = false, isBlasting = true) ==
            NotifReply.ReplyDecision.BLOCKED_BLAST_ACTIVE
    )

    // 14. Log summary is app + title only — the body never appears.
    val logged = notifSummaryForLog(entry("com.whatsapp", "Alice", "secret body text"))
    check(
        "summary has app and title only",
        logged == "whatsapp: Alice" && !logged.contains("secret body text")
    )
    // 15. Empty title gets a placeholder, not an empty summary.
    check(
        "summary empty title placeholder",
        notifSummaryForLog(entry("com.app", "")) == "app: (no title)"
    )
    // 16. Long title is truncated.
    val long = notifSummaryForLog(entry("com.app", "x".repeat(200)))
    check("summary truncates long title", long.length <= "app: ".length + 81)

    // 17. Sensitive detection: OTP-style content.
    check("sensitive OTP digits", looksSensitive("Your code is 482913"))
    check("sensitive verification wording", looksSensitive("Your verification code"))
    check("sensitive password wording", looksSensitive("Reset your password"))
    check("not sensitive plain text", !looksSensitive("See you at 7 for dinner"))
    check("not sensitive short number", !looksSensitive("Room 12 is ready"))

    // 18. Sensitive title is hidden from the log summary (digits included).
    val hidden = notifSummaryForLog(entry("com.bank", "Your code is 482913"))
    check(
        "sensitive title hidden from log",
        hidden.contains("looks sensitive") && !hidden.contains("482913")
    )

    println("NotifStoreTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
