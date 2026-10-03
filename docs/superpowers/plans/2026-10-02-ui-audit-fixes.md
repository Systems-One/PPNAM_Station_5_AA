# Station 5 UI Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every Station 5 finding from the 2026-10-01 PPNAM handheld UI audit (S5-01..S5-11 plus the static rows that apply to S5) and bring the app onto the cross-app "Recommended standard" of the consolidated report, without touching MQTT contracts or broker defaults.

**Architecture:** Station 5 is an XML + ViewBinding Android app (three activities: `LoginActivity` → `MainActivity` (empty "No workflows" home) and `SettingsActivity` (PIN-gated broker form)). Fixes are applied in the audit's Tier order: manifest/theme one-liners first, then small shared helpers (`SubmitKeys`, `PinGate`, `LoginFailure`), then the two behaviour changes (inactivity auto sign-out copied from Station 1, and "Test & Apply" replacing "Save & Restart"). Pure logic lands in small Kotlin objects with JVM JUnit tests; UI changes are verified on the `emulator-5558` C72 clone with uiautomator dumps because the repo has no Robolectric/Espresso tests.

**Tech Stack:** Kotlin 2.0, AGP 8.4.2, Gradle 8.7 wrapper, Material Components 1.12.0 (M3 themes), AppCompat 1.7, ConstraintLayout 2.2, HiveMQ MQTT 3 client, JUnit 4 (JVM unit tests only — `app/src/test`; no `androidTest` sources exist).

**Spec:** `C:\Users\Jonathan\AppData\Local\Temp\claude\C--Dev-Clients-PPNAM\ba7a1680-4205-4b04-bcb6-1b1f23c94914\scratchpad\audit\CONSOLIDATED_REPORT.md` (sections 3, 4, 5, 6, 7), with the app audit `...\audit\station5.md` and the static audit `...\audit\static_consistency.md`. The planning brief is `...\audit\PLANNING_BRIEF.md`.

## Global Constraints

- Repo: `C:\Dev\Clients\PPNAM\Station 5\PPNAM_Station_5_AA`, package `com.mitas.ppnam.station5aa`, `applicationId com.mitas.ppnam.station5aa`, `minSdk 26`, `compileSdk/targetSdk 35`, `versionName 1.2.0`, `versionCode 2` — do not change the version.
- Work on branch `fix/ui-audit-2026-10-02` cut from `master` (Task 0). Commit after every task; never `git add -A`; add only the task's files. Every commit message ends with the two trailer lines `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q`.
- Line numbers in this plan refer to the files as they are on `master` at `e69a2ca`. Earlier tasks shift later numbers (e.g. Task 3 adds three lines to `themes.xml`, Task 4 adds one import to each activity) — always locate a block by the quoted content / `android:id` / function name, and use the number only as a hint.
- Pre-existing dirty files at planning time: **none** (`git status --porcelain` was empty on `master` at `e69a2ca`). If Task 0 finds any, list them in the task's commit body and never stage them.
- OUT OF SCOPE (do not do): changing broker/credential defaults in `BrokerSettings.kt` (production defaults `mqtt.sysone.co.za:443`, WS on, TLS on, blank credentials stay exactly as they are — S5-06 is deliberately not fixed); any change to MQTT topics, payloads, schema versions or SCRAM (`MqttTopics.kt`, `Schema41.kt`, `ScramCrypto.kt`, `AuthClient.kt` request/response code); the "MAIN STATION OFFLINE" overlay and its relaunch logic (`MainActivity.stationStatusListener`, `ScannerApp.checkStationStatus`); session persistence across process restart (the in-memory `OperatorSessionHolder` stays as is).
- Build: `.\gradlew.bat :app:assembleDebug --offline` from the repo root (drop `--offline` only if it fails on a missing dependency). APK: `app\build\outputs\apk\debug\app-debug.apk`. Unit tests: `.\gradlew.bat :app:testDebugUnitTest --offline`.
- Emulator: serial `emulator-5558`; adb is `C:\Users\Jonathan\AppData\Local\Android\Sdk\platform-tools\adb.exe`. The emulator currently has the provisioning-signed APK installed, so the first install must be `adb -s emulator-5558 uninstall com.mitas.ppnam.station5aa` then `adb -s emulator-5558 install -r -g app\build\outputs\apk\debug\app-debug.apk`. After a reinstall, re-enter Settings (PIN `079545`): host `10.0.2.2`, port `9001`, WebSocket ON, TLS OFF, username `test`, password `test`. Logins: `operator1`/`pass`, `manager1`/`secret`; badges `BADGE000000000000000001`/`...002` via `adb -s emulator-5558 shell am broadcast -a com.rscja.scanner.action.scanner.RFID --es data <EPC>`; Settings shortcut tag `E28011700000021B2F6E9827`.
- Keyboard check recipe (used by several tasks): tap the field, `adb -s emulator-5558 shell dumpsys window | findstr ITYPE_IME` gives the IME top edge (≈1023 px for the text keyboard, ≈1155 px for the numeric pad); `adb -s emulator-5558 shell uiautomator dump /sdcard/ui.xml && adb -s emulator-5558 pull /sdcard/ui.xml .` and read the `bounds` of the node in question — a primary button is "visible" when its bottom bound is < the IME top, an error is visible when its bounds are above the IME top.
- Copy rules (consolidated report §5 "Recommended standard"): settings glyph = Material gear, tinted `@color/text_primary` on every screen; accent stays the icon amber `#A85F14` (already true for S5); pill vocabulary `Offline / Reconnecting / Connected / Station 5 offline` and the Diagnostics broker row uses the same three words (`Offline`, not `Disconnected`); station row `Online / Offline / Unknown`; dialogs are M3 rounded with a neutral dismiss and a red destructive confirm; log-out casing is **"Log out"** everywhere (button and dialog); exit dialog is "Close the app?" [Stay | Close] on Login **and** Main; errors use danger red `#E25C5C`; every activity is portrait-locked; every activity with a text field declares `stateHidden|adjustResize`; the PIN lockout is persisted with a 1 s ticker and a blank guard; Enter / `KEYCODE_ENTER` submits every single-line field; raw protocol strings never reach the operator; plurals for counts.
- Empty-state wording (S5-10 / static-14): the shared standard is **Station 5's current copy** — title "No workflows", body "No workflows are enabled for this station yet.", icon alpha 0.6. S5 does not change; Station 3's plan adopts these strings. Do not edit `no_workflows_title` / `no_workflows_message`.
- `values-night/themes.xml` (static-09, inferred for S5): verified absent in this repo (`git ls-files | grep -i night` is empty, `app/src/main/res` has no `values-night` folder). Nothing to delete; do not add one.

## Review Focus

1. Test & Apply on a freshly installed device with the password field left blank and no stored password — a reasonable person expects an inline "Enter the broker password" under the field and no connection attempt, not a 10 s spinner ending in a failure. (Pinned in Task 11 Step 6 verification.)
2. A supervisor who is locked out, backs out of Settings (or the process is killed) and reopens it — they expect the countdown to continue, Unlock and the PIN field to stay disabled until it ends, and an empty Unlock tap to never count as an attempt. (Pinned by `PinGateTest` in Task 7 and the manual reopen check in Task 7 Step 8.)
3. Hardware Enter from the C72 keypad on the **username** field should move focus to the password field (not submit, not insert a newline); on the password field it must submit exactly once even though the key sends both ACTION_DOWN and ACTION_UP. (Pinned by `SubmitKeysTest` in Task 5 and the `keyevent 66` check in Task 5 Step 7.)
4. Auto sign-out minutes set to `0` must disable the timer, and a value saved while an operator is logged in must take effect without re-login. (Pinned by `InactivityMonitorTest` "zero or negative timeout disables the monitor" in Task 10 and Task 11 Step 6's "apply while logged in" check.)
5. Pressing Back (or the toolbar up arrow) while "Testing connection…" is in flight must close Settings cleanly; when the broker answers afterwards nothing may crash or touch the destroyed binding. (Pinned in Task 11 Step 6, with `onDestroy` cancelling the pending listener/timeout.)

---

## File map

| File | Responsibility after this plan |
|---|---|
| `app/src/main/AndroidManifest.xml` | portrait lock + `stateHidden\|adjustResize` on all three activities |
| `app/src/main/res/values/themes.xml` | M3 dialog overlay (`AppAlertDialogTheme`), nav bar colour |
| `app/src/main/res/values/colors.xml` | `brand_tint` corrected to README `#E8C89D` |
| `app/src/main/res/values/strings.xml` | all new operator-facing copy, plurals |
| `app/src/main/res/drawable/ic_settings_gear.xml` | new: Material gear vector |
| `app/src/main/res/layout/activity_login.xml` | error above fields, password toggle, `singleLine`, scroller id |
| `app/src/main/res/layout/activity_main.xml` | gear, operator chip end-constrained |
| `app/src/main/res/layout/activity_settings.xml` | PIN error above field, MaterialSwitch, `singleLine` + IME chain, auto sign-out field, Test & Apply + status row, "Log out" |
| `.../station5aa/SubmitKeys.kt` | new: Enter/IME-action submit rule + `TextView.setOnSubmit` |
| `.../station5aa/LoginFailure.kt` | new: raw failure text → operator-facing kind |
| `.../station5aa/PinGate.kt` | new: pure PIN attempt/lockout state machine + `PinGateStore` (SharedPreferences) |
| `.../station5aa/AutoLogout.kt`, `InactivityMonitor.kt`, `SessionGuard.kt`, `SessionActivity.kt` | new: inactivity auto sign-out, copied from Station 1 (station-offline sign-out deliberately omitted) |
| `.../station5aa/SystemBars.kt` | + `Activity.hideKeyboard()` |
| `.../station5aa/SettingsRepository.kt` | + `autoLogoutMinutes()` / `saveAutoLogoutMinutes()` |
| `.../station5aa/LoginActivity.kt` | submit helper, IME hide, scroll button into view, error mapping, signed-out reason |
| `.../station5aa/MainActivity.kt` | "Close the app?" on Back, M3 dialog, `ime()` insets, `SessionActivity` |
| `.../station5aa/SettingsActivity.kt` | persisted PIN gate with ticker, Test & Apply, M3 dialog, `SessionActivity` |
| `.../station5aa/ScannerApp.kt` | background scan gate, `SessionGuard.install`, `touch()` on scans |
| `app/src/test/.../SubmitKeysTest.kt`, `LoginFailureTest.kt`, `PinGateTest.kt`, `AutoLogoutTest.kt`, `InactivityMonitorTest.kt` | new JVM tests |

---

### Task 0: Branch and baseline

**Files:** none modified.

- [ ] **Step 1: Record the working tree**

Run (from `C:\Dev\Clients\PPNAM\Station 5\PPNAM_Station_5_AA`):
```
git status --porcelain
git branch --show-current
```
Expected: no output from the first command (clean tree) and `master` from the second. If any files are listed, write them down as "pre-existing, untouched" for the final report and never `git add` them.

- [ ] **Step 2: Create the branch**

```
git switch -c fix/ui-audit-2026-10-02
```
Expected: `Switched to a new branch 'fix/ui-audit-2026-10-02'`.

- [ ] **Step 3: Baseline build and tests**

```
.\gradlew.bat :app:assembleDebug --offline
.\gradlew.bat :app:testDebugUnitTest --offline
```
Expected: `BUILD SUCCESSFUL` for both (5 existing test classes pass). If `--offline` fails with "No cached version", rerun once without `--offline` and keep using whichever form worked for the rest of the plan.

- [ ] **Step 4: Confirm the emulator and backend**

```
C:\Users\Jonathan\AppData\Local\Android\Sdk\platform-tools\adb.exe -s emulator-5558 shell getprop ro.build.version.sdk
```
Expected: `33`. If the fake backend is not running, start it per `CONSOLIDATED_REPORT.md` §8 (`start_broker.ps1`, `fake_stations.py --host 127.0.0.1 --port 1884 --mode happy --accept-any-session`).

No commit for this task.

---

### Task 1: Portrait lock, `adjustResize` on Settings, IME insets on Main (Tier 1 #1, #2, #3)

Closes: **S5-09** (landscape), **S5-03** (toolbar pans off under the keyboard — root cause is the missing `windowSoftInputMode` on Settings, so the window used `adjustPan`), the resize half of **S5-04** and **S5-07**, group (e) and group (a) sub-cause 1 for S5.

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:2, 21-40`
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/MainActivity.kt:61-65`

- [x] **Step 1: Edit the manifest**

Replace lines 2 and 21–40 of `app/src/main/AndroidManifest.xml` so the file reads:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:name=".ScannerApp"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.SysOneScanner">

        <!-- Mirrors Station 1's flow: LoginActivity is the launcher entry point,
             MainActivity requires an operator session. The label stays the app name so
             the kiosk launcher keeps showing "Station 5".

             Every activity is portrait-locked (UI audit 2026-10-01, group (e)): the C72 has
             auto-rotate on and a handheld scanner gains nothing from landscape. Every activity
             with a text field resizes for the keyboard so the primary button and error text
             stay reachable (group (a)). -->
        <activity
            android:name=".LoginActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:screenOrientation="portrait"
            android:windowSoftInputMode="stateHidden|adjustResize"
            tools:ignore="LockedOrientationActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".MainActivity"
            android:exported="false"
            android:screenOrientation="portrait"
            android:windowSoftInputMode="stateHidden|adjustResize"
            tools:ignore="LockedOrientationActivity" />

        <activity
            android:name=".SettingsActivity"
            android:exported="false"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.SysOneScanner"
            android:windowSoftInputMode="stateHidden|adjustResize"
            tools:ignore="LockedOrientationActivity" />

    </application>

</manifest>
```

- [x] **Step 2: Pad Main for the IME as well as the system bars**

In `MainActivity.kt` replace lines 61–65:

```kotlin
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            // systemBars() alone would defeat the manifest's adjustResize if a field is ever
            // added here (UI audit group (a) sub-cause 2) — pad for the keyboard too.
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
```

- [x] **Step 3: Build, install, verify**

```
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5558 uninstall com.mitas.ppnam.station5aa
adb -s emulator-5558 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5558 shell settings put system accelerometer_rotation 0
adb -s emulator-5558 shell settings put system user_rotation 1
adb -s emulator-5558 shell am start -n com.mitas.ppnam.station5aa/.LoginActivity
adb -s emulator-5558 shell dumpsys window | findstr "mCurrentRotation"
```
Expected: `BUILD SUCCESSFUL`; the Login screen stays upright (`mCurrentRotation=ROTATION_0`) even with `user_rotation 1`. Open Settings (gear) and check the same; then restore `adb -s emulator-5558 shell settings put system user_rotation 0`.

Then configure the broker in Settings (PIN `079545`, host `10.0.2.2`, port `9001`, WS on, TLS off, `test`/`test`, Save & Restart — still the old button at this point). Back in Settings tap the PIN field: run the keyboard check recipe — `toolbar` bounds must no longer be `[0,0][0,0]`; the "Settings" title, up arrow and pill remain on screen above the keyboard (closes S5-03). While editing the broker Username, the form must now scroll with the keyboard up (drag up — `btnSaveSettings` becomes reachable; S5-07 resize half).

- [x] **Step 4: Commit**

```
git add app/src/main/AndroidManifest.xml app/src/main/java/com/mitas/ppnam/station5aa/MainActivity.kt
git commit -m "fix(ui): portrait-lock every activity and resize Settings for the keyboard" -m "Closes S5-09, S5-03 and the resize half of S5-04/S5-07 from the 2026-10-01 UI audit (groups (a) and (e))." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 2: Material gear icon on both screens + operator chip end constraint (Tier 1, static-12, static-26)

Closes: **static-12** (wrench → gear, same tint everywhere), **static-26 / group (m)** (`tvOperator` can run under the header buttons).

**Files:**
- Create: `app/src/main/res/drawable/ic_settings_gear.xml`
- Modify: `app/src/main/res/layout/activity_login.xml:31-38`
- Modify: `app/src/main/res/layout/activity_main.xml:44-56, 78-89`
- Modify: `app/src/main/res/values/strings.xml` (add `cd_settings`)

- [x] **Step 1: Add the gear vector**

Create `app/src/main/res/drawable/ic_settings_gear.xml` (Material Icons "settings", 24dp):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Material "settings" glyph: the one settings affordance across every PPNAM handheld app
     (UI audit static-12) — replaces the legacy framework wrench ic_menu_preferences. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z" />
</vector>
```

- [x] **Step 2: Add the content description string**

In `app/src/main/res/values/strings.xml` add after line 7 (`error_fill_all_fields`):

```xml
    <string name="cd_settings">Settings</string>
```

- [x] **Step 3: Login toolbar button**

Replace lines 31–38 of `activity_login.xml`:

```xml
                <ImageButton
                    android:id="@+id/btnSettings"
                    android:layout_width="48dp"
                    android:layout_height="48dp"
                    android:background="?attr/selectableItemBackgroundBorderless"
                    android:contentDescription="@string/cd_settings"
                    android:src="@drawable/ic_settings_gear"
                    app:tint="@color/text_primary" />
```

- [x] **Step 4: Main header — gear and chip**

Replace lines 44–56 of `activity_main.xml` (the `layoutOperator` opening tag and its attributes) with:

```xml
            <LinearLayout
                android:id="@+id/layoutOperator"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:layout_marginEnd="8dp"
                android:background="?attr/selectableItemBackground"
                android:clickable="true"
                android:focusable="true"
                android:gravity="center_vertical"
                android:orientation="horizontal"
                android:padding="4dp"
                app:layout_constraintEnd_toStartOf="@id/btnSettings"
                app:layout_constraintHorizontal_bias="0"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@id/imgLogo"
                app:layout_constraintWidth_max="wrap">
```
(`0dp` + `layout_constraintWidth_max="wrap"` keeps the chip at its natural width but caps it at the gear's start edge, so a long "name · role" ellipsises inside `tvOperator` instead of running under the buttons — static-26.)

Replace lines 78–89 (the Settings `ImageButton`):

```xml
            <!-- Settings Button -->
            <ImageButton
                android:id="@+id/btnSettings"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/cd_settings"
                android:src="@drawable/ic_settings_gear"
                app:layout_constraintBottom_toBottomOf="parent"
                app:layout_constraintEnd_toStartOf="@id/connectionPill"
                app:layout_constraintTop_toTopOf="parent"
                app:tint="@color/text_primary" />
```

- [x] **Step 5: Build, install, verify**

```
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5558 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5558 shell am start -n com.mitas.ppnam.station5aa/.LoginActivity
cmd /c "adb -s emulator-5558 exec-out screencap -p > shot_task2_login.png"
```
Expected: the toolbar shows a white gear (not a wrench). Badge-login with `BADGE000000000000000002`, screenshot Main: gear in the header, same white tint; `uiautomator dump` shows `layoutOperator` right bound ≤ `btnSettings` left bound. Log out again (tap the operator chip → Log Out) before the next task.

- [x] **Step 6: Commit**

```
git add app/src/main/res/drawable/ic_settings_gear.xml app/src/main/res/layout/activity_login.xml app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml
git commit -m "fix(ui): Material gear for Settings on every screen, constrain operator chip" -m "Closes static-12 and static-26 (group m) for Station 5." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 3: "Log out" casing, switch tint, Diagnostics vocabulary, nav bar colour, `brand_tint` (Tier 1 #7, S5-08, static-23)

Closes: **S5-08** (all three parts: "LOG OUT" casing, lavender switch tracks, "Disconnected" vs pill "Offline"), **static-23** (`brand_tint` differs from README and is unused — corrected to the README value), group (h) nav-bar colour for S5.

**Files:**
- Modify: `app/src/main/res/values/strings.xml:25, 43`
- Modify: `app/src/main/res/layout/activity_settings.xml:348-361, 472-481`
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/SettingsActivity.kt:130-152`
- Modify: `app/src/main/res/values/themes.xml:23-25`
- Modify: `app/src/main/res/values/colors.xml:25`

- [x] **Step 1: Strings**

In `strings.xml` change line 25 and add the Diagnostics words after line 43 (`label_signed_in_as`):

```xml
    <string name="btn_log_out">Log out</string>
```
```xml
    <!-- Diagnostics pills use the toolbar pill's vocabulary (UI audit §5 "Pill vocabulary") -->
    <string name="diag_broker_connected">Connected</string>
    <string name="diag_broker_reconnecting">Reconnecting</string>
    <string name="diag_broker_offline">Offline</string>
    <string name="diag_station_online">Online</string>
    <string name="diag_station_offline">Offline</string>
    <string name="diag_station_unknown">Unknown</string>
```

- [x] **Step 2: Switches → `MaterialSwitch` (themed track)**

Replace lines 348–361 of `activity_settings.xml`:

```xml
                        <!-- MaterialSwitch takes its track/thumb colours from the theme's
                             colorPrimary (amber) instead of SwitchMaterial's lavender defaults
                             (UI audit S5-08). -->
                        <com.google.android.material.materialswitch.MaterialSwitch
                            android:id="@+id/swBrokerWebSocket"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="12dp"
                            android:text="@string/label_broker_websocket"
                            android:textColor="@color/text_primary_dark" />

                        <com.google.android.material.materialswitch.MaterialSwitch
                            android:id="@+id/swBrokerTls"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="@string/label_broker_tls"
                            android:textColor="@color/text_primary_dark" />
```
(`SettingsActivity.kt` only reads/writes `.isChecked`, which `MaterialSwitch` inherits from `CompoundButton`, so no Kotlin change is needed.)

- [x] **Step 3: Log out button — M3 outlined style, no caps**

Replace lines 472–481 of `activity_settings.xml`:

```xml
                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/btnLogOut"
                            style="@style/Widget.Material3.Button.OutlinedButton"
                            android:layout_width="match_parent"
                            android:layout_height="56dp"
                            android:layout_marginTop="12dp"
                            android:text="@string/btn_log_out"
                            android:textAllCaps="false"
                            android:textColor="@color/danger"
                            app:cornerRadius="14dp"
                            app:strokeColor="@color/danger" />
```

- [x] **Step 4: Diagnostics vocabulary**

Replace lines 130–152 of `SettingsActivity.kt` (`updateDiagnostics`):

```kotlin
    /**
     * The Diagnostics card, mirroring Station 2's SettingsScreen: broker link and station
     * presence are separate failures with separate remedies, and the composite pill can only
     * name one of them at a time — so both get their own row here. The broker row uses the
     * toolbar pill's own words (Offline / Reconnecting / Connected) so one state never has two
     * names on the same screen (UI audit S5-08).
     */
    private fun updateDiagnostics(status: ConnectionStatus) {
        val green = getColor(R.color.success)
        val brand = getColor(R.color.primary_action)
        val red = getColor(R.color.danger)
        val muted = getColor(R.color.text_muted)

        when (status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.STATION_OFFLINE ->
                binding.pillBroker.setAppearance(green, getString(R.string.diag_broker_connected))
            ConnectionStatus.RECONNECTING ->
                binding.pillBroker.setAppearance(brand, getString(R.string.diag_broker_reconnecting))
            ConnectionStatus.OFFLINE ->
                binding.pillBroker.setAppearance(red, getString(R.string.diag_broker_offline))
        }

        // With the broker down, the retained presence value is stale rather than false — saying
        // "offline" there would blame the station for the broker's fault.
        when (status) {
            ConnectionStatus.CONNECTED ->
                binding.pillStation.setAppearance(green, getString(R.string.diag_station_online))
            ConnectionStatus.STATION_OFFLINE ->
                binding.pillStation.setAppearance(brand, getString(R.string.diag_station_offline))
            else -> binding.pillStation.setAppearance(muted, getString(R.string.diag_station_unknown))
        }
    }
```

- [x] **Step 5: Nav bar colour and `brand_tint`**

In `themes.xml` replace lines 23–25 with:

```xml
        <!-- Status / navigation bars: both on the window colour so the bottom strip never
             flips between black and light grey across screens (UI audit group (h)). -->
        <item name="android:statusBarColor">@color/window_background</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:navigationBarColor">@color/window_background</item>
        <item name="android:windowLightNavigationBar" tools:targetApi="o_mr1">false</item>
```

In `colors.xml` change line 25 to the README icon-pack value:

```xml
    <color name="brand_tint">#E8C89D</color>
```

- [x] **Step 6: Build, install, verify**

```
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5558 install -r -g app\build\outputs\apk\debug\app-debug.apk
```
Open Settings (pill should say "Connected" if the broker config survived; otherwise re-enter it). Expected: Diagnostics shows "MQTT BROKER · Connected" / "STATION 5 · Online"; with the broker unreachable it would read "Offline" (same word as the toolbar pill). Unlock with `079545`: both switches have an amber track when on and a dark grey track when off (no lavender/pink). Badge-login, open Settings again: the SESSION card button reads "Log out" in mixed case (`uiautomator dump` → `text="Log out"`). The nav bar strip is `#07101A` on every screen (screenshot).

- [x] **Step 7: Commit**

```
git add app/src/main/res/values/strings.xml app/src/main/res/layout/activity_settings.xml app/src/main/java/com/mitas/ppnam/station5aa/SettingsActivity.kt app/src/main/res/values/themes.xml app/src/main/res/values/colors.xml
git commit -m "fix(ui): 'Log out' casing, themed switches, pill vocabulary in Diagnostics, nav bar colour" -m "Closes S5-08 and static-23 for Station 5." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 4: M3 dialog style — rounded, inset, neutral dismiss, red destructive confirm (static-15, §5 "Dialog style")

Closes: **static-15** for S5 and the §5 "Dialog style" row (S2's M3 look: 28 dp corners, 24 dp inset, neutral dismiss, red destructive confirm). The three existing AppCompat dialogs (Login exit, Main log-out, Settings log-out) move to `MaterialAlertDialogBuilder`; Task 8 adds the fourth (Main exit) with the same builder.

**Files:**
- Modify: `app/src/main/res/values/themes.xml:27-42`
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/LoginActivity.kt:12, 153-160`
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/MainActivity.kt:93-107`
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/SettingsActivity.kt:168-182`

- [x] **Step 1: Theme**

In `themes.xml` replace lines 27–32 (inside `Base.Theme.SysOneScanner`) with:

```xml
        <!-- Every dialog goes through MaterialAlertDialogBuilder, which reads this overlay:
             rounded M3 surface on the card colour, neutral dismiss, red destructive confirm
             (UI audit static-15 / §5 "Dialog style"). -->
        <item name="materialAlertDialogTheme">@style/AppAlertDialogTheme</item>
```

Replace lines 37–42 (the old `AppAlertDialogTheme`) with:

```xml
    <style name="AppAlertDialogTheme" parent="ThemeOverlay.Material3.MaterialAlertDialog">
        <item name="alertDialogStyle">@style/AppAlertDialog</item>
        <item name="materialAlertDialogTitleTextStyle">@style/AppAlertDialogTitle</item>
        <item name="materialAlertDialogBodyTextStyle">@style/AppAlertDialogBody</item>
        <item name="buttonBarPositiveButtonStyle">@style/AppAlertDialogButton.Destructive</item>
        <item name="buttonBarNegativeButtonStyle">@style/AppAlertDialogButton.Neutral</item>
    </style>

    <style name="AppAlertDialog" parent="MaterialAlertDialog.Material3">
        <item name="backgroundTint">@color/card_background</item>
        <item name="shapeAppearance">@style/ShapeAppearance.SysOneScanner.Dialog</item>
        <item name="backgroundInsetStart">24dp</item>
        <item name="backgroundInsetEnd">24dp</item>
    </style>

    <style name="ShapeAppearance.SysOneScanner.Dialog" parent="ShapeAppearance.Material3.Corner.ExtraLarge">
        <item name="cornerSize">28dp</item>
    </style>

    <style name="AppAlertDialogTitle" parent="MaterialAlertDialog.Material3.Title.Text">
        <item name="android:textColor">@color/text_primary</item>
    </style>

    <style name="AppAlertDialogBody" parent="MaterialAlertDialog.Material3.Body.Text">
        <item name="android:textColor">@color/text_muted</item>
    </style>

    <!-- Positive = the destructive action in every dialog this app shows (Close, Log out). -->
    <style name="AppAlertDialogButton.Destructive" parent="Widget.Material3.Button.TextButton.Dialog">
        <item name="android:textColor">@color/danger</item>
        <item name="android:textAllCaps">false</item>
    </style>

    <style name="AppAlertDialogButton.Neutral" parent="Widget.Material3.Button.TextButton.Dialog">
        <item name="android:textColor">@color/text_primary</item>
        <item name="android:textAllCaps">false</item>
    </style>
```

- [x] **Step 2: LoginActivity**

Replace the import on line 12 (`import androidx.appcompat.app.AlertDialog`) with:

```kotlin
import com.google.android.material.dialog.MaterialAlertDialogBuilder
```

Replace lines 153–160 (`showExitDialog`):

```kotlin
    private fun showExitDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.exit_dialog_title))
            .setMessage(getString(R.string.exit_dialog_message))
            .setPositiveButton(getString(R.string.exit_dialog_close)) { _, _ -> finishAffinity() }
            .setNegativeButton(getString(R.string.exit_dialog_stay), null)
            .show()
    }
```

- [x] **Step 3: MainActivity**

Add the import (after line 9, `import androidx.core.view.WindowInsetsCompat`):

```kotlin
import com.google.android.material.dialog.MaterialAlertDialogBuilder
```

Replace lines 93–107 (`showLogoutDialog`):

```kotlin
    private fun showLogoutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.logout_dialog_title))
            .setMessage(getString(R.string.logout_dialog_message))
            .setPositiveButton(getString(R.string.btn_log_out)) { _, _ ->
                AuthClient(this).logout {
                    startActivity(Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
```

- [x] **Step 4: SettingsActivity**

Add the import (after line 9, `import androidx.appcompat.app.AppCompatActivity`):

```kotlin
import com.google.android.material.dialog.MaterialAlertDialogBuilder
```

Replace lines 168–182 (the `btnLogOut` click listener inside `setupSessionSection`):

```kotlin
        binding.btnLogOut.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.logout_dialog_title))
                .setMessage(getString(R.string.logout_dialog_message))
                .setPositiveButton(getString(R.string.btn_log_out)) { _, _ ->
                    AuthClient(this).logout {
                        startActivity(Intent(this, LoginActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        })
                        finish()
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
```

- [x] **Step 5: Confirm no AppCompat builder remains**

```
findstr /s /n "AlertDialog.Builder" app\src\main\java\*.kt
```
Expected: no matches (only `MaterialAlertDialogBuilder` is used now). Also `findstr /s /n "alertDialogTheme" app\src\main\res\values\themes.xml` must return nothing except the `materialAlertDialogTheme` line.

- [x] **Step 6: Build, install, verify**

```
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5558 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5558 shell am start -n com.mitas.ppnam.station5aa/.LoginActivity
adb -s emulator-5558 shell input keyevent 4
cmd /c "adb -s emulator-5558 exec-out screencap -p > shot_task4_exit_dialog.png"
```
Expected: "Close the app?" dialog on the card colour with visibly rounded corners, ≥ 24 dp (≈72 px) from each screen edge (uiautomator dump: dialog root left bound ≈ 72), "Stay" in white and "Close" in red, both mixed-case. Tap Stay. Badge-login, tap the operator chip → "Log out?" dialog: "Cancel" white, "Log out" red. Cancel.

- [x] **Step 7: Commit**

```
git add app/src/main/res/values/themes.xml app/src/main/java/com/mitas/ppnam/station5aa/LoginActivity.kt app/src/main/java/com/mitas/ppnam/station5aa/MainActivity.kt app/src/main/java/com/mitas/ppnam/station5aa/SettingsActivity.kt
git commit -m "fix(ui): Material 3 dialog theme with neutral dismiss and red destructive confirm" -m "Closes static-15 for Station 5 (§5 Dialog style)." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 5: Enter / `KEYCODE_ENTER` submits every single-line field (Tier 2 #9, S5-02, group (b))

Closes: **S5-02** (Enter does not unlock the PIN / save; login password IME shows the newline glyph).

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/SubmitKeys.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station5aa/SubmitKeysTest.kt`
- Modify: `app/src/main/res/layout/activity_login.xml:92-99, 113-120`
- Modify: `app/src/main/res/layout/activity_settings.xml:239-246, 322-327, 340-345, 373-378, 392-397`
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/LoginActivity.kt:10, 63-70`
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/SettingsActivity.kt:7, 58-65`

**Interfaces:**
- Produces: `object SubmitKeys { const val NO_KEY = -1; fun isSubmit(actionId: Int, keyCode: Int, keyAction: Int): Boolean; fun isEnterKey(keyCode: Int): Boolean }` and `fun TextView.setOnSubmit(action: () -> Unit)`. Tasks 10 and 11 call `setOnSubmit` on new fields.

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/mitas/ppnam/station5aa/SubmitKeysTest.kt`:

```kotlin
package com.mitas.ppnam.station5aa

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One rule for "the operator pressed submit": the IME action button, or a hardware Enter —
 * which the C72 keypad and a scanner-wedge suffix deliver as actionId IME_NULL plus a
 * KEYCODE_ENTER KeyEvent (UI audit group (b)). Only compile-time constants from android.jar
 * are used here, so this runs on the plain JVM.
 */
class SubmitKeysTest {

    @Test
    fun `IME Done submits`() {
        assertTrue(SubmitKeys.isSubmit(EditorInfo.IME_ACTION_DONE, SubmitKeys.NO_KEY, SubmitKeys.NO_KEY))
    }

    @Test
    fun `IME Go, Send and Search submit`() {
        assertTrue(SubmitKeys.isSubmit(EditorInfo.IME_ACTION_GO, SubmitKeys.NO_KEY, SubmitKeys.NO_KEY))
        assertTrue(SubmitKeys.isSubmit(EditorInfo.IME_ACTION_SEND, SubmitKeys.NO_KEY, SubmitKeys.NO_KEY))
        assertTrue(SubmitKeys.isSubmit(EditorInfo.IME_ACTION_SEARCH, SubmitKeys.NO_KEY, SubmitKeys.NO_KEY))
    }

    @Test
    fun `IME Next does not submit`() {
        assertFalse(SubmitKeys.isSubmit(EditorInfo.IME_ACTION_NEXT, SubmitKeys.NO_KEY, SubmitKeys.NO_KEY))
    }

    @Test
    fun `hardware Enter key-down submits`() {
        assertTrue(SubmitKeys.isSubmit(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN))
        assertTrue(SubmitKeys.isSubmit(EditorInfo.IME_NULL, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN))
    }

    @Test
    fun `hardware Enter key-up is swallowed but does not submit a second time`() {
        assertFalse(SubmitKeys.isSubmit(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP))
        assertTrue(SubmitKeys.isEnterKey(KeyEvent.KEYCODE_ENTER))
        assertTrue(SubmitKeys.isEnterKey(KeyEvent.KEYCODE_NUMPAD_ENTER))
    }

    @Test
    fun `other keys neither submit nor get swallowed`() {
        assertFalse(SubmitKeys.isSubmit(EditorInfo.IME_NULL, KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN))
        assertFalse(SubmitKeys.isEnterKey(KeyEvent.KEYCODE_A))
        assertFalse(SubmitKeys.isEnterKey(SubmitKeys.NO_KEY))
    }
}
```

- [x] **Step 2: Run it to verify it fails**

```
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station5aa.SubmitKeysTest"
```
Expected: compilation FAILS with `Unresolved reference: SubmitKeys`.

- [x] **Step 3: Implement `SubmitKeys`**

Create `app/src/main/java/com/mitas/ppnam/station5aa/SubmitKeys.kt`:

```kotlin
package com.mitas.ppnam.station5aa

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView

/**
 * One rule for "the operator pressed submit in this field": the IME's action button
 * (Done / Go / Send / Search) or a hardware Enter. The C72 keypad and a scanner-wedge
 * suffix send the latter, which reaches OnEditorActionListener as actionId IME_NULL plus a
 * KEYCODE_ENTER KeyEvent for both ACTION_DOWN and ACTION_UP — listeners that only check
 * actionId ignore it (UI audit group (b)).
 */
object SubmitKeys {
    /** Sentinel for "no KeyEvent" (IME action buttons arrive with a null event). */
    const val NO_KEY = -1

    fun isSubmit(actionId: Int, keyCode: Int, keyAction: Int): Boolean {
        val imeAction = actionId == EditorInfo.IME_ACTION_DONE ||
            actionId == EditorInfo.IME_ACTION_GO ||
            actionId == EditorInfo.IME_ACTION_SEND ||
            actionId == EditorInfo.IME_ACTION_SEARCH
        val enterDown = isEnterKey(keyCode) && keyAction == KeyEvent.ACTION_DOWN
        return imeAction || enterDown
    }

    /** Enter in either of its key codes; the ACTION_UP half must be consumed too, or the
     *  field inserts a newline / the next focused view receives a click. */
    fun isEnterKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
}

/** Runs [action] once per submit gesture (IME action or hardware Enter key-down). */
fun TextView.setOnSubmit(action: () -> Unit) {
    setOnEditorActionListener { _, actionId, event ->
        val keyCode = event?.keyCode ?: SubmitKeys.NO_KEY
        val keyAction = event?.action ?: SubmitKeys.NO_KEY
        when {
            SubmitKeys.isSubmit(actionId, keyCode, keyAction) -> {
                action()
                true
            }
            SubmitKeys.isEnterKey(keyCode) -> true // swallow the ACTION_UP half
            else -> false
        }
    }
}
```

- [x] **Step 4: Run the test to verify it passes**

```
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station5aa.SubmitKeysTest"
```
Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [x] **Step 5: Wire the activities**

`LoginActivity.kt`: delete line 10 (`import android.view.inputmethod.EditorInfo`) and replace lines 63–70 with:

```kotlin
        binding.etPassword.setOnSubmit { submitCredentials() }
```

`SettingsActivity.kt`: delete line 7 (`import android.view.inputmethod.EditorInfo`) and replace lines 58–65 with:

```kotlin
        binding.etPin.setOnSubmit { submitPin() }
        // Done on the last broker field is the same gesture as tapping the primary button.
        binding.etBrokerPassword.setOnSubmit { binding.btnSaveSettings.performClick() }
```

- [x] **Step 6: Single-line fields with an explicit IME chain**

`activity_login.xml` — replace lines 92–99 (`etUsername`) and 113–120 (`etPassword`):

```xml
                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/etUsername"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:imeOptions="actionNext"
                            android:inputType="text"
                            android:maxLines="1"
                            android:singleLine="true"
                            android:textColor="@color/text_primary_dark" />
```
```xml
                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/etPassword"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:imeOptions="actionDone"
                            android:inputType="textPassword"
                            android:maxLines="1"
                            android:singleLine="true"
                            android:textColor="@color/text_primary_dark" />
```

`activity_settings.xml` — replace the five `TextInputEditText` blocks:

lines 239–246 (`etPin`):
```xml
                            <com.google.android.material.textfield.TextInputEditText
                                android:id="@+id/etPin"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:imeOptions="actionDone"
                                android:inputType="numberPassword"
                                android:maxLength="6"
                                android:singleLine="true"
                                android:textColor="@color/text_primary_dark" />
```
lines 322–327 (`etBrokerHost`):
```xml
                            <com.google.android.material.textfield.TextInputEditText
                                android:id="@+id/etBrokerHost"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:imeOptions="actionNext"
                                android:inputType="textUri"
                                android:singleLine="true"
                                android:textColor="@color/text_primary_dark" />
```
lines 340–345 (`etBrokerPort`):
```xml
                            <com.google.android.material.textfield.TextInputEditText
                                android:id="@+id/etBrokerPort"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:imeOptions="actionNext"
                                android:inputType="number"
                                android:singleLine="true"
                                android:textColor="@color/text_primary_dark" />
```
lines 373–378 (`etBrokerUsername`):
```xml
                            <com.google.android.material.textfield.TextInputEditText
                                android:id="@+id/etBrokerUsername"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:imeOptions="actionNext"
                                android:inputType="text"
                                android:singleLine="true"
                                android:textColor="@color/text_primary_dark" />
```
lines 392–397 (`etBrokerPassword`):
```xml
                            <com.google.android.material.textfield.TextInputEditText
                                android:id="@+id/etBrokerPassword"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:imeOptions="actionDone"
                                android:inputType="textPassword"
                                android:singleLine="true"
                                android:textColor="@color/text_primary_dark" />
```
(Line numbers are those of the file at the start of this task — after Task 3 the switch block is two lines longer, so re-locate each block by its `android:id` rather than trusting the numbers.)

- [x] **Step 7: Build, install, verify**

```
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5558 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5558 shell am start -n com.mitas.ppnam.station5aa/.SettingsActivity
```
1. Tap the PIN field, `adb -s emulator-5558 shell input text 000000`, `adb -s emulator-5558 shell input keyevent 66` (hardware Enter). Expected: "Incorrect PIN. 4 attempts left before lockout." appears and the field clears (Enter now unlocks/validates — S5-02).
2. `input text 079545`, `keyevent 66` → the form unlocks.
3. Tap Host, press the IME action: focus moves Host → Port → Username → Password (Next chain); on Password, `keyevent 66` → the Save & Restart action fires (at this point it still restarts; Task 11 changes it).
4. Login screen: tap Password, screenshot — the IME action key is a tick/Done glyph, not the newline glyph (S5-02). Tap Username, `keyevent 66` → focus moves to Password without submitting and without inserting a newline (Review Focus 3). Type `operator1` / `wrongpw`, `keyevent 66` once → exactly one "Logging in" spinner cycle (watch `adb logcat -s AuthClient`), no double request.

- [x] **Step 8: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station5aa/SubmitKeys.kt app/src/test/java/com/mitas/ppnam/station5aa/SubmitKeysTest.kt app/src/main/res/layout/activity_login.xml app/src/main/res/layout/activity_settings.xml app/src/main/java/com/mitas/ppnam/station5aa/LoginActivity.kt app/src/main/java/com/mitas/ppnam/station5aa/SettingsActivity.kt
git commit -m "fix(input): Enter and IME actions submit every single-line field" -m "Closes S5-02 (group b): shared SubmitKeys rule accepts the IME action or a hardware KEYCODE_ENTER, singleLine + explicit IME chain on every field." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 6: Login — error above the fields, password toggle, keep Log In visible, operator-facing failure text (S5-01, group (f), §5 "Login layout")

Closes: **S5-01** (error under the keyboard, Log In clipped, "SCRAM proof rejected." shown), group (f) for S5, §5 Login layout row (password visibility toggle, error line above the fields; "Please fill in all fields" already exists at `LoginActivity.kt:101-104`), §5 timeout wording for the login round trip (10 s single attempt already in `AuthClient.REQUEST_TIMEOUT_MS`; the Log In button is the retry).

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/LoginFailure.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station5aa/LoginFailureTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/SystemBars.kt` (add `hideKeyboard`)
- Modify: `app/src/main/res/layout/activity_login.xml:49-58, 102-132`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/LoginActivity.kt`

**Interfaces:**
- Produces: `enum class LoginFailureKind { WRONG_CREDENTIALS, TIMEOUT, NOT_CONNECTED, OTHER }`, `object LoginFailure { fun classify(rawMessage: String?): LoginFailureKind }`, `fun Activity.hideKeyboard()` (used again in Tasks 7 and 11).

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/mitas/ppnam/station5aa/LoginFailureTest.kt`:

```kotlin
package com.mitas.ppnam.station5aa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Raw protocol text ("SCRAM proof rejected.") must never reach the operator (UI audit
 * group (f)); the classifier maps what AuthClient/Schema41 produce onto operator-facing kinds.
 */
class LoginFailureTest {

    @Test
    fun `SCRAM proof rejection is a wrong-credentials failure`() {
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("SCRAM proof rejected."))
    }

    @Test
    fun `password and credential wording is wrong-credentials`() {
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("Invalid password"))
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("Unknown credential"))
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("AUTH_FAILED"))
    }

    @Test
    fun `AuthClient's silence message is a timeout`() {
        assertEquals(LoginFailureKind.TIMEOUT, LoginFailure.classify("Station did not respond"))
        assertEquals(LoginFailureKind.TIMEOUT, LoginFailure.classify("Timed out waiting for 10000 ms"))
    }

    @Test
    fun `broker-side failures are not-connected`() {
        assertEquals(LoginFailureKind.NOT_CONNECTED, LoginFailure.classify("Not connected to the station"))
        assertEquals(LoginFailureKind.NOT_CONNECTED, LoginFailure.classify("Could not reach the station"))
    }

    @Test
    fun `anything else, including blank, is passed through as other`() {
        assertEquals(LoginFailureKind.OTHER, LoginFailure.classify("Badge not registered"))
        assertEquals(LoginFailureKind.OTHER, LoginFailure.classify(""))
        assertEquals(LoginFailureKind.OTHER, LoginFailure.classify(null))
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(LoginFailureKind.WRONG_CREDENTIALS, LoginFailure.classify("scram PROOF Rejected"))
    }
}
```

- [x] **Step 2: Run it to verify it fails**

```
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station5aa.LoginFailureTest"
```
Expected: compilation FAILS with `Unresolved reference: LoginFailure`.

- [x] **Step 3: Implement `LoginFailure`**

Create `app/src/main/java/com/mitas/ppnam/station5aa/LoginFailure.kt`:

```kotlin
package com.mitas.ppnam.station5aa

/** What the operator can do about a failed login. */
enum class LoginFailureKind { WRONG_CREDENTIALS, TIMEOUT, NOT_CONNECTED, OTHER }

/**
 * Classifies a login failure so LoginActivity can show an operator-facing string instead of
 * protocol text (UI audit group (f): "SCRAM proof rejected." was reaching the screen).
 * OTHER is passed through: the contract says the station's free-text `reason` is already
 * sanitised for display, so an unrecognised reason is still better than a generic one.
 */
object LoginFailure {
    fun classify(rawMessage: String?): LoginFailureKind {
        val m = rawMessage.orEmpty().trim().lowercase()
        return when {
            m.isEmpty() -> LoginFailureKind.OTHER
            "scram" in m || "proof" in m || "password" in m || "credential" in m || "auth_failed" in m ->
                LoginFailureKind.WRONG_CREDENTIALS
            "did not respond" in m || "timed out" in m || "timeout" in m -> LoginFailureKind.TIMEOUT
            "not connected" in m || "could not reach" in m -> LoginFailureKind.NOT_CONNECTED
            else -> LoginFailureKind.OTHER
        }
    }
}
```

- [x] **Step 4: Run the test to verify it passes**

```
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station5aa.LoginFailureTest"
```
Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [x] **Step 5: `hideKeyboard` helper**

Append to `app/src/main/java/com/mitas/ppnam/station5aa/SystemBars.kt` (add `import androidx.core.view.WindowInsetsCompat` after the existing `WindowCompat` import):

```kotlin

/**
 * Dismisses the soft keyboard. Called on submit so the result (error line, status row) is
 * never drawn underneath the IME (UI audit S5-01).
 */
fun Activity.hideKeyboard() {
    WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
}
```

- [x] **Step 6: Strings**

Add to `strings.xml` after `label_or_scan_badge` (line 18):

```xml
    <!-- Login failures, operator-facing (UI audit group (f)); OTHER passes the station's own reason through -->
    <string name="login_error_wrong_credentials">Incorrect username or password.</string>
    <string name="login_error_timeout">Station 5 did not respond. Check the station and retry.</string>
    <string name="login_error_not_connected">Not connected to the broker. Check Settings and try again.</string>
    <string name="login_error_generic">Login failed. Try again.</string>
```

- [x] **Step 7: Layout — scroller id, error above the fields, password toggle**

In `activity_login.xml` replace lines 49–58 (the `NestedScrollView` opening tag):

```xml
    <androidx.core.widget.NestedScrollView
        android:id="@+id/scrollLogin"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:clipToPadding="false"
        android:fillViewport="true"
        android:overScrollMode="never"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/appBarLayout">
```

Move the error line **above** the username field: delete the `tvLoginError` block (lines 123–132 in the original file, between `tilPassword` and the button `FrameLayout`) and insert this as the first child of the card's inner `LinearLayout` (immediately before `tilUsername`):

```xml
                    <!-- Above the fields (UI audit group (a) sub-cause 4): laid out below them it
                         landed exactly under the keyboard's top edge. -->
                    <TextView
                        android:id="@+id/tvLoginError"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="16dp"
                        android:textColor="@color/danger"
                        android:textSize="15sp"
                        android:visibility="gone"
                        tools:text="Incorrect username or password."
                        tools:visibility="visible" />
```

Replace the `tilPassword` opening tag (originally lines 102–111) to add the visibility toggle:

```xml
                    <com.google.android.material.textfield.TextInputLayout
                        android:id="@+id/tilPassword"
                        style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="16dp"
                        android:hint="@string/hint_password"
                        app:boxStrokeColor="@color/outline_dark"
                        app:endIconMode="password_toggle"
                        app:endIconTint="@color/text_muted"
                        app:hintTextColor="@color/text_secondary_dark">
```

- [x] **Step 8: LoginActivity — hide IME on submit, keep Log In in view, map failures**

Add imports to `LoginActivity.kt` (after `import android.os.Bundle`):

```kotlin
import android.graphics.Rect
import android.widget.EditText
```

In `onCreate`, after `binding.btnLogin.applyPressScaleFeedback()` (original line 76) insert:

```kotlin
        // adjustResize shrinks the scroller when the keyboard opens; bring the primary button
        // back into view so it is never left clipped under the IME (UI audit S5-01, §6).
        binding.scrollLogin.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val shrank = (bottom - top) < (oldBottom - oldTop)
            if (shrank && currentFocus is EditText) {
                binding.btnLogin.post { binding.btnLogin.bringIntoView() }
            }
        }
```

Replace `submitCredentials` (original lines 98–108):

```kotlin
    private fun submitCredentials() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.error_fill_all_fields))
            return
        }
        if (loginInFlight || loggedIn) return
        hideKeyboard()
        setLoggingIn(true)
        authClient.login(username, password) { result -> onLoginResult(result) }
    }
```

Replace `onLoginResult` (original lines 118–128):

```kotlin
    private fun onLoginResult(result: Result<OperatorSession>) {
        result
            .onSuccess {
                loggedIn = true
                goHome()
            }
            .onFailure { e ->
                setLoggingIn(false)
                showError(operatorMessage(e))
            }
    }

    /** Operator-facing wording for a failed login; protocol text never reaches the screen. */
    private fun operatorMessage(e: Throwable): String = when (LoginFailure.classify(e.message)) {
        LoginFailureKind.WRONG_CREDENTIALS -> getString(R.string.login_error_wrong_credentials)
        LoginFailureKind.TIMEOUT -> getString(R.string.login_error_timeout)
        LoginFailureKind.NOT_CONNECTED -> getString(R.string.login_error_not_connected)
        LoginFailureKind.OTHER ->
            e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.login_error_generic)
    }
```

Replace `showError` (original lines 140–143):

```kotlin
    private fun showError(message: String) {
        binding.tvLoginError.text = message
        binding.tvLoginError.visibility = View.VISIBLE
        binding.tvLoginError.post { binding.tvLoginError.bringIntoView() }
    }

    private fun View.bringIntoView() {
        requestRectangleOnScreen(Rect(0, 0, width, height), false)
    }
```

- [x] **Step 9: Build, install, verify (keyboard matrix S5 Login rows)**

```
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5558 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5558 shell am start -n com.mitas.ppnam.station5aa/.LoginActivity
```
1. Tap Password: keyboard check recipe → `btnLogin` bottom bound < IME top (≈1023) — fully visible, not 153/168 px clipped.
2. The password field shows an eye icon; tapping it reveals the text.
3. Type `operator1` / `wrongpw`, press the IME Done key. Expected: keyboard closes, red line **above** the Username field reads "Incorrect username or password." (not "SCRAM proof rejected."); `uiautomator dump` shows `tvLoginError` bounds inside the screen and above the fields.
4. Clear both fields, tap Log In: "Please fill in all fields" above the fields.
5. Stop the fake backend (Ctrl-C `fake_stations.py`), log in: after ~10 s the line reads "Station 5 did not respond. Check the station and retry." Restart the backend.

- [x] **Step 10: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station5aa/LoginFailure.kt app/src/test/java/com/mitas/ppnam/station5aa/LoginFailureTest.kt app/src/main/java/com/mitas/ppnam/station5aa/SystemBars.kt app/src/main/res/layout/activity_login.xml app/src/main/res/values/strings.xml app/src/main/java/com/mitas/ppnam/station5aa/LoginActivity.kt
git commit -m "fix(login): error above the fields, password toggle, keep Log In visible, operator-facing failures" -m "Closes S5-01 and group (f) for Station 5; §5 Login layout row (toggle, error position)." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 7: Persisted PIN lockout with ticker, blank guard, error above the field (Tier 2 #10, #11; group (c), S5-04)

Closes: group (c) for S5 (inferred — the PIN code is identical to Station 1's verified bypass: Cancel/Back + reopen resets the counter at `SettingsActivity.kt:25-26`; empty Unlock counts; countdown never ticks), **S5-04** (PIN error under the PIN pad), §5 "persisted PIN lockout with ticker and blank guard".

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/PinGate.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station5aa/PinGateTest.kt`
- Modify: `app/src/main/res/layout/activity_settings.xml` (PIN card: move `tvPinError`/`tvPinLockout` above the PIN row)
- Modify: `app/src/main/res/values/strings.xml` (plurals + lockout string)
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/SettingsActivity.kt:23-31, 185-228, onCreate/onDestroy`

**Interfaces:**
- Produces: `class PinGate(correctPin, maxAttempts, lockoutMs, failedAttempts, lockedUntilMs)` with `sealed class Outcome { Blank, Unlocked, Wrong(attemptsLeft), LockedOut(remainingMs) }`, `fun submit(pin: String, nowMs: Long): Outcome`, `fun remainingMs(nowMs: Long): Long`, `fun isLocked(nowMs: Long): Boolean`; `class PinGateStore(context) { fun load(): PinGate; fun save(gate: PinGate) }`. Task 11 calls `relockPinGate()` defined here.

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/mitas/ppnam/station5aa/PinGateTest.kt`:

```kotlin
package com.mitas.ppnam.station5aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Supervisor PIN gate (UI audit group (c)): five wrong attempts lock the gate for 30 s, the
 * counter survives leaving the screen (it is constructed from persisted values), a blank
 * submit is not an attempt, and the lockout counts down from a caller-supplied clock.
 */
class PinGateTest {

    private val t0 = 1_700_000_000_000L

    private fun gate(attempts: Int = 0, lockedUntil: Long = 0L) =
        PinGate(correctPin = "079545", failedAttempts = attempts, lockedUntilMs = lockedUntil)

    @Test
    fun `correct pin unlocks and resets the counter`() {
        val g = gate(attempts = 3)
        assertEquals(PinGate.Outcome.Unlocked, g.submit("079545", t0))
        assertEquals(0, g.failedAttempts)
        assertEquals(0L, g.lockedUntilMs)
    }

    @Test
    fun `wrong pin counts down the attempts left`() {
        val g = gate()
        assertEquals(PinGate.Outcome.Wrong(4), g.submit("000000", t0))
        assertEquals(PinGate.Outcome.Wrong(3), g.submit("000000", t0))
        assertEquals(2, g.failedAttempts)
    }

    @Test
    fun `fifth wrong pin locks the gate for thirty seconds`() {
        val g = gate(attempts = 4)
        assertEquals(PinGate.Outcome.LockedOut(30_000L), g.submit("000000", t0))
        assertTrue(g.isLocked(t0 + 29_999))
        assertEquals(t0 + 30_000, g.lockedUntilMs)
        assertEquals(0, g.failedAttempts)
    }

    @Test
    fun `while locked even the correct pin is refused with the remaining time`() {
        val g = gate(lockedUntil = t0 + 30_000)
        assertEquals(PinGate.Outcome.LockedOut(12_000L), g.submit("079545", t0 + 18_000))
        assertEquals(12_000L, g.remainingMs(t0 + 18_000))
    }

    @Test
    fun `after the lockout elapses the correct pin unlocks`() {
        val g = gate(lockedUntil = t0 + 30_000)
        assertFalse(g.isLocked(t0 + 30_000))
        assertEquals(0L, g.remainingMs(t0 + 31_000))
        assertEquals(PinGate.Outcome.Unlocked, g.submit("079545", t0 + 30_000))
    }

    @Test
    fun `blank submit is not an attempt`() {
        val g = gate(attempts = 2)
        assertEquals(PinGate.Outcome.Blank, g.submit("", t0))
        assertEquals(PinGate.Outcome.Blank, g.submit("   ", t0))
        assertEquals(2, g.failedAttempts)
    }

    @Test
    fun `a persisted counter carries across reopening the gate`() {
        // Simulates Cancel/Back and reopen: a new gate built from stored values.
        val g = gate(attempts = 4)
        assertEquals(PinGate.Outcome.LockedOut(30_000L), g.submit("111111", t0))
    }
}
```

- [x] **Step 2: Run it to verify it fails**

```
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station5aa.PinGateTest"
```
Expected: compilation FAILS with `Unresolved reference: PinGate`.

- [x] **Step 3: Implement `PinGate` and `PinGateStore`**

Create `app/src/main/java/com/mitas/ppnam/station5aa/PinGate.kt`:

```kotlin
package com.mitas.ppnam.station5aa

import android.content.Context

/**
 * Supervisor PIN gate for Settings: five wrong attempts lock the gate for 30 s. Pure
 * Kotlin — the caller supplies the clock — so it is unit-testable and so its state can be
 * persisted (UI audit group (c): leaving the screen used to reset the counter).
 */
class PinGate(
    private val correctPin: String = SUPERVISOR_PIN,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val lockoutMs: Long = LOCKOUT_MS,
    var failedAttempts: Int = 0,
    var lockedUntilMs: Long = 0L,
) {
    sealed class Outcome {
        /** Nothing typed — not an attempt. */
        object Blank : Outcome()
        object Unlocked : Outcome()
        data class Wrong(val attemptsLeft: Int) : Outcome()
        data class LockedOut(val remainingMs: Long) : Outcome()
    }

    fun isLocked(nowMs: Long): Boolean = nowMs < lockedUntilMs

    fun remainingMs(nowMs: Long): Long = (lockedUntilMs - nowMs).coerceAtLeast(0L)

    fun submit(pin: String, nowMs: Long): Outcome {
        if (isLocked(nowMs)) return Outcome.LockedOut(remainingMs(nowMs))
        if (pin.isBlank()) return Outcome.Blank
        if (pin == correctPin) {
            failedAttempts = 0
            lockedUntilMs = 0L
            return Outcome.Unlocked
        }
        failedAttempts++
        if (failedAttempts >= maxAttempts) {
            failedAttempts = 0
            lockedUntilMs = nowMs + lockoutMs
            return Outcome.LockedOut(lockoutMs)
        }
        return Outcome.Wrong(maxAttempts - failedAttempts)
    }

    companion object {
        // Ported from Station 2's SettingsViewModel so every app's supervisor lock behaves identically.
        const val SUPERVISOR_PIN = "079545"
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }
}

/** Persists the gate's counter and lockout deadline so Back/reopen or a restart cannot bypass it. */
class PinGateStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("pin_gate", Context.MODE_PRIVATE)

    fun load(): PinGate = PinGate(
        failedAttempts = prefs.getInt(KEY_ATTEMPTS, 0),
        lockedUntilMs = prefs.getLong(KEY_LOCKED_UNTIL, 0L),
    )

    fun save(gate: PinGate) {
        prefs.edit()
            .putInt(KEY_ATTEMPTS, gate.failedAttempts)
            .putLong(KEY_LOCKED_UNTIL, gate.lockedUntilMs)
            .apply()
    }

    private companion object {
        const val KEY_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKED_UNTIL = "locked_until_ms"
    }
}
```

- [x] **Step 4: Run the test to verify it passes**

```
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station5aa.PinGateTest"
```
Expected: `BUILD SUCCESSFUL`, 7 tests pass.

- [x] **Step 5: Strings and plurals**

Add to `strings.xml` after `section_configuration`:

```xml
    <string name="pin_prompt">Enter supervisor PIN to edit settings</string>
    <string name="hint_pin">PIN</string>
    <string name="btn_unlock">Unlock</string>
    <plurals name="pin_attempts_left">
        <item quantity="one">Incorrect PIN. %1$d attempt left before lockout.</item>
        <item quantity="other">Incorrect PIN. %1$d attempts left before lockout.</item>
    </plurals>
    <string name="pin_locked_out">Too many attempts. Try again in %1$d s.</string>
```

- [x] **Step 6: Layout — error lines above the PIN row**

In `activity_settings.xml`, inside `cardPinLock`, replace the whole inner `LinearLayout` (from the `<LinearLayout` after the card tag down to the matching `</LinearLayout>` before `</com.google.android.material.card.MaterialCardView>`; originally lines 209–276) with:

```xml
                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="16dp">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/pin_prompt"
                        android:textColor="@color/text_muted"
                        android:textSize="15sp" />

                    <!-- Error / lockout text sits ABOVE the field (UI audit S5-04): below it,
                         it landed exactly under the numeric keypad's top edge. -->
                    <TextView
                        android:id="@+id/tvPinError"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="10dp"
                        android:textColor="@color/danger"
                        android:textSize="13sp"
                        android:visibility="gone"
                        tools:text="Incorrect PIN. 4 attempts left before lockout."
                        tools:visibility="visible" />

                    <TextView
                        android:id="@+id/tvPinLockout"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="10dp"
                        android:textColor="@color/danger"
                        android:textSize="13sp"
                        android:visibility="gone" />

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:gravity="center_vertical"
                        android:orientation="horizontal">

                        <com.google.android.material.textfield.TextInputLayout
                            android:id="@+id/tilPin"
                            style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:hint="@string/hint_pin"
                            app:boxStrokeColor="@color/outline_dark"
                            app:hintTextColor="@color/text_secondary_dark">

                            <com.google.android.material.textfield.TextInputEditText
                                android:id="@+id/etPin"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:imeOptions="actionDone"
                                android:inputType="numberPassword"
                                android:maxLength="6"
                                android:singleLine="true"
                                android:textColor="@color/text_primary_dark" />
                        </com.google.android.material.textfield.TextInputLayout>

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/btnUnlock"
                            android:layout_width="wrap_content"
                            android:layout_height="56dp"
                            android:layout_marginStart="12dp"
                            android:text="@string/btn_unlock" />
                    </LinearLayout>
                </LinearLayout>
```

- [x] **Step 7: SettingsActivity — persisted gate, ticker, blank guard**

Replace lines 23–31 (the `correctPin`/`failedPinAttempts`/`lockedOutUntilMs` fields and the companion object) with:

```kotlin
    private lateinit var pinGateStore: PinGateStore
    private lateinit var pinGate: PinGate
    private val tickHandler = Handler(Looper.getMainLooper())
    private val lockoutTicker = object : Runnable {
        override fun run() = renderLockout()
    }
```

Add imports (after `import android.os.Bundle`):

```kotlin
import android.os.Handler
import android.os.Looper
```

In `onCreate`, immediately after `setupSessionSection()` (original line 44) insert:

```kotlin
        pinGateStore = PinGateStore(this)
        pinGate = pinGateStore.load()
```

and immediately before `onBackPressedDispatcher.addCallback(this) { finishBackward() }` insert:

```kotlin
        // A lockout that was running when the screen was last left is still running now.
        renderLockout()
```

Replace `submitPin`, `showErrorMessage`, `showLockoutMessage`, `hidePinMessages` (original lines 185–228) with:

```kotlin
    private fun submitPin() {
        val now = System.currentTimeMillis()
        when (val outcome = pinGate.submit(binding.etPin.text.toString(), now)) {
            PinGate.Outcome.Blank -> return // nothing typed: not an attempt (UI audit group (c))
            PinGate.Outcome.Unlocked -> {
                hidePinMessages()
                hideKeyboard()
                binding.cardPinLock.visibility = View.GONE
                binding.groupSettingsFields.visibility = View.VISIBLE
            }
            is PinGate.Outcome.Wrong -> {
                binding.etPin.setText("")
                showErrorMessage(
                    resources.getQuantityString(
                        R.plurals.pin_attempts_left, outcome.attemptsLeft, outcome.attemptsLeft
                    )
                )
            }
            is PinGate.Outcome.LockedOut -> {
                binding.etPin.setText("")
                renderLockout()
            }
        }
        pinGateStore.save(pinGate)
    }

    /** Shows the live countdown while locked (1 s ticker) and disables the gate's inputs. */
    private fun renderLockout() {
        val remainingMs = pinGate.remainingMs(System.currentTimeMillis())
        tickHandler.removeCallbacks(lockoutTicker)
        if (remainingMs <= 0L) {
            if (binding.tvPinLockout.visibility == View.VISIBLE) hidePinMessages()
            setPinInputEnabled(true)
            return
        }
        setPinInputEnabled(false)
        val seconds = ((remainingMs + 999) / 1_000).toInt()
        showLockoutMessage(getString(R.string.pin_locked_out, seconds))
        tickHandler.postDelayed(lockoutTicker, 1_000)
    }

    private fun setPinInputEnabled(enabled: Boolean) {
        binding.etPin.isEnabled = enabled
        binding.btnUnlock.isEnabled = enabled
    }

    /** Hides the form behind the PIN gate again (used after a successful Test & Apply). */
    private fun relockPinGate() {
        binding.etPin.setText("")
        hidePinMessages()
        binding.groupSettingsFields.visibility = View.GONE
        binding.cardPinLock.visibility = View.VISIBLE
    }

    private fun showErrorMessage(message: String) {
        binding.tvPinError.text = message
        binding.tvPinError.visibility = View.VISIBLE
        binding.tvPinLockout.visibility = View.GONE
    }

    private fun showLockoutMessage(message: String) {
        binding.tvPinLockout.text = message
        binding.tvPinLockout.visibility = View.VISIBLE
        binding.tvPinError.visibility = View.GONE
    }

    private fun hidePinMessages() {
        binding.tvPinError.visibility = View.GONE
        binding.tvPinLockout.visibility = View.GONE
    }
```

Replace `onDestroy` (original lines 238–241):

```kotlin
    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacks(lockoutTicker)
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
```

- [x] **Step 8: Build, install, verify**

```
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5558 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5558 shell am start -n com.mitas.ppnam.station5aa/.SettingsActivity
```
1. Tap the PIN field, tap Unlock with nothing typed: no message, attempts unchanged (blank guard).
2. `input text 000000` + `keyevent 66`: "Incorrect PIN. 4 attempts left before lockout." is rendered **above** the field — run the keyboard check recipe: `tvPinError` bounds are above the numeric IME top (≈1155) (closes S5-04).
3. Repeat wrong PINs until "Too many attempts. Try again in 30 s." Watch it tick 29, 28, … (screenshot twice 2 s apart). `etPin` and `btnUnlock` report `enabled="false"` in `uiautomator dump`.
4. Press Back, reopen Settings (gear) within 30 s: the countdown is still showing and still ticking (Review Focus 2). `adb -s emulator-5558 shell am force-stop com.mitas.ppnam.station5aa`, relaunch, open Settings: still locked if < 30 s have passed.
5. After it expires, the message disappears, the field re-enables, and `079545` unlocks.
6. Wrong PIN four times, Back, reopen, one more wrong → immediate lockout (counter persisted).

- [x] **Step 9: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station5aa/PinGate.kt app/src/test/java/com/mitas/ppnam/station5aa/PinGateTest.kt app/src/main/res/layout/activity_settings.xml app/src/main/res/values/strings.xml app/src/main/java/com/mitas/ppnam/station5aa/SettingsActivity.kt
git commit -m "fix(settings): persist the supervisor PIN lockout, tick the countdown, ignore blank submits" -m "Closes group (c) for Station 5 and S5-04 (error above the PIN field)." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 8: "Close the app?" on Main; confirm it on Login (Tier 2 #12, S5-05, static-04, group (d))

Closes: **S5-05**, **static-04** for S5. Note: source `LoginActivity.kt:80` already registers the exit dialog on Login — the audit's "Back does nothing on Login" was observed on the older provisioning APK; this task adds the Main half and re-verifies Login on the new build.

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/MainActivity.kt` (imports, `onCreate`, new `showExitDialog`)

- [x] **Step 1: Add the callback and dialog**

Add the import (after `import android.os.Bundle`):

```kotlin
import androidx.activity.addCallback
```

In `onCreate`, after `setupDashboard()` insert:

```kotlin
        // System Back used to finish the app silently (UI audit S5-05 / group (d)); every
        // station confirms with the same "Close the app?" dialog as the Login screen.
        onBackPressedDispatcher.addCallback(this) { showExitDialog() }
```

Add after `showLogoutDialog`:

```kotlin
    private fun showExitDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.exit_dialog_title))
            .setMessage(getString(R.string.exit_dialog_message))
            .setPositiveButton(getString(R.string.exit_dialog_close)) { _, _ -> finishAffinity() }
            .setNegativeButton(getString(R.string.exit_dialog_stay), null)
            .show()
    }
```

- [x] **Step 2: Build, install, verify**

```
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5558 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5558 shell am start -n com.mitas.ppnam.station5aa/.LoginActivity
adb -s emulator-5558 shell input keyevent 4
```
Expected: "Close the app?" [Stay | Close] on Login (Close is red). Tap Stay. Badge-login (`BADGE000000000000000002`), `keyevent 4` on Main: the same dialog; Stay keeps Main; Back again → Close → the app leaves the screen (`adb shell dumpsys activity activities | findstr station5aa` shows no resumed S5 activity). Relaunch: goes straight to Main (session kept in-process — unchanged, out of scope).

- [x] **Step 3: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station5aa/MainActivity.kt
git commit -m "fix(ui): confirm before closing the app from the home screen" -m "Closes S5-05 / static-04 (group d) for Station 5." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 9: Ignore the Settings-shortcut tag while the app is in the background (Tier 3 #23, S5-11, group (l))

Closes: **S5-11** / group (l) for S5. `LoginActivity`'s badge receiver is already registered only between `onResume`/`onPause`; the app-wide shortcut receiver in `ScannerApp` is the one that fires from the background.

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/ScannerApp.kt:17-29`

- [x] **Step 1: Gate the receiver on a resumed activity**

Replace lines 17–29 of `ScannerApp.kt`:

```kotlin
    private val rfidShortcutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action != "com.rscja.scanner.action.scanner.RFID") return
            // The Chainway broadcast reaches every app on the handheld. Only the app in the
            // foreground may act on it (UI audit group (l)): currentActivity is non-null only
            // between onResume and onPause.
            if (currentActivity == null) return
            val data = intent.getStringExtra("data")
            if (data == SETTINGS_RFID) {
                val settingsIntent = Intent(context, SettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(settingsIntent)
            }
        }
    }
```

- [x] **Step 2: Build, install, verify**

```
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5558 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5558 shell am start -n com.mitas.ppnam.station5aa/.LoginActivity
adb -s emulator-5558 shell am broadcast -a com.rscja.scanner.action.scanner.RFID --es data E28011700000021B2F6E9827
```
Expected: Settings opens (foreground case still works). Press Back to Login, then `adb -s emulator-5558 shell input keyevent 3` (Home) and send the same broadcast: Station 5 must **not** come to the front (`dumpsys activity activities | findstr "ResumedActivity"` is not `station5aa`). Also log in with a badge and repeat from Main — Settings opens from Main as before.

- [x] **Step 3: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station5aa/ScannerApp.kt
git commit -m "fix(scan): act on the Settings RFID shortcut only while an activity is resumed" -m "Closes S5-11 (group l) for Station 5." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 10: Inactivity auto sign-out with a reason on Login, configurable in Settings (Tier 3 #19, static-05, group (j))

Closes: **static-05** / group (j) for S5 (no inactivity timer, no reason text) and the "auto sign-out minutes" field of §5 "Settings action & field set". Station 1's `AutoLogout`, `InactivityMonitor`, `SessionGuard` and `SessionActivity` are copied with the package renamed; the station-offline sign-out half of S1's `SessionGuard` is **omitted** on purpose (S5 keeps its overlay — out of scope). Session persistence across process restart stays as is.

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/AutoLogout.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/InactivityMonitor.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/SessionGuard.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/SessionActivity.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station5aa/AutoLogoutTest.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station5aa/InactivityMonitorTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/SettingsRepository.kt:22-30, 66-73`
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/ScannerApp.kt` (install guard, touch on scan)
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/LoginActivity.kt` (companion + reason)
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/MainActivity.kt:7, 17` and `SettingsActivity.kt:9, 12` (extend `SessionActivity`)
- Modify: `app/src/main/res/layout/activity_settings.xml` (auto sign-out field after the broker password)
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/SettingsActivity.kt` (read/validate/save minutes on save)

**Interfaces:**
- Produces: `object AutoLogout { DEFAULT_MINUTES = 15; MAX_MINUTES = 1440; parseMinutes(String): Int?; timeoutMs(Int): Long }`; `class InactivityMonitor(now, schedule, cancel, onExpired)` with `start(timeoutMs)`, `touch()`, `checkNow()`, `stop()`, `isRunning`; `object SessionGuard { install(app); touch(); checkNow(); applyTimeout(); signOut(reason) }`; `abstract class SessionActivity : AppCompatActivity`; `SettingsRepository.autoLogoutMinutes(): Int` / `saveAutoLogoutMinutes(Int)`; `LoginActivity.EXTRA_SIGNED_OUT_REASON`; layout ids `tilAutoLogout` / `etAutoLogout`. Task 11 uses all of these.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/mitas/ppnam/station5aa/AutoLogoutTest.kt`:

```kotlin
package com.mitas.ppnam.station5aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoLogoutTest {

    @Test
    fun `default is fifteen minutes`() {
        assertEquals(15, AutoLogout.DEFAULT_MINUTES)
    }

    @Test
    fun `parses whole minutes within range`() {
        assertEquals(0, AutoLogout.parseMinutes("0"))
        assertEquals(15, AutoLogout.parseMinutes(" 15 "))
        assertEquals(1440, AutoLogout.parseMinutes("1440"))
    }

    @Test
    fun `rejects blanks, negatives, decimals and out-of-range values`() {
        assertNull(AutoLogout.parseMinutes(""))
        assertNull(AutoLogout.parseMinutes("-1"))
        assertNull(AutoLogout.parseMinutes("1.5"))
        assertNull(AutoLogout.parseMinutes("1441"))
        assertNull(AutoLogout.parseMinutes("abc"))
    }

    @Test
    fun `timeout in milliseconds, zero means disabled`() {
        assertEquals(0L, AutoLogout.timeoutMs(0))
        assertEquals(0L, AutoLogout.timeoutMs(-3))
        assertEquals(60_000L, AutoLogout.timeoutMs(1))
        assertEquals(900_000L, AutoLogout.timeoutMs(15))
    }
}
```

Create `app/src/test/java/com/mitas/ppnam/station5aa/InactivityMonitorTest.kt`:

```kotlin
package com.mitas.ppnam.station5aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inactivity auto-logout timer (copied from Station 1). Time and scheduling are injected so
 * the tests are deterministic: `scheduled` holds the pending runnable (at most one) and
 * `fireScheduled()` advances the clock to its due time and runs it.
 */
class InactivityMonitorTest {

    private var now = 1_000_000L
    private var scheduled: Pair<Long, Runnable>? = null
    private var expired = 0

    private val monitor = InactivityMonitor(
        now = { now },
        schedule = { delay, r -> scheduled = (now + delay) to r },
        cancel = { r -> if (scheduled?.second === r) scheduled = null },
        onExpired = { expired++ },
    )

    private fun fireScheduled() {
        val (due, r) = scheduled ?: error("nothing scheduled")
        scheduled = null
        now = maxOf(now, due)
        r.run()
    }

    @Test
    fun `expires once the timeout elapses without activity`() {
        monitor.start(60_000)
        assertTrue(monitor.isRunning)
        fireScheduled()
        assertEquals(1, expired)
        assertFalse(monitor.isRunning)
    }

    @Test
    fun `touch defers the deadline`() {
        monitor.start(60_000)
        now += 40_000
        monitor.touch()
        // The original deadline arrives: only 20s since the touch, so no expiry yet.
        fireScheduled()
        assertEquals(0, expired)
        assertTrue(monitor.isRunning)
        assertEquals(now + 40_000, scheduled!!.first)
        fireScheduled()
        assertEquals(1, expired)
    }

    @Test
    fun `stop cancels the pending deadline and never fires`() {
        monitor.start(60_000)
        monitor.stop()
        assertFalse(monitor.isRunning)
        assertNull(scheduled)
        assertEquals(0, expired)
    }

    @Test
    fun `checkNow after a long gap fires immediately`() {
        monitor.start(60_000)
        now += 3_600_000 // app was in the background for an hour
        monitor.checkNow()
        assertEquals(1, expired)
        assertNull(scheduled)
    }

    @Test
    fun `checkNow before the deadline does nothing`() {
        monitor.start(60_000)
        now += 10_000
        monitor.checkNow()
        assertEquals(0, expired)
        assertTrue(monitor.isRunning)
    }

    @Test
    fun `zero or negative timeout disables the monitor`() {
        monitor.start(0)
        assertFalse(monitor.isRunning)
        assertNull(scheduled)
        monitor.touch()
        monitor.checkNow()
        assertEquals(0, expired)
    }

    @Test
    fun `touch and checkNow are no-ops when stopped`() {
        monitor.touch()
        monitor.checkNow()
        assertEquals(0, expired)
        assertNull(scheduled)
    }

    @Test
    fun `restart replaces the previous timeout`() {
        monitor.start(60_000)
        monitor.start(5_000)
        assertEquals(now + 5_000, scheduled!!.first)
        fireScheduled()
        assertEquals(1, expired)
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

```
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station5aa.AutoLogoutTest" --tests "com.mitas.ppnam.station5aa.InactivityMonitorTest"
```
Expected: compilation FAILS with `Unresolved reference: AutoLogout` / `InactivityMonitor`.

- [ ] **Step 3: Copy the pure classes from Station 1**

Create `app/src/main/java/com/mitas/ppnam/station5aa/AutoLogout.kt`:

```kotlin
package com.mitas.ppnam.station5aa

/** Inactivity auto-logout setting rules (Station 1 spec §3): whole minutes, 0 = never, max one day. */
object AutoLogout {
    const val DEFAULT_MINUTES = 15
    const val MAX_MINUTES = 1440

    fun parseMinutes(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in 0..MAX_MINUTES }

    fun timeoutMs(minutes: Int): Long = if (minutes <= 0) 0L else minutes * 60_000L
}
```

Create `app/src/main/java/com/mitas/ppnam/station5aa/InactivityMonitor.kt`:

```kotlin
package com.mitas.ppnam.station5aa

/**
 * Inactivity auto-logout timer (copied from Station 1). Pure Kotlin: the caller supplies a
 * monotonic clock and a scheduler, so production uses SystemClock.elapsedRealtime plus a
 * main-thread Handler while tests drive time by hand.
 *
 * The deadline is wall-clock from the last activity, so time spent in the background
 * still counts; hosts call [checkNow] on resume to catch a deadline that passed while
 * no Handler was running. [onExpired] fires at most once per [start].
 */
class InactivityMonitor(
    private val now: () -> Long,
    private val schedule: (Long, Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onExpired: () -> Unit,
) {
    private var timeoutMs = 0L
    private var lastActivity = 0L
    private var pending: Runnable? = null

    val isRunning: Boolean get() = timeoutMs > 0

    fun start(timeoutMs: Long) {
        stop()
        if (timeoutMs <= 0) return
        this.timeoutMs = timeoutMs
        lastActivity = now()
        scheduleCheck(timeoutMs)
    }

    fun touch() {
        if (!isRunning) return
        lastActivity = now()
    }

    fun checkNow() {
        if (!isRunning) return
        val remaining = timeoutMs - (now() - lastActivity)
        if (remaining <= 0) {
            stop()
            onExpired()
        } else {
            scheduleCheck(remaining)
        }
    }

    fun stop() {
        timeoutMs = 0
        pending?.let(cancel)
        pending = null
    }

    private fun scheduleCheck(delayMs: Long) {
        pending?.let(cancel)
        val r = Runnable {
            pending = null
            checkNow()
        }
        pending = r
        schedule(delayMs, r)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station5aa.AutoLogoutTest" --tests "com.mitas.ppnam.station5aa.InactivityMonitorTest"
```
Expected: `BUILD SUCCESSFUL`, 4 + 8 tests pass.

- [ ] **Step 5: Repository, guard, base activity**

`SettingsRepository.kt` — add the key inside `private object Keys` (after line 27, `MQTT_USERNAME`):

```kotlin
        const val AUTO_LOGOUT_MINUTES = "auto_logout_minutes"
```

and add before `/** Wipes the broker credential. ... */` (line 69):

```kotlin
    /** Inactivity auto-logout, in minutes; 0 = never (Station 1 spec §3). */
    fun autoLogoutMinutes(): Int =
        prefs.getInt(Keys.AUTO_LOGOUT_MINUTES, AutoLogout.DEFAULT_MINUTES)

    fun saveAutoLogoutMinutes(minutes: Int) {
        prefs.edit().putInt(Keys.AUTO_LOGOUT_MINUTES, minutes.coerceIn(0, AutoLogout.MAX_MINUTES)).apply()
    }

```

Create `app/src/main/java/com/mitas/ppnam/station5aa/SessionGuard.kt`:

```kotlin
package com.mitas.ppnam.station5aa

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Process-wide owner of the inactivity sign-out (copied from Station 1's SessionGuard; the
 * station-offline sign-out is deliberately not ported — Station 5 keeps its overlay).
 *
 * Ends in [signOut]: best-effort `reader_logout_requested`, session cleared, and
 * LoginActivity brought up with the reason so the operator knows what happened. Installed
 * once from ScannerApp; activities only ever call [touch] (any interaction or scan).
 */
object SessionGuard {

    private const val TAG = "SessionGuard"

    private lateinit var app: Application
    private val mainHandler = Handler(Looper.getMainLooper())
    private var monitor: InactivityMonitor? = null

    fun install(app: Application) {
        this.app = app
        monitor = InactivityMonitor(
            now = { SystemClock.elapsedRealtime() },
            schedule = { delay, r -> mainHandler.postDelayed(r, delay) },
            cancel = { r -> mainHandler.removeCallbacks(r) },
            onExpired = {
                val minutes = SettingsRepository(app).autoLogoutMinutes()
                signOut(app.resources.getQuantityString(R.plurals.signed_out_inactivity, minutes, minutes))
            },
        )

        // Start/stop the inactivity timer with the session itself.
        OperatorSessionHolder.addListener { session ->
            mainHandler.post { if (session == null) monitor?.stop() else applyTimeout() }
        }

        // A deadline that passed while the app was backgrounded is caught on the next resume.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { checkNow() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Any operator interaction or scanner read. Safe from any thread. */
    fun touch() {
        mainHandler.post { monitor?.touch() }
    }

    fun checkNow() {
        monitor?.checkNow()
    }

    /** (Re)reads the configured timeout; called when a session starts and after Settings saves. */
    fun applyTimeout() {
        if (OperatorSessionHolder.session == null) return
        val minutes = SettingsRepository(app).autoLogoutMinutes()
        monitor?.start(AutoLogout.timeoutMs(minutes))
    }

    /** Idempotent: a second trigger racing the first finds no session and does nothing. */
    fun signOut(reason: String) {
        if (OperatorSessionHolder.session == null) return
        Log.i(TAG, "Signing out: $reason")
        AuthClient(app).logout {
            app.startActivity(Intent(app, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(LoginActivity.EXTRA_SIGNED_OUT_REASON, reason)
            })
        }
    }
}
```

Create `app/src/main/java/com/mitas/ppnam/station5aa/SessionActivity.kt`:

```kotlin
package com.mitas.ppnam.station5aa

import androidx.appcompat.app.AppCompatActivity

/**
 * Base for every screen that requires a signed-in operator: each touch or key press
 * counts as activity for the inactivity auto-logout. Scanner broadcasts don't pass
 * through onUserInteraction, so receivers call SessionGuard.touch() themselves.
 */
abstract class SessionActivity : AppCompatActivity() {
    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionGuard.touch()
    }
}
```

- [ ] **Step 6: Wire the app, the activities and Login's reason**

`ScannerApp.kt` — replace the whole `rfidShortcutReceiver` declaration (as left by Task 9) with this version, which also counts every scan as operator activity:

```kotlin
    private val rfidShortcutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action != "com.rscja.scanner.action.scanner.RFID") return
            // The Chainway broadcast reaches every app on the handheld. Only the app in the
            // foreground may act on it (UI audit group (l)): currentActivity is non-null only
            // between onResume and onPause.
            if (currentActivity == null) return
            // Scanner reads don't pass through onUserInteraction, so they count as activity here.
            SessionGuard.touch()
            val data = intent.getStringExtra("data")
            if (data == SETTINGS_RFID) {
                val settingsIntent = Intent(context, SettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(settingsIntent)
            }
        }
    }
```
and in `onCreate`, after `mqtt.connect()` insert:

```kotlin
        // Inactivity sign-out with a reason on Login (UI audit group (j), copied from Station 1).
        SessionGuard.install(this)
```

`MainActivity.kt`: delete line 7 (`import androidx.appcompat.app.AppCompatActivity`) and change line 17 to:

```kotlin
class MainActivity : SessionActivity() {
```

`SettingsActivity.kt`: delete line 9 (`import androidx.appcompat.app.AppCompatActivity`) and change line 12 to:

```kotlin
class SettingsActivity : SessionActivity() {
```

`LoginActivity.kt` — add inside the class, after `private var loggedIn = false`:

```kotlin
    companion object {
        /** Why the operator landed here without asking to; shown as the error line. */
        const val EXTRA_SIGNED_OUT_REASON = "signed_out_reason"
    }
```
and in `onCreate`, immediately before `onBackPressedDispatcher.addCallback(this) { showExitDialog() }`:

```kotlin
        intent.getStringExtra(EXTRA_SIGNED_OUT_REASON)?.takeIf { it.isNotBlank() }
            ?.let { showError(it) }
```

- [ ] **Step 7: Strings and the Settings field**

Add to `strings.xml` after `hint_broker_password`:

```xml
    <string name="section_session_policy">Session</string>
    <string name="hint_auto_logout_minutes">Auto sign-out (minutes, 0 = never)</string>
    <string name="error_auto_logout_minutes">Enter 0–1440</string>
```
and after `station_offline_message` (before `</resources>`):

```xml
    <!-- Forced sign-out reason shown on Login (UI audit group (j)) -->
    <plurals name="signed_out_inactivity">
        <item quantity="one">Signed out after %1$d minute of inactivity.</item>
        <item quantity="other">Signed out after %1$d minutes of inactivity.</item>
    </plurals>
```

In `activity_settings.xml`, inside the Broker card's inner `LinearLayout`, immediately after the `tilBrokerPassword` `TextInputLayout` closes (`</com.google.android.material.textfield.TextInputLayout>` for the password, before the inner `</LinearLayout>`), insert:

```xml

                        <TextView
                            style="@style/SettingsSectionLabel"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="20dp"
                            android:text="@string/section_session_policy"
                            android:textColor="@color/primary_action" />

                        <com.google.android.material.textfield.TextInputLayout
                            android:id="@+id/tilAutoLogout"
                            style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="12dp"
                            android:hint="@string/hint_auto_logout_minutes"
                            app:boxStrokeColor="@color/outline_dark"
                            app:hintTextColor="@color/text_secondary_dark">

                            <com.google.android.material.textfield.TextInputEditText
                                android:id="@+id/etAutoLogout"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:imeOptions="actionDone"
                                android:inputType="number"
                                android:maxLength="4"
                                android:singleLine="true"
                                android:textColor="@color/text_primary_dark" />
                        </com.google.android.material.textfield.TextInputLayout>
```
Also change `etBrokerPassword`'s `android:imeOptions` from `actionDone` to `actionNext` (the auto sign-out field is now the last one).

- [ ] **Step 8: SettingsActivity — populate, validate and save the minutes (still under Save & Restart)**

In `onCreate`, after `binding.etBrokerUsername.setText(current.username)` insert:

```kotlin
        binding.etAutoLogout.setText(settingsRepository.autoLogoutMinutes().toString())
```

Replace the line `binding.etBrokerPassword.setOnSubmit { binding.btnSaveSettings.performClick() }` (from Task 5) with:

```kotlin
        binding.etAutoLogout.setOnSubmit { binding.btnSaveSettings.performClick() }
```

Inside the `btnSaveSettings` click listener, after the port check (`if (port == null) { ... return@setOnClickListener }`) insert:

```kotlin
            val autoLogoutMinutes = AutoLogout.parseMinutes(binding.etAutoLogout.text.toString())
            if (autoLogoutMinutes == null) {
                binding.tilAutoLogout.error = getString(R.string.error_auto_logout_minutes)
                return@setOnClickListener
            }
            binding.tilAutoLogout.error = null
            settingsRepository.saveAutoLogoutMinutes(autoLogoutMinutes)
            SessionGuard.applyTimeout()
```

- [ ] **Step 9: Build, install, verify**

```
.\gradlew.bat :app:assembleDebug --offline
.\gradlew.bat :app:testDebugUnitTest --offline
adb -s emulator-5558 install -r -g app\build\outputs\apk\debug\app-debug.apk
```
1. Settings → unlock → the Broker card now ends with a "SESSION" label and "Auto sign-out (minutes, 0 = never)" showing `15`. Enter `9999` → Save & Restart → inline "Enter 0–1440" under the field. Enter `1`, Save & Restart (relaunches to Login).
2. Badge-login, then do not touch the device for 65 s. Expected: Login screen with the red line "Signed out after 1 minute of inactivity." (singular — plurals). `adb logcat -s SessionGuard` shows `Signing out: ...`.
3. Badge-login again, tap the screen every 30 s for 90 s: no sign-out (touch defers). Open Settings, set `0`, save, badge-login, wait 90 s: still logged in (Review Focus 4).
4. Set it back to `15`.

- [ ] **Step 10: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station5aa/AutoLogout.kt app/src/main/java/com/mitas/ppnam/station5aa/InactivityMonitor.kt app/src/main/java/com/mitas/ppnam/station5aa/SessionGuard.kt app/src/main/java/com/mitas/ppnam/station5aa/SessionActivity.kt app/src/test/java/com/mitas/ppnam/station5aa/AutoLogoutTest.kt app/src/test/java/com/mitas/ppnam/station5aa/InactivityMonitorTest.kt app/src/main/java/com/mitas/ppnam/station5aa/SettingsRepository.kt app/src/main/java/com/mitas/ppnam/station5aa/ScannerApp.kt app/src/main/java/com/mitas/ppnam/station5aa/LoginActivity.kt app/src/main/java/com/mitas/ppnam/station5aa/MainActivity.kt app/src/main/java/com/mitas/ppnam/station5aa/SettingsActivity.kt app/src/main/res/layout/activity_settings.xml app/src/main/res/values/strings.xml
git commit -m "feat(session): inactivity auto sign-out with a reason on Login, configurable in Settings" -m "Closes static-05 / group (j) for Station 5 — Station 1's AutoLogout, InactivityMonitor, SessionGuard and SessionActivity ported; station-offline sign-out intentionally not ported." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 11: "Test & Apply" — validate, test in place, confirm, keep the session (Tier 3 #20, static-06, S5-07)

Closes: **static-06** (§5 "Settings action & field set": Test & Apply behaviour with S1's host/port validation and a visible "Connected — settings saved" confirmation; password toggle already present at `activity_settings.xml` `tilBrokerPassword`; auto sign-out field from Task 10), the remainder of **S5-07** (IME Done on the last field is the primary action, the keyboard closes and the status row is visible), §5 Diagnostics row order (already S1 order: MQTT Broker, Station 5, Version, Device ID — no change). The screen no longer relaunches `MainActivity`; the operator stays on Settings and keeps their session.

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/layout/activity_settings.xml` (`btnSaveSettings` text; status row after `groupSettingsFields`)
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/SettingsActivity.kt` (replace the save listener with `testAndApply()`; new helpers; `onDestroy`)

**Interfaces:**
- Consumes: `SettingsRepository.saveAutoLogoutMinutes`, `SessionGuard.applyTimeout`, `AutoLogout.parseMinutes`, `hideKeyboard()`, `relockPinGate()`, `setOnSubmit`, `MqttManager.disconnect(onComplete)`, `MqttManager.connect()`, `MqttManager.addConnectionListener/removeConnectionListener((Boolean) -> Unit)` (the listener is invoked once synchronously with the current state on add, then on every connect/disconnect).

- [ ] **Step 1: Strings**

Add to `strings.xml` after `error_auto_logout_minutes`:

```xml
    <!-- Test & Apply (UI audit §5 "Settings action"): test in place, stay on screen, keep the session -->
    <string name="btn_test_apply">Test &amp; Apply</string>
    <string name="settings_testing">Testing connection…</string>
    <string name="settings_apply_success">Connected — settings saved</string>
    <string name="settings_apply_failed">Could not connect to %1$s:%2$d within 10 s. Check the settings and try again.</string>
    <string name="error_host_required">Enter the broker host</string>
    <string name="error_port_invalid">Enter a port from 1 to 65535</string>
    <string name="error_broker_username_required">Enter the broker username</string>
    <string name="error_broker_password_required">Enter the broker password</string>
    <string name="error_password_store">Could not store the password securely</string>
```

- [ ] **Step 2: Layout — button text and status row**

Change `btnSaveSettings`'s text attribute from `android:text="Save &amp; Restart"` to:

```xml
                    android:text="@string/btn_test_apply"
```

Immediately after `groupSettingsFields`'s closing `</LinearLayout>` (and before the `<!-- ============ Session ... -->` comment of `groupSession`) insert the status row. It lives **outside** the unlockable group so the confirmation stays readable after the gate re-locks:

```xml

            <!-- Test & Apply result (UI audit §5): "Testing connection…" / green success / red failure -->
            <LinearLayout
                android:id="@+id/layoutApplyStatus"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:gravity="center_vertical"
                android:orientation="horizontal"
                android:visibility="gone"
                tools:visibility="visible">

                <ProgressBar
                    android:id="@+id/progressApply"
                    android:layout_width="20dp"
                    android:layout_height="20dp"
                    android:layout_marginEnd="8dp"
                    android:indeterminateTint="@color/accent_action"
                    android:visibility="gone"
                    tools:visibility="visible" />

                <TextView
                    android:id="@+id/tvApplyStatus"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:textColor="@color/text_muted"
                    android:textSize="15sp"
                    tools:text="Testing connection…" />
            </LinearLayout>
```

- [ ] **Step 3: SettingsActivity — replace Save & Restart with Test & Apply**

Add fields after `private val lockoutTicker = ...` (from Task 7):

```kotlin
    private lateinit var settingsRepository: SettingsRepository
    private val applyHandler = Handler(Looper.getMainLooper())
    private var pendingConnectionListener: ((Boolean) -> Unit)? = null
    private var applyTimeout: Runnable? = null

    private enum class ApplyStatus { HIDDEN, TESTING, SUCCESS, FAILED }

    private companion object {
        /** Single attempt, same budget as the login round trip (UI audit §5 "Timeout"). */
        const val APPLY_TIMEOUT_MS = 10_000L
        /** Station 2 re-locks the gate shortly after a successful apply. */
        const val RELOCK_DELAY_MS = 2_000L
    }
```

In `onCreate` change `val settingsRepository = SettingsRepository(this)` to:

```kotlin
        settingsRepository = SettingsRepository(this)
```

Replace the entire `binding.btnSaveSettings.setOnClickListener { ... }` block (originally lines 67–111, extended in Task 10) with:

```kotlin
        binding.btnSaveSettings.setOnClickListener { testAndApply() }
        // Typing again clears a field's inline error.
        listOf(binding.tilBrokerHost, binding.tilBrokerPort, binding.tilBrokerUsername,
            binding.tilBrokerPassword, binding.tilAutoLogout).forEach { til ->
            til.editText?.doAfterTextChanged { til.error = null }
        }
```
and add the import `import androidx.core.widget.doAfterTextChanged`.

Add these methods after `setupSessionSection`:

```kotlin
    /**
     * Station 1's validation (host, port) plus the credential and minutes checks, each shown
     * inline under its field. Returns null when something is wrong (focus moves to it).
     */
    private fun validatedInput(): Pair<BrokerSettings, Int>? {
        val host = binding.etBrokerHost.text.toString().trim()
        if (host.isBlank()) {
            binding.tilBrokerHost.error = getString(R.string.error_host_required)
            binding.etBrokerHost.requestFocus()
            return null
        }
        val port = BrokerSettings.parsePort(binding.etBrokerPort.text.toString())
        if (port == null) {
            binding.tilBrokerPort.error = getString(R.string.error_port_invalid)
            binding.etBrokerPort.requestFocus()
            return null
        }
        val username = binding.etBrokerUsername.text.toString().trim()
        if (username.isBlank()) {
            binding.tilBrokerUsername.error = getString(R.string.error_broker_username_required)
            binding.etBrokerUsername.requestFocus()
            return null
        }
        // Blank field keeps the already-provisioned password: the repository only writes a
        // non-blank password to the Keystore. With nothing stored either, there is nothing to test.
        val password = binding.etBrokerPassword.text.toString()
            .ifBlank { settingsRepository.brokerSettings().password }
        if (password.isBlank()) {
            binding.tilBrokerPassword.error = getString(R.string.error_broker_password_required)
            binding.etBrokerPassword.requestFocus()
            return null
        }
        val minutes = AutoLogout.parseMinutes(binding.etAutoLogout.text.toString())
        if (minutes == null) {
            binding.tilAutoLogout.error = getString(R.string.error_auto_logout_minutes)
            binding.etAutoLogout.requestFocus()
            return null
        }
        val settings = BrokerSettings(
            host = host,
            port = port,
            useWebSocket = binding.swBrokerWebSocket.isChecked,
            useTls = binding.swBrokerTls.isChecked,
            username = username,
            password = password,
        )
        return settings to minutes
    }

    /**
     * Test & Apply (UI audit §5): save, reconnect against the new broker and report the outcome
     * in place. The operator stays on this screen and keeps their session; nothing relaunches.
     */
    private fun testAndApply() {
        val (newSettings, minutes) = validatedInput() ?: return
        hideKeyboard()
        setApplyInFlight(true)
        showApplyStatus(ApplyStatus.TESTING)

        val mqtt = MqttManager.getInstance(this)
        cancelPendingApply(mqtt)

        // 1. Properly disconnect from the OLD broker first (publishes presence offline).
        mqtt.disconnect {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 2. Save the new settings after the old presence is offline.
                if (!settingsRepository.save(newSettings)) {
                    binding.tilBrokerPassword.error = getString(R.string.error_password_store)
                    showApplyStatus(ApplyStatus.HIDDEN)
                    setApplyInFlight(false)
                    mqtt.connect()
                    return@runOnUiThread
                }
                settingsRepository.saveAutoLogoutMinutes(minutes)
                SessionGuard.applyTimeout()
                // 3. Reconnect against the new broker and wait (once) for the verdict.
                awaitConnection(mqtt, newSettings)
            }
        }
    }

    private fun awaitConnection(mqtt: MqttManager, settings: BrokerSettings) {
        lateinit var listener: (Boolean) -> Unit
        listener = { connected ->
            if (connected) runOnUiThread {
                if (pendingConnectionListener === listener) {
                    cancelPendingApply(mqtt)
                    onApplyResult(connected = true, settings = settings)
                }
            }
        }
        val timeout = Runnable {
            if (pendingConnectionListener === listener) {
                cancelPendingApply(mqtt)
                onApplyResult(connected = false, settings = settings)
            }
        }
        pendingConnectionListener = listener
        applyTimeout = timeout
        applyHandler.postDelayed(timeout, APPLY_TIMEOUT_MS)
        // addConnectionListener fires once immediately with the current (disconnected) state,
        // which the listener ignores; the real verdict arrives on connect.
        mqtt.addConnectionListener(listener)
        mqtt.connect()
    }

    private fun cancelPendingApply(mqtt: MqttManager) {
        pendingConnectionListener?.let { mqtt.removeConnectionListener(it) }
        pendingConnectionListener = null
        applyTimeout?.let { applyHandler.removeCallbacks(it) }
        applyTimeout = null
    }

    private fun onApplyResult(connected: Boolean, settings: BrokerSettings) {
        if (isFinishing || isDestroyed) return
        setApplyInFlight(false)
        if (connected) {
            showApplyStatus(ApplyStatus.SUCCESS)
            applyHandler.postDelayed({ if (!isFinishing && !isDestroyed) relockPinGate() }, RELOCK_DELAY_MS)
        } else {
            showApplyStatus(
                ApplyStatus.FAILED,
                getString(R.string.settings_apply_failed, settings.host, settings.port)
            )
        }
    }

    private fun setApplyInFlight(inFlight: Boolean) {
        binding.btnSaveSettings.isEnabled = !inFlight
    }

    private fun showApplyStatus(status: ApplyStatus, message: String? = null) {
        binding.layoutApplyStatus.visibility =
            if (status == ApplyStatus.HIDDEN) View.GONE else View.VISIBLE
        binding.progressApply.visibility =
            if (status == ApplyStatus.TESTING) View.VISIBLE else View.GONE
        val (text, colorRes) = when (status) {
            ApplyStatus.HIDDEN -> "" to R.color.text_muted
            ApplyStatus.TESTING -> getString(R.string.settings_testing) to R.color.text_muted
            ApplyStatus.SUCCESS -> getString(R.string.settings_apply_success) to R.color.success
            ApplyStatus.FAILED -> (message ?: "") to R.color.danger
        }
        binding.tvApplyStatus.text = text
        binding.tvApplyStatus.setTextColor(getColor(colorRes))
    }
```

In `submitPin`, replace the `Unlocked` branch so a stale result row is cleared when the form is opened again:

```kotlin
            PinGate.Outcome.Unlocked -> {
                hidePinMessages()
                hideKeyboard()
                showApplyStatus(ApplyStatus.HIDDEN)
                binding.cardPinLock.visibility = View.GONE
                binding.groupSettingsFields.visibility = View.VISIBLE
            }
```

Replace `onDestroy`:

```kotlin
    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacks(lockoutTicker)
        cancelPendingApply(MqttManager.getInstance(this))
        applyHandler.removeCallbacksAndMessages(null)
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
```

Finally delete the now-unused `import android.content.Intent`? **No** — it is still used by the log-out dialog's `Intent(this, LoginActivity::class.java)`. Keep it.

- [ ] **Step 4: Compile and run all unit tests**

```
.\gradlew.bat :app:assembleDebug --offline
.\gradlew.bat :app:testDebugUnitTest --offline
```
Expected: `BUILD SUCCESSFUL` both times (SubmitKeys, LoginFailure, PinGate, AutoLogout, InactivityMonitor plus the five pre-existing classes).

- [ ] **Step 5: Install and verify the happy path**

```
adb -s emulator-5558 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5558 shell am start -n com.mitas.ppnam.station5aa/.SettingsActivity
```
Unlock (`079545`). The button reads "Test & Apply". Tap the broker Password field, type `test`, press the IME action (it is Next → moves to the minutes field); on the minutes field press Done: the keyboard closes, "Testing connection…" with a spinner appears under the form, then within ~2 s the green "Connected — settings saved"; the toolbar pill returns to "Connected"; after 2 s the form hides behind the PIN gate while the green line stays visible; **no** relaunch to Login/Main (closes S5-07, static-06).

- [ ] **Step 6: Verify validation, failure, logged-in and Back cases (Review Focus 1, 4, 5)**

1. Unlock, clear Host, Test & Apply → inline "Enter the broker host" under Host, nothing else happens. Restore `10.0.2.2`. Port `70000` → "Enter a port from 1 to 65535". Restore `9001`. Minutes `2000` → "Enter 0–1440". Restore `15`.
2. Fresh-install case (Review Focus 1): `adb -s emulator-5558 uninstall com.mitas.ppnam.station5aa`, reinstall, open Settings, unlock, fill host/port/WS/TLS/username but leave Password blank → Test & Apply → "Enter the broker password" inline, no spinner, no 10 s wait. Then type `test` → success.
3. Failure: set port `9002`, Test & Apply → spinner for 10 s, then red "Could not connect to 10.0.2.2:9002 within 10 s. Check the settings and try again."; the pill shows "Reconnecting"/"Offline"; the form stays unlocked so the port can be fixed. Set `9001`, Test & Apply → green again.
4. Logged in: badge-login from Login, open Settings from Main, unlock, set minutes `1`, Test & Apply → success, press Back → still on Main (session kept, not thrown to Login); wait 65 s → Login with "Signed out after 1 minute of inactivity." (Review Focus 4: a value applied while logged in takes effect). Set minutes back to `15`.
5. Back during testing (Review Focus 5): unlock, set port `9002`, Test & Apply, press Back within 2 s. Expected: Settings closes with the slide-out; `adb -s emulator-5558 shell logcat -d -s AndroidRuntime:E` shows no crash during the following 15 s; reopen Settings → pill "Reconnecting"; fix the port.

- [ ] **Step 7: Commit**

```
git add app/src/main/res/values/strings.xml app/src/main/res/layout/activity_settings.xml app/src/main/java/com/mitas/ppnam/station5aa/SettingsActivity.kt
git commit -m "feat(settings): Test & Apply — validate inline, test in place, confirm, keep the session" -m "Closes static-06 (§5 Settings action) and the remainder of S5-07 for Station 5." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 12: Full regression on the emulator and keyboard-matrix re-check

**Files:** none modified (fix-ups only if a check fails; commit them separately with `fix(ui): ...`).

- [ ] **Step 1: Clean build and tests**

```
.\gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest --offline
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Reinstall and reconfigure**

```
adb -s emulator-5558 uninstall com.mitas.ppnam.station5aa
adb -s emulator-5558 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5558 shell am start -n com.mitas.ppnam.station5aa/.LoginActivity
```
Configure the broker through Settings (PIN `079545`, `10.0.2.2`, `9001`, WS on, TLS off, `test`/`test`, Test & Apply → green).

- [ ] **Step 3: Re-run the §6 keyboard matrix rows for S5**

| Screen / field | Must be true | How |
|---|---|---|
| Login / Password | `btnLogin` bottom < IME top; error line (after a wrong password) above the fields and on screen; Enter submits once | keyboard check recipe + `keyevent 66` |
| Settings / PIN | Unlock visible; `tvPinError` above the IME top (≈1155); toolbar still on screen; Enter unlocks | recipe + `keyevent 66` with `079545` |
| Settings / Host … Auto sign-out | each field scrolls into view with the keyboard up (adjustResize); Done on the minutes field runs Test & Apply; status row visible after the keyboard closes | tap each field in turn, `dumpsys window \| findstr ITYPE_IME`, `uiautomator dump` |

Record the IME top value and the three button/error bounds in the final report.

- [ ] **Step 4: Walk every finding once**

- S5-01: wrong password → "Incorrect username or password." above the fields, Log In fully visible.
- S5-02: Enter on PIN unlocks; Done on the last Settings field applies; password IME key is Done.
- S5-03: Settings toolbar/pill/up-arrow visible with the keyboard up.
- S5-04: PIN error above the field, visible over the numeric pad.
- S5-05: Back on Login and on Main → "Close the app?" [Stay | Close].
- S5-07: form scrolls with the keyboard; Done applies.
- S5-08: "Log out" casing in Settings and both dialogs; amber switch tracks; Diagnostics says Offline/Reconnecting/Connected.
- S5-09: no rotation with `user_rotation 1` on all three screens (restore `0` afterwards).
- S5-10: wording unchanged ("No workflows" / "No workflows are enabled for this station yet.") — this is the shared standard S3 adopts.
- S5-11: settings tag ignored while Station 5 is backgrounded.
- group (c): lockout persists across Back/reopen and force-stop, ticks every second, blank Unlock ignored.
- group (j): 1-minute auto sign-out shows the reason on Login; 0 disables.
- static-12/15/23/26: gear on both screens, M3 dialogs, chip capped at the gear, `brand_tint` = `#E8C89D`.
- `adb -s emulator-5558 shell logcat -d -s AndroidRuntime:E` → empty.

- [ ] **Step 5: Final report**

`git log --oneline master..fix/ui-audit-2026-10-02` → eleven commits (Tasks 1–11). Report the branch, the commit list, the keyboard-matrix numbers from Step 3, and the "not covered" list below.

---

## Findings deliberately not covered by this plan

| ID | Why |
|---|---|
| S5-06 / static-20 (production broker defaults, group (k)) | Explicitly out of scope in the planning brief: broker/credential defaults in `BrokerSettings.kt:19-24` must stay exactly as they are. |
| static-07 (station-offline handling: S5's full-screen overlay + relaunch) | Out of scope ("station-offline overlay redesign"); `MainActivity.stationStatusListener` and `ScannerApp.checkStationStatus` untouched. |
| static-24 (operator dropdown on Login, §5 Login layout "operator dropdown") | S5 has no `requestOperatorList` MQTT round trip; adding it means a new request/response type on the S5 topics, which the brief forbids. Every other §5 Login-layout item (badge row, password toggle, empty-field check, error above the fields) is done. |
| static-09 (`values-night/themes.xml` purple regression) | Inferred for S5 but **not present**: the repo has no `values-night` folder. Verified; nothing to do. |
| S5-10 / static-14 (empty-state wording) | No change to S5 — S5's current copy is the shared standard; Station 3's plan adopts it. |
| static-10 (status-bar strip `#102233` vs `#07101A`), static-11 (button/card radii), static-13 (home chrome), static-16 (motion), static-22 (toolbar title 22 sp vs 18 sp) | Cross-stack token decisions with no single "Recommended standard" value in §5 beyond "one shared token set"; changing S5 alone would create a new inconsistency. Left for the shared-theme work (§7 item 22) once a value is chosen. The nav-bar colour (the only S5-specific part of group (h)) is done in Task 3. |
| §5 "Timeout seconds / wording" Retry button | S5 has no workflow requests; the only round trip is login, which already uses a 10 s single attempt (`AuthClient.REQUEST_TIMEOUT_MS`), now worded per S3 (Task 6), and the Log In button is the retry. |
| Session persistence across process restart (part of group (j)) | Out of scope per the brief ("leave as is"); only the inactivity timer and reason text were added. |
