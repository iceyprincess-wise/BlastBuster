# Blast Buster

Theft alarm for Android, by Ed's idea: a phone can be stolen, but one SMS
from any other phone makes it scream. The trigger is SMS, not the internet,
so it works offline — as long as the SIM is in the phone and the phone is on.

## How it works

- The app listens for incoming SMS. When it sees the **trigger code**
  (default `BLAST 1234`) from a trusted number, it starts the blast:
  a looping siren at maximum alarm-stream volume (bypasses silent mode and
  Do Not Disturb), flashlight strobe, vibration, and the screen wakes up.
  It also sends one SMS back to the sender with the phone's last-known GPS
  coordinates.
- The blast stops when the **stop code** (default `STOP 1234`) arrives by
  SMS, or when the owner taps Stop in the notification or in the app.
- A quiet foreground service (the "guard") runs while the phone is armed,
  so HyperOS cannot silently kill the listener. It restarts on boot.
- Codes and the trusted-number list are changeable in the app. When the
  trusted list is empty, anyone who knows the code can trigger the siren —
  keep the code secret.

## Setup (on the phone — all of these matter)

1. Grant the permissions (SMS, location, notifications, camera).
2. Do Not Disturb access: allow Blast Buster, so it can force DND off.
3. Autostart ON: Security app > Autostart > Blast Buster ON.
4. Battery: Settings > Apps > Blast Buster > Battery saver > No restrictions.
5. Lock in recents: long-press the Blast Buster card and lock it.
6. Keep the screen lock ON — a thief who cannot unlock the phone cannot
   force-stop the app.
7. Test it: text the trigger code from a second number, then the stop code.

## Honest limits

- If the thief removes the SIM or powers the phone off, no SMS can reach
  it and no app can help.
- The siren is a generated two-tone wail (700/1400 Hz), synthesized in
  code — no audio files. It plays on STREAM_ALARM at maximum volume.
- On-device SMS triggering is tested by the owner with a second number.

## Build

Free-code stack only: plain Android + Kotlin, no dependencies, no AndroidX.

```sh
./gradlew :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Note: `gradle/wrapper/gradle-wrapper.jar` is intentionally not committed
(the GitHub push for this repo is text-only). Run `gradle wrapper` once
with Gradle 8.5 installed to regenerate it, or build with `gradle`
directly.

## v1 — what this build does

- `SmsReceiver` — SMS trigger/stop matching with trusted-number whitelist.
- `BlastService` — foreground guard + siren (AudioTrack, STREAM_ALARM),
  flashlight strobe, vibration, screen wake, DND force-off, GPS reply SMS.
- `BootReceiver` — restarts the guard after reboot when armed.
- `MainActivity` — status, code/number settings, test/stop, permissions,
  and the setup checklist.
- `Siren` — pure PCM tone synthesis (no Android classes; JVM-tested).
