# Step counter for Android: build instructions

You are building a personal Android step counter app for Marko. This document is the full brief. Read it all, then **ask Marko your questions and wait for his answers before you write any code** (see "Before you start").

## The situation

- **Who it is for:** one person, Marko, on his own phone. It will be installed by sideloading an APK. It is not going to the Play Store, so Play policy does not constrain you, but Android's own permission and background rules do.
- **Target phone:** Xiaomi 17T. Android 16 (API 36), Xiaomi HyperOS 3, MediaTek Dimensity 8500 Ultra, 6.59" 1268x2756 AMOLED. The app should work on other Android phones too, but when a trade-off appears, the 17T wins.
- **Where you work:** the local folder `H:\temp\step` (currently empty) and the GitHub repo named `step`. Clone the repo into the folder, or initialise the folder and connect it to the repo if the repo is empty. Check whether the repo is public or private before you put anything sensitive near it.
- **Marko's PC:** Windows 11. Assume no Android Studio, Android SDK or JDK is installed, and do not install them. Builds happen on GitHub Actions.
- **You cannot touch the phone.** You have no device and no emulator on the PC. Marko installs the APK and tells you what happened. Design for that: make the app able to explain its own state (see "Diagnostics").

## What the app does

1. **Counts steps** continuously in the background, all day, surviving app closure and reboots.
2. **Shows today's step count on the lock screen** through a silent ongoing notification.
3. **Has a home screen widget** showing today's step count.
4. **Shows statistics per week, month and year.**
5. **Daily goal:** a configurable step goal, with the percentage reached shown in the app, in the widget and in the notification.
6. **Export and import** of the full step history, so the data survives a phone change or reinstall.

Not wanted: accounts, cloud sync, ads, analytics, Health Connect, distance or calorie estimates, social features. The app needs no internet permission at all, so do not request one.

## Decisions already made

Marko has already chosen these. Do not reopen them unless you find one is impossible, and then say so and ask.

- **Step source:** the phone's own step sensor only, stored in a local database. Fully offline.
- **Lock screen:** an ongoing notification, not a lock screen widget. HyperOS 3 does not reliably let third-party apps place widgets on the lock screen, and the notification doubles as the foreground service notification that keeps counting alive.
- **Builds:** GitHub Actions produces the APK. Marko downloads it and installs it.
- **Units and conventions:** metric, 24-hour time, weeks start on Monday.

## Technical guidance

These are recommendations with the reasoning behind them. Where you know a better way, take it and tell Marko why. Android 16 and HyperOS 3 are both recent, so **check current Android documentation for anything version-specific** (permissions, foreground service types, library versions) instead of relying on memory.

### Stack

Kotlin, Jetpack Compose with Material 3, Room for storage, Jetpack Glance for the widget, DataStore for settings. Target SDK 36. A minimum SDK around 29 is reasonable, since the runtime activity recognition permission starts there. Use current stable versions of the Gradle plugin, Kotlin and the libraries, and commit the Gradle wrapper so CI builds reproducibly.

### Counting steps correctly

This is the heart of the app and the part most likely to go subtly wrong.

- Use `Sensor.TYPE_STEP_COUNTER`. It is a hardware-backed cumulative count since the last boot, and it keeps counting while your app is not running. That property is what makes the app robust: if HyperOS kills the service for two hours, the next reading still includes the steps taken in between, and you can credit them.
- Xiaomi's public spec sheet for the 17T lists accelerometer and gyroscope but does not mention a step counter. It almost certainly exposes one, as nearly every modern phone does, but **do not assume it**. Check at runtime, and if the sensor is missing, show a clear message in the app. Ask Marko before building an accelerometer-based fallback, because that costs battery and accuracy.
- Because the sensor value is "steps since boot", you must store the last value you saw and work with differences. Cases to handle deliberately:
  - **Reboot:** the counter restarts from zero. Detect it (the value went down, or the boot time changed) and start a new baseline without producing negative or huge step counts. Restart the service on `BOOT_COMPLETED`.
  - **Midnight:** steps must land on the correct day. A reading that arrives at 00:03 containing steps from 23:50 onward cannot be split perfectly; choose a sensible rule, apply it consistently, and document it.
  - **Service was dead for a while:** credit the gap to the period in which it most plausibly happened, and never drop it.
  - **Time zone or clock changes:** store timestamps in a way that does not corrupt history when the zone changes.
- Ask the sensor to batch its reports (a generous `maxReportLatency`) so the phone's processor can sleep. A step counter should cost almost no battery.
- Store steps in **hourly buckets**, not only daily totals. It costs almost nothing, daily, weekly, monthly and yearly figures are all sums over it, and it leaves room for an hour-by-hour view of today.
- Put the counting logic (differences, reboot handling, day rollover, bucket assignment) in plain Kotlin with no Android dependencies, and cover it with unit tests. Since you cannot test on the phone, these tests are your main evidence that the counting is right.

### Staying alive on HyperOS

HyperOS is aggressive about killing background apps. Plan for this from the first commit.

- Run a foreground service with the appropriate health-related foreground service type and its matching permissions, plus `ACTIVITY_RECOGNITION` and `POST_NOTIFICATIONS`. Verify the exact Android 16 requirements against the current documentation.
- On first run, walk Marko through granting permissions with a short explanation of why each is needed.
- Some protections can only be set by hand in HyperOS settings: allowing autostart, setting battery saver to "No restrictions" for the app, and locking the app in the recent apps view. Give the app a setup screen that explains each one and opens the right settings page where an intent exists, falling back to the app's details page where it does not.
- Add a cheap periodic safety net (WorkManager, for example) that restarts the service if it has died. Thanks to the cumulative sensor, a restart recovers the missed steps.

### Lock screen notification

- One ongoing, silent, low-importance notification on its own channel. It shows today's steps and the goal percentage, for example "6 482 steps · 65 % of 10 000".
- Set its lock screen visibility to public so the count shows without unlocking.
- HyperOS has its own per-app switches for lock screen notifications and for showing notification content while locked. The setup screen should tell Marko to enable these.
- Update the notification when the count changes, but throttle it. Updating on every single step wastes battery and can get the app rate-limited.
- Tapping it opens the app.

### Widget

- A Glance widget showing today's steps, with the goal percentage as a number and a progress ring or bar.
- Make it resizable. The smallest size shows only the number; larger sizes add the goal progress.
- It should follow the system light and dark theme and look at home on a HyperOS home screen.
- Update it from the counting service when the count changes, throttled like the notification, and make sure it rolls over to zero at midnight even if no steps are taken.

### Statistics

- **Today:** large step count, goal ring with percentage, and an hour-by-hour bar chart.
- **Week, month, year:** a bar chart for the period (days for week and month, months for year), with total, daily average, best day and the number of days the goal was reached. Let Marko move backward and forward through past periods.
- Use the user's locale for number formatting. Empty days show as zero, not as missing bars.
- Keep the charts simple and readable. Drawing bars directly in Compose is fine and avoids a dependency; use a chart library only if it clearly earns its place.

### Goal

- Default 10 000 steps, changeable in settings.
- When the goal changes, past days keep being judged against the goal that applied on that day, so history does not rewrite itself. Store the goal with each day, or keep a goal history.
- Whether reaching the goal triggers a one-time notification is a question for Marko.

### Export and import

- Export the full history (hourly buckets plus goal history) to a single file through the system file picker, so no storage permission is needed. JSON for a faithful backup; add CSV as well if it is cheap, since it opens in a spreadsheet.
- Import reads the same JSON format back. Define what happens when imported data overlaps existing data (for each hour, keeping the larger value is a safe rule) and tell the user what was imported.
- Put a format version number in the file.

### Diagnostics

Because Marko is your only tester and you cannot see the phone, include a diagnostics screen showing: whether the step sensor exists and its vendor name, the current raw sensor value, the time of the last reading, whether the service is running, the state of each permission, the last detected reboot, and a short log of recent events (service starts, reboots detected, gaps credited). Add a button that copies all of it as text, so Marko can paste it to you when something misbehaves.

### Look and language

- Material 3, dark theme supported, clean and uncluttered. One main screen for today, one for statistics, one for settings.
- Interface in English, with every string in resources so a Slovenian translation can be added later.

## Building and delivering

- Set up a GitHub Actions workflow that, on every push to the main branch, runs the unit tests and builds an installable APK, then uploads it as a workflow artifact. For tagged versions, also attach the APK to a GitHub Release, which is easier to download on the phone.
- **Signing matters.** Android refuses to install an update over an app signed with a different key, and uninstalling to get around that wipes the step history. Every build must therefore be signed with the same key. Create one release keystore, keep it out of the repository, and supply it to the workflow through GitHub Actions secrets. If you cannot set the secrets yourself, give Marko exact steps. Tell him to keep a backup of the keystore and its passwords.
- Increase the version code with every build so updates install cleanly.
- After each push, check that the workflow run actually succeeded, and fix it if it did not. A red build is not a delivery.
- Write a `README.md` covering: what the app does, how to download and install the APK, the HyperOS settings to change by hand, how export and import work, and how the counting rules behave (midnight, reboot).

## How to work

- **Build in stages and deliver each one as an installable APK**, so that problems on the real phone surface early. A sensible order:
  1. Project skeleton, CI producing a signed APK, a screen showing the raw sensor value and the diagnostics. This proves the sensor and the build pipeline before anything depends on them.
  2. Foreground service, storage, correct counting, the lock screen notification.
  3. The widget.
  4. Statistics screens.
  5. Goal, export and import, setup guidance, polish.
- After each stage, tell Marko what to install and exactly what to check on the phone, then wait for his report before building on top of it.
- Commit in small, clearly described steps. Do not commit secrets, keystores or build output.
- When something behaves differently on the 17T than the documentation suggests, believe the phone. Ask Marko for the diagnostics text and work from that.
- Be honest about what is verified and what is not. "Unit tests pass and the CI build is green" is a different claim from "this works on the phone", and only Marko can confirm the second one.
- If you hit a decision that changes what Marko gets (a feature that cannot work as described, a permission he might not want to grant, a trade-off between battery and accuracy), stop and ask instead of choosing quietly.

## Before you start

Look at the folder and the repo first, so your questions are informed. Then ask Marko everything you need in one message and wait for his answers. At minimum, settle these:

1. The app's display name and its package name (for example `si.marko.step`).
2. Whether the repo is public or private, and whether you have the access needed to push and to set Actions secrets.
3. Whether reaching the daily goal should trigger a notification.
4. Whether the notification should show anything besides steps and goal percentage.
5. Any preference for the app's look: accent colour, icon idea.
6. Anything in this brief that seems unclear, contradictory or impossible on the 17T.

Add any further questions of your own. Once Marko has answered, summarise the plan in a few lines and begin with stage 1.
