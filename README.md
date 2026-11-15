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

1. Grant the permissions (SMS, location, notifications, camera, microphone, phone state).
2. Do Not Disturb access: allow Blast Buster, so it can force DND off.
3. Display over other apps: allow it — this is what blocks the notification shade during a blast.
4. Device admin: enable it — this is what locks the screen when the siren starts.
5. Autostart ON: Security app > Autostart > Blast Buster ON.
6. Battery: Settings > Apps > Blast Buster > Battery saver > No restrictions.
7. Lock in recents: long-press the Blast Buster card and lock it.
8. Keep the screen lock ON — a thief who cannot unlock the phone cannot
   force-stop the app.
9. Test it: text the trigger code from a second number, then the stop code.
   Test the lockout too: start a test blast, stop it with your fingerprint
   on the sensor (side power button) — no scanner shows on screen.

## Honest limits

- If the thief powers the phone off, no app can help. If the thief
  removes the SIM, the phone now blasts by itself (v1.1) — but the
  trigger SMS can never arrive after the SIM is out, so the auto-blast
  is the defence.
- The siren is a generated wail (700/1400 Hz FM sweep with 3rd and 5th
  harmonics), synthesized in code — no audio files. It plays on
  STREAM_ALARM at maximum volume; absolute loudness is capped by the
  phone's single speaker.
- The power button cannot be blocked by any app. The red lockout comes
  back the moment the screen turns on, and the siren keeps blasting
  through it.
- The voice trigger matches words, not the owner's voice. The no-UI
  fingerprint path is best-effort on Android 16 — if it falls back to
  BiometricPrompt, its system prompt will show.
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

## v1.1 — loopholes closed (Ed tested v1 on the Redmi 15C)

- **DND access list fixed:** v1 never declared `ACCESS_NOTIFICATION_POLICY`, so the app could not appear in Settings > Apps > Special app access > Do Not Disturb access. v1.1 declares it and the in-app DND button opens that settings page.
- **Red lockout screen:** when the siren starts, the phone locks (device admin, a setup step) and a red lockout screen shows over the lock screen — big 🚨, no password field. Volume up/down and Back are swallowed. It unlocks ONLY by the owner's fingerprint: no scanner UI is shown (FingerprintManager, no-UI path; BiometricPrompt is the fallback and its system prompt WILL show). The stop SMS code also dismisses it, so the owner can never be locked out. The power button cannot be blocked by any app — the lockout comes straight back when the screen turns on. The fingerprint sensor on the Redmi 15C is the side power button, not under the screen.
- **Notification shade blocked:** immersive sticky mode plus a top-edge overlay (needs "Display over other apps") that eats the pull-down gesture. The blast notification has no Stop action and cannot be dismissed. Force-stop in Settings still cannot be blocked if the phone is unlocked — the screen lock plus this lockout is the protection.
- **SIM removal:** the phone watches the SIM while armed. Pull it and the phone blasts by itself (no SMS needed — the trigger SMS could never arrive after the SIM is out). Pull it during a blast and the blast escalates: faster strobe + the shouting layer.
- **Voice:** during a blast, TextToSpeech shouts "Thief! Thief! This phone is stolen!" on a loop on the alarm stream (needs the device's TTS voice data installed). A voice trigger phrase (default "Blast Buster", OFF by default) starts the siren when spoken near the phone — phrase match only, NOT voice verification: anyone saying the phrase can trigger it, and it needs the speech recognizer + offline pack or network, and costs battery while listening.
- **Louder:** the siren wave now carries 3rd and 5th harmonics (square-ish, perceptually louder at the same peak), the alarm volume is re-asserted every 5 seconds, and audio focus is held with gain while blasting. Absolute loudness is still capped by the phone's single speaker.

## v1 — what this build does

- `SmsReceiver` — SMS trigger/stop matching with trusted-number whitelist.
- `BlastService` — foreground guard + siren (AudioTrack, STREAM_ALARM),
  flashlight strobe, vibration, screen wake, DND force-off, GPS reply SMS.
- `BootReceiver` — restarts the guard after reboot when armed.
- `MainActivity` — status, code/number settings, test/stop, permissions,
  and the setup checklist.
- `Siren` — pure PCM tone synthesis (no Android classes; JVM-tested).
