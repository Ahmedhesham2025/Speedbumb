# Road-test guide

How to record test drives that tell us how well Bump Beeper finds speed bumps and potholes. No technical
knowledge needed. Each drive needs **two people: a driver and a passenger.**

## Safety first
- **The driver only drives.** The driver never touches the phone, never taps a label, never looks at the screen.
- **The passenger does all the tapping.** If the passenger is busy or unsure, skip the tap. A missing tap is
  much better than an unsafe one.
- Drive normally and legally. Do not hit bumps or potholes on purpose, and do not slow down or speed up "for the test".
- Set everything up **before** you start the car. If something goes wrong with the app, finish the loop and fix it
  when parked.

## What you need
- The phone(s) under test, charged, with the latest Bump Beeper test build installed.
- A phone holder fixed to the dashboard or windscreen. The phone must not slide or rattle.
- Over the whole test we want **4 different phones, 3 drivers and 3 kinds of vehicle** (e.g. small car, SUV/van,
  microbus or pickup). Use the **Mounted** placement (phone in the holder) for most drives; if you have time, one extra
  run per route with the phone in a **Cup holder** or a **Pocket** (pick the matching placement in Settings).

## Set up the app (once per phone, while parked)
1. Open **Settings → Debug recording** and switch on **Record raw sensor data while driving**.
2. Open **Settings → Road testing** and switch on **Label mode**.
3. In the same **Road testing** card, under **Where is the phone?**, pick **Mounted** (phone in a holder),
   **Cup holder** or **Pocket**. It is saved in the recording as `mounted`, `cupholder` or `pocket`.
4. Start a drive on the Drive tab (or let auto start do it when the car's Bluetooth connects). While recording,
   the Drive tab shows a panel **"PASSENGER: TAP WHAT YOU JUST DROVE OVER"** with big buttons:
   **Bump** · **Rough road**, then **Pothole left** · **Pothole right**, then **Undo**.
5. Label mode only works from the **next** recording. If you switched it on while a drive was already recording,
   stop the drive and start it again (the Drive tab says so, and a tap would show "Label not saved").

## What to tap, and when
Tap **right after the wheels hit** it — not when you first see it.
- **Bump**: a speed bump (مطب).
- **Rough road**: a broken or bumpy stretch that is not one single spot. Tap once when it starts.
- **Pothole left / Pothole right**: a hole (حفرة) under the left or right wheels of the car.
- **Undo**: tapped the wrong button, or tapped by mistake? Press **Undo** straight away; it removes your last tap.
- Tap every time you go over it, on every run, even if you tapped the same bump last time.
- Felt the car hit nothing? Don't tap.

## Routes and runs
- The owner picks **3 loop routes**, each about 10–20 minutes, with about **15 known hazards** (bumps and potholes,
  some on each side). Write each route down once (start point, turns) so every run follows the same roads.
- Drive **each loop 5 times** (5 runs × 3 routes). Runs can be on different days.
- Mix the times: some quiet runs and some in normal traffic.
- Stop the drive in the app at the end of each loop, so **one recording = one loop**.
- Aim: together, at least **600 km** of labelled driving.

## Export the recordings
When you are back home (not while driving):
1. **Settings → Debug recording → Export recordings.**
2. The files are saved in **Downloads/BumpBeeper/recordings** on the phone. The app keeps only the last few drives,
   so export after every test day.

## Upload
- Upload the files **only to the owner's private Google Drive folder** for road tests (the owner shares the link).
- **Never** upload recordings to GitHub, WhatsApp groups or anywhere public. They contain where you drove.
  Only the owner's team runs them through the anonymizer before anything goes into the project.
- Rename each file before uploading:
  `route<1-3>_run<1-5>_<driver initials>_<phone model>_<placement>_<yyyy-mm-dd>.csv`
  for example `route2_run3_AH_A51_mounted_2026-10-12.csv` (placement: `mounted`, `cupholder` or `pocket`).
- In the folder's notes sheet, add one line per file: vehicle type, weather, traffic, anything odd
  ("forgot to tap at the third bump", "phone fell off at the end").

## After upload (project team)
1. Score each route with all its runs together, oldest first, on one shared map:
   `replay --trace route2_run1.csv --trace route2_run2.csv … --out metrics.json` (definitions: `metrics.md`).
   One run alone starts with an empty map, so it says almost nothing about false warnings or learning.
2. Only anonymized data goes into `testdata/`: `anonymize --in raw.csv --out anon.csv` (tools/replay).
   - Commit **short segments** only (the stretch around one failure, not a whole loop): the shape of a long track
     could still be matched to real streets. Exception: full-loop drives may be committed when trimmed ≥1.5 km at
     each end and rotated, owner-approved 2026-10-03 (example: `testdata/README.md`, drive01_pocket).
   - Name the anonymized file **without driver initials or dates**, e.g. `route2_run3_A51_mounted_seg1.csv`. The
     anonymizer writes exactly the `--out` name it is given, so choosing a clean name is up to you.
3. Each real failure becomes an issue for the engine agent, with the anonymized trace segment attached.
