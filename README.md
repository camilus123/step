# Step

A personal, fully offline step counter for Android, built for a Xiaomi 17T (Android 16, HyperOS 3).
No account, no cloud, no ads, no analytics, and no internet permission.

Licensed under the GNU GPL v3 (see `LICENSE`). The original build brief is in `INSTRUCTIONS.md`.

## Status

The app is being built in stages, each delivered as an installable APK.

| Stage | Contents | State |
|---|---|---|
| 1 | Project skeleton, signed CI build, raw sensor readout, diagnostics | **this build** |
| 2 | Background service, storage, correct counting, lock screen notification | planned |
| 3 | Home screen widget | planned |
| 4 | Statistics per week, month and year | planned |
| 5 | Daily goal, export and import, HyperOS setup guide, polish | planned |

### What stage 1 does

It opens to a single screen that shows the phone's raw step counter value (steps since the last
boot) while the app is open, plus diagnostics: whether the sensor exists and who made it, the
last reading and how old it was on arrival, permission states, boot information and a short log
of events. **Copy diagnostics** puts all of it on the clipboard as text.

It does not yet count in the background, store history or show a notification.

## Download and install

1. On the phone, open the repository's [Releases](https://github.com/camilus123/step/releases)
   page and download the newest `step-….apk`.
2. Open the downloaded file. The first time, Android asks you to allow your browser or file
   manager to install apps; allow it.
3. Later versions install straight over the old one and keep all data. Do not uninstall first.

Every push to `main` also produces an APK as a workflow artifact (Actions tab), but artifacts
need a GitHub login and arrive zipped, so Releases are the comfortable route.

## HyperOS settings to change by hand

Not needed for stage 1. From stage 2 on, HyperOS has to be told to leave the app alone:
autostart allowed, battery saver set to "No restrictions", the app locked in recent apps, and
lock screen notifications enabled with content shown. The app will walk through these, and this
section will list the exact menu paths once they are confirmed on the phone.

## Counting rules

Implemented from stage 2; these are the rules it will follow.

- **Source:** the hardware step counter (`TYPE_STEP_COUNTER`), which counts cumulatively since
  boot even while the app is not running. The app stores differences between readings.
- **Storage:** hourly buckets. Days, weeks, months and years are sums over them.
- **Midnight and hour boundaries:** each reading carries the sensor's own timestamp, so steps
  land in the hour they were taken, including steps delivered late in a batch.
- **Gaps (the service was not running for a while):** the steps taken in the gap are never
  dropped. They are spread evenly over the hours between the last reading and the new one,
  limited to the current and the previous day.
- **Reboot:** the counter restarts from zero. The app detects the reboot and starts a new
  baseline; steps counted since boot are credited from the boot time onward.
- **Conventions:** metric, 24-hour time, weeks start on Monday.

## Export and import

Planned for stage 5: the full history as one JSON file (plus CSV) through the system file
picker, and import of the same JSON format.

## Building

Builds run on GitHub Actions (`.github/workflows/build.yml`); nothing needs to be installed on
a PC. Each run executes the unit tests, builds a signed release APK and checks that it is signed
with the expected key. Tags of the form `v*` also publish a GitHub Release with the APK attached.
The version code is the workflow run number, so every build is newer than the last.

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
