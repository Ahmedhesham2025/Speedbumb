# Bump Beeper

Android app that learns the speed bumps on your routes and warns you about them.

- **First pass over a bump:** the phone feels the jolt and saves the spot. No beep.
- **Every pass after that:** 2 beeps about 6 seconds before you reach it (3 beeps above 50 km/h). It keeps recording while it beeps.
- **Keeps improving:** each pass refines the bump's position. A spot you pass 3+ times but feel less than half the time is muted automatically, and **Mute last warning** silences a false alarm with one tap.
- **Speed bump or pothole:** told apart from how the car moves (see *How it works*). Every pothole is recorded and counted; it also learns **which side** it's on (which wheel hits it) and **how harsh** it is.
- **Voice warning for harsh potholes only**, saying which way to go around it: *"Pothole on the right. Keep left."* (English or Egyptian Arabic, offline text-to-speech). Smaller potholes are counted but stay silent.
- **Quiet when you're already slow:** no warning below 20 km/h (adjustable), since you've clearly seen it.
- **Auto start/stop** when the phone connects to / disconnects from your car's Bluetooth.
- **Start recording when I drive** (optional, off by default): notices driving without Bluetooth, from the motion sensor and short GPS checks (Google's in-vehicle detection in the Play edition), even with the app closed; needs location *Allow all the time*. Trips that started by themselves ask **"Was this a drive?"**: their bump points, speed-limit route and training samples stay on the phone until you say yes (the ~1 km rounded position at trip start and end is still sent with *Receive only* or *Share and receive*). Recording stops after the car is parked for a few minutes (adjustable).
- **Your bumps:** offline map and list; open a spot in Google Maps, mute it, correct bump/pothole, delete it; **share** your map and **import** someone else's.
- **Debug recording:** saves every sensor reading of a drive, to check afterwards what happened at a spot.
- **Driving score out of 100** for every trip and overall (Trips tab): speeding, harsh braking / acceleration, harsh cornering, swerving, speed bumps taken fast, and handling the phone while moving. With tips, a trend chart, and a shareable trip report.
- **Four tabs:** Drive (big Start/Stop, live speed, trip tiles, setup checklist; the screen stays on while recording) · Map · Trips · Settings. Dark theme for night driving.

## Sharing with others
- **Share…** (Map tab or Settings → Your data) offers: *Bump file (CSV)* for another Bump Beeper or Excel, *Map file (KML)* for Google Earth / Google My Maps, and *Trips & scores (CSV)*. A trip's details have their own **Share** (text report).
- **Receiving:** tap the bump file in WhatsApp / Gmail / Files / Drive and choose **Bump Beeper**, or use **Import a file**. The spots are merged into your map; spots you already have are kept as they are, so importing the same file twice does nothing.

## Driving score
Every trip starts at 100. Points come off for speeding (time above *your* limit in Settings, more the further over), harsh braking (> ≈0.35 g), harsh acceleration (> ≈0.3 g), harsh cornering (> ≈0.4 g sideways), swerves (a sudden left-right), speed bumps from your map taken above 25 km/h, and the phone being picked up while moving. Events count per 10 km (short trips count as 5 km); trips under 0.5 km aren't scored. Overall = distance-weighted average of the last 20 trips. 90+ Excellent · 75+ Good · 60+ Fair · below Needs work.

Braking and acceleration come from the phone's sensors once it knows which way is forward, and from GPS speed until then. Sideways force = speed × turning rate (gyroscope, or GPS heading without one). Each trip's numbers are stored (`trips` table, *Trips & scores* CSV) in a form a fleet system could take in later.

**Real speed limits (optional, off by default).** Without them, speeding means time above the limit you set. If you turn on *Settings → Driving score → Use real speed limits* (and agree to the notice), after each trip the app sends the route, minus its first and last 300 m, to our server, which asks TomTom for the legal limit of each road and keeps nothing except a daily count of lookups. When limits are known for at least half the trip, speeding is time over each road's limit by +10, +20 and +30 km/h instead; the trip then shows how much was known, the time in each band, and "© TomTom". The route waits on the phone until it is looked up; after about 2 days it is never sent, and it is deleted the next time the app runs. The lookup runs in Supabase's Frankfurt region.

**Live speed limit & warning (optional, off by default, v1.7).** A separate switch in *Settings → Driving score*. After you agree to its notice, while you drive the app sends your last few GPS points (about the last 300 m) through our server to TomTom (about every kilometre, more often on a road change or when driving slowly: at most every 30 s, about every 2 min when slow; never below 10 km/h), and shows the road's limit as a round sign next to your speed (with "© TomTom"; "– –" when unknown or older than 5 minutes). The speed turns orange when you're over the limit and red when you're over it by your margin (+5, +10 or +20 km/h); after 3 s over the margin you hear a tone and then the limit spoken. Nothing is sent in the first 300 m of a trip or within 300 m of where it started, but the end of a trip can't be protected (the app doesn't know where you'll stop). TomTom never gets your phone ID or IP address, and the times it gets are shifted to the year 2000; our server keeps only a daily count of lookups (30 days). The limit is kept only in memory (at most 5 min). Up to 60 lookups a day, shared with the after-trip lookup (live stops when 4 are left). Also works on trips that started by themselves, before you answer "Was this a drive?".

Android 10 or newer. No sign-up: online features use an anonymous ID, never your name, email or phone number. Your bump map, trips and scores are kept on the phone (and in your Google backup if Android backup is on). What goes online:
- **Shared map (optional, asked once):** *Receive only* downloads confirmed bumps near you (the app sends a rough position, rounded to about 1 km, when a recording starts and when a trip ends). *Share and receive* also uploads the bump and pothole **points** you find (never your route, nothing near where trips start or end) and crash reports. Until you answer, nothing goes to our server.
- **Real speed limits (optional, off by default):** see *Driving score* above. Only route stretches go out, and only via our server to TomTom.
- **Live speed limit (optional, off by default):** see *Driving score* above. The last ~300 m of GPS points while driving (about every kilometre, more often on a road change or when slow), via our server to TomTom.
- **Help improve detection (optional, off by default, separate from the shared map):** short motion-sensor samples around possible bumps and route-free trip summaries, under a random ID that changes each time you turn it on; no coordinates. Kept 12 months; turning it off deletes them on the server.
- **Update check (automatic, the only call before you choose):** at most once a day the app asks GitHub whether a newer version exists; nothing about you or your drives is sent.

Details: [privacy policy](docs/privacy/privacy-policy.md).

---

## Get it onto your phone

### Option A: Android Studio (recommended)
1. Install [Android Studio](https://developer.android.com/studio) and open this folder (**File → Open → BumpBeeper**). Let it sync; it downloads what it needs the first time.
2. On the phone, turn on **Developer options → USB debugging** (tap *Build number* 7 times in *Settings → About phone*).
3. Plug the phone in and press the green **Run ▶** button.

To get an APK file instead: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The file lands in `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the phone and open it (allow *Install unknown apps*).

### Option B: no Android Studio (GitHub builds it)
1. Create an empty GitHub repository and push this folder to it.
2. Add your signing key as two repository secrets (**Settings → Secrets and variables → Actions**): `KEYSTORE_BASE64` (the `.p12` key file, base64) and `KEYSTORE_PASSWORD`. The key alias must be `bumpbeeper`.
3. GitHub builds it automatically (**Actions** tab → *Build APK*). This takes about 5 minutes.
4. Open the finished run → **Artifacts → BumpBeeper-apk**, unzip it, and install `BumpBeeper-<number>.apk` on the phone.

Every build is signed with the same key and gets a higher version number, so **a new version installs over the old one and keeps your bumps**. Keep a backup of the key: without it, updates can't be installed over the app.

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
- within `speed × 7 s` (at least 40 m, at most 250 m; the 7 s is a setting),
- and you're not already slower than the "don't warn below" setting (20 km/h by default; logged as `beep_quiet`).

**Speed bump or pothole?** Each hit is scored from −1 (speed bump) to +1 (pothole) from two clues:
- *Which way the car moves first.* A speed bump pushes the car up first. A pothole drops a wheel down first, then slams it into the far edge.
- *How the car rocks* (gyroscope). A speed bump spans the lane, so both front wheels rise together and the car pitches nose-up/nose-down. A pothole usually catches one wheel, so the car rolls sideways. To separate roll from pitch the engine needs to know which way the car's nose points in phone coordinates; it learns that by matching the accelerometer's horizontal push with GPS speed changes (speeding up and braking), so it's ready after a few of those. It re-learns if the phone is moved in its holder.

Each spot keeps a running average of its hits' scores, so one odd reading doesn't flip it. Above the speed bump limit (50 km/h), a jolt is still recorded if it is clearly a pothole (score ≥ 0.6 with the gyroscope), up to 100 km/h. Without a gyroscope only the first clue is used. You can correct a spot by hand in *Your bumps*.

**Which side, and how harsh.** A pothole usually catches the wheels on one side, so the car first tips towards that side. With the forward direction known, the gyroscope's roll says which: right side dropping first = pothole on the right. Each spot keeps a running average of the sides seen, plus its average jolt; it is *harsh* when that average is at least 6 m/s² (setting). The phone can tell *which wheel* hit it, not its exact position across the lane (GPS is only good to a few metres), so "keep left" means move left within your lane.

**Auto start.** Android announces every Bluetooth connection, even to closed apps. When it's the car you picked, recording starts; when it disconnects, recording stops after 60 s (so a short drop doesn't end the trip). Starting from the background needs location *Allow all the time*; if Android still refuses, you get a notification that starts recording with one tap.

**Counting passes.** When you come within 25 m of a bump and then move away, that's a pass: a *hit* if you felt it, a *miss* if not. A slow pass (under 12 km/h) with no jolt counts neither way. Otherwise the app would slowly mute real bumps *because* it warned you and you crawled over them.

Every number above is in `EngineConfig` at the top of `BumpEngine.kt`.

---

## Run the tests (no phone needed)
In Android Studio, right-click `app/src/test/java/app/bumpbeeper/BumpEngineTest.kt` → **Run**. GitHub also runs them on every build. Seventeen simulated scenarios (the driving-score ones: **calmDrivingScoresHigh**, **speedingAndHardBraking**, **swerving**, **speedBumpsTakenFast**, **phoneHandledWhileDriving**), including:

- **learnThenBeep:** 4 drives over 3 bumps and one one-off pothole hit. The first drive is silent. Drives 2–4 beep about 65–95 m before each bump. After 4 passes each bump's position is within about 3 m, and the one-off spot is muted.
- **otherDirection:** eastbound bumps don't beep westbound.
- **handlingIgnored:** a passenger grabbing the phone isn't recorded as a bump.
- **parkedAndNoGps:** a door slam while parked, or a jolt before the first GPS fix, is ignored.
- **crawlVersusRemoved:** a crawled-over bump stays active; a removed bump gets muted.
- **userMute:** after *Mute last warning*, that bump stays silent.
- **potholeVsBump:** two speed bumps and a pothole are classified correctly on the first drive, warn with the right sound on the second, and the pothole stays silent with *Warn for potholes* off.
- **potholeSidesAndCounts:** a right-side and a left-side harsh pothole get the right side; a small pothole is counted but silent; trip counts are right.
- **potholeVsBumpNoGyro:** same, on a phone without a gyroscope.
- **fastPothole:** at 70 km/h a pothole is still recorded, an ordinary jolt is rejected.
- **quietWhenSlow:** at 18 km/h there's no warning (logged as `beep_quiet`); with the setting at 0 it warns.
- **missReportsNearbyJolt:** a miss records the strongest jolt felt near the bump.

---

## Tuning with your own data
**Export CSV** writes two files to `Downloads/BumpBeeper`:

- `bumps_….csv`: the map. It has `lat`, `lon`, `hits`, `passes`, `hit_rate`, `muted`, `kind` (speed bump / pothole / unsure) and `kind_score`. Import it into **Google My Maps** to see your bumps on a map, or into the app on another phone (*Your bumps → Import map*).
- `events_….csv`: one row per event: `new_bump`, `hit`, `miss`, `pass_slow`, `beep`, `beep_quiet`, `rejected` (with the reason in `note`) and `user_mute`. Each row has `peak_ms2` (jolt size), `speed_kmh` and `slowdown_kmh` (how much you braked in the previous 10 s). Hits and new bumps carry the bump/pothole clues in `note` (`score`, `first=up/down`, `roll/pitch`). For a `miss`, `peak_ms2` is the strongest jolt felt near the bump: just under the trigger means *raise the sensitivity*.

**Debug recording** (setting, off by default) writes one file per drive with every accelerometer and gyroscope sample, every GPS fix and every event (`type` = `accel` / `gps` / `event`). **Export recordings** copies them to `Downloads/BumpBeeper/recordings`. The last 20 drives are kept, about 12 MB per hour.

Practice ideas:
1. **Pick your threshold.** Load `events.csv` into pandas and plot a histogram of `peak_ms2` for `hit`/`new_bump` rows against `rejected` rows. Is 3.0 in the right place for your car?
2. **Is braking a better signal?** Compare `slowdown_kmh` for bumps you confirmed (hit 2+ times) with one-off spots. Could "braked more than 10 km/h" replace part of the threshold?
3. **Beep timing.** For `beep` rows, `distance_m ÷ (speed_kmh / 3.6)` is the seconds of warning you got. Is 7 s (`leadSeconds`) right for how you drive?

---

## Known limits
- Tested in simulation, not yet on a real road. The thresholds are sensible starting points; use the jolt meter and the export to tune them for your car and phone.
- Bump/pothole detection is also tested only in simulation. Speed bumps crossed at an angle, or potholes that span the whole lane, can look like the other kind; a spot gets surer with every pass, and you can correct it by hand.
- The map in *Your bumps* has no streets (the app downloads no map tiles). Use *Open in Google Maps* for context.
- GPS is weaker between tall buildings and under bridges. Beeps may come a little early or late there.
