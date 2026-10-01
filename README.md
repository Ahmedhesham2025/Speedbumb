# Bump Beeper

Android app that learns the speed bumps on your routes and warns you about them.

- **First pass over a bump:** the phone feels the jolt and saves the spot. No beep.
- **Every pass after that:** 2 beeps about 6 seconds before you reach it (3 beeps above 50 km/h). It keeps recording while it beeps.
- **Keeps improving:** each pass refines the bump's position. A spot you pass 3+ times but feel less than half the time is muted automatically, and **Mute last beep** silences a false alarm with one tap.

Android 10 or newer. No internet, no account: everything stays on the phone.

---

## Get it onto your phone

### Option A: Android Studio (recommended)
1. Install [Android Studio](https://developer.android.com/studio) and open this folder (**File → Open → BumpBeeper**). Let it sync; it downloads what it needs the first time.
2. On the phone, turn on **Developer options → USB debugging** (tap *Build number* 7 times in *Settings → About phone*).
3. Plug the phone in and press the green **Run ▶** button.

To get an APK file instead: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The file lands in `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the phone and open it (allow *Install unknown apps*).

### Option B: no Android Studio (GitHub builds it)
1. Create an empty GitHub repository and push this folder to it.
2. GitHub builds it automatically (**Actions** tab → *Build APK*). This takes about 5 minutes.
3. Open the finished run → **Artifacts → BumpBeeper-apk**, unzip it, and install `app-debug.apk` on the phone.

---

## First drive
1. Put the phone in a **holder**. A loose phone in a cup holder gives noisy readings.
2. Open the app → **Test beep**. If you hear nothing, raise the media volume, or tick *Loud beeps*.
3. Tap **Battery settings** and allow background running. Xiaomi, Oppo, Realme and Samsung phones kill background apps aggressively; on those also set the app's battery mode to *No restrictions*.
4. **Start recording**, then drive. The screen can be off.
5. Watch the **jolt meter** now and then (or have a passenger watch it): bumps should poke above the dashed line, and normal driving should stay below it. If rough asphalt keeps crossing the line, choose **Low**; if real bumps don't reach it, choose **High**.

---

## How it works

```
accelerometer 50/s ─┐
                    ├─► BumpEngine ─► new bump? save it
GPS 1/s ────────────┘        │        known bump? +1 hit, refine position
                             └──────► known bump ahead, same direction, close enough? BEEP
```

| File | What it does |
|---|---|
| `BumpEngine.kt` | **The brain.** Pure Kotlin, no Android, so it runs in tests on a laptop. |
| `BumpService.kt` | Runs in the background, feeds the sensors into the engine, plays the beeps. |
| `BumpDb.kt` | SQLite on the phone: `bumps`, `events`, `trips`. |
| `MainActivity.kt` | The screen. |
| `Beeper.kt` | Generates the beep tone. It plays like navigation voice (car Bluetooth, music ducks). |
| `app/src/test/…/Simulator.kt` | Fake drives (road noise, braking, GPS lag and error, phone tilted in a holder) used to test the engine. |

**Detecting a bump.** The phone can sit at any angle, so the engine first works out which way is "up". It averages the accelerometer over about 1 second; that average is gravity. It then projects each reading onto "up" and subtracts gravity, which leaves the pure vertical jolt. A jolt counts as a bump when:
- it is above the threshold (3.0 m/s², about 0.3 g, on *Normal*),
- the car is doing 3–50 km/h,
- the phone isn't being picked up (the engine compares a fast and a slow estimate of "down"; if they disagree by more than 25°, the phone is moving).

**Where it is.** GPS arrives once a second, so the last fix is moved forward by speed × time to the moment of the jolt. Each later hit averages the position a little more.

**Same bump or new?** A jolt within 20 m of a known bump, travelling within 45° of the same direction, is that bump. Direction matters on divided roads: the bump on the other carriageway is a separate entry, learned the first time you drive that way.

**When to beep.** On every GPS fix the engine checks each known bump. It beeps for one that is:
- ahead of you (within 45° of your heading),
- no more than 20 m sideways from your path, which ignores parallel service roads,
- facing your direction of travel,
- within `speed × 7 s` (at least 40 m, at most 250 m).

**Counting passes.** When you come within 25 m of a bump and then move away, that's a pass: a *hit* if you felt it, a *miss* if not. A slow pass (under 12 km/h) with no jolt counts neither way. Otherwise the app would slowly mute real bumps *because* it warned you and you crawled over them.

Every number above is in `EngineConfig` at the top of `BumpEngine.kt`.

---

## Run the tests (no phone needed)
In Android Studio, right-click `app/src/test/java/app/bumpbeeper/BumpEngineTest.kt` → **Run**. Six simulated scenarios:

- **learnThenBeep:** 4 drives over 3 bumps and one one-off pothole hit. The first drive is silent. Drives 2–4 beep about 65–95 m before each bump. After 4 passes each bump's position is within about 3 m, and the one-off spot is muted.
- **otherDirection:** eastbound bumps don't beep westbound.
- **handlingIgnored:** a passenger grabbing the phone isn't recorded as a bump.
- **parkedAndNoGps:** a door slam while parked, or a jolt before the first GPS fix, is ignored.
- **crawlVersusRemoved:** a crawled-over bump stays active; a removed bump gets muted.
- **userMute:** after *Mute last beep*, that bump stays silent.

---

## Tuning with your own data
**Export CSV** writes two files to `Downloads/BumpBeeper`:

- `bumps_….csv`: the map. It has `lat`, `lon`, `hits`, `passes`, `hit_rate` and `muted`. Import it into **Google My Maps** to see your bumps on a map.
- `events_….csv`: one row per event: `new_bump`, `hit`, `miss`, `pass_slow`, `beep`, `rejected` (with the reason in `note`) and `user_mute`. Each row has `peak_ms2` (jolt size), `speed_kmh` and `slowdown_kmh` (how much you braked in the previous 10 s).

Practice ideas:
1. **Pick your threshold.** Load `events.csv` into pandas and plot a histogram of `peak_ms2` for `hit`/`new_bump` rows against `rejected` rows. Is 3.0 in the right place for your car?
2. **Is braking a better signal?** Compare `slowdown_kmh` for bumps you confirmed (hit 2+ times) with one-off spots. Could "braked more than 10 km/h" replace part of the threshold?
3. **Beep timing.** For `beep` rows, `distance_m ÷ (speed_kmh / 3.6)` is the seconds of warning you got. Is 7 s (`leadSeconds`) right for how you drive?

---

## Known limits
- Tested in simulation, not yet on a real road. The thresholds are sensible starting points; use the jolt meter and the export to tune them for your car and phone.
- Potholes you hit every time will be learned too, and will beep. That's arguably useful; use *Mute last beep* if not.
- GPS is weaker between tall buildings and under bridges. Beeps may come a little early or late there.
