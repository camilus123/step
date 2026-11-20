# Step

A personal, fully offline step counter for Android, built for a Xiaomi 17T (Android 16, HyperOS 3).
No account, no cloud, no ads, no analytics, and no internet permission.

Licensed under the GNU GPL v3 (see `LICENSE`). The original build brief is in `INSTRUCTIONS.md`.

## What it does

- **Counts steps all day** with a foreground service reading the phone's hardware step counter,
  and stores them in hourly buckets. Counting restarts after a reboot and after an app update,
  and a watchdog checks every half hour that it is still running.
- **Lock screen:** an ongoing notification without sound shows today's steps, the percentage of
  the goal and a progress bar, on the lock screen and on the always-on display. A check mark
  appears once the goal is reached. The status bar shows the step count as a small chip.
- **Home screen widgets** in four sizes, following the system colours and light or dark mode.
- **Three tabs.** *Today*: the count, goal progress and a bar chart of the day by hour.
  *Statistics*: week, month or year with a bar chart, total, daily average, best day and the
  number of days the goal was reached. *Settings*: daily goal, export and import, a guide to the
  phone settings that keep counting alive, and diagnostics.
- **Daily goal**, 10 000 steps unless changed. A change applies from that day on; earlier days
  stay judged against the goal they had.
- **Export and import** of the whole history as one file.

Built in five stages, each confirmed on the phone before the next: sensor check and signed
builds; background counting and notification; widget; statistics; goal, backup and setup guide.
All of it has run on the Xiaomi 17T except the last stage, which is the current build.

## Download and install

1. On the phone, open the repository's [Releases](https://github.com/camilus123/step/releases)
   page and download the newest `step-….apk`.
2. Open the downloaded file. The first time, Android asks you to allow your browser or file
   manager to install apps; allow it.
3. Later versions install straight over the old one and keep all data. Do not uninstall first.

Every successful build is published there automatically. Version numbers look like `0.2.6`:
the stage, then a build number that only ever goes up.

## HyperOS settings to change by hand

HyperOS stops background apps aggressively. **Settings → Keep counting reliably** in the app
lists what to set, shows what is already done, and has a button for each page it can open:

1. **Permissions:** physical activity (to read the step sensor) and notifications.
2. **Background activity:** exempts the app from battery optimisation. This also lets the app
   restart counting by itself.
3. **Autostart:** on, so the app starts after a reboot.
4. **Battery saver:** "No restrictions" for Step.
5. **Lock screen:** in the app's notification settings, keep "Lock screen notifications" and
   "Notification spotlight" on, and under Notification categories → Step count set "On the Lock
   screen" to "Show all notifications and their contents".
6. **Lock in recent apps:** open recent apps, long-press the Step card, tap the padlock.

The autostart and battery saver pages belong to Xiaomi, not to Android. If the phone does not
let the app open them directly, the button opens the app's system settings page instead.

What the 17T taught us about the lock screen: HyperOS 3 hides low-importance ("silent")
notifications there regardless of these settings, so the step notification has normal importance
with sound and vibration switched off; and only an Android 16 live update shows on the always-on
display.

## Widget

Long-press the home screen, open the widget picker and look for **Step** (third-party widgets
are usually listed under "Android widgets"). The HyperOS launcher of the 17T does not let the
widget be resized, so it comes in four fixed sizes, each its own entry in the picker:

| Widget | Shows |
|---|---|
| Step count 1 × 1 | only the number |
| Step count 2 × 1 | number, progress bar, goal percentage |
| Step count 2 × 2 | the same with a larger number and "steps" on its own line |
| Step count 4 × 1 | the same with the progress bar across the full width |

Tapping a widget opens the app. The counting service updates the widgets whenever the count
changes, at most every five seconds, and at midnight. On launchers that do resize widgets, any
of them can be resized and adapts its layout.

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

## Statistics

- A week runs from Monday to Sunday. Week and month show one bar per day, the year one bar per
  month. Tap a bar to read its value. The dashed line is the daily goal.
- Days without steps are shown, and counted, as zero.
- **Daily average** and **goal reached on x of y days** are taken over the days of the period
  that have already begun and are not earlier than the first day the app recorded steps. So a
  week that is three days old is averaged over three days, and the days before the app was
  installed do not drag the first week down.
- **Best day** is always a single day, also in the year view.
- The sensor on the 17T reports steps in batches of about ten, so the count moves in jumps; the
  totals are exact.

## Export and import

In **Settings → Export and import**. Files are chosen through the system file picker, so the app
needs no storage permission.

- **Export backup** writes the whole hourly history and the goal history to one JSON file, named
  `step-backup-<date>.json`. Keep it somewhere off the phone.
- **Import backup** reads such a file back. For every hour the larger of the two numbers wins, so
  an import can never lose steps, importing the same file twice changes nothing, and a backup
  made earlier today does not overwrite the steps walked since. A goal entry from the file is
  added only for days the app has no entry for. Afterwards the app says how many hours were
  added, raised and left alone. A file that is damaged, or made by a newer version of the app,
  is refused as a whole and nothing changes.
- **Export table (CSV)** writes `date,hour,steps` lines for a spreadsheet. It is for looking at,
  not for importing.

Moving to a new phone: export on the old one, install the app on the new one, import. The new
phone then starts counting from its own sensor; the old phone's position in its sensor count is
not part of a backup.

The file format (version 1):

```json
{
  "format": "step-backup",
  "formatVersion": 1,
  "exportedAt": "2026-11-20T21:50:00+01:00",
  "appVersion": "0.5.11",
  "hours": [ { "date": "2026-11-18", "hour": 12, "steps": 340 } ],
  "goals": [ { "from": "2026-11-20", "goal": 8000 } ]
}
```

Dates are local calendar dates and hours are 0 to 23 in local time, as stored.

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
