# Step

A personal, fully offline step counter for Android, built for a Xiaomi 17T (Android 16, HyperOS 3).
No account, no cloud, no ads, no analytics, and no internet permission.

Licensed under the GNU GPL v3 (see `LICENSE`). The original build brief is in `INSTRUCTIONS.md`.

## Status

The app is being built in stages, each delivered as an installable APK.

| Stage | Contents | State |
|---|---|---|
| 1 | Project skeleton, signed CI build, raw sensor readout, diagnostics | done, confirmed on the 17T |
| 2 | Background service, storage, correct counting, lock screen notification | done, confirmed on the 17T |
| 3 | Home screen widget | **this build** |
| 4 | Statistics per week, month and year | planned |
| 5 | Daily goal setting, export and import, HyperOS setup guide, polish | planned |

### What works now

- Steps are counted all day by a foreground service and stored in hourly buckets.
- An ongoing notification without sound shows today's steps, the percentage of the goal and a
  progress bar, on the lock screen and on the always-on display. A check mark appears once the
  goal is reached. The status bar shows the step count as a small chip.
- A resizable home screen widget shows today's steps; from two cells wide it adds the goal
  percentage and a progress bar. It follows the system colours and light or dark mode.
- Counting restarts after a reboot and after an app update, and a watchdog checks every half
  hour that it is still running.
- The app screen shows today's count and goal progress, plus diagnostics with a
  **Copy diagnostics** button.

The goal is fixed at 10 000 steps until settings arrive in stage 5.

## Download and install

1. On the phone, open the repository's [Releases](https://github.com/camilus123/step/releases)
   page and download the newest `step-….apk`.
2. Open the downloaded file. The first time, Android asks you to allow your browser or file
   manager to install apps; allow it.
3. Later versions install straight over the old one and keep all data. Do not uninstall first.

Every successful build is published there automatically. Version numbers look like `0.2.6`:
the stage, then a build number that only ever goes up.

## HyperOS settings to change by hand

HyperOS stops background apps aggressively. These settings tell it to leave Step alone. The menu
names below are the usual HyperOS ones and still need confirming on HyperOS 3.

1. **Background activity:** in the app, tap **Allow background activity** and confirm. This also
   lets the app restart counting by itself.
2. **Autostart:** Settings → Apps → Manage apps → Step → Autostart: on. Without it the app is not
   started after a reboot.
3. **Battery saver:** Settings → Apps → Manage apps → Step → Battery saver → No restrictions.
4. **Lock in recent apps:** open recent apps, long-press the Step card, tap the padlock.
5. **Lock screen notification:** in the app's notification settings, keep "Lock screen
   notifications" on, and under Notification categories → Step count set "On the Lock screen" to
   "Show all notifications and their contents". Keep "Notification spotlight" on: it is what puts
   the count on the always-on display.

What the 17T taught us about the lock screen: HyperOS 3 hides low-importance ("silent")
notifications there regardless of these settings, so the step notification has normal importance
with sound and vibration switched off; and only an Android 16 live update shows on the always-on
display.

## Widget

Long-press the home screen, open the widget picker and look for **Step** (third-party widgets
are usually listed under "Android widgets"). It starts two cells wide and can be resized down to
one cell, where it shows only the number, or made larger. Tapping it opens the app. The counting
service updates it whenever the count changes, at most every five seconds, and at midnight.

## Counting rules

- **Source:** the hardware step counter (`TYPE_STEP_COUNTER`), which counts cumulatively since
  boot even while the app is not running. The app stores differences between readings.
- **Storage:** hourly buckets. Days, weeks, months and years are sums over them. A bucket belongs
  to the local date and hour at the time of recording, so travelling to another time zone does
  not move old steps to a different day.
- **First start:** the first reading only sets the baseline. Steps walked before the app first
  saw the sensor are not counted.
- **Where the steps of a reading go:** the steps between two readings were walked somewhere
  between the moment the sensor took the previous reading and the moment it took the new one.
  They are spread evenly over the end of that time, over at most three seconds per step, and cut
  at hour boundaries. While the app is counting, readings are seconds apart, so steps land in
  the hour, and on the day, they were walked.
- **Quiet periods:** the 17T reports the first ten or so steps of a walk in one go. Thanks to
  the three-seconds-per-step limit those land in the minute they were walked, not across the
  hours the phone lay on the desk.
- **Gaps (counting was stopped for a while):** the steps taken in the gap are never dropped.
  The same rule spreads them: a few hundred steps go to the end of the gap, thousands of steps
  cover the hours of the gap, reaching back no further than the start of the previous day. On
  the 17T the sensor stamps each reading with the time of the last counted step, so a gap ends
  where the walking ended, not where the app woke up.
- **Reboot:** the counter restarts from zero. The app detects the reboot through Android's boot
  counter and starts a new baseline; steps counted since boot are credited from the boot time
  onward. Steps walked after the last reading before a shutdown are lost if counting was stopped
  at that moment.
- **Conventions:** metric, 24-hour time, weeks start on Monday.

The rules live in `app/src/main/java/si/marko/step/core/StepAccountant.kt` and are covered by
unit tests.

## Export and import

Planned for stage 5: the full history as one JSON file (plus CSV) through the system file
picker, and import of the same JSON format.

## Building

Builds run on GitHub Actions (`.github/workflows/build.yml`); nothing needs to be installed on
a PC. Each run executes the unit tests, builds a signed release APK and checks that it is signed
with the expected key, then publishes it as a GitHub Release. The version code is the workflow
run number, so every build is newer than the last. The stage number lives in `gradle.properties`
(`stepVersion`).

### Signing

Android only installs an update over an app signed with the same key, and uninstalling instead
wipes the step history. So every build is signed with one release key, which is **not** in this
repository. The workflow reads it from four repository secrets
(Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `STEP_KEYSTORE_BASE64` | the keystore file, base64 encoded on one line |
| `STEP_KEYSTORE_PASSWORD` | keystore password |
| `STEP_KEY_ALIAS` | key alias |
| `STEP_KEY_PASSWORD` | key password |

Keep a backup of the keystore and its passwords somewhere safe. If the key is lost, no update
can be installed over the existing app.
