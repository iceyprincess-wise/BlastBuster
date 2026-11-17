# Blast Buster v1.3 — Muse Companion Suite: Design Document

**Status:** v1.3 INTEGRATED — the five v1.3 module agents' work is merged
in `~/workspace/blastbuster-v3`, the JVM suite is green, no Gradle build
run, no signing, no push (the parent does those after integration).
**Author:** design lead (subagent), 2026-11-17; v1.3 integration 2026-11-17.
**Repo:** https://github.com/iceyprincess-wise/BlastBuster, branch `main`.
**Baseline:** v1.2, `versionName 1.2`, `versionCode 3`, package
`com.splendor.blastbuster`, remote HEAD `e8fc485a` (verified live).
**Target:** v1.3, `versionName 1.3`, `versionCode 4`, same package, same app name.
**Target device:** Redmi 15C, 4 GB RAM, Android 16 / API 36, HyperOS, MediaTek Helio G81,
dual SIM (Airtel + MTN). The app must also be usable by other Muse owners, so
**nothing personal is hardcoded**: no trigger codes, no phone numbers, no SIM
identities, no Tailscale addresses, no tokens, no schedules — everything
configurable in-app, everything defaulting to OFF/safe except the blast core,
which keeps its v2 defaults.

## What changed in v1.3

Eleven items, each built by a module agent and wired in by integration.
The blast core is untouched (verified: the ten blast files' mtimes predate
the round and contain no v1.3 identifiers). One known build-breaker was
fixed at integration: `Settings.Global.PRIVATE_DNS_MODE` /
`PRIVATE_DNS_SPECIFIER` are hidden APIs absent from the android-34
`android.jar`, so every reference now uses the string literals
`"private_dns_mode"` / `"private_dns_specifier"` (same values) — behaviour
unchanged.

1. **Bridge auto re-bind on Tailscale up/down** (`bridge/BridgeRebind.kt`) —
   the server re-binds only when the tailnet address actually changes
   (same address, including still-no-tailnet, means no rebind); bind set is
   always 127.0.0.1 plus the tailnet address when one exists, port 8741.
2. **Bridge `GET /v1/log` export — §8 D-04 DONE** (`bridge/LogExportGate.kt`) —
   the log leaves the phone only behind an explicit per-export confirm in
   the app: single-use, 5-minute window, never persisted (a restart clears
   it). The server logs every grant/deny/export.
3. **Notification capture** (`notif/NotifListener.kt`, `NotifStore.kt`) — a
   second `NotificationListenerService`, system-bound via component state,
   OFF by default; latest 50 notifications kept; `EventLog` records app +
   title only, never the message body.
4. **WhatsApp reply** (`notif/NotifReply.kt`, `NotifActivity.kt`) — reply
   through the notification's own reply action (WhatsApp exposes one);
   nothing is ever auto-replied; replies are blocked during a blast.
5. **Elimination mode** (`ram/EliminationMode.kt`, "Eliminate now" in
   `RamActivity`) — one tap kills rogue apps only, one at a time,
   blast-gated; the result (killed / restarted / failed) is reported
   honestly, never assumed.
6. **Low-memory alert** (`ram/RamSnapshot.kt` `LowMemoryEpisode`,
   banner in `RamActivity`) — a banner shows when free RAM drops below
   the system's own low-memory threshold.
7. **RAM-list UsageStats rows** (`ram/RamWatch.kt`, `RamListPresentation`) —
   the list now shows recently-used apps from `UsageStatsManager` with
   last-used time. Honest verdict: **DUMP unlocks no per-app PSS list** —
   there is no public API that yields other apps' pids, so no DUMP path
   is wired and the screen shows the same numbers with or without the
   grant (§7.2 D-09).
8. **DND schedule from UI + fight rule** (`dnd/DndGuard.kt`,
   `DndFightTracker`, `DndReapplyLimiter`, `decideDndAction`) — the
   schedule the UI saves is the schedule the guard reads; after 3
   re-applies in an hour the guard stops re-applying, logs
   `DND_FIGHT_DETECTED` once per episode, and lets the user decide.
   Honest verdict: **the READ_LOGS logcat tier often returns nothing on
   Android 16** — it only runs when notification access is also granted,
   and the log may show only this app's own entries (§7.2 D-07).
9. **Net ADB outcomes** (`net/NetOutcomes.kt`) — flick / mode-lock
   results are mapped to honest status lines: `write_ok_but_not_applied`
   is surfaced instead of claimed success, `needs_adb` shows the guided
   manual path, and the choice is saved when the grant is missing.
10. **Private DNS warning + 1.1.1.1 fallback** (`dns/DnsActivity.kt`,
    `dns/DnsState.kt`) — Private DNS mode is read live from
    `Settings.Global`; the screen warns when it bypasses the pipe, and
    offers a one-tap fallback to `one.one.one.one` with the
    `WRITE_SECURE_SETTINGS` grant (manual steps without it). Refused
    while another VPN holds the slot — Tailscale's resolver does not
    speak DoT.
11. **DNS pipe vs Tailscale VPN-slot choice — §8 D-08 SHIPPED** — the DNS
    screen states the conflict (one VPN slot; the pipe and Tailscale
    cannot hold it together) and lets the user choose. Honest verdicts:
    **running DNS through Tailscale is not feasible from the app**, and
    **DoH/DoT upstream in the pipe is out — next round** (§8 D-09).

The v1.2 design below is otherwise unchanged and still describes the
architecture, permissions matrix, and module behaviour; §7.2 and §8 are
updated for v1.3 where noted.

---

## 0. Baseline verification (done by the designer, 2026-11-17)

Sandbox source inspected: `~/workspace/blastbuster-build/`.

- `app/build.gradle.kts` confirms: `applicationId com.splendor.blastbuster`,
  `compileSdk 34`, `minSdk 26`, `targetSdk 34`, `versionCode 2`,
  `versionName "1.1"`, zero dependencies, `android.useAndroidX=false`.
- 13 Kotlin files, 2,244 lines total, matching the v2 description:
  `BlastService.kt` (698), `MainActivity.kt` (467), `LockoutActivity.kt` (342),
  `SimWatcher.kt` (156), `VoiceTrigger.kt` (137), `Prefs.kt` (96), `Siren.kt` (93),
  `SmsReceiver.kt` (53), `SimPolicy.kt` (27), `BlastAdminReceiver.kt` (24),
  `BootReceiver.kt` (23), `VoicePhrase.kt` (26), plus `AndroidManifest.xml` (96).
- Manifest confirms already-declared: `RECEIVE_SMS`, `SEND_SMS`,
  `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`,
  `WAKE_LOCK`, `CAMERA`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`,
  `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `VIBRATE`,
  `ACCESS_NOTIFICATION_POLICY` (v1.1 DND fix), `SYSTEM_ALERT_WINDOW` (v1.1
  shade block), `USE_BIOMETRIC` / `USE_FINGERPRINT`, `READ_PHONE_STATE`,
  `RECORD_AUDIO`. Theme is `@android:style/Theme.Material.Light`, UI built
  programmatically with plain widgets (no layouts, no AndroidX).
- `Prefs.kt` confirms the settings model: single `SharedPreferences` file
  `"blast_buster"`, codes default `BLAST 1234` / `STOP 1234`, optional
  trusted-number list (empty = anyone with the code), voice phrase default
  `"Blast Buster"` OFF by default, `isBlasting` flag that `MainActivity`
  checks on launch to redirect to the lockout screen.
- `BlastService` confirms the two modes: GUARD (quiet foreground service,
  SIM watcher, optional voice listener) and BLAST (siren on `STREAM_ALARM`,
  strobe, vibration, TTS shouting, `lockNow()`, lockout screen, GPS reply SMS).
- Live device check (same day): the paired REDMI 15C (`f219979e8fe83a5f`) is
  **online**, Android `16.0.0.11.159`, 43 device commands. Its command list
  was read in full (see §2.5). **There is no command that talks to a
  third-party app, runs an intent, or reads another app's data.** The only
  messaging command is `message.send` (SMS, user approval on-device by
  default, only when the Muse app is the default assistant).

**Baseline caveat (honest):** the sandbox copy is *not* a git checkout —
`git log` reports "your current branch 'master' does not have any commits
yet" and every file is untracked — so HEAD `ec45dcfb` could **not** be
verified from the sandbox. The version numbers, file inventory and behaviour
above all match the v2 description exactly, so the design proceeds on that
basis. **Code agents must re-verify HEAD `ec45dcfb` from GitHub before
starting** (see §4.7, precondition P0). If the remote HEAD differs, stop and
report — do not silently rebase the design onto a different base.

---

## 1. What v1.2 is — and what it is not

v1.2 turns Blast Buster from a single-purpose theft alarm into a small
**companion suite**: the blast core stays exactly as it is, and four new
capability areas hang off it — RAM watch, DND enforcement, network tools,
and a bridge to Muse — behind a redesigned premium UI.

**Non-goals (explicit, do not build these in v1.2):**

- No root, no Magisk, no Shizuku, no custom ROM behaviour. Everything must
  work on a stock, unrooted Redmi 15C.
- No paid service or library, no Firebase / FCM, no third-party SDK.
  Pure Kotlin, no dependencies, no AndroidX — same constraint as v2.
- No cloud account, no user login, no server of ours. The app never sends
  the user's data to any server we run, because there is none.
- No "RAM booster" that claims to add RAM, no "signal booster" that claims
  to create coverage, no fake "AI optimisation". Every honest limit in this
  document must appear in the in-app copy next to the feature it limits.
- No change to blast behaviour: same trigger/stop codes, same siren, same
  strobe, same lockout, same SIM auto-blast, same priority. If any new
  module conflicts with a blast, the blast wins and the module yields (§1.3).

### 1.1 Module map

| # | Module | Owner service / entry point | Runs when |
|---|--------|------------------------------|-----------|
| 0 | **Blast core (v2, untouched)** | `BlastService` (GUARD + BLAST), `SmsReceiver`, `LockoutActivity` | Always when armed |
| 1 | **RAM watch** | `RamWatch` inside `CompanionService` + `RamActivity` | Guard running, user-enabled |
| 2 | **DND enforcement** | `DndGuard` inside `CompanionService` + `DndListener` (`NotificationListenerService`) | Guard running, user-enabled |
| 3 | **Network tools** | `NetWatch` inside `CompanionService` + `DnsVpnService` (`VpnService`) | Guard running (watch) / user toggle (DNS pipe) |
| 4 | **Muse bridge** | `BridgeServer` (local HTTP) inside `CompanionService` + `EventLog` | Guard running, user-enabled |
| 5 | **Premium UI** | `MainActivity` rebuilt as hub + per-module screens | App open |

One new foreground service hosts modules 1–4: **`CompanionService`**.
It is separate from `BlastService` on purpose — if the companion service is
killed, paused or crashes, the blast guard is unaffected, and vice versa.
Both services are `specialUse` foreground services with their own
notifications and channels. `BootReceiver` starts both when armed (blast)
and when enabled (companion).

### 1.2 How the modules interact

```
                          ┌──────────────────────────────┐
                          │  MainActivity (hub, §6)      │
                          │  Blast / RAM / DND / Net /   │
                          │  Bridge tabs + Setup checklist│
                          └──────┬───────────┬───────────┘
                                 │ reads/writes
                    ┌────────────▼─┐   ┌─────▼────────────┐
                    │  Prefs       │   │  EventLog        │
                    │  (all        │   │  (append-only    │
                    │   settings)  │   │   ring, §2.6)    │
                    └────┬─────────┘   └─────▲────────────┘
                         │                   │ every module writes
   ┌─────────────────────┼───────────────────┼─────────────────────┐
   │ BlastService        │ CompanionService  │                     │
   │ (GUARD/BLAST, v2)   │ ┌───────────────┐ │  DnsVpnService      │
   │  ├─ SimWatcher     │ │ RamWatch      │ │  (own service,    │
   │  ├─ VoiceTrigger   │ │ DndGuard      │ │   own process      │
   │  └─ Siren/TTS      │ │ NetWatch      │ │   lifetime)        │
   │                     │ │ BridgeServer  │ │                     │
   │                     │ └───────────────┘ │                     │
   │                     │  BlastGate (§1.3)│                     │
   └─────────────────────┴───────────────────┴─────────────────────┘
        ▲ blast state published via Prefs.isBlasting + broadcast
        │ (CompanionService subscribes; never the reverse)
```

Rules of interaction:

1. **Settings flow one way:** UI writes `Prefs`; services read `Prefs`.
   No service writes another module's settings.
2. **Events flow one way:** every module appends to `EventLog`; the bridge
   *reads* `EventLog` and serves it; the UI *reads* it for the history
   screens. Nothing deletes another module's events except the ring-buffer
   cap (oldest-first, §2.6).
3. **Blast state flows one way:** `BlastService` is the sole writer of
   `Prefs.isBlasting` and of the `BLAST_STARTED` / `BLAST_STOPPED` local
   broadcasts. `CompanionService` and `DnsVpnService` subscribe and yield.
   No new module ever starts, stops, or delays a blast, and no new module
   ever writes `isBlasting`.
4. **No module depends on another module at runtime.** RAM watch works with
   DND off; the bridge works with the DNS pipe off; the DNS pipe works with
   the bridge off. Each module degrades independently to its no-permission
   fallback (§3).

### 1.3 The blast-priority rule — enforced where

This is the single most important invariant in v1.2. It is enforced in
**four** places, not one, so no single bug can break it:

1. **`BlastGate` (new, pure logic, JVM-tested).** A tiny class both services
   consult: `BlastGate.isBlasting(ctx)` reads `Prefs.isBlasting`, and
   `BlastGate.shouldPauseCompanionWork()` returns true while blasting.
   Every companion action (RAM kill, DND re-apply, airplane flick, DNS
   restart, bridge command execution) calls `BlastGate.check()` first and
   aborts — logging `SKIPPED_BLAST_ACTIVE` — when it returns true.
   Exception: bridge *reads* (`GET /status`, `GET /events`) are allowed
   during a blast — Muse must be able to see that a blast is happening.
   Bridge *writes* (any command that changes phone state) are blocked
   during a blast, except `POST /blast/stop`, which is the owner's remote
   stop and is allowed only with the bridge token *and* the stop code.
2. **`CompanionService.onStartCommand`.** When `BLAST_STARTED` arrives it
   suspends all timers (RAM scan, DND re-check, NetWatch poll, bridge
   keep-alive) within one tick, releases the wake lock it holds for scans,
   and does **not** restart them until `BLAST_STOPPED`. It never touches
   audio, camera, vibration or the lock screen.
3. **`DnsVpnService`.** On `BLAST_STARTED` it keeps the tunnel up (dropping
   the tunnel mid-blast could stall the GPS reply SMS path and the bridge)
   but stops all blocklist reloads and all log writes until `BLAST_STOPPED`.
   It never calls `stopSelf()` on blast, and never starts on blast.
4. **`MainActivity` / UI.** The v2 rule is preserved verbatim: while
   `Prefs.isBlasting` is true, `MainActivity.onCreate` redirects straight
   to `LockoutActivity` and finishes. No companion screen is reachable
   during a blast. The lockout screen itself is **not redesigned** (§6.4) —
   it stays red, full-screen, fingerprint-only, no Stop button.

Regression test for this rule is mandatory in every module's test plan
(§7): "start blast → companion action attempted → action skipped +
event logged + blast unaffected".

---

## 2. The Muse bridge — decision first

### 2.1 The decision (one paragraph, as required)

**DECISION: v1.2 ships a poll-based local HTTP bridge over Tailscale, and
nothing else, as the Muse channel.** The phone runs a tiny hand-rolled HTTP
server (`BridgeServer`, plain `ServerSocket`, no dependencies) inside
`CompanionService`, bound to the phone's Tailscale IP on a fixed port
(8741) and to localhost, protected by a per-install random token the user
copies into chat once. Muse — which lives in the cloud and whose VM is a
Tailscale *client only* (it makes outgoing TCP connections and accepts
none, hosts nothing) — reaches that server through the existing Tailscale
connector tunnel, polls `GET /status` and `GET /events`, and sends commands
with `POST`. The phone **never pushes to Muse**, because there is no
endpoint on Muse's side to push to. All findings therefore surface when
Muse asks, not the moment they happen; urgent findings (blast started,
SIM pulled) are also written to `EventLog` and surfaced on the next poll,
and the existing SMS trigger path remains the only near-instant remote
channel. No cloud relay, no Firebase/FCM, no GitHub polling, no fake
"live push" is built in v1.2.

### 2.2 Why this is the only honest design — what was ruled out

Worked through from the actual facts, verified 2026-11-17:

**Fact 1 — Muse lives in the cloud; the app lives on the phone.**
There is no shared process, no shared filesystem, no local socket between
them. Any channel must cross a network.

**Fact 2 — What the device pairing does and does not give you.**
Verified live against the REDMI 15C (online, Android 16.0.0.11.159, 43
commands): the pairing exposes Muse-side commands on the phone —
`location.get`, `network.state`, `battery.get`, `message.search/send`,
`notifications.get`, `contacts/calendar/call_log` search, alarms/reminders,
geofences. It does **not** expose any command that talks to a third-party
app, runs an Android intent, reads another app's files or SharedPreferences,
or delivers a payload into an app. The pairing is between Muse and *its own
companion app on the phone*, not between Muse and Blast Buster. So the
pairing **cannot** be the bridge: Blast Buster cannot receive anything
through it, and Muse cannot read Blast Buster's state through it
(`notifications.get` sees only posted notifications — useful as a weak
read-only hint, never as a channel; do not build on it).

**Fact 3 — What the Tailscale connector does and does not give you.**
Verified from the connector doc: the Muse VM joins the user's tailnet as a
client only (`/opt/hatch/bin/tailscale`, device name `muse`). It makes
**outgoing TCP connections** and accepts **none**, hosts nothing, routes
nothing. Reach a private service with `curl` through the tunnel proxy
(port 3130). First request to each tailnet address needs a user approval.
Consequences: (a) the phone **can** be the server and the Muse VM the
client — this direction works; (b) the reverse direction (phone pushing to
the Muse VM) **cannot** work — the VM accepts no inbound TCP, and there is
no webhook URL to POST to; (c) UDP, ping and ICMP do not cross the tunnel,
so no UDP discovery, no push notification, no keep-alive datagrams — TCP
polling only; (d) the phone must run the Tailscale app (or WireGuard via
the Tailscale app) and be on the same tailnet, and the Muse VM must be
joined — both are user setup steps, not code.

**Fact 4 — No push service exists for a no-dependency app.**
FCM needs Firebase (a dependency, a Google project, a server key) — excluded
by the no-dependency rule and by "no paid services". There is no free,
reliable, private push service that works without a server. Any design
claiming "Muse gets notified instantly" without a server is a fake.

**Options considered and rejected:**

| Option | Verdict | Reason |
|---|---|---|
| Phone pushes to Muse (webhook / socket) | **Impossible** | Muse VM accepts no inbound TCP, hosts nothing, exposes no webhook. No endpoint exists to push to. |
| Cloud relay (our server / free tier) | **Rejected** | No server budget, no account, no one to run it; would also put the user's location + security events on a third party. Revisit only if Splendor funds a server — see §8, D-01. |
| FCM push | **Rejected** | Needs Firebase dependency + Google project + server key. Violates no-dependency rule; also needs a server to send from. |
| GitHub gist / repo file polling | **Rejected** | Public by default, rate-limited, secrets would live in a repo, not real-time, and polling a repo from the phone is fragile on HyperOS. A hack, not a channel. |
| SMS as the main channel | **Rejected as primary; kept as fallback** | Muse *can* send an SMS via `message.send` (verified: the command exists), but each send needs on-device user approval by default, costs carrier SMS, is not silent, and delivery is not guaranteed. It already works today for blast/stop (trigger code by SMS) with no new code — that is the fallback, not the bridge. |
| Bluetooth / Wi-Fi Direct / hotspot | **Rejected** | Needs physical proximity, pairing, and a second local transport; Muse's VM is not on the local LAN. Solves nothing the Tailscale path doesn't solve better. |
| **Local HTTP server on phone + Tailscale polling (chosen)** | **Shipped** | Only direction that matches the actual network facts: phone serves, Muse polls. No server, no dependency, no cost, private to the tailnet, token-authenticated. |

### 2.3 The chosen design in detail

**Server — `BridgeServer` (new, `companion/bridge/`).**

- Plain `java.net.ServerSocket`, one accept thread + a small fixed thread
  pool (3 threads max — this is a 4 GB phone; the server must be cheap).
  **No HTTP library exists in the no-dependency constraint**, so the HTTP
  parsing is hand-rolled and deliberately tiny: request line + headers +
  optional body, `Content-Length` only, no chunked encoding, no keep-alive
  beyond one request per connection (close after each response — simpler,
  and polling is infrequent). Response is hand-built JSON via `MiniJson`
  (§4.2) — no JSON library either.
- Bind: `0.0.0.0` is **forbidden**. Bind to (a) the Tailscale interface
  address (obtained via `NetworkInterface` scan for `100.64.0.0/10`, the
  tailnet range) and (b) `127.0.0.1` for on-device testing. If no Tailscale
  address is present, the server still starts on localhost and reports
  `tailscale: false` in `/status` — the bridge is "local only" until the
  user opens the Tailscale app. Never bind to the Wi-Fi/LAN address:
  anyone on the same Wi-Fi could reach it.
- Port: **8741** fixed in v1.2 (documented in-app; a custom port setting
  is a v1.3 option, not v1.2 scope). If 8741 is taken, fail loudly in the
  UI and in `EventLog` — do not silently pick another port Muse doesn't
  know about.
- Auth: `Authorization: Bearer <token>` on every endpoint, no exceptions.
  Token = 32 random bytes, Base64-URL, generated once at first enable,
  stored in `Prefs` (private SharedPreferences, `MODE_PRIVATE`), shown in
  the Bridge screen behind a "show" tap, with Regenerate and Copy buttons.
  No token in logs, no token in `EventLog`, no token in screenshots.
  Wrong/missing token → `401`, and the attempt is logged (count only, no
  token value). Rate-limit: max 10 failed auths per minute per IP, then
  that IP is ignored for 5 minutes (in-memory only).
- TLS: **none in v1.2 — stated plainly.** Tailscale traffic is already
  encrypted end-to-end by WireGuard, and localhost needs none. If the
  server were ever bound to LAN/Wi-Fi, plain HTTP would be unsafe — which
  is why it is never bound there. A future self-signed TLS option is
  open decision D-02 (§8), not v1.2 work.

**Endpoints (all JSON, all token-authenticated):**

| Method + path | Purpose | Blast rule |
|---|---|---|
| `GET /v1/status` | One-shot snapshot: armed, blasting, battery, RAM summary, DND state, per-SIM signal, DNS pipe state, bridge uptime, tailnet reachable, counts. The polling workhorse. | Allowed during blast (read-only). |
| `GET /v1/events?since=<id>&limit=100` | `EventLog` tail (see §2.6). `since` is a monotonic event id; response includes `next_since`. | Allowed during blast. |
| `GET /v1/ram` | Current RAM snapshot: total/avail, top 10 by PSS, rogue verdicts. | Allowed during blast. |
| `GET /v1/dnd` | DND schedule, current state, last 20 DND changes with culprit. | Allowed during blast. |
| `GET /v1/net` | Per-SIM signal, airplane flick state, network-mode lock state, DNS stats. | Allowed during blast. |
| `POST /v1/command` | Single command entry point. Body: `{"command": "...", "args": {...}, "code": "..."}`. See command table below. | Writes blocked during blast except `blast.stop` (token + stop code). |
| `GET /v1/health` | Liveness only: `{"ok": true}`. No auth? **No — auth required too.** A health endpoint without auth is a presence oracle for anyone scanning the tailnet. | Allowed during blast. |

**Commands (`POST /v1/command`) — deliberately small in v1.2:**

| Command | Args | Effect | Guard |
|---|---|---|---|
| `ram.snapshot` | — | Forces a fresh RAM scan, returns it. | None (read). |
| `ram.kill` | `package` | Kills that package's background processes (same rules as in-app kill, §5.1). One package per call; no bulk kill. | Blocked during blast; package must not be Blast Buster itself, the system UI, or the phone app — denylist in `BridgeCommands`. |
| `dnd.apply` | `mode` (`on`/`off`) | Applies DND now (and logs it as a Muse-initiated change). | Blocked during blast. |
| `net.flick` | — | Airplane-mode flick (needs ADB grant; without it returns `needs_adb`, §3). | Blocked during blast. |
| `net.mode` | `mode` (`4g`/`5g`/`auto`) | Network-mode lock (needs ADB grant). | Blocked during blast. |
| `dns.set` | `enabled` (bool) | Starts/stops the DNS pipe. | Blocked during blast. |
| `blast.status` | — | Same as `GET /v1/status` blast fields. | Allowed during blast. |
| `blast.stop` | `code` (stop code) | Stops a blast. Requires **both** the bridge token **and** the user's stop code in the body. If no blast is running, returns `not_blasting`. | **Allowed during blast — the only write allowed.** |
| `blast.start` | `code` (trigger code) | **NOT IMPLEMENTED in v1.2.** Deliberately absent: a remote start is the most dangerous command (a stolen token = a phone screaming in the owner's pocket at will, and a false trigger path). The owner starts a blast by SMS trigger code, which already exists. Revisit only on Splendor's explicit call — see §8, D-03. | — |

Every command returns `{"ok": true|false, "result": ..., "error": "..."}` and
is appended to `EventLog` as `BRIDGE_CMD` with the command name and outcome
(never with args that contain codes or the token).

**Muse side (what the parent agent / Muse must do — not app code):**

1. The Muse VM joins the user's tailnet once (`tailscale up`, user approves
   the link). This is a one-time setup; the VM's Tailscale identity persists
   until `tailscale down`.
2. Muse polls `GET /v1/status` + `GET /v1/events?since=<last>` on the
   schedule Splendor chooses (a cron, e.g. every 15 minutes, plus on-demand
   when Splendor asks "what is my phone doing?"). Polling is Muse's job;
   the app cannot trigger a poll.
3. First request to the phone's tailnet address needs a user approval in
   Muse (per-address approval, then it covers other ports at that address).
   The agent must use **one** address form consistently (tailnet IP, not
   MagicDNS name one time and IP the next — each form is a separate
   approval).
4. Muse sends commands only when Splendor asks, and only the commands in
   the table above. A command that returns `needs_adb` or
   `blocked_blast_active` is reported as such — never retried blindly, never
   presented as done.
5. The bridge token lives in Muse's memory for this user (set by Splendor
   pasting it once in chat). It is never written into a file, a repo, or a
   message to anyone else.

**What the bridge cannot do in v1.2 (honest limits, also shown in-app):**

- **No push.** Muse learns about a blast, a SIM pull, or a rogue app only
  on the next poll (or when Splendor asks). Worst-case latency = the poll
  interval. If Splendor needs near-instant blast alerts, the honest answer
  is: use the SMS path (the blast already sends a GPS reply SMS to the
  trusted sender) or shorten the poll interval — not a fake push.
- **No offline reach.** If the phone has no data, Tailscale is off, the
  Muse VM is not joined, or the phone is off, the bridge is unreachable
  and polling fails. The app queues events locally and serves them when
  connectivity returns — nothing is lost, but nothing is live either.
- **No wake.** The bridge cannot wake a sleeping phone, start the guard if
  HyperOS killed it, or reach the phone if the companion service is dead.
  The guard's existing autostart / no-restrictions / recents-lock setup
  steps are what keep it alive.
- **No `blast.start`.** See table above.
- **No file transfer, no screenshots, no remote UI.** v1.2 serves JSON
  status/events/commands only. (A `GET /v1/log` export is D-04, §8.)

### 2.4 Fallback channel — SMS (no new code)

Already true today, no v1.2 code needed, documented here so nobody
re-invents it: Muse can send an SMS from the paired phone via the device
pairing's `message.send` command (verified present, 2026-11-17). Sending
the trigger code (`BLAST 1234` by default, user-changeable) to the phone's
own number from the paired device delivers as an incoming SMS, which
`SmsReceiver` handles exactly like a trigger SMS from any other phone —
subject to the trusted-number list if one is set. Caveats, all real:
each send needs on-device user approval by default (the user can set
always-allow in the Muse app); it costs a carrier SMS; it is not silent;
delivery is not guaranteed; and it only works when the Muse app is the
default assistant on that phone. **This is the emergency fallback, not the
bridge.** The bridge design must never depend on it.

### 2.5 What the device pairing is *for* in v1.2

Two legitimate, limited uses — both Muse-side, neither needing app code:

1. **Independent verification.** Muse can cross-check the bridge against
   the pairing's own reads: `battery.get`, `network.state`,
   `location.get` vs the bridge's `/v1/status`. If bridge and pairing
   disagree, trust the pairing's live read and report the mismatch.
2. **Emergency SMS.** §2.4.

That is all. The pairing is not a data pipe for Blast Buster findings.

### 2.6 EventLog — the shared record every module writes

- **Store:** one append-only file in the app's private files dir
  (`eventlog.jsonl`, JSON-lines, one event per line), plus an in-memory
  ring for the UI. Cap: **500 events / 256 KB**, oldest-first eviction.
  No personal data beyond what the user already configured (package names,
  phone numbers are **never** logged — trusted numbers are matched, not
  stored in events; GPS coordinates are **never** logged).
- **Event shape:** `{"id": <monotonic long>, "ts": <epoch ms>,
  "module": "ram|dnd|net|dns|bridge|blast|system",
  "type": "...", "summary": "<human sentence>", "data": {...}}`.
  `id` is the `since` cursor for `GET /v1/events`.
- **Writers:** RAM watch (scan summaries, rogue verdicts, kills),
  DND guard (schedule applies, external changes with culprit, re-applies),
  NetWatch (signal drops, flicks, mode locks), DNS pipe (start/stop,
  top blocked domains daily count only — never full query logs),
  Bridge (every command + auth failures by count), Blast (start/stop —
  BlastService already owns these; it appends via the same `EventLog`
  API, it does not change its own behaviour).
- **Privacy rule:** the log is on-device only. It leaves the phone only
  through the authenticated bridge (`GET /v1/events`) or an explicit
  in-app Share/Export tap by the user. There is no automatic upload.

---

## 3. Permissions matrix

### 3.1 How to read this matrix

- **Grant path A — runtime prompt:** `requestPermissions()` in-app, user taps Allow.
- **Grant path B — Settings tap:** no runtime prompt exists; the user flips
  a switch in Settings (usually *Special app access*). The app deep-links
  to the exact page; it cannot flip the switch itself.
- **Grant path C — one-time ADB (`pm grant`):** no UI path exists at all.
  Only `adb shell pm grant <pkg> <permission>` from a computer (or wireless
  debugging) can grant it. See §3.3 for the persistence reasoning and §9
  for the exact steps.
- **Grant path D — user consent dialog:** a system dialog shown by the API
  itself (VPN `prepare()`, device-admin enable). Not a runtime permission.
- **Normal permission:** granted automatically at install, no prompt.

`KILL_BACKGROUND_PROCESSES` is a **normal** permission — declared in the
manifest, granted at install, no prompt, no ADB. (It is often mislabelled
as ADB-only; it is not.)

### 3.2 The matrix

| # | Permission / access | Feature that needs it | Grant path | If not granted (v1.2 fallback) |
|---|---|---|---|---|
| P01 | `RECEIVE_SMS`, `SEND_SMS` | Blast trigger/stop + GPS reply (v2) | A — runtime | Blast core dead. No fallback. (v2 behaviour) |
| P02 | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | GPS reply SMS (v2); bridge `location` field | A — runtime | GPS reply omitted; bridge reports `location: null`. |
| P03 | `POST_NOTIFICATIONS` | Guard + companion foreground notifications (v2) | A — runtime | Android 13+ may hide notifications; services still run. |
| P04 | `CAMERA` | Strobe (v2) | A — runtime | Blast without strobe. (v2 behaviour) |
| P05 | `RECORD_AUDIO` | Voice trigger (v2) | A — runtime | Voice trigger stays off. (v2 behaviour) |
| P06 | `READ_PHONE_STATE` | SIM watch (v2); per-SIM signal (v1.2) | A — runtime | SIM watch + per-SIM signal off; single-SIM fallback via `TelephonyManager` without subscription id is also gated — report `unknown`. |
| P07 | `ACCESS_NOTIFICATION_POLICY` | DND force-off in blast (v2); DND schedule enforcement (v1.2) | B — Settings › DND access | DND module inert; shows "grant DND access" card. (Already declared in v2.) |
| P08 | `SYSTEM_ALERT_WINDOW` | Shade block overlay (v2) | B — Settings › Display over other apps | Shade block off. (v2 behaviour) |
| P09 | Device admin (`BIND_DEVICE_ADMIN`) | `lockNow()` on blast (v2) | D — device-admin enable screen | No lock on blast; lockout still shows when screen turns on. (v2 behaviour) |
| P10 | `BIND_NOTIFICATION_LISTENER_SERVICE` (`NotificationListenerService`) | DND change detection + culprit hints (v1.2) | B — Settings › Notification access | DND changes detected only by polling `NotificationManager` state (up to 60 s late); culprit = best-guess foreground app, labelled as inference. |
| P11 | `PACKAGE_USAGE_STATS` (Usage Access) | RAM watch: per-app usage + foreground-app-at-the-time (culprit hints, rogue verdicts) | B — Settings › Usage access | RAM watch falls back to `ActivityManager` running processes only (no historical usage, no foreground-at-time); rogue verdicts lose one signal and say so. |
| P12 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Battery exemption for both services (v2 setup step) | B — Settings › Battery › No restrictions (or intent) | HyperOS may kill the guard/companion; user sees a warning card. (v2 behaviour) |
| P13 | `KILL_BACKGROUND_PROCESSES` | RAM watch kill (v1.2) | Normal — install-time | N/A — always granted if declared. Note: it only kills *background* processes; see §5.1 limits. |
| P14 | `BIND_VPN_SERVICE` + `VpnService.prepare()` | Local DNS pipe (v1.2) | D — system VPN consent dialog (first start) | DNS pipe cannot start; shows "start DNS pipe" card. |
| P15 | `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, `ACCESS_WIFI_STATE` | NetWatch (signal/transport reporting) | Normal — install-time | N/A. |
| P16 | `INTERNET` | DNS pipe upstream queries (DoH/UDP to 1.1.1.1); bridge server is local-only but the app still needs `INTERNET` for its own sockets | Normal — install-time | DNS pipe cannot forward; bridge server itself is local sockets and still works, but declare `INTERNET` regardless (Android requires it for `ServerSocket` use by apps targeting API 34 — verify in build; if the build proves otherwise, drop it and note why). |
| P17 | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` (+ new `FOREGROUND_SERVICE_DATA_SYNC`? — see note) | Both foreground services | Normal — install-time | N/A. **Note for code agents:** `CompanionService` should reuse `specialUse` like `BlastService` (same declared type) unless the build proves a second type is needed; do **not** invent a new foreground-service type without checking `FOREGROUND_SERVICE_*` on API 34/36. |
| P18 | `DUMP` | Deep diagnostics: `dumpsys`-class detail for RAM (see §3.4) | **C — ADB only** | Core RAM watch works fully without it (§5.1). DUMP unlocks only the *extra* diagnostics tier; the UI must label which rows need it. |
| P19 | `WRITE_SECURE_SETTINGS` | Airplane-mode flick + network-mode lock (v1.2) | **C — ADB only** | Both degrade to guided manual steps: deep-link to the exact Settings page + a countdown + a "done" check (§5.3). |
| P20 | `READ_LOGS` | DND culprit detection from logcat (who changed DND, §5.2) | **C — ADB only** | Culprit = foreground-app inference (P10/P11) or "unknown", labelled as such. **Must-verify on device** — see §3.4. |
| P21 | `READ_EXTERNAL_STORAGE` / media permissions | *None in v1.2* | — | Not requested. Do not add. |
| P22 | `BIND_ACCESSIBILITY_SERVICE` (accessibility) | *None in v1.2* | — | **Deliberately not used.** Accessibility is the most abused permission on Android; v1.2 needs nothing from it. Do not add an accessibility service to work around a missing grant. |

### 3.3 ADB grants — persistence, verified reasoning

**Claim:** `pm grant` permissions survive reboots and app updates, and are
lost on uninstall.

**Why this is believed (not just asserted):** the grant state is recorded by
the package manager in the system's per-package permission state (the same
store that holds runtime grants), keyed by package name — not in the app's
own data and not in a volatile session. Three independent sources agree:
(1) the Stride runbook (a production app using exactly this pattern for
`WRITE_SECURE_SETTINGS`) states the grant "survives reinstalls as long as
the app keeps declaring it" and tells the user to confirm with
`dumpsys package <pkg> | grep WRITE_SECURE_SETTINGS` expecting
`granted=true`; (2) the ATOTO field guide states grants "survive reboots
and updates and are lost only on uninstall"; (3) the long-standing Naptime
precedent (`pm grant … DUMP` + `WRITE_SECURE_SETTINGS`) has worked this way
across Android versions. A reboot does not touch the package-manager store;
an update keeps the package name and signature, so the stored grant is
re-applied; an uninstall removes the package record entirely, so the grant
goes with it.

**Honest caveats the design carries (verify on the Redmi 15C in §7):**

- A **factory reset** wipes everything, grants included — re-run §9.
- A **different signing key** (e.g. switching from a debug APK to a
  release APK signed differently) is treated as a different app identity;
  re-run §9 after any such switch. Same-key updates are fine.
- A **major OS update** (e.g. HyperOS/Android major version) *should*
  preserve grants, but OEM migrations have historically reset development
  permissions — the app must detect a lost grant at startup and show the
  "re-run ADB setup" card rather than silently degrading.
- `pm grant` only works for permissions the app **declares** in its
  manifest. If v1.2 forgets to declare `DUMP` / `WRITE_SECURE_SETTINGS` /
  `READ_LOGS`, the grant command fails with a clear error — the setup
  screen must surface that, not a generic failure.
- `pm grant` grants are **not** revoked by `pm clear` (clearing app data)
  or by toggling the app's runtime permissions — but the code must still
  re-check with `checkSelfPermission()` on every start, because the user
  can revoke via `pm revoke` and the system can revoke on its own.

**Detection:** each ADB-gated feature checks its grant at service start
and on entering its screen, via `Context.checkSelfPermission()`, and
records the result in `EventLog` (`ADB_GRANT_MISSING` / `ADB_GRANT_OK`).
The Setup screen shows a live grant-status row per permission with the
exact re-run command.

### 3.4 What each ADB grant actually unlocks (and the traps)

- **`WRITE_SECURE_SETTINGS` — the valuable one.** Lets the app write
  `Settings.Global` / `Settings.Secure` directly: `airplane_mode_on` +
  the `AIRPLANE_MODE_CHANGED` broadcast (the flick), and
  `preferred_network_mode` / `preferred_network_mode1/2` per subscription
  (the network-mode lock). This is the only grant that turns two features
  from "guided manual" into "one tap". Trap: on some OEM builds the
  telephony layer ignores `preferred_network_mode` writes from third-party
  apps even with the grant, or HyperOS resets it on SIM change — the
  feature must **verify after writing** (read back the setting + read the
  live `SignalStrength` / network type 30 s later) and report
  `write_ok_but_not_applied` rather than claiming success.
- **`DUMP` — the narrow one.** `DUMP` does **not** let an app run
  `dumpsys` as a shell command, and it does not magically hand over
  `adb shell dumpsys meminfo` output. What it does: it authorises the app
  to call `dump()` on system services through Binder where the framework
  checks `DUMP` — which, for a third-party app without hidden APIs, is
  almost nothing reachable in practice. **Honest design consequence:**
  the core RAM watch (§5.1) is built entirely on public APIs that need no
  grant (`ActivityManager.getMemoryInfo`, `getProcessMemoryInfo`,
  `UsageStatsManager`). `DUMP` is specified in v1.2 only as an
  *experimental* tier: the code attempts the documented
  `ActivityManager`-adjacent dump paths and records what actually works on
  the Redmi 15C; whatever does not work is **removed from the UI**, not
  left as a dead button. If on-device testing shows `DUMP` unlocks nothing
  usable, the v1.2 release notes say so and the grant is dropped from the
  recommended setup (the code stays, gated behind the grant check, so a
  future Android version can light it up). **Do not ship a "DUMP
  diagnostics" screen that shows the same numbers as the no-grant screen.**
- **`READ_LOGS` — the uncertain one.** Historically `pm grant … READ_LOGS`
  let an app read logcat, which is how DND-culprit detection would find the
  package that changed the interruption filter. But Android has tightened
  log access repeatedly (per-app log isolation since 4.1, further
  restrictions in later versions), and on Android 16 a third-party app may
  see only its own logs even with the grant. **This is the single most
  uncertain permission in the design.** The DND module is therefore built
  so it works *without* it (foreground-app inference, §5.2), and the
  `READ_LOGS` path is a progressive enhancement that must prove itself on
  the Redmi 15C in §7 testing. If it proves useless, it is documented as
  such and the setup steps drop it. The setup screen must never present
  `READ_LOGS` as required.

### 3.5 What works with NO ADB at all

Everything except the three ADB-gated enhancements: blast core (full),
RAM watch core (scan, name, verdicts, kill), DND schedule + re-apply +
inference-based culprit, NetWatch (signal per SIM, history), DNS pipe
(full — it needs only the VPN consent dialog), bridge (full, needs only
Tailscale), premium UI (full). **A user who never touches ADB gets a
complete v1.2 except one-tap airplane flick and one-tap network-mode lock.**
That sentence must appear verbatim in the app's Setup screen and in the
release notes.

---

## 4. Architecture

### 4.1 Services and lifetimes

| Service | Type | When it runs | Death / restart |
|---|---|---|---|
| `BlastService` (v2) | Foreground, `specialUse` | Armed (guard) / blasting | `START_STICKY`-equivalent v2 behaviour; `BootReceiver` restarts when armed. **Unchanged.** |
| `CompanionService` (new) | Foreground, `specialUse` | Any companion module enabled (RAM/DND/Net/Bridge); stops itself when all are off *and* no blast is active | `START_STICKY`; `BootReceiver` restarts if any module enabled. Never started during a blast; if a blast starts while it runs, it suspends (§1.3). |
| `DnsVpnService` (new) | `VpnService` (foreground while tunnel up) | Only while the DNS pipe toggle is on | System restarts via `VpnService` lifecycle; on kill, tunnel drops and traffic returns to normal (fail-open by design — a dead filter must never black-hole the phone). |
| `DndListener` (new) | `NotificationListenerService` | While DND module enabled (user granted notification access) | System-managed; re-bind on `onListenerDisconnected` with a back-off, max 3 attempts, then log + stop trying until next app launch. |

Memory budget (4 GB phone, hard ceiling): `CompanionService` + all its
timers must stay under **~60 MB PSS** when idle (measured in §7); the DNS
pipe under **~40 MB** including blocklists; `BridgeServer` under **~10 MB**.
If a module exceeds its budget, it logs `MEMORY_BUDGET_EXCEEDED` and sheds
load (longer poll intervals, smaller caches) rather than growing.

Battery: RAM scan every 60 s (not continuous), DND re-check every 60 s,
NetWatch poll every 30 s, bridge server is passive (no polling of its own).
No module holds a permanent wake lock; scans use a short wake lock only
while reading, then release. Voice trigger (v2) remains the only
continuous listener, and it stays OFF by default.

### 4.2 Shared helpers (pure Kotlin, JVM-testable)

These are the "no Android classes" core, following the `Siren` precedent
(and the `SimPolicy` precedent — pure decision logic, tested on JVM):

| Helper | Purpose | Android-free? |
|---|---|---|
| `MiniJson` | Minimal JSON writer/parser for bridge payloads + `EventLog` lines. Writer: strings, numbers, booleans, null, objects, arrays, escaping. Parser: flat objects + arrays, enough for command bodies. | Yes |
| `BlastGate` | §1.3 — the blast-priority check + skip logging. | Yes (takes a `() -> Boolean` blasting supplier; Android wrapper reads `Prefs`). |
| `DndSchedule` | Schedule model: list of (days, start, end, mode) rules; `modeAt(time)`; overnight wrap (22:00–07:00); next-transition calculation. | Yes |
| `RamVerdict` | Rogue-app classifier: inputs (PSS, PSS trend, CPU time, foreground?, system?, user-kill history) → `OK / WATCH / ROGUE` + reason string. Thresholds are constants, tuned in §5.1. | Yes |
| `DnsParser` | DNS wire format: parse query (name, type, class), build response (A/AAAA), build NXDOMAIN / 0.0.0.0 sinkhole answers, encode/decode names with compression pointers. | Yes |
| `Blocklist` | Domain matching: exact + suffix (`*.tracker.example` matches `a.tracker.example`), case-insensitive, backed by a sorted set + a small in-memory trie/hash. Ship-list + user entries. | Yes |
| `BridgeCommands` | Command-name validation, arg validation, denylist (never kill self/system UI/phone app), `blast.start` rejection. | Yes |
| `SignalGrade` | Signal → grade (`NONE/POOR/FAIR/GOOD/EXCELLENT`) per transport, with per-SIM history smoothing (last-3 median) so one bad sample never triggers a flick. | Yes |

### 4.3 Data flow per module (summary; detail in §5)

- **RAM:** `ActivityManager` + `UsageStatsManager` → `RamSnapshot`
  (pure data class) → `RamVerdict` per app → UI list + `EventLog` +
  bridge `GET /v1/ram`. Kill path: UI/bridge → `BlastGate.check()` →
  `ActivityManager.killBackgroundProcesses(pkg)` → verify by re-scan
  after 5 s → log outcome.
- **DND:** `DndSchedule.modeAt(now)` + `NotificationManager` state →
  `DndGuard` applies when drift detected → `DndListener` /
  `READ_LOGS` (if granted) / foreground inference → culprit →
  `EventLog` + bridge `GET /v1/dnd`.
- **Net:** `TelephonyManager` per subscription + `ConnectivityManager`
  → `NetSnapshot` → `SignalGrade` → history ring → UI + bridge.
  Flick/mode-lock: UI/bridge → `BlastGate.check()` → grant check →
  `Settings.Global` write (ADB) or guided-manual intent (no ADB).
- **DNS:** `VpnService.Builder` (DNS-only routes) → tun fd →
  `DnsPipe` packet loop → `DnsParser` + `Blocklist` → forward or sinkhole
  → per-domain counters (in-memory, daily reset) → UI stats + bridge.
- **Bridge:** `ServerSocket` → `BridgeServer` → auth (`BridgeAuth`) →
  route → module read/command → `MiniJson` response → `EventLog`.

### 4.4 Threading rules

- Never block the main thread: all scans, DNS loop, and HTTP handling run
  on dedicated background threads (`HandlerThread` or plain threads —
  no coroutines library exists in this codebase; do not add one).
- One thread per long-lived loop (RAM scan timer, DND timer, NetWatch
  timer, DNS loop, bridge accept loop). Each loop catches all exceptions,
  logs, and continues — a crashing scan must never kill the service.
- All `Prefs` reads are cheap `SharedPreferences` reads; no I/O on the
  main thread. `EventLog` writes go through a single-threaded writer
  (synchronised, append-only) so concurrent modules cannot interleave lines.

### 4.5 What does NOT change in v2 code

- `Siren.kt`, `VoicePhrase.kt`, `VoiceTrigger.kt`, `SimPolicy.kt`,
  `SimWatcher.kt`, `SmsReceiver.kt`, `BlastAdminReceiver.kt`: **no changes**.
- `BlastService.kt`: **no behavioural change.** Only the two additions in
  §4.8 (broadcasts + `EventLog` appends), owned by Agent A (§4.7).
- `LockoutActivity.kt`: **no changes at all.**
- `BootReceiver.kt`: one addition — start `CompanionService` when any
  companion module is enabled (owned by Agent A).
- `Prefs.kt`: additive getters/setters only, no changes to existing keys
  or defaults (owned by Agent A).
- `AndroidManifest.xml`: additive entries only (owned by Agent C, §4.7).

### 4.6 Version / build

- `app/build.gradle.kts`: `versionCode 3`, `versionName "1.2"`. Nothing
  else changes (same `compileSdk 34`, `minSdk 26`, `targetSdk 34`,
  same plugins, still zero dependencies). **Raising `targetSdk`/`compileSdk`
  to 36 is explicitly out of v1.2** — it is open decision D-05 (§8); the
  app already runs on Android 16 at target 34, and a target bump is a
  separate risk (foreground-service types, photo/media permissions,
  exact-alarm rules) that must not ride along with a feature release.
- `gradle.properties`: unchanged (`android.useAndroidX=false` stays).

### 4.7 File plan — three parallel agents without collisions

**Inventory of the baseline (verified):** 13 `.kt` files + `AndroidManifest.xml`
+ `app/build.gradle.kts` + `strings.xml` + `ic_siren.xml` + launcher icons
+ `device_admin.xml`.

**New files: 27.** **Touched existing files: 9.** Total files in scope: 36.

#### New files (26)

| # | Path (package `com.splendor.blastbuster`, subpackages as shown) | Purpose | Owner |
|---|---|---|---|
| N01 | `companion/CompanionService.kt` | Foreground host for RAM/DND/Net/Bridge; timers, blast suspend/resume, lifecycle. | A |
| N02 | `companion/BlastGate.kt` | Blast-priority check (§1.3). Pure logic + Android wrapper. | A |
| N03 | `companion/EventLog.kt` | Append-only JSONL ring log (§2.6); writer + reader + cursor. | A |
| N04 | `companion/MiniJson.kt` | Minimal JSON writer/parser (§4.2). Pure Kotlin. | C |
| N05 | `ram/RamWatch.kt` | Scanner: `ActivityManager` + `UsageStatsManager` → `RamSnapshot`; 60 s timer. | A |
| N06 | `ram/RamVerdict.kt` | Rogue classifier (§4.2). Pure Kotlin. | A |
| N07 | `ram/RamSnapshot.kt` | Pure data classes: snapshot, per-app entry, verdict. | A |
| N08 | `ram/RamKiller.kt` | Kill path + 5 s verify + denylist (never self/system). | A |
| N09 | `ram/RamActivity.kt` | RAM screen: summary cards, top-apps list, kill buttons, history. | A |
| N10 | `dnd/DndSchedule.kt` | Schedule model + `modeAt()` + overnight wrap (§4.2). Pure Kotlin. | A |
| N11 | `dnd/DndGuard.kt` | Enforcement loop: drift detect → re-apply → log; 60 s timer. | A |
| N12 | `dnd/DndListener.kt` | `NotificationListenerService`: DND change observation + culprit capture. | A |
| N13 | `dnd/DndCulprit.kt` | Culprit resolution: logcat path (READ_LOGS) vs foreground inference vs unknown; labels honesty. | A |
| N14 | `dnd/DndActivity.kt` | DND screen: schedule editor, state, change history with culprit. | A |
| N15 | `net/NetWatch.kt` | Per-SIM signal polling (30 s), history ring, drop detection. | B |
| N16 | `net/SignalGrade.kt` | Signal → grade + smoothing (§4.2). Pure Kotlin. | B |
| N17 | `net/NetTools.kt` | Airplane flick + network-mode lock: ADB write path + guided-manual path + verify-after-write. | B |
| N18 | `net/NetActivity.kt` | Network screen: per-SIM cards, flick button, mode lock, history graph (text-based, no chart lib). | B |
| N19 | `dns/DnsVpnService.kt` | `VpnService`: builder (DNS-only routes), tunnel lifecycle, fail-open on death. | B |
| N20 | `dns/DnsPipe.kt` | Tun packet loop: read UDP/53, parse, match, forward/sinkhole, reply. | B |
| N21 | `dns/DnsParser.kt` | DNS wire format (§4.2). Pure Kotlin. | B |
| N22 | `dns/Blocklist.kt` | Domain matcher + bundled list loader + user entries (§4.2). Pure Kotlin. | B |
| N23 | `dns/DnsActivity.kt` | DNS screen: on/off, upstream choice, blocked-count stats, top blocked domains, user entries editor. | B |
| N24 | `bridge/BridgeServer.kt` | Hand-rolled HTTP server (§2.3): accept loop, routing, auth, JSON responses. | C |
| N25 | `bridge/BridgeAuth.kt` | Token generate/store/verify + failed-auth rate limit. | C |
| N26 | `bridge/BridgeCommands.kt` | Command validation + dispatch + denylist (§2.3). Pure logic where possible. | C |
| N27 | `bridge/BridgeActivity.kt` | Bridge screen: enable, status, tailnet address, port, token show/regenerate/copy, poll hint, event tail. | C |

(Count check: N01–N27 is **27 new files**, not 26 — the table above is
authoritative: **27 new files**. The "26" in the paragraph above is a
counting error in this draft and is corrected here; code agents work from
the table.)

#### Touched existing files (9) — exactly what changes, and who owns each

| File | Change (exact) | Owner — **only this agent edits it** |
|---|---|---|
| `AndroidManifest.xml` | Add: `KILL_BACKGROUND_PROCESSES`, `PACKAGE_USAGE_STATS`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `INTERNET`, `DUMP`, `WRITE_SECURE_SETTINGS`, `READ_LOGS` (all three ADB ones declared so `pm grant` can work), `BIND_VPN_SERVICE` is **not** a `<uses-permission>` (it is the service's `android:permission` attribute — do not declare it as one); add `<service>` entries for `CompanionService`, `DnsVpnService` (with `android:permission="android.permission.BIND_VPN_SERVICE"`, intent-filter `android.net.VpnService`), and `DndListener` (with `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`, intent-filter `android.service.notification.NotificationListenerService`); add `<activity>` entries for `RamActivity`, `DndActivity`, `NetActivity`, `DnsActivity`, `BridgeActivity` (all `exported=false`); change `android:theme` on the `<application>` to the new dark theme (§6) — theme change only, no other application-attribute change. | **C** |
| `app/build.gradle.kts` | `versionCode 2 → 3`, `versionName "1.1" → "1.2"`. Nothing else. | **C** |
| `Prefs.kt` | **Additive only:** getters/setters for RAM (enabled, kill-confirm, thresholds), DND (enabled, schedule JSON, re-apply on/off), Net (flick cooldown, preferred mode per SIM), DNS (enabled, upstream, user entries, stats-opt-out), Bridge (enabled, token, port display). No existing key, default, or function changes. | **A** |
| `MainActivity.kt` | Rebuild as the hub (§6.1): keep the v2 blast section (status, arm, codes, trusted numbers, voice, permissions checklist, setup checklist) as the first tab/section, unchanged in behaviour; add navigation (buttons or tabs — no AndroidX, so plain `Button` row or a `TabHost`-free manual switcher) to the five new screens; add the grant-status rows (§3.3) and the "no ADB needed" note (§3.5). The `isBlasting → LockoutActivity` redirect at the top of `onCreate` is **untouched**. | **C** |
| `BlastService.kt` | **Two additions only, no behaviour change:** (1) broadcast `BLAST_STARTED` / `BLAST_STOPPED` (explicit intents, action strings in `BlastGate` companion) at the exact points `setBlasting(true/false)` is already called; (2) append `EventLog` entries on blast start/stop (module `blast`, no GPS, no sender number — sender is never logged). No change to siren, strobe, TTS, lock, SIM, or notification code. | **A** |
| `BootReceiver.kt` | Add: after the existing armed-guard start, start `CompanionService` if any companion module is enabled in `Prefs`. Nothing else. | **A** |
| `res/values/strings.xml` | Add all new UI strings (module names, honest-limit copy, grant rows, bridge copy). No existing string changes. | **C** |
| `res/values/themes.xml` | **New file** (counts as touched-res, not a new Kotlin file): dark premium theme (§6.2) — `Theme.BlastBuster.Dark` parented on `@android:style/Theme.Material` (no AndroidX), colour attributes, button/card styles. `AndroidManifest` points at it. | **C** |
| `res/drawable/` (new drawables) | **New files:** `card_bg.xml` (rounded dark card), `btn_primary.xml`, `btn_danger.xml`, `btn_ghost.xml` (ripple + rounded), `chip_bg.xml`, `divider.xml` if needed. Pure XML shapes/ripples, no images. | **C** |

**Files nobody touches in v1.2:** `Siren.kt`, `VoicePhrase.kt`,
`VoiceTrigger.kt`, `SimPolicy.kt`, `SimWatcher.kt`, `SmsReceiver.kt`,
`BlastAdminReceiver.kt`, `LockoutActivity.kt`, `ic_siren.xml`, launcher
icons, `device_admin.xml`, `gradle.properties`, `settings.gradle.kts`,
top-level `build.gradle.kts`, `README.md` (updated by the design lead at
release, not by code agents).

#### Parallel-work rules (so the three copies merge cleanly)

- **Agent A — RAM + DND + shared spine:** owns N01–N14, `Prefs.kt`,
  `BlastService.kt` (2 additions), `BootReceiver.kt`. Delivers
  `CompanionService` with RAM/DND wired and Net/Bridge/DNS as **no-op
  stubs that compile** (empty `start()/stop()` behind the same interfaces
  the other agents implement — interfaces defined in §4.8).
- **Agent B — Network + DNS:** owns N15–N23. Works against the stub
  interfaces from §4.8; never edits `CompanionService.kt` — instead
  delivers `NetModule.kt` + `DnsModule.kt` wiring notes (or, if the merge
  plan changes, a patch hunk for `CompanionService` clearly marked
  `// AGENT B — insert here` for Agent A to apply at merge).
- **Agent C — Bridge + UI + manifest/build:** owns N04, N24–N27,
  `AndroidManifest.xml`, `app/build.gradle.kts`, `MainActivity.kt`,
  `strings.xml`, `themes.xml`, drawables. Delivers `BridgeModule` wiring
  the same way as B.
- **Merge order:** A lands first (spine + interfaces), then B, then C —
  or the design lead merges the three copies by hand using the ownership
  table. **No agent edits a file owned by another agent, for any reason,
  including "a one-line fix".** Cross-agent needs go in the delivery
  notes as exact file/line requests.
- **Precondition P0 (every agent, before any edit):** verify remote HEAD
  is `ec45dcfb` on `main` and the working tree is byte-identical to the
  sandbox baseline (13 Kotlin files, versionCode 2). If not, stop and
  report.

#### 4.8 Module interfaces (the contract the three agents share)

```kotlin
// Every module implements this; CompanionService holds one of each.
interface CompanionModule {
    fun start(ctx: Context)   // idempotent; no-op if disabled in Prefs
    fun stop()                // idempotent; releases timers/listeners
    fun isRunning(): Boolean
    fun statusJson(): String  // MiniJson fragment for GET /v1/status
}
// BlastGate companion holds the broadcast action strings:
//   ACTION_BLAST_STARTED = "com.splendor.blastbuster.BLAST_STARTED"
//   ACTION_BLAST_STOPPED = "com.splendor.blastbuster.BLAST_STOPPED"
// CompanionService registers a BroadcastReceiver for both; DnsVpnService
// registers the same two actions independently (it must not depend on
// CompanionService being alive to hear them).
```

---

## 5. Module designs

### 5.1 RAM watch

**What it does.** Every 60 s (and on demand from the RAM screen or
`ram.snapshot`): reads `ActivityManager.getMemoryInfo()` (total/avail/
threshold/low-memory flag), `getRunningAppProcesses()` + 
`getProcessMemoryInfo(pids)` for PSS per process, and — if Usage Access
is granted — `UsageStatsManager` for foreground time / last-used. It names
the top 10 apps by PSS, computes a per-app verdict, and stores the
snapshot (in-memory ring, last 24 snapshots; only summaries go to
`EventLog`).

**Rogue verdict (`RamVerdict`, pure + tested).** Inputs per app: PSS,
PSS trend over the last 3 snapshots, CPU time delta (from
`/proc/<pid>/stat` is **not** available to third-party apps — use
`UsageStats` foreground time + PSS growth instead; do not claim CPU
numbers the API cannot give), whether it is currently foreground,
whether it is a system app (`ApplicationInfo.FLAG_SYSTEM`), whether the
user has killed it before (kill history in `Prefs`). Output:
- `ROGUE` — PSS > 400 MB *and* growing *and* background *and* not a
  system app. (Thresholds are constants at the top of `RamVerdict.kt`,
  tuned after on-device testing; 400 MB is the starting value for a
  4 GB phone, not a law of nature.)
- `WATCH` — PSS > 250 MB background, or fast growth, or repeated
  re-appearance after a kill.
- `OK` — everything else. System apps and the foreground app are never
  `ROGUE` (they are labelled, not killed).

**Kill.** `ActivityManager.killBackgroundProcesses(packageName)` — a
normal permission, no ADB. Rules, all enforced in `RamKiller.kt`:
- One package per tap/call; **no "kill all" button** (a bulk kill on
  HyperOS causes a restart storm and can kill the guard's own helpers).
- Never kill: Blast Buster itself, `com.android.systemui`, the default
  dialer/SMS app, the launcher, or any package on the user's protected
  list (editable in the RAM screen). Denylist is a constant + `Prefs`.
- Confirm dialog on first kill per package per day ("Kill X? It may
  restart itself — this frees RAM now, it does not fix the app").
- Verify: re-scan after 5 s; report `killed` (PSS gone / process gone),
  `restarted` (package came back — common on HyperOS, reported honestly),
  or `failed`. Log the outcome.
- Cooldown: same package cannot be auto-killed more than once per hour;
  there is **no fully-automatic kill in v1.2** — every kill is a user tap
  or an explicit Muse command (`ram.kill`), never a background sweep.
  (Automatic killing is open decision D-06, §8 — it is a foot-gun until
  the verdicts prove themselves.)

**ADB `meminfo` equivalence (honest).** The in-app numbers *are* the
equivalent of `adb shell dumpsys meminfo` for the questions a user asks
("how much RAM is free, who is eating it"): `getProcessMemoryInfo`
returns the same PSS the `meminfo` command prints, per process.
What the app cannot reproduce in-app: the system-wide `dumpsys meminfo`
roll-up (total PSS by category, native heap by process, binder counts)
and `dumpsys cpuinfo`. Those stay ADB-from-a-computer diagnostics; the
RAM screen says so in one line ("For full system meminfo, run
`adb shell dumpsys meminfo` from your computer — the numbers here are the
same per-app PSS"). `DUMP` (§3.4) is the experimental tier for closing
that gap; it must prove itself or be dropped.

**Honest limits (in-app copy, next to the feature):** "This phone has
4 GB of RAM. No app can add more. Killing a background app frees RAM
now; badly behaved apps will restart and eat it again. If RAM is always
full, the fix is fewer heavy apps, not a stronger killer."

### 5.2 DND enforcement

**What it does.** The user defines a schedule in the DND screen — e.g.
"22:00–07:00 every day: DND on (alarms only); 07:00–22:00: DND off" —
stored as JSON in `Prefs` via `DndSchedule`. `DndGuard` (60 s timer +
check on every `DndListener` callback) compares the schedule's expected
mode with `NotificationManager.currentInterruptionFilter`. On drift:
- If the schedule says DND should be ON and it is OFF → re-apply
  (`setInterruptionFilter(INTERRUPTION_FILTER_ALARMS)` — the v2 "alarms
  only" choice, kept consistent) and log `DND_REAPPLIED` with the
  culprit if known.
- If the schedule says OFF and it is ON → turn it off the same way and
  log it. (A schedule that says "off" is a real user choice — e.g. they
  want DND off during the day even if some app turned it on.)
- Cooldown: max one re-apply per 5 minutes per direction, so a fighting
  app and the guard cannot loop forever. After 3 re-applies in an hour,
  the guard stops re-applying, logs `DND_FIGHT_DETECTED`, and shows a
  card naming the suspected app — the user decides, the app does not
  escalate a war with another app.

**Culprit detection (three tiers, honestly labelled).**
1. **Logcat tier (needs `READ_LOGS`):** `DndCulprit` reads logcat for
   `ZenModeHelper` / `NotificationManagerService` lines naming the
   package that called `setInterruptionFilter`. Label: "seen in system
   log". Must-verify on Android 16 (§7) — if the grant yields only the
   app's own logs, this tier reports nothing and says so.
2. **Listener tier (needs notification access, P10):** `DndListener`
   records the foreground package (via `UsageStatsManager`, P11) at the
   moment a DND change is observed, plus any notification posted in the
   same second. Label: "app in front when DND changed" — an inference,
   not proof.
3. **Polling tier (no grants):** the guard's 60 s poll records the
   foreground app at detection time. Label: "app in front when noticed
   (up to 60 s after the change)".

The history screen shows the tier label on every row. **A row never
claims a culprit it did not observe.**

**Blast interaction (critical, do not get wrong).** During a blast, v2
forces DND **off** (so the siren is not silenced). `DndGuard` must
**never** re-apply a scheduled DND-ON while blasting — `BlastGate`
blocks it, and the guard logs `DND_SKIPPED_BLAST_ACTIVE`. When the blast
ends, the guard re-evaluates the schedule on the next tick. The DND
screen shows a banner during a blast ("DND control paused — blast
active") — though in practice the user cannot see it, because
`MainActivity` redirects to the lockout screen while blasting (§1.3).

### 5.3 Network tools

**Signal watch (`NetWatch`).** Every 30 s per subscription
(`SubscriptionManager.activeSubscriptionInfoList`, dual SIM Airtel + MTN):
`TelephonyManager.createForSubscriptionId(id)` →
`signalStrength` → `SignalGrade` (smoothed over the last 3 samples).
Records: transport (LTE/NR), dBm, level (0–4), carrier name, data state.
History ring: last 48 samples per SIM (24 minutes) in memory; daily
min/max + drop count persisted as a summary in `Prefs` (not a growing
file). Drop = grade falls two levels within 60 s → `EventLog` entry
`NET_DROP` with SIM + before/after. The UI shows two SIM cards side by
side (SIM 1 / SIM 2 with carrier names from the subscription info —
**never hardcoded to Airtel/MTN**; another user's SIMs show their own
carriers).

**Airplane-mode flick.** Purpose: force the radio to detach and re-attach
to the tower when signal is stuck (a real, known recovery for a phone
that shows bars but passes no data).
- *With `WRITE_SECURE_SETTINGS`:* `Settings.Global.putInt(AIRPLANE_MODE_ON, 1)`
  + broadcast `Intent.ACTION_AIRPLANE_MODE_CHANGED`, wait 8 s,
  put 0 + broadcast, then verify: poll `NetWatch` for 60 s and report
  `flick_ok_signal_restored` / `flick_ok_no_improvement` / `flick_failed`.
  Cooldown 5 minutes; blocked during blast (`BlastGate`); a visible
  countdown runs in the UI so the user knows the phone will be offline
  for ~10 s.
- *Without the grant:* the button opens `Settings.ACTION_AIRPLANE_MODE_SETTINGS`
  with a 3-step illustrated instruction ("turn on, wait 8 seconds, turn
  off") and a "check signal now" button afterwards. Same verify step.
  The button label always states which path will run
  ("Flick (one tap)" vs "Flick (guided — needs ADB grant for one tap)").

**Network-mode lock.** Choice per SIM: `Auto (5G/4G/3G)` / `4G only` /
`5G only` (where the radio supports it) / `3G only`.
- *With the grant:* write `preferred_network_mode` (+ per-subscription
  `preferred_network_mode1/2`) via `Settings.Global`, then verify by
  reading back the setting **and** the live network type after 30 s.
  If the radio ignores the write, report `write_ok_but_not_applied`
  (§3.4 trap) and offer the guided path.
- *Without the grant:* deep-link to the SIM's mobile-network settings
  (`Settings.ACTION_NETWORK_OPERATOR_SETTINGS` / per-SIM settings intent)
  with instructions; the app records the user's stated choice and checks
  the live network type afterwards, but cannot enforce it.

**Honest limits (in-app copy):** "No app can create signal that isn't
there. A flick or a mode lock can make the phone re-attach to a tower
that exists — it cannot invent coverage. If both SIMs show no service,
the problem is the towers, not the phone."

### 5.4 Local DNS pipe (`DnsVpnService` + `DnsPipe`)

**Pattern (proven by NetGuard / RethinkDNS / personalDNSfilter):** a
`VpnService` that is *not* a VPN. It creates a local TUN interface, the
system sends DNS queries into it, the app answers or forwards them
on-device, and no traffic leaves the phone except the forwarded DNS
queries to the chosen upstream. **No external server, no account, no
real VPN tunnel.**

**How it works in v1.2 (DNS-only, the personalDNSfilter shape):**
- `VpnService.Builder`: `addAddress("10.0.0.2", 32)`,
  `addDnsServer("1.1.1.1")` (or the user's chosen upstream),
  `addRoute("1.1.1.1", 32)` + `addRoute` for each configured upstream IP
  — **no `0.0.0.0/0` route.** Only DNS traffic enters the tunnel; all
  other traffic goes direct and is never seen by the app. This is the
  key scoping decision: v1.2 is a DNS filter, not a firewall. A per-app
  firewall (full packet forwarding, TCP/UDP state machine) is a much
  larger build and is **not** v1.2 scope (open decision D-07).
- `DnsPipe` loop: read packets from the TUN fd, parse IPv4/UDP headers
  (hand-rolled, no library), extract the DNS query via `DnsParser`,
  check `Blocklist`: blocked → reply with `0.0.0.0` (A) / `::` (AAAA)
  sinkhole, or NXDOMAIN for the domains the user marks "lie" (default
  sinkhole `0.0.0.0` — simpler and debuggable); allowed → forward the
  query bytes via a plain `DatagramSocket` to the upstream, wait for the
  reply (2 s timeout), write it back into the tunnel.
- Private DNS (Android's *Private DNS* setting) **must be off** for this
  to work — the DNS screen checks and warns, with a deep-link. If Private
  DNS is on, the system bypasses the tunnel's DNS and the pipe silently
  does nothing; the app must detect this state and say so.
- **One VPN slot:** Android allows exactly one `VpnService` at a time.
  If the user starts a real VPN (or Tailscale's own VPN — note:
  Tailscale on the phone *is* a `VpnService`), the DNS pipe is
  disconnected by the system. The app detects this, logs
  `DNS_YIELDED_TO_VPN`, shows a persistent card ("DNS pipe paused —
  another VPN is active"), and offers to restart when the other VPN
  stops. **Design consequence for the bridge:** if Splendor runs
  Tailscale on the phone (needed for the bridge, §2.3), the DNS pipe and
  Tailscale cannot both hold the VPN slot. The bridge still works when
  the DNS pipe is off — but the user must choose which one holds the slot
  at any moment. This conflict is real, must appear in the UI, and is
  open decision D-08 (§8): a future option is to run the DNS pipe
  *inside* the Tailscale slot is impossible — instead, a future version
  could forward DNS through Tailscale's exit/DNS settings. v1.2 does not
  solve it; it reports it.
- **Blocklists:** one bundled list shipped in `res/raw/` — a compact
  subset of StevenBlack's unified hosts (ads + malware + tracking,
  capped at ~5,000 domains so the file stays small and the matcher stays
  fast on 4 GB RAM). No auto-download in v1.2 (no background fetch, no
  update server). User custom entries (block/allow) in `Prefs`, editable
  in the DNS screen. Allow-list beats block-list (user's explicit choice
  wins). Full query logging is **off by default**; the stats screen shows
  counts + top blocked domains only, never a full per-query history.
  (A full query log is a privacy liability on a theft-alarm phone.)
- **Upstreams (user choice, no hardcoding):** Cloudflare `1.1.1.1`,
  Quad9 `9.9.9.9`, Google `8.8.8.8`, or "system default". Default:
  Cloudflare. All plain UDP/53 forwarding in v1.2; DoH/DoT is a later
  option (D-09) — plain DNS to a chosen upstream is the honest v1.2
  scope, and the UI says the queries are unencrypted to the upstream.
- **Fail-open:** any exception in the pipe loop, any upstream timeout
  storm (>10 consecutive failures), or service death → stop the tunnel,
  traffic returns to normal, log `DNS_FAIL_OPEN`. A dead filter must
  never black-hole the phone.
- **"Faster DNS" claim (honest):** the pipe can be faster than the
  carrier's DNS (carrier DNS is often slow) and it blocks junk domains
  before they load — that is the real benefit. It does not make the
  *connection* faster, and the first query for a domain is slower (tunnel
  + forward). The UI copy says exactly that.

### 5.5 Muse bridge — implementation notes (beyond §2)

- `BridgeServer` starts when the bridge is enabled in `Prefs` **and**
  `CompanionService` is running; it stops when disabled. It binds
  lazily: on start, and again whenever the Tailscale address appears or
  changes (listen for `CONNECTIVITY_ACTION`-adjacent network callbacks
  via `ConnectivityManager.NetworkCallback` — the modern API, not the
  deprecated broadcast).
- `GET /v1/status` assembles its JSON from the modules' `statusJson()`
  fragments + `Prefs` + `BlastGate` — it never triggers a scan itself
  (a poll must be cheap; scans run on their own timers).
- Command execution runs on a bridge worker thread, never the accept
  thread, with a 10 s timeout per command; a timed-out command returns
  `{"ok": false, "error": "timeout"}` and is logged.
- The bridge never returns: the token, any code (trigger/stop/voice),
  any trusted number, GPS coordinates more precise than the v2 SMS reply
  already sends (bridge `location` is the same last-known location the
  SMS reply uses — no new location exposure), or the full DNS query log.
- If the bridge is enabled but Tailscale is not installed/running, the
  Bridge screen shows the exact next step ("Install Tailscale from the
  Play Store, join your tailnet, then come back") with a Play Store link
  (an `Intent` to the store page — the app does not bundle Tailscale).

---

## 6. UI direction — premium, dark, native-only

### 6.1 Structure

`MainActivity` becomes a **hub** with five sections, switched by a plain
`Button` row (no AndroidX `TabLayout`, no `ViewPager` — a manual switcher
that shows/hides five `LinearLayout` sections in one `ScrollView`, the
same construction technique v2 already uses). Each section is a summary
card that opens its dedicated screen (`RamActivity`, `DndActivity`,
`NetActivity`, `DnsActivity`, `BridgeActivity`) — the dedicated screens
hold the detail, the hub holds the state at a glance.

Hub sections (top to bottom):
1. **Blast** (v2 content, unchanged in behaviour — status, arm toggle,
   codes, trusted numbers, voice, permissions checklist, setup checklist).
   Visually restyled to the new theme; functionally identical.
2. **RAM** — free/total bar, top offender, "Open RAM watch".
3. **Do Not Disturb** — schedule state, last change + culprit, "Open DND".
4. **Network** — SIM 1 / SIM 2 signal grades, "Open network tools".
5. **Muse bridge** — on/off, tailnet status, last poll time, "Open bridge".
6. **Setup & grants** — the §3.3 grant-status rows + the "no ADB needed"
   note + the v2 setup checklist (autostart, battery, recents lock).

### 6.2 Theme

- **Base:** `@android:style/Theme.Material` (platform Material, no
  AndroidX) with `android:windowBackground` dark. New
  `res/values/themes.xml`: `Theme.BlastBuster.Dark`.
- **Palette (exact hex, to be used as `colors.xml` values):**

  | Token | Hex | Use |
  |---|---|---|
  | `bb_bg` | `#0D1117` | Screen background |
  | `bb_surface` | `#161D27` | Cards |
  | `bb_surface2` | `#1E2836` | Pressed / nested surfaces |
  | `bb_text` | `#E8EDF3` | Primary text |
  | `bb_text2` | `#9AA7B8` | Secondary text |
  | `bb_accent` | `#E53935` | Blast red — reserved for blast UI only |
  | `bb_accent2` | `#2F81F7` | Primary actions (blue) |
  | `bb_ok` | `#3FB950` | OK / good signal / granted |
  | `bb_warn` | `#D29922` | Warnings / fair signal |
  | `bb_bad` | `#F85149` | Danger / poor signal / denied (not the blast red) |
  | `bb_divider` | `#2A3442` | Dividers |

  Blast red (`#E53935`) is used **only** for blast-related UI. Companion
  modules use blue/green/amber. This is deliberate: red must always mean
  "theft alarm", never "a RAM warning".
- **Cards:** `GradientDrawable` rounded corners (16 dp radius),
  1 dp stroke `bb_divider`, 4 dp elevation (platform elevation, API 21+),
  16 dp padding, 12 dp gaps. Defined once in `res/drawable/card_bg.xml`
  and reused everywhere.
- **Buttons:** three styles in drawables — `btn_primary.xml` (blue fill,
  white text, ripple), `btn_danger.xml` (blast red fill — blast actions
  only), `btn_ghost.xml` (transparent, blue text, ripple). All 12 dp
  radius, 48 dp minimum height, 14 sp bold text.
- **Type:** platform `sans-serif` (Roboto on the device); section titles
  18 sp bold, card titles 16 sp bold, body 14 sp, captions 12 sp
  `bb_text2`. No custom fonts (no font files to ship).
- **Motion:** no animation library exists in this codebase. Use the free
  platform tools only: `View.animate()` (alpha/translation, 150–250 ms)
  for section switches and card appearance, `LayoutTransition` on the hub
  container for expand/collapse, and `RippleDrawable` for touch feedback.
  No Lottie, no GIFs, no video. Transitions must be instant on a Helio
  G81 — if a transition drops frames on the Redmi 15C, it is removed,
  not tuned.
- **Status bar / navigation:** dark, `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR`
  off; edge-to-edge is **not** attempted (v2 does not do it; keep the
  layout stable).

### 6.3 What changes, screen by screen

| Screen | Change |
|---|---|
| `MainActivity` | Restyled to dark theme + hub switcher (§6.1). Blast section behaviour identical. |
| `RamActivity` (new) | Dark cards, RAM bar (custom `View` drawing a two-colour bar — no chart lib), top-10 list (plain `LinearLayout` rows, not `RecyclerView` — no AndroidX; 10 rows is fine in a `ScrollView`), per-row Kill button, honest-limit footer. |
| `DndActivity` (new) | Schedule editor (day chips as toggle `Button`s, start/end `TimePicker` — platform widget), current-state card, history list with culprit + tier label. |
| `NetActivity` (new) | Two SIM cards with signal grade (text + coloured bar), flick button with countdown, mode-lock radio group (plain `RadioButton`s), history as a text list of the last 10 drops. |
| `DnsActivity` (new) | Toggle, upstream selector, blocked-today counter, top-5 blocked domains, custom entries editor, Private-DNS warning, VPN-slot conflict card. |
| `BridgeActivity` (new) | Enable toggle, tailnet address + port, token show/copy/regenerate, "how Muse reaches this phone" explainer (polling, no push — honest copy), last-poll time, recent bridge events. |
| `LockoutActivity` | **NO CHANGE.** Red, full-screen, fingerprint-only. Not restyled, not "premiumised". The lockout screen is a security surface, not a branding surface. |

### 6.4 Copy rules (applies to every new screen)

- Every feature carries its honest limit in one sentence, directly under
  its title (the sentences in §5 are the approved copy — use them, do
  not rewrite them into marketing).
- No "AI", "boost", "turbo", "pro", "max" language for companion
  features. "Watch", "enforce", "flick", "filter" are the verbs.
- Numbers always carry their source ("PSS from ActivityManager",
  "signal from TelephonyManager") in a caption, so a wrong number can be
  traced.
- Any state that is an *inference* is labelled as one ("inferred",
  "best guess", "up to 60 s old"). Any state that is *unverified* says so.

---

## 7. Test plan

### 7.1 What is JVM-testable (no device, no emulator)

Pure-Kotlin helpers with zero Android imports — the `Siren` precedent.
Each gets a `src/test/` JUnit test class (JUnit 4, the only test framework
that ships with the Gradle plugin setup — **verify the test dependency is
available offline** before promising a count; if `testImplementation
'junit:junit:4.13.2'` cannot be fetched in the sandbox, tests are written
against the same harness the v2 `Siren` tests use, or as plain `main()`
verification scripts — the code agents must confirm which, and record it).

| Class under test | Tests to aim for | What must be covered |
|---|---|---|
| `Siren` (existing) | keep existing | No regression. |
| `MiniJson` | 20 | Round-trip all types, escaping (`"`, `\`, control chars), malformed input rejected, nested objects/arrays, empty input. |
| `BlastGate` | 8 | Blocks writes during blast, allows reads, allows `blast.stop` only, logs skip reason. |
| `DndSchedule` | 18 | Overnight wrap, midnight boundary, day-of-week, overlapping rules (first match wins — documented), next-transition. |
| `RamVerdict` | 15 | Each threshold boundary, system-app never ROGUE, foreground never ROGUE, trend detection, kill-history influence. |
| `DnsParser` | 20 | Parse A/AAAA, compression pointers, malformed packets rejected, build sinkhole + NXDOMAIN, round-trip encode/decode. |
| `Blocklist` | 15 | Exact, suffix, case-insensitive, allow-beats-block, empty list, 5,000-entry load time < 100 ms on JVM. |
| `BridgeCommands` | 12 | Unknown command rejected, `blast.start` rejected, denylist packages rejected, arg validation, missing token rejected. |
| `SignalGrade` | 10 | Grade boundaries per transport, smoothing (one bad sample ignored, three bad samples grade down). |
| `EventLog` (ring logic only — the file I/O half is on-device) | 8 | Cap eviction oldest-first, cursor monotonic, malformed line skipped not crash. |
| **Total new JVM tests to aim for** | **126** | Plus existing `Siren` tests. Target: **126 new, 0 skipped, all passing before any on-device work.** |

### 7.2 What is on-device-only (Redmi 15C, Splendor runs these)

| # | Check | Pass criteria |
|---|---|---|
| D-01 | Install over v1.2 (same key), open app | No crash, blast settings preserved, version shows 1.3 / code 4. |
| D-02 | Blast regression (full v2 test) | Trigger SMS → siren + strobe + lockout; stop SMS → stops; SIM pull → auto-blast; fingerprint unlock works. **Any failure here stops the release.** |
| D-03 | Blast priority | Start blast → attempt RAM kill, DND re-apply, flick, DNS restart, bridge write from Muse → all skipped/logged, blast unaffected, lockout still fingerprint-only. |
| D-04 | RAM watch | Top-10 list matches `adb shell dumpsys meminfo` PSS within ±5% for the same packages; kill a known background app → freed, verified by re-scan; kill a restarting app → reported `restarted`, not `killed`. **v1.3:** "Eliminate now" kills rogue apps only, one at a time, and reports killed/restarted/failed honestly; low-memory banner appears when free RAM is below the system threshold; list rows show last-used time from UsageStats when granted. |
| D-05 | RAM budget | `CompanionService` PSS < 60 MB idle; DNS pipe < 40 MB; bridge < 10 MB (read via `adb shell dumpsys meminfo com.splendor.blastbuster`). |
| D-06 | DND schedule | Set 2-minute test window → DND applies within 60 s; turn DND off manually inside the window → re-applied within 60 s + logged with a culprit label; outside the window → DND off. **v1.3:** the schedule is set from the DND screen UI (one nightly rule) and the guard reads exactly what the UI saved; after 3 re-applies in an hour the guard stops, logs `DND_FIGHT_DETECTED`, and lets the user decide. |
| D-07 | DND culprit tiers | With `READ_LOGS` granted: change DND from another app → check whether the logcat tier names it. **Record the result either way** — this decides whether `READ_LOGS` stays in §9. Without it: inference tier labels correctly. **v1.3 verdict (from the code): the logcat tier often returns nothing on Android 16** — the log may show only this app's own entries — and it only runs when notification access is also granted. Still open on-device: confirm on the Redmi 15C. |
| D-08 | ADB grants persistence | Grant all three (§9) → reboot → `dumpsys package` shows all three `granted=true` → update (reinstall same key) → still granted → uninstall/reinstall → grants gone (expected — re-run §9). |
| D-09 | DUMP tier | With `DUMP` granted, record exactly which extra diagnostics (if any) the app can surface beyond the no-grant RAM screen. If none, the DUMP screen/row is removed and §9 drops the grant. **v1.3 verdict (from the code): DUMP unlocks no per-app PSS list** — no public API yields other apps' pids, so no DUMP path is wired; the RAM screen shows the same numbers with or without it. Still open on-device: confirm `dumpPackageState` surfaces nothing extra, then drop the grant from §9. |
| D-10 | Airplane flick | With grant: one tap → offline ~10 s → signal returns, verified by `NetWatch` + a data check (load a page). Without grant: guided path works end-to-end. **v1.3:** the outcome line is honest either way (`flick_ok_signal_restored` / `flick_ok_no_improvement` / `flick_failed` / `needs_adb`). |
| D-11 | Network-mode lock | With grant: set 4G-only → live network type reads LTE after 30 s; set Auto → returns. Record honestly if HyperOS ignores the write (`write_ok_but_not_applied`). **v1.3:** without the grant the choice is saved and SIM settings open; a write the radio ignores is reported as `write_ok_but_not_applied`, never as success. |
| D-12 | DNS pipe | Enable → `dnsleaktest`-style check shows 1.1.1.1 (or chosen upstream); open a known blocked domain → fails to load, counter increments; open an allowed site → loads; disable → normal DNS returns. Start a real VPN → pipe yields with the conflict card. Private DNS on → warning shown, pipe reports inert. **v1.3:** Private DNS warning is live; the 1.1.1.1 fallback works one-tap with the grant and guided-manual without it, and is refused while another VPN holds the slot. |
| D-13 | Bridge over Tailscale | Phone on tailnet + Muse VM joined → `curl` via tunnel proxy to `GET /v1/status` returns 200 + valid JSON; wrong token → 401; `GET /v1/events?since=…` pages correctly; `POST /v1/command ram.snapshot` returns a snapshot; `POST blast.stop` with wrong code → rejected; with right code during a test blast → stops. Phone off tailnet → polling fails cleanly (no crash, no retry storm — Muse-side back-off). **v1.3:** `GET /v1/log` returns 403 without a fresh in-app grant and 200 once within 5 minutes of tapping "Allow one log export"; a second fetch without a fresh grant is denied. |
| D-14 | Bridge without Tailscale | Bridge on, Tailscale off → server on localhost only, `/status` reports `tailscale: false`, UI shows the install step. **v1.3:** turning Tailscale on afterwards re-binds the server to the tailnet address without restarting the app (D-18). |
| D-15 | Battery / HyperOS survival | 24 h with all modules on: guard + companion still alive, no "app killed" gap in `EventLog`, battery delta attributable to the app < 5% (read from battery stats). |
| D-16 | No personal data hardcoded | Fresh install on a second phone (or emulator): no trigger code, number, token, address, or schedule from Splendor's phone appears; all defaults are the generic ones. |
| D-17 | UI on Redmi 15C | All screens render, no clipped text, no overlap at the device's font scale; section switches animate without visible jank; lockout screen pixel-identical in behaviour to v2. **v1.3:** the hub has a seventh tab (Notifications) alongside RAM/DND/Network/Bridge/Setup; the Setup screen re-checks all three ADB grants on every launch and shows the exact re-run `pm grant` command for any missing grant. |
| D-18 | Bridge re-bind on Tailscale up/down (v1.3) | Bridge on with Tailscale off → server on localhost only. Turn Tailscale on → the server re-binds to the tailnet address without an app restart, and `GET /v1/status` answers on the tailnet address. Turn Tailscale off → back to localhost-only, no crash, no retry storm. A flapping network must not thrash the server (no re-bind when the address is unchanged). |
| D-19 | Bridge `/v1/log` grant/deny (v1.3) | Without an in-app grant: `GET /v1/log` → 403/denied, logged. Tap "Allow one log export" → one fetch within 5 minutes succeeds; a second fetch without a fresh tap is denied; after 5 minutes it is denied again; after an app restart it is denied (the grant is never persisted). |
| D-20 | Notification capture + WhatsApp reply (v1.3) | Turn capture on + grant notification access → a WhatsApp message arrives → it appears in the Notifications screen (newest first); the EventLog entry records app + title only, never the message body. Reply from the app → the reply is delivered via WhatsApp's own reply action. Nothing is auto-replied. Turn capture off → nothing further is captured. |
| D-21 | Elimination mode one-tap (v1.3) | In the RAM screen, tap "Eliminate now" → rogue apps only are killed, one at a time; each result is reported honestly (killed / restarted / failed). During a blast the button refuses and kills nothing. |
| D-22 | Low-memory alert (v1.3) | Fill RAM until free memory drops below the system threshold → the RAM screen shows the LOW MEMORY banner with the free amount; when memory recovers the banner clears. |
| D-23 | RAM-list UsageStats rows (v1.3) | With Usage Access granted, list rows show each app's last-used time from UsageStats; without it, rows show PSS + verdict only and say usage data is unavailable. |
| D-24 | DND schedule from UI (v1.3) | Set a nightly rule in the DND screen and save → the guard enforces exactly that rule (DND on in the window, off outside it); clearing the schedule stops enforcement. |
| D-25 | Private DNS warning + 1.1.1.1 fallback (v1.3) | With Private DNS on → the DNS screen warns the pipe is bypassed. With the ADB grant, tap the fallback → Private DNS is set to `one.one.one.one` and the warning clears. Without the grant, the guided manual steps open instead. While Tailscale (or another VPN) holds the slot, the fallback is refused with the reason stated. |

### 7.3 Release gates

1. All 126 JVM tests pass.
2. D-02 (blast regression) passes in full.
3. D-03 (blast priority) passes in full.
4. D-13 (bridge) passes, or the bridge ships **disabled by default**
   with the failure documented (a bridge that only works sometimes must
   never be presented as working).
5. D-07 + D-09 results are written into the release notes, whichever way
   they go — no silent "maybe it works".

---

## 8. Open decisions left for Splendor

| # | Decision | Options / designer recommendation |
|---|---|---|
| D-01 | Cloud relay for true push — fund a server? | **Recommendation: no in v1.2.** Polling over Tailscale is free and private. A relay would cost money every month, need someone to run it, and put location/security events on a server. Revisit only if the poll delay actually hurts in practice. **Still NO in v1.3 — unchanged.** |
| D-02 | TLS on the bridge? | Tailscale already encrypts the traffic; localhost needs none. **Recommendation: no in v1.2.** A self-signed cert adds trust prompts and failure modes for zero benefit on a tailnet-only server. **Still NO in v1.3 — unchanged.** |
| D-03 | Remote `blast.start` via the bridge? | **Recommendation: do not add.** The SMS trigger already starts a blast remotely. A bridge start command adds a second, more dangerous trigger path (stolen token = screaming phone). Only add if Splendor explicitly asks, with a separate confirmation step. **Still NO in v1.3 — unchanged.** |
| D-04 | Bridge `GET /v1/log` export (download the event log)? | **DONE in v1.3.** Behind an explicit per-export confirm in the app: single-use, 5-minute window, never persisted; the server logs every grant/deny/export. |
| D-05 | Raise `targetSdk`/`compileSdk` to 36? | **Recommendation: separate release, not v1.2.** The app runs on Android 16 at target 34 today. A target bump changes permission/foreground-service behaviour and deserves its own test round. **Still NO in v1.3 — unchanged.** |
| D-06 | Automatic RAM killing (background sweep, no tap)? | **Recommendation: no — ever, without a proven verdict record.** A wrong auto-kill can kill the app Splendor is using. Keep kills manual in v1.2; revisit after a month of `WATCH`/`ROGUE` accuracy data. **Still NO in v1.3 — Elimination mode is one tap by the user, never a background sweep.** |
| D-07 | Full per-app firewall (not just DNS)? | **Recommendation: not in v1.2.** It needs a TCP/UDP forwarding engine — an order of magnitude more code than the DNS-only pipe, and it would fight Tailscale for the VPN slot anyway. **Still NO in v1.3 — unchanged.** |
| D-08 | DNS pipe vs Tailscale VPN-slot conflict | Both need the single VPN slot on the phone. **Recommendation: the UI reports the conflict and lets the user choose; the bridge keeps working either way.** A technical fix (DNS through Tailscale's own DNS settings) is a v1.3 investigation, not a v1.2 promise. **SHIPPED in v1.3: the choice UI is in the DNS screen. Verdict of the investigation: running DNS through Tailscale is not feasible from the app** — Tailscale's resolver does not speak DoT, so Android Private DNS cannot connect while Tailscale's DNS is on, and the app cannot set Tailscale's DNS settings. The user chooses which holds the slot. |
| D-09 | DoH/DoT (encrypted DNS upstream)? | Plain UDP to a chosen upstream in v1.2, stated in the UI. **Recommendation: v1.3** — encrypted upstream is real work (TLS in the pipe, cert handling) and must not delay v1.2. **OUT in v1.3 — next round.** Plain UDP to the chosen upstream is still the v1.3 scope; the Private DNS 1.1.1.1 fallback is the encrypted-DNS option that shipped instead. |
| D-10 | Bridge poll interval (Muse-side cron)? | The app doesn't set it — Muse does. **Recommendation: 15 minutes** as the default cron, plus on-demand polls when Splendor asks. Shorter = fresher but more battery/radio wakeups on the phone; longer = staler. Splendor's call. **Noted for v1.3 — Muse-side, 15 minutes, unchanged.** |
| D-11 | Blocklist source/updates | Bundled ~5,000-domain subset, no auto-update, in v1.2. **Recommendation: keep.** An auto-updating list needs a download source, a signature check, and a failure story — v1.3 at the earliest. **KEPT bundled in v1.3 — unchanged.** |
| D-12 | DND "alarms only" vs "priority only" vs "total silence"? | v2 uses alarms-only for the blast force-off; the schedule re-apply uses the same. If Splendor wants total silence at night, that is a one-line change in `DndGuard` — his call. |

---

## 9. One-time ADB setup (for Splendor — exact steps)

Do this **once**, on a computer, with the phone connected. It is needed
only for the two one-tap features (§3.5: everything else works without it).
`READ_LOGS` is listed third and marked experimental — see §3.4.

**Before you start:** install v1.2, open it once (so the app exists and
has declared the permissions), and enable Developer options → USB
debugging (or Wireless debugging) on the phone. The app must be installed
before the grants — `pm grant` fails on a package that isn't there, and
fails if the permission isn't declared in the manifest.

```sh
# 1. Check the phone is seen (Termux: use `adb` from your computer, or
#    wireless debugging on the phone itself — either way, this must print
#    your device):
adb devices

# 2. Confirm the app is installed:
adb shell pm list packages | grep blastbuster
# expect: package:com.splendor.blastbuster

# 3. Grant the two production ADB permissions:
adb shell pm grant com.splendor.blastbuster android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.splendor.blastbuster android.permission.DUMP

# 4. Grant the experimental one (DND culprit detection — may prove
#    useless on Android 16; the app will tell you in the DND screen):
adb shell pm grant com.splendor.blastbuster android.permission.READ_LOGS

# 5. Verify all three took (each line must say granted=true):
adb shell dumpsys package com.splendor.blastbuster | grep -E "WRITE_SECURE_SETTINGS|DUMP|READ_LOGS"
# expect, per permission:  android.permission.XXX: granted=true

# 6. Reboot the phone, then re-run step 5 — the grants must still say
#    granted=true. If any says granted=false after a reboot, report it;
#    do not assume the feature works.
```

**What survives what (§3.3):** reboot — survives. App update (same
signing key) — survives. Uninstall — **lost**, re-run steps 3–5 after
reinstalling. Factory reset — lost, re-run everything. Major OS update —
usually survives; the app's Setup screen re-checks on every launch and
will show "re-run ADB setup" if a grant is gone.

**To undo (any time):**

```sh
adb shell pm revoke com.splendor.blastbuster android.permission.WRITE_SECURE_SETTINGS
adb shell pm revoke com.splendor.blastbuster android.permission.DUMP
adb shell pm revoke com.splendor.blastbuster android.permission.READ_LOGS
```

Revoking only drops the app to its no-ADB fallbacks (§3.2) — nothing
breaks, nothing crashes.

---

## 10. Delivery checklist for the code agents (summary)

- [ ] P0: verify remote HEAD `ec45dcfb`, byte-identical baseline (§4.7).
- [ ] Build the 27 new files + 9 touched files per the ownership table —
      no agent edits another agent's files.
- [ ] 126 new JVM tests, all passing (§7.1).
- [ ] Blast behaviour byte-identical: `BlastService` diff is the two
      additions in §4.7 only; `LockoutActivity` untouched; `Siren`,
      `SmsReceiver`, `SimWatcher`, `VoiceTrigger` untouched.
- [ ] `versionCode 3`, `versionName "1.2"`, package unchanged, zero new
      dependencies, no AndroidX.
- [ ] Every honest-limit sentence from §5 appears in the app next to its
      feature.
- [ ] No personal data hardcoded anywhere (grep for it before delivery:
      no phone numbers, no `BLAST 1234` beyond the existing generic
      default, no tailnet addresses, no tokens).
- [ ] On-device checks D-01–D-17 run on the Redmi 15C; D-07 and D-09
      results recorded either way.
- [ ] Release notes state: what works with no ADB (§3.5), what the bridge
      cannot do (§2.3), and the D-07/D-09 verdicts.

---

*End of design. Design only — no code was changed to produce this document.*
