# Blast Buster v1.3 — Release Notes (draft)

**Version:** 1.3 (`versionCode 4`), package `com.splendor.blastbuster`.
**Baseline:** v1.2, remote HEAD `e8fc485a`.
**Status:** built, signed and pushed — JVM suite 533 passed / 0 failed /
3 skipped (environmental), Gradle release build SUCCESSFUL with zero
compiler warnings, APK signed with the permanent release key, commit
`8ce487a7` on GitHub main. The blast core is untouched in this release.

> ⚠️ **ONE-LAST-UNINSTALL — read this first.** v1.2 was signed with a
> debug key that is now lost, so v1.3 **cannot install on top of v1.2**.
> Uninstall v1.2 once, then install v1.3. Uninstalling wipes everything
> the app stored: your settings (trigger/stop codes, trusted numbers,
> schedules), the bridge token, and all three ADB grants — you will need
> to set them up again (steps below). After this one reinstall, every
> future update installs on top forever, as long as the signing key is
> kept.

## What's new in v1.3 (11 items)

1. **Bridge auto re-bind on Tailscale up/down** — if Tailscale wasn't up
   when the bridge started, the server now re-binds to the tailnet address
   when Tailscale comes up, without restarting the app. It only re-binds
   when the address actually changes.
2. **Bridge `GET /v1/log` export** — the event log leaves the phone only
   after you tap "Allow one log export" in the Bridge screen. One export,
   5 minutes, then it closes again; the grant is never saved, so a restart
   clears it.
3. **Notification capture** — a new Notifications tab. Turn capture on and
   grant Notification access, and the app shows notifications as they
   arrive (latest 50). The log records app + title only — never the
   message body.
4. **WhatsApp reply** — reply to a notification from inside the app,
   through that app's own reply action (WhatsApp has one). Nothing is ever
   auto-replied, and replies are blocked during a blast.
5. **Elimination mode** — one "Eliminate now" button in the RAM screen
   kills rogue apps only, one at a time, and reports honestly whether each
   one was killed, restarted, or failed. Blocked during a blast.
6. **Low-memory alert** — when free RAM drops below the system's own
   threshold, the RAM screen shows a LOW MEMORY banner with the free amount.
7. **RAM-list UsageStats rows** — with Usage Access granted, each row shows
   when the app was last used. Honest verdict: **DUMP unlocks no per-app
   PSS list** — the RAM screen shows the same numbers with or without the
   DUMP grant.
8. **DND schedule from the UI + fight rule** — set your nightly rule in the
   DND screen and the guard enforces exactly that. If another app keeps
   fighting it, the guard stops after 3 re-applies in an hour, logs it once,
   and lets you decide. Honest verdict: **the READ_LOGS logcat tier often
   returns nothing on Android 16** — the log may show only this app's own
   entries — and it only runs when notification access is also granted.
9. **Net ADB outcomes, honestly surfaced** — a flick or mode lock that the
   radio ignores now says so (`write_ok_but_not_applied`) instead of
   claiming success; without the ADB grant your choice is saved and the
   right Settings page opens.
10. **Private DNS warning + 1.1.1.1 fallback** — the DNS screen reads your
    Private DNS setting live and warns when it bypasses the pipe. One tap
    sets it to 1.1.1.1 (encrypted DNS to Cloudflare) with the ADB grant;
    without the grant it shows manual steps. Refused while another VPN
    holds the slot.
11. **DNS pipe vs Tailscale — you choose** — the DNS screen states the
    conflict plainly: Android has one VPN slot, so the DNS pipe and
    Tailscale cannot hold it together. Honest verdicts: **running DNS
    through Tailscale is not feasible from the app**, and **DoH/DoT
    upstream in the pipe is out — next round** (the Private DNS 1.1.1.1
    fallback above is the encrypted-DNS option that shipped instead).

## What works with NO ADB

Everything except the three one-tap features: one-tap airplane flick,
one-tap network-mode lock, and one-tap Private DNS 1.1.1.1 fallback. A
user who never touches ADB gets a complete v1.3 — blast core, RAM watch
and Elimination, DND schedule, NetWatch, DNS pipe, bridge, and
notification capture — with guided manual steps where one tap would need
a grant.

## What needs the ADB grants

One-time setup from a computer, after installing v1.3 and opening it once:

```sh
adb shell pm grant com.splendor.blastbuster android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.splendor.blastbuster android.permission.DUMP
adb shell pm grant com.splendor.blastbuster android.permission.READ_LOGS
```

Verify with:

```sh
adb shell dumpsys package com.splendor.blastbuster | grep -E "WRITE_SECURE_SETTINGS|DUMP|READ_LOGS"
# each line must say granted=true
```

- `WRITE_SECURE_SETTINGS` — one-tap airplane flick, one-tap network-mode
  lock, one-tap Private DNS 1.1.1.1 fallback.
- `DUMP` — experimental diagnostics. Honest verdict: it unlocks no per-app
  PSS list in v1.3; keep it only for the on-device D-09 check.
- `READ_LOGS` — experimental DND culprit detection from the system log.
  Honest verdict: it often returns nothing on Android 16, and only runs
  when notification access is also granted.

The grants survive reboots and app updates (same signing key) and are lost
on uninstall — the app re-checks on every launch and shows the exact
re-run command for any grant that is gone.

## DND schedule note

DND enforcement is **OFF by default**. When you turn it on, set your hours
in the DND screen (e.g. 22:00–07:00 every day). When the schedule says DND
should be on, the guard applies **alarms only** — alarms still sound. The
guard never re-applies DND while a blast is active (the siren must not be
silenced).

## Blast regression checklist (run on the phone before trusting v1.3)

The blast core is untouched, but prove it anyway — any failure here stops
the release:

1. From your **second number**, text `BLAST 1234` to this phone → siren
   sounds, strobe flashes, red lockout screen appears.
2. Text `STOP 1234` from the second number → siren stops.
3. Start a test blast again → unlock it with your **fingerprint** on the
   side power button (no scanner shows on screen).
4. Check the reply SMS on the second number → it carries the phone's
   last-known GPS coordinates.
5. Pull the SIM while armed → the phone blasts by itself (no SMS needed).

## On-device checks still open

These could not be proven in the sandbox — they need the Redmi 15C:

- **D-07** logcat tier — does `READ_LOGS` actually name the app that
  changed DND on Android 16, or only this app's own entries?
- **D-09** `dumpPackageState` — confirm DUMP surfaces nothing beyond the
  no-grant RAM screen, then drop the grant from the setup steps.
- **D-10 / D-11 / D-12** flick / mode lock / DNS — one-tap paths with the
  grant, guided paths without it, `write_ok_but_not_applied` if HyperOS
  ignores a write, Private DNS fallback, VPN-slot conflict card.
- **Bridge re-bind on real Tailscale up/down** — bridge on with Tailscale
  off, then turn Tailscale on: the server must answer on the tailnet
  address without an app restart.
- **WhatsApp reply** — capture a real WhatsApp notification and reply
  from inside the app; confirm the reply is delivered and nothing is
  auto-replied.

## What the bridge still cannot do

No push — Muse learns about a blast, a SIM pull, or a rogue app only on
the next poll (15-minute default). No offline reach, no wake, no remote
`blast.start` (start a blast by SMS trigger code, as before). The bridge
serves status, events, and commands only.
