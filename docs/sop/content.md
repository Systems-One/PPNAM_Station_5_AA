---
app_name: Station 5
file_stem: Station_5
station: Station 5 cage loading
mark: "5"
accent: "#A85F14"
accent_tint: "#E8C89D"
icon: images/icon.png
version: 1.5.0
date: 2026-11-06
device: Chainway C72
standfirst: >-
  How to load weighed Packs into a cage one tag scan at a time, complete the
  cage, and what to do when Station 5 refuses a scan.
description: Standard operating procedure for the PPNAM Station 5 cage-loading handheld app 1.5.0 on the Chainway C72.
crop: {top: 72, bottom: 126}
revisions:
  - version: 1.5.0
    date: 2026-11-06
    change: "Suite release: visual refresh, consistency pass, scan sounds. First Station 5 handheld SOP."
---

<!-- Screenshots: v1.5.0 (7) debug build of master on the c72_clone emulator, against
     tools/station5-sim (broker on 1887, control on 8792). Cage tags, Pack labels and the
     operator "sim" are simulator demo data. -->

# Purpose and scope {#purpose}

The Station 5 app runs on the Chainway C72 handheld in the cage-loading area (Zone 8). Operators use it to **load weighed Packs into a cage** and to **complete the cage** when it is full. Every scan is checked by the Station 5 desktop, which keeps the cage's Pack list, count and weight.

## What it does

- Arms a cage when you scan its RFID tag, then adds **one** Pack to it when you scan the Pack's QR label.
- Shows the cage's Pack count, total weight and the Packs already in it, as Station 5 records them.
- Completes the cage after you confirm a summary, and shows the SAP receipt and manifest print status.
- Stops you, full screen, when a Pack must not go into the cage.

## What it does not do

- It does not weigh Packs or release them from QC. Those happen at the weigh station and on the desktop.
- It does not post to SAP or print the manifest. The Station 5 desktop does both after you complete the cage.
- It cannot take a Pack out of a cage. Corrections are made on the Station 5 desktop.

> [!NOTE] Who this is for
> Cage-loading operators at Station 5. Sections marked **Supervisor** are for the shift supervisor, who holds the settings PIN.

# Before you start {#before}

- [ ] The handheld is charged. Put it back on its charger at the end of the shift.
- [ ] You are on the plant Wi-Fi, in coverage at the cages.
- [ ] You know your Station 5 username and password.
- [ ] The Station 5 desktop is running. The status pill shows **Connected** (section 3).
- [ ] Each cage has its RFID tag, and each Pack has its QR label.

::: step Opening the app
shot: 01_login_empty.png
caption: The Log In screen.
alt: Station 5 Log In screen with Username and Password fields, an amber Log In button and "or scan your badge"
The handheld starts in the **PPNAM Station Launcher**. Tap the amber **Station 5** tile.

The app opens on **Log In**, or straight on the cage screen if you are still signed in. Wait for the status pill in the top-right corner to settle on **Connected** before you start.
:::

# The status pill and header {#status}

The coloured pill in the top-right corner is on every screen. It reports two links: the **network link** (the broker that carries messages) and **Station 5 itself** (the desktop). It waits 1.5 seconds before it changes, so a short blip does not make it flicker.

::: pills
go | Connected | The network is up and the Station 5 desktop is running. Everything works. | Carry on.
warn | Reconnecting | The handheld lost the network link and is getting it back. It retries every 5 seconds. | Wait. If it does not clear, move into Wi-Fi coverage.
warn | Station 5 offline | The network is fine, but the Station 5 desktop is not running or not reachable. The cage screen is covered by a warning. | Do not load Packs. Wait for **Connected** and tell your supervisor if it lasts.
stop | Offline | The handheld is not trying to connect: it has no broker account set up yet, or a supervisor is applying new settings. | Ask your supervisor to set up the broker in Settings.
:::

::: shots
10_cage_idle.png | Connected: ready for a cage tag. | Cage screen with a Connected pill and "Scan a cage tag"
20_station_offline.png | Station 5 offline: the cage screen is blocked. | Warning screen "Station 5 offline — don't load Packs" and "Waiting for the Station 5 desktop…"
02_login_unprovisioned.png | Offline: no broker account set up yet. | Log In screen with a red Offline pill
:::

::: step The header
shot: 12_cage_armed.png
caption: Your name and role, Settings, the pill, and the cage you are loading.
alt: Cage screen header with "sim · Operator", the Settings gear, a Connected pill and an amber LOADING CAGE CAGE-001 banner
- **Your name and role** sit under the logo. Tap them to log out (section 10).
- The **gear** opens Settings.
- Below the header, the amber **LOADING CAGE** banner names the open cage and the job of its last Pack.
:::

# Signing in {#signin}

Every Pack you load is recorded against your name, so the app does nothing until you sign in.

::: step By username and password
shot: 05_login_logging_in.png
caption: The spinner shows while Station 5 checks your password.
alt: Log In screen with username sim, a masked password and a spinner on the Log In button
1. Tap **Username** and type your username. Once the station has sent its list, an arrow lets you pick your name instead (each row reads *username — Your Name*).
2. Tap **Password** and type your password. The eye icon shows what you typed.
3. Tap **Log In**. A spinner replaces the button while Station 5 checks your password, and the fields are locked.
:::

::: step Badge sign-in is not available yet
shot: 07_login_badge_refused.png
caption: A badge read is refused by the current Station 5 desktop.
alt: Log In screen with the red message "Login was refused by the station (invalid_request). Ask a supervisor."
The screen says *or scan your badge*, and a badge read is sent to Station 5, but the current Station 5 desktop does not accept badge sign-in. It answers *Login was refused by the station (invalid_request). Ask a supervisor.*

Sign in with your username and password.
:::

::: step If sign-in fails
shot: 06_login_error.png
caption: The reason appears in red above the fields.
alt: Log In screen with the red message "Incorrect username or password."
The reason appears in red at the top of the card. Typing in either box hides it again. The common ones:

- **Incorrect username or password.** Retype them carefully.
- **Station 5 did not respond. Check the station and retry.** No answer within 10 seconds. Check the pill.
- **Not connected to the broker. Check Settings and try again.** The handheld has no network link.
- **Please fill in all fields** Both boxes are needed.

Every sign-in message is listed in section 8.
:::

::: step Signed out by the app
shot: 08_login_session_ended.png
caption: Why you were signed out is shown on Log In.
alt: Log In screen with the red message "Your session ended. Sign in and rescan the cage."
- If nobody touches the handheld or scans for **15 minutes**, the app signs you out with *Signed out after 15 minutes of inactivity.* Your supervisor can change the time. Time with the app in the background counts too.
- If Station 5 ends your session during a scan, you see *Your session ended. Sign in and rescan the cage.*

Sign in again and scan the cage tag again.
:::

# Loading a cage {#workflow-loading}

The rule is **one tag scan arms one Pack**. Scan the cage's RFID tag, then scan **one** Pack's QR label within two minutes. Repeat for every Pack. Station 5 checks each Pack before it is added.

::: step Scan the cage tag
label: Step 1
shot: 10_cage_idle.png
caption: Nothing is armed yet.
alt: Cage screen with a dashed box reading "Scan a cage tag"
Hold the handheld to the cage's RFID tag and pull the trigger. The step box briefly reads **Sending…** while Station 5 answers.

If you scan a Pack first, the app stops you with *Do NOT load this Pack yet: Scan the cage tag first, then scan this Pack again.*
:::

::: step Scan ONE Pack QR now
label: Step 2
shot: 12_cage_armed.png
caption: Armed for one Pack, with the time left.
alt: Amber banner LOADING CAGE CAGE-001, tiles 0 Packs and 0.00 kg, and an amber box "Scan ONE Pack QR now" with a 2:00 countdown
When Station 5 accepts the tag you hear the success chirp. The cage banner turns amber and the box reads **Scan ONE Pack QR now** with a countdown from **2:00**.

Scan the QR label of the **one** Pack you are putting in the cage. The tiles show the cage's Pack count and total kg as Station 5 records them.
:::

::: step The Pack is added
label: Step 3
shot: 13_pack_added.png
caption: Added, and the cage is disarmed again.
alt: Green card "Added S5QA-20261102-S001 · 1.42 kg · Pass", 1 Pack 1.42 kg, and the box "Scan the cage tag / One tag scan arms one Pack"
You hear the success chirp and a green card reads, for example, *✓ Added S5QA-20261102-S001 · 1.42 kg · Pass*. **Pass** means it passed QC; **Manager accepted** means a manager released it.

Put the Pack in the cage. The arm is now spent: the box goes back to **Scan the cage tag** / *One tag scan arms one Pack*.
:::

::: step Repeat for every Pack
label: Step 4
shot: 15_pack_list.png
caption: The Packs already in this cage, newest first.
alt: List under "Packs in this cage" showing Bag IDs, job and outer code, and weights
Scan the cage tag, then one Pack, for each Pack. Scroll down to see **Packs in this cage**: each row shows the Bag ID, *Job · outer code* and the weight.

If you scan a Pack that is already in this cage, you hear the chirp and see *S5QA-… is already in this cage.* Nothing changes.
:::

::: step If the two minutes run out
shot: 16_arm_expired.png
caption: The arm expired before a Pack was scanned.
alt: Amber notice "Cage tag expired — scan it again" above the cage banner
When the countdown reaches 0:00 the cage disarms and an amber notice reads *Cage tag expired — scan it again.* Nothing was sent. Scan the cage tag again, then the Pack.
:::

## When a Pack is refused

::: step Do NOT load this Pack
shot: 17_refusal_qc_required.png
caption: A refusal covers the whole screen until you tap Got it.
alt: Full-screen refusal with a red cross, "Do NOT load this Pack", "This Pack is waiting for QC on the desktop." and "Code: qc_required", with a Got it button
A refusal fills the screen with a red **✕**, a headline, what to do, and a code. You hear the fail buzz. **Do what the headline says.** For *Do NOT load this Pack*, keep that Pack out of the cage and put it aside.

Tap **Got it** to carry on. Scans are blocked until you do. Section 8 lists every refusal.
:::

::: shots
18_refusal_unknown_cage.png | An unregistered cage tag. Ask a Manager. | Refusal "Cage not recognised", "This cage tag isn't registered — ask a Manager.", Code: unknown_cage
19_refusal_label_not_recognised.png | A label that is not a Pack QR. | Refusal "Do NOT load this Pack", "Label not recognised. Scan the Pack's QR label again or put it aside for checking.", Code: local_invalid_qr
11_refusal_scan_cage_first.png | A Pack scanned before the cage tag. | Refusal "Do NOT load this Pack yet", "Scan the cage tag first, then scan this Pack again.", Code: local_cage_scan_required
:::

> [!NOTE] A bad label does not use up the arm
> *Label not recognised* is decided on the handheld, so nothing is sent and the countdown carries on after **Got it**. Scan the right label before it runs out.

## While Station 5 is busy or offline

::: step Station 5 offline
shot: 20_station_offline.png
caption: Do not load Packs while this shows.
alt: Warning screen "Station 5 offline — don't load Packs", "Waiting for the Station 5 desktop…"
If the desktop goes offline, the cage screen is covered by *Station 5 offline — don't load Packs* / *Waiting for the Station 5 desktop…*. Scans play the fail buzz and do nothing. The header stays usable, so you can still log out.

Wait for **Connected**. A scan that was already on its way is kept and sent again until Station 5 answers.
:::

::: step Paused or busy
shot: 21_paused_retrying.png
caption: The station is paused. The app keeps retrying.
alt: Amber notice "Station 5 is paused — retrying…" above the cage banner, with Sending… in the step box
*Station 5 is paused — retrying…* or *Station 5 is busy — retrying…* means the desktop has your scan but cannot act on it yet. The app resends it every 10 seconds; wait for the answer.

While a scan shows **Sending…**, a scan of a **different** code plays the fail buzz with *Wait for Sending… to finish, then scan again.* A repeat read of the same code is ignored.
:::

> [!WARNING] After a pause, rescan the cage
> While Station 5 is paused, a Pack scan still uses up the arm. When it resumes you may see *Scan the cage tag first*: scan the cage tag again, then the Pack.

# Completing a cage {#workflow-complete}

When the cage is full, complete it. After that no more Packs can be added, and the Station 5 desktop posts the SAP receipt and prints the manifest.

::: step Tap Complete cage
label: Step 1
shot: 14_pack_added3.png
caption: Complete cage appears once the cage holds a Pack.
alt: Cage screen with 4 Packs, 20.83 kg, the last Pack added, and an amber-outlined Complete cage button
**Complete cage** sits under the step box. It shows only when the cage is open, holds at least one Pack, and nothing is sending. Tap it.
:::

::: step Check the summary
label: Step 2
shot: 22_complete_sheet.png
caption: The summary to check before completing.
alt: Sheet "Complete CAGE-001?", "5 Packs · 28.95 kg", job line, "Once completed, no more Packs can be added.", "Summary expires in 1:58", Keep loading and Complete cage buttons
A sheet asks *Complete CAGE-001?* and shows the Pack count, total weight and a job line, with *Once completed, no more Packs can be added.*

- Check the count and weight against the cage.
- Tap **Complete cage** to confirm, or **Keep loading** to go back without changing anything.
- The summary is valid for two minutes (*Summary expires in 1:58*). If it runs out you see *Summary expired — tap Complete cage again.*

Scans are ignored while the sheet is open.
:::

> [!NOTE] The job line shows one job
> If the cage holds Packs from more than one job, the summary's job line still shows a single job. Check the count and weight, not the job line.

::: step The cage is completed
label: Step 3
shot: 24_cage_completed_posted.png
caption: Completed, posted to SAP and printed.
alt: Green card "CAGE-001 completed · 5 Packs · 28.95 kg · 14:59", "SAP receipt: Posted to SAP", "Manifest print: Printed", a Refresh button and "Scan the next cage tag"
A green card confirms it, for example *✓ CAGE-001 completed · 5 Packs · 28.95 kg · 14:59*, with *Completed on the station. SAP and printing are handled by the desktop.* Completing makes no sound.

**SAP receipt** and **Manifest print** show how the desktop is getting on. Tap **Refresh** to update them. Amber text means the desktop needs attention: tell your supervisor. Then scan the next cage tag.
:::

Table: SAP receipt and manifest print status
| Status | What it means |
|---|---|
| Not sent yet / Not printed yet | The desktop has not started. |
| Pending | Waiting in the desktop's queue. |
| Sending to SAP / Printing | In progress. |
| Waiting for receipt | The manifest prints after SAP confirms the receipt. |
| Posted to SAP / Printed | Done. |
| Failed — check the desktop | It failed. Tell your supervisor. |
| Unconfirmed — check the desktop | The desktop could not confirm it. Tell your supervisor. |
| Needs review on the desktop | A person must check it on the desktop. |

# Scan sounds and vibration {#sounds}

Every PPNAM handheld uses the same two sounds. The Chainway scanner beeps by itself when it reads anything; the app's sound comes **after** that, when Station 5 has answered.

- **Success chirp** (a bright rising two-note chirp) with one short 40 ms vibration: the scan was **accepted**.
- **Fail buzz** (a low falling double buzz) with two 200 ms vibrations: the scan **failed**. Read the screen.

The sounds play at the **media volume**. If you cannot hear them, turn the media volume up; the vibration still tells you.

Table: What you hear in the Station 5 app
| When | Outcome | Sound |
|---|---|---|
| Cage tag | Station 5 accepts the cage and it is open for loading | {ok} |
| Cage tag | Refused (unknown cage, cage not available), or accepted with no open load | {fail} |
| Pack QR | Added to the cage, or already in this cage | {ok} |
| Pack QR | Refused by Station 5 (*Do NOT load this Pack* and others) | {fail} |
| Pack QR | Scanned before the cage tag, or the label is not a Pack QR | {fail} |
| Any scan while *Sending…* | A different code | {fail} |
| Any scan while *Sending…* | The same code again (a repeat read) | {none} |
| Any scan while *Station 5 offline* shows | Refused on the handheld | {fail} |
| Any scan while a refusal screen or the summary sheet shows | Ignored | {none} |
| *Station 5 is paused / busy — retrying…* | Waiting for the final answer | {none} |
| Your session ended during a scan | Signed out | {fail} |
| Complete cage, Keep loading, Refresh, Got it | Buttons | {none} |
| Log In, Settings | Signing in, settings | {none} |

## The repeat-read rule

One pull of the trigger can read the same tag several times. A repeat of the code that is **already sending**, or of a code **just refused on the handheld** within 1.5 seconds, makes no sound. A different code plays the fail buzz once. When Station 5 answers, you hear exactly one sound for that scan.

# Messages and what to do {#messages}

Every message the app shows, word for word. *Ask a Manager* or *Ask a supervisor* means the problem cannot be fixed on the handheld.

Table: Signing in
| Message | What it means | What to do |
|---|---|---|
| Please fill in all fields | The username or password box is empty. | Fill in both. |
| Incorrect username or password. | Station 5 did not accept the password. | Retype carefully. Ask your supervisor if you are locked out. |
| Station 5 did not respond. Check the station and retry. | No answer within 10 seconds. | Check that the desktop is running, then retry. |
| Not connected to the broker. Check Settings and try again. | The handheld has no network link. | Check the pill and Wi-Fi, then retry. |
| Login was refused by the station (*code*). Ask a supervisor. | Station 5 refused for another reason, for example `processing_paused`, or `invalid_request` for a badge. | Use your username and password. Tell your supervisor the code if it repeats. |
| Login failed. Try again. | Sign-in failed without a reason. | Try again. |
| Station sent an incomplete / invalid / unusable authentication challenge | The station's answer could not be used. | Retry. Report it if it repeats. |
| Station's authentication challenge did not match this device's request | The answer was for another request. | Retry. |
| Station failed authentication verification — this response is not trusted | The answer could not be verified. | Do not keep retrying. Tell your supervisor. |
| Station accepted the login but issued no session / Station closed this session immediately | The desktop did not open a session. | Retry, then tell your supervisor. |
| Signed out after *N* minutes of inactivity. | The automatic sign-out ran. | Sign in again. |
| Your session ended. Sign in and rescan the cage. | Station 5 ended your session during a scan. | Sign in, then scan the cage tag again. |

Table: Refusal screens (tap Got it after doing what it says)
| Headline and message | Code | What to do |
|---|---|---|
| Do NOT load this Pack yet: Scan the cage tag first, then scan this Pack again. | `local_cage_scan_required` | Scan the cage tag, then the Pack. |
| Do NOT load this Pack: Label not recognised. Scan the Pack's QR label again or put it aside for checking. | `local_invalid_qr` | Scan the QR label, not another barcode. Put a damaged Pack aside. |
| Do NOT load this Pack: *Bag ID* is already in another cage. Put it aside and check it on the desktop. | `pack_allocated` | Put it aside; a supervisor checks the desktop. |
| Do NOT load this Pack: This Pack hasn't been weighed. Take it back to the weigh station. | `pack_not_found` | Take it to the weigh station. |
| Do NOT load this Pack: This Pack is waiting for QC on the desktop. | `qc_required` | Put it aside until QC releases it. |
| Do NOT load this Pack: Label doesn't match the weighed Pack. Put it aside for checking. | `invalid_barcode`, `outer_code_changed` | Put it aside for checking. |
| Do NOT load this Pack: Job card on this label doesn't match. Put it aside for checking. | `production_order_…` | Put it aside for checking. |
| Do NOT load this Pack: This Pack's outer code doesn't match the cage. Use a matching cage. | `mixed_outer_code` | Load it into a cage for that outer code. |
| Scan the cage tag first: Scan the cage tag again, then scan this Pack within two minutes. | `cage_scan_required` | Scan the cage tag, then the Pack. |
| Cage not recognised: This cage tag isn't registered — ask a Manager. | `unknown_cage` | Use another cage and tell a Manager. |
| Cage not available: This cage is already completed. | `cage_locked` | Use another cage. |
| Cage not available: This cage is no longer active on this handheld. Rescan the cage tag. | `cage_not_active`, `cage_context_changed` | Scan the cage tag again. |
| Cannot complete: Add Packs before completing. | `empty_cage`, `completion_blocked` | Load at least one Pack first. |
| Cage not completed: The summary expired. Tap Complete cage again. | `confirmation_expired` | Tap **Complete cage** again. |
| Cage not completed: Another Pack was added to this cage. Check the new count and tap Complete cage again. | `cage_contents_changed` | Check the new count, then complete again. |
| Check the handheld's clock: The handheld's time is wrong. Ask a supervisor to correct it, then scan again. | `request_expired` | Ask a supervisor to set the clock. |
| Not accepted: This request clashed with an earlier one. Note the time and contact support. Ref: … | `message_id_conflict` | Note the time and the Ref; contact support. |
| Not accepted (or Cage not completed): *the station's reason* | any other code | Read the reason; tell a Manager. |

Table: Notices above the cage
| Message | What it means | What to do |
|---|---|---|
| Cage tag expired — scan it again | Two minutes passed without a Pack. | Scan the cage tag again. |
| *Bag ID* is already in this cage. | The Pack was already loaded here. | Nothing; carry on. |
| Wait for Sending… to finish, then scan again. | You scanned another code while one was sending. | Wait, then scan again. |
| Station 5 is paused — retrying… / Station 5 is busy — retrying… | The desktop cannot act yet. | Wait; the app retries every 10 seconds. |
| Summary expired — tap Complete cage again | The completion summary timed out. | Tap **Complete cage** again. |
| Tap Complete cage again to see the new count. | The cage changed before you confirmed. | Tap **Complete cage** again. |
| *Bag ID* was recorded in *cage* | After a new sign-in: your last Pack did get in. | Nothing. |
| Last Pack *Bag ID* was not confirmed. Rescan cage *cage*, then rescan that Pack. | After a new sign-in: the last Pack's outcome is unknown. | Rescan the cage, then that Pack. |
| Cage *cage* is still open. Rescan the cage tag to review. | After a new sign-in: your cage is still open. | Scan the cage tag. |
| Couldn't confirm the last scan. Rescan the cage tag. | After a new sign-in. | Scan the cage tag. |

Table: Settings
| Message | What it means | What to do |
|---|---|---|
| Incorrect PIN. *N* attempts left before lockout. | Wrong PIN. | Supervisor only. |
| Too many attempts. Try again in *N* s. | Five wrong PINs lock the box for 30 seconds. | Wait for the countdown. |
| Enter the broker host / Enter a port from 1 to 65535 / Enter the broker username / Enter the broker password / Enter 0–1440 | A field is missing or out of range. | Supervisor: fill in the field. |
| Could not store the password securely | The handheld could not save the password. | Try again; report it if it repeats. |
| Saved, but could not connect to *host*:*port*. Check the broker settings. | The settings were saved but do not connect. | Supervisor: check the values. |

# Settings {#settings}

The gear opens Settings from Log In or the cage screen. The top half is open to everyone and is what support will ask you to read out. The bottom half needs the supervisor PIN.

::: step Diagnostics: anyone
shot: 26_settings_applied.png
caption: Diagnostics, and the PIN gate below.
alt: Settings Diagnostics: MQTT Broker Connected, Station 5 Online, Version v1.5.0 (7), Device ID, and the supervisor PIN box
- **MQTT Broker**: the network link on its own (Connected, Reconnecting, Offline).
- **Station 5**: the desktop (Online, Offline, Unknown). Broker *Connected* with Station 5 *Offline* means the network is fine and the desktop is the problem.
- **Version**: `v1.5.0 (7)` for this release.
- **Device ID**: this handheld's identity, such as `scanner_59fcb3c4129d`. Read it out when you report a fault.
- **Signed in as** and **Log out** appear at the bottom when you opened Settings while signed in.
:::

> [!SUPERVISOR] Configuration
> Type the supervisor PIN and tap **Unlock**. Five wrong attempts lock the PIN box for 30 seconds, and the count survives leaving Settings. A supervisor's settings tag also opens Settings from any screen. Never tell an operator the PIN; operators should ask the supervisor.

::: step Unlocked: supervisors only
shot: 27_settings_unlocked.png
caption: Unlocked, showing the standard broker settings on a new handheld.
alt: Unlocked Settings: Host mqtt.sysone.co.za, Port 443, Use WebSocket on, Use TLS on, empty Username and Password, Session section
- **Host** and **Port**: normally `mqtt.sysone.co.za` and `443`.
- **Use WebSocket** and **Use TLS**: both on for the standard setup.
- **Username** and **Password**: the broker account, not an operator account. A new handheld has none, so it shows **Offline** until they are filled in. The password box is always empty; leave it blank to keep the saved one.
- **Auto sign-out (minutes, 0 = never)**: 15 by default, up to 1440.

**Test & Apply** saves the settings, shows *Testing connection…* and reconnects. *Connected — settings saved* means it worked, and the section locks again after two seconds. Otherwise it says *Saved, but could not connect to host:port. Check the broker settings.*
:::

# Finishing up {#finishing}

::: step Handing the handheld over
shot: 30_logout_dialog.png
caption: Log out asks first.
alt: Log out? dialog reading "You'll need to log in again to continue." with Cancel and Log out
1. Make sure nothing shows **Sending…**.
2. Tap your name in the header (or **Log out** in Settings).
3. Confirm **Log out**.

If a scan is still being sent, the dialog asks *A scan is still being sent. Sign out anyway?* Tap **Cancel** and wait for the answer. If you sign out anyway, the app checks that scan at the next sign-in and tells you what to rescan.

Always log out before you pass the handheld to someone else. Otherwise their Packs are recorded under your name.
:::

::: step Closing the app
shot: 31_exit_dialog.png
caption: Back from Log In or the cage screen.
alt: Close the app? dialog reading "You'll leave PPNAM Station 5 and return to the home screen." with Stay and Close
From Log In or the cage screen, the **Back** key asks *Close the app?*. Tap **Close** to return to the Launcher, or **Stay**.

At the end of the shift, log out and put the handheld on its charger.
:::

# Troubleshooting {#troubleshooting}

Table: Common problems
| Problem | Likely cause | What to do |
|---|---|---|
| *Station 5 offline — don't load Packs* covers the screen. | The desktop is off or its program is not running. | Do not load Packs. Wait for **Connected**; tell your supervisor. |
| The pill stays **Reconnecting**. | No Wi-Fi where you are, or wrong broker settings. | Move into coverage. If it stays, your supervisor checks Settings. |
| The pill is red **Offline** on a new handheld. | No broker account is set up. | Your supervisor fills in Settings. |
| Every Pack says *Scan the cage tag first*. | The arm expired, was spent, or a pause used it up. | Scan the cage tag immediately before each Pack. |
| **Sending…** does not clear. | The answer was lost. The app resends every 10 seconds. | Wait. If it lasts, check the pill. |
| **Complete cage** is missing. | The cage has no Packs, or a scan is still sending. | Load a Pack, or wait for Sending… to clear. |
| *Failed — check the desktop* after completing. | SAP or the printer failed on the desktop. | Tell your supervisor. The cage is still completed. |
| No scan sounds. | Media volume is down. | Turn up the media volume. The vibration still works. |
| A badge does not sign you in. | Station 5 does not accept badge sign-in. | Use your username and password. |
| Signed out unexpectedly. | 15 minutes without a touch or scan, or Station 5 ended the session. | Sign in again and rescan the cage tag. |

> [!NOTE] Reporting a fault
> Give your supervisor the **Device ID** and **Version** from Settings, the time, the cage and Bag ID, and the exact message and code on screen.
