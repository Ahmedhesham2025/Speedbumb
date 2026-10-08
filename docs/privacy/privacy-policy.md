# Bump Beeper privacy policy

*Draft for review (security-privacy). Last updated: 2026-10-08. Arabic version below.*

Bump Beeper warns you about speed bumps and potholes and scores your driving. It is free, open source (GPL-3.0,
<https://github.com/Ahmedhesham2025/Speedbumb>), and has **no ads, no analytics and no trackers**. You never sign up:
there is no name, email or phone number anywhere in the app.

## What is kept on your phone
Your bump map, your trips and scores, your settings, and debug recordings (if you turn them on) are kept on the phone.
They leave it when **you** share or export them (Share, Save all, Export recordings), and through **Android backup**:
if backup is on for your phone, Android copies the bump map, trips, scores and settings to your Google account
backup. Debug recordings, crash files and routes waiting for a speed-limit lookup are never put in the cloud backup
(recordings do move along in a direct phone-to-phone transfer). The shared-map sign-in and the "Was this a drive?"
holds (see below) are never backed up or moved to a new phone either. Training samples not sent yet and bump points
waiting for "Was this a drive?" are stored in the bump database, so they are part of the backup. The restored phone
signs in as a new anonymous device that never agreed to *Help improve detection*, so on its first start after a restore,
before it connects to our server, the app switches that option off, deletes the unsent samples and the held points, and
tells you; you can turn it on again. Your shared-map and speed-limit choices stay as they were.

**Debug recordings** (off by default) hold every sensor reading and GPS fix of a drive, the app's events, and now also
*power* rows: when the app slowed GPS down because the car stood still, and when it sped it up again.

**Research recordings** (off unless you said yes, see section 5) hold the full recording of each trip, **including the
whole GPS route**, in app storage that is never backed up or moved to a new phone. They are kept up to **14 days**
(7 days after their upload). *Share recordings* also saves a zip of them in **Downloads/BumpBeeper/research**, where
other apps (a file manager, a PC) can read it and the 14 days don't apply. Recordings from before you last turned it
off are never uploaded and are deleted when you turn it on again. After a backup restore research recording is off
(the new anonymous device never agreed to it) and the first-start question comes again.

## Start recording when I drive (off by default; Settings → Auto start)
If you turn this on, the app notices that you are driving and starts recording without Bluetooth, **even when the app
is closed or not in use**. Before Android asks for location, the app shows what it collects and why, and you can say
*No thanks*.
- **How it notices:** the phone's significant-motion sensor wakes the app when the phone starts moving; then a GPS
  check of a few minutes (at most about 3½) measures your speed. Location updates that other apps already asked for (Android's
  *passive* location) can start a check too. **Everything is checked on the phone**; nothing about this is sent.
- **Play edition with Google Play services:** if you allow *Physical activity*, Google's activity recognition tells the
  app when you get into or out of a vehicle, instead of the motion sensor. Google Play services decides the activity,
  under Google's privacy policy (<https://policies.google.com/privacy>); the app only receives the changes "in a
  vehicle" and "walking". The F-Droid / GitHub (foss) edition never uses it.
- **Background location ("Allow all the time")** is needed because the recording starts while the app is not on
  screen. It is used only to check whether you are driving and to record the drive. While it waits, the built-in
  detection shows a quiet "Ready to detect driving" notification.
- **A recording that started by itself is like one you started:** the same data is kept on the phone, and the same
  features (shared map, real speed limits, help improve detection) apply. That includes the rough position (rounded to
  about 1 km) sent when a recording starts and when a trip ends, if you chose *Receive only* or *Share and receive*.
- **It may start on a bus, a train or a bike.** So after such a trip the app asks **"Was this a drive?"**. Until you
  answer *Yes, I drove*, the trip's bump points, its speed-limit route and its training samples **stay on the phone**.
  *No* deletes them, together with the trip and the spots only it found. With no answer within **24 hours** they are
  never sent, and they are deleted at the next sync or app start after that (the trip itself stays on the phone). A trip during which your car's Bluetooth connected counts as
  confirmed. **Exception:** with *Live speed limit & warning* on, its lookups are sent during the trip, before you
  answer; nothing of them is kept, so *No* has nothing to delete (and cannot recall lookups already made).
- **Auto-stop:** a recording stops after the car has been parked for the minutes you choose (default 5; *never* is
  possible), only after real driving and never while the car's Bluetooth is connected. With a parked time set, it also
  stops 1 minute after Google (play edition) sees you walking, or after 15 minutes without any GPS fix. Whatever the
  setting, even *never*, a recording that started by itself and never reached driving speed stops after 10 minutes.
- **After a restart or an app update** the detection is set up again, but only if this switch is on.
- **To withdraw:** turn the switch off, or set Bump Beeper's location permission to *Only while using the app* or *Don't
  allow* in Android settings (Settings → Apps → Bump Beeper → Permissions → Location). The switch then shows "Needs a
  permission". *Physical activity* can be withdrawn the same way.

## What leaves your phone

### 1. Update check (automatic)
At most once a day the app asks GitHub (<https://github.com>) whether a newer version exists. Nothing about you, your
phone or your drives is sent; GitHub sees an ordinary web request (your internet address and the app version). This is
the only thing the app sends to us or GitHub before you answer the shared-map question (map tiles, below, come from
OpenFreeMap whenever a map is on screen).

### 1b. Map tiles (whenever a map is on screen: Map tab, and the Drive screen's map if on)
The street map is downloaded from **OpenFreeMap** (<https://openfreemap.org>, free, run by Hyperknot Software Kft., Hungary, possibly through the Cloudflare
CDN), with map data © OpenStreetMap contributors. Like any website, OpenFreeMap sees your internet (IP) address
and which map tiles you load, so it can tell roughly **which area you are looking at** (and, with the Drive screen's map,
roughly where you are driving); its policy says it does not keep IP addresses in its normal logs. No account, no ID, no key, and none of your spots or trips are sent; the spots are
drawn on top of the map on your phone. Tiles you have seen are cached on the phone (up to about 50 MB) so the map works
without internet. OpenFreeMap's privacy notice: <https://openfreemap.org/privacy/>. Turn *Show map while driving* off in
Settings to load no tiles while driving.

### 2. Shared map (asked once; Settings → Shared map)
Until you choose, nothing is sent to our server.

| Choice | What is sent | Why |
|---|---|---|
| *Receive only* | An anonymous ID (a random sign-in the app creates; not your phone number), the app and Android version, your choice, and a rough position (rounded to about 1 km) when a recording starts and when a trip ends | To download confirmed bumps and potholes near you |
| *Share and receive* | The above, plus each bump or pothole you find: place, time (rounded down to the hour on the server), direction, speed, how strong it was. **Never your route**, and nothing within 300 m of where a trip starts or ends. Plus crash reports if the app crashes (see below) | To build the shared map; to fix crashes |

**Crash reports** (*Share and receive* only) contain: the crash time with your time-zone offset, the app version, the
phone's manufacturer and model, the Android version, the name of the app thread that crashed, and the error message
and stack trace, with file paths and numbers that could be coordinates removed. No location.

### 3. Real speed limits (off by default; Settings → Driving score → Use real speed limits)
Only after you agree to the notice, and only while the shared map is on.
- **What:** after each trip, the trip's route (sampled positions with times), **without its first and last 300 m**,
  together with your anonymous ID so our server can enforce a daily limit of lookups. This is **precise location**.
- **Where:** to our server, which forwards only the positions to **TomTom** to find the speed limit of each road.
  TomTom does not get your anonymous ID or your internet (IP) address (it only sees our server), and the times it gets
  are shifted to the year 2000, so it does not learn when you drove. TomTom receives this data under its own privacy
  notice: <https://www.tomtom.com/privacy/>.
- **Why:** to score speeding against each road's real limit. The trip then shows "© TomTom".
- **How long:** our server keeps nothing from the lookup except a count of lookups per anonymous ID per day, deleted
  after 30 days. On the phone the route waits until it is looked up. A route older than about **48 hours** is never
  sent; it is deleted the next time the app or the lookup runs. It is also deleted at once if you turn the switch off,
  turn the shared map off, delete your shared data or clear the map. The limits themselves are saved only in your trip
  on the phone.

### 3b. Live speed limit & warning (off by default; Settings → Driving score → Live speed limit & warning)
A separate switch from *Use real speed limits*. Only after you agree to its notice (consent version 1), and only while
the shared map is on.
- **What:** while you drive with it on, the app sends your **last few GPS points (about the last 300 m)**, with your
  anonymous ID so our server can enforce the daily limit. This is **precise location**. **How often:** about **every
  kilometre**, and more often after you turn onto another road or when you drive slowly (at most every 30 seconds;
  about every 2 minutes when slow); **never below 10 km/h**. Nothing is sent in the **first 300 m** of a trip or
  **within 300 m of where the trip started**. The **end of a trip cannot be protected**: the app does not know where
  you will stop, so the last lookup can be close to where you park. This also works on trips that started by
  themselves (*Start recording when I drive*), including before you answer "Was this a drive?" (see above).
  **At most 60 lookups a day, shared with the after-trip lookup**; live lookups stop for the day when 4 are left, so
  the after-trip lookup still works.
- **Where:** to our server, which forwards only the positions to **TomTom**. TomTom does not get your anonymous ID or
  your internet (IP) address (it only sees our server), and **the times it gets are shifted to the year 2000**, so it
  does not learn when you drove. TomTom's privacy notice: <https://www.tomtom.com/privacy/>.
- **Why:** to show the speed limit of the road you are on next to your speed (with "© TomTom"), and to warn you: when
  you are over it by your chosen margin (+5, +10 or +20 km/h) for 3 seconds, you hear a tone and then the limit spoken.
- **How long:** our server keeps nothing from the lookup except the count of lookups per anonymous ID per day, deleted
  after 30 days. On the phone the limit is kept **only in memory**, for at most 5 minutes, never on disk, in your trips or
  in recordings. Turning the switch off stops the lookups at once.

### 4. Help improve detection (off by default; Settings → Help improve detection)
A separate choice from the shared map, offered only after you answered the shared-map question, and only after you agree
to its notice. It sends compact learning samples so the detection of bumps and potholes can be improved.
- **For each possible bump the app judges** (learned, hit, rejected, missed, passed without feeling it, or a warning you
  muted): **up to 4 seconds of motion-sensor readings** (2 s before and after) (the vertical shake, plus roll and pitch rotation if the phone
  has a gyroscope), the app's decision and its reason, the speed, the change of heading, the GPS accuracy, and where the
  phone sits (mounted, cup holder, pocket). **Only the date** of the drive, no time of day. Also the Android version,
  the app version and the phone **brand** (never the model).
- **For each trip:** date, duration, distance, the counts of those decisions, beeps, the driving-score figures (harsh
  events, speeding time, score) and the battery level at start and end.
- **No route and no coordinates.** The only place information is the number of a **public, confirmed** shared bump you
  passed, and never for anything within 300 m of where a trip starts or ends. Over time these numbers can hint at the
  areas you drive in.
- **A random ID** (pseudonym) that changes each time you turn this on. On our server it is stored with this phone's
  anonymous device record (`devices.training_subject`, so it can be deleted when you turn this off), and that record is
  also linked to the phone's shared-map contributions. It is dropped, and a new one made, each time you turn this on
  again. Samples are not linked to trips, so they can't be put in order.
- **When and how much:** uploaded 1 to 6 hours after a drive, at a random time; about 30 to 90 KB per drive. Trips that
  started by themselves are held until you answer "Was this a drive?" (see above).
- **Why and who:** only to improve detection. The app's owner analyses the samples offline; they are never sold and
  never used for ads.
- **Turning it off** or **Delete my shared data** deletes the samples and trip summaries on our server at once when
  the phone is online, otherwise at its next connection (Settings shows that it is pending), and empties the queue on
  the phone at once, except copies in the provider's backups and logs, which rotate. If the anonymous
  sign-in is reset, the app switches this off and tells you; you can turn it on again (with a new random ID).

### 5. Research recordings (asked once at first start; Settings → Research recordings)
A separate choice from all of the above, asked once on a first-start screen ("Help improve detection with full sensor
recordings", consent version 1) after the shared-map question; it stays off unless you say **Yes**. It records every
useful phone sensor during your trips so the detection can be improved offline.
- **Recorded during trips:** all motion sensors at up to 200 times a second (accelerometer, gyroscope and their raw
  versions, gravity, rotation, compass), pressure, light and proximity; every GPS fix (position, speed, heading,
  accuracy) and satellite counts; **phone-use signals**: screen on/off, unlock, lock state, call state and where sound
  goes (Bluetooth or wired, never what is said), car Bluetooth, charging and battery; **steps and walking/driving
  detection** (Play Store version only, with the *Physical activity* permission: the phone's step detector and Google's
  activity recognition); labels you tap in label mode. Each file also holds **the trip's date and time** (to the
  second, also in its name), how the trip started (you, the car's Bluetooth or motion detection), where the phone sits,
  the phone's **manufacturer and model** with its list of sensors, the Android and app versions, a random research file
  ID (new each time you say yes) and the trip number on the phone. **No microphone, no camera**, no name, email or
  phone number.
- **On the phone:** see *What is kept on your phone* (the full recording, whole GPS route included; 14 days).
- **What leaves the phone:** only on **Wi-Fi** (an unmetered network), and **never the start or end of a trip**: every
  line, of every kind, recorded before the car has both driven 300 m and got 300 m (in a straight line) from where the
  trip started is removed, and so is everything after the last point that is both more than 300 m driven before the
  end and more than 300 m from where the trip ended. So a phone parked at home sends nothing of that time, even if its
  GPS wanders. Nothing of a trip shorter than 600 m is sent. (A route that passes near the start or end again in the
  middle of the trip is kept.) Trips that started by themselves are held until you answer "Was this a drive?"; *No*
  deletes their recordings. About 27–32 MB per hour of driving, in files of about 5 MB.
- **Where and who:** to a private store on our server (Supabase, Frankfurt), in a folder named after your anonymous
  account ID, shown in Settings as your **Research ID**. Phones can only add files there, never read them. The app's
  owner downloads the files for research (at least weekly), which deletes them from the server, and uses them only to
  improve detection: never sold, never used for ads.
- **Turning it off** stops recording and uploading at once (the server is told when the phone is next online). Files
  already uploaded **stay until deleted on request**. The private way is **Delete my shared data** (Settings → Shared
  map): it has them erased, together with any downloaded copies, at the owner's next run (at least weekly). You can also
  ask the owner with your Research ID (Settings → Research recordings → *Copy Research ID*) through the private contact
  below. **GitHub issues are public: never post your Research ID there.** If the anonymous sign-in is reset, the app
  switches research off and tells you.

## Where the data is processed
Our server is [Supabase](https://supabase.com) in Frankfurt, Germany (EU). The speed-limit lookup asks Supabase to run
in Frankfurt too (`x-region: eu-central-1`); other requests reach the database in Frankfurt. Like any web service,
Supabase sees the internet address a request comes from; its own sign-in records may keep it. Our tables do not.

## How long

| Data | Kept |
|---|---|
| Bump and pothole reports | Merged into shared spots, then deleted about a day later |
| Shared spots | As long as they are useful. Each spot keeps when it was first seen and last hit |
| Which phones contributed to a spot (spot, anonymous ID, hit and clear counts; no times) | Until you delete your shared data |
| Device record (anonymous ID, app/Android version, your choice) | Until you delete your shared data |
| Crash reports | Until you delete your shared data (no automatic expiry yet) |
| Speed-limit lookups (after-trip and live) | Only a daily count per anonymous ID, deleted after 30 days |
| Live speed limit (on the phone) | In memory only, at most 5 minutes; never saved |
| Anonymous sign-in (the ID itself, and the sign-in service's records) | Not deleted by the app yet |
| Route waiting for a lookup (on the phone) | Until looked up; never sent after about 48 hours |
| Training samples and trip summaries (server) | 12 months, or until you turn *Help improve detection* off or delete your shared data |
| Training samples not sent yet (on the phone) | 7 days |
| Data of a trip waiting for "Was this a drive?" (on the phone) | Until you answer; never sent after 24 hours, deleted at the next sync or app start. Exception: live speed-limit lookups are sent during the trip (nothing of them is kept) |
| Daily upload counters for training samples (counts per device, no content) | 2 days |
| Research recordings (on the phone, whole GPS route included) | 14 days, or 7 days after their upload; a zip you shared stays in Downloads until you delete it |
| Research recordings (our server) | Until the owner downloads them (at least weekly); with *Delete my shared data*, erased at the owner's next run |
| Research recordings downloaded by the app's owner | **TODO-OWNER: The owner keeps downloaded copies for up to 12 months** (to be confirmed by the owner), or until you ask for deletion with your Research ID or use *Delete my shared data* |
| Research upload records (anonymous ID, file name with the trip's start time, size, upload time) | Kept after the owner clears the file, until you use *Delete my shared data* or ask for deletion |

## Your choices
- **Change or withdraw consent** at any time: Settings → Shared map (*Receive only*, *Share and receive*, or *Off*), and
  the *Use real speed limits* and *Live speed limit & warning* switches, the *Help improve detection* switch, the
  *Research recordings* switch, and *Start recording when I drive* (or the location permission in Android settings).
- **Research recordings:** turning the switch off stops recording and uploading; to have files already uploaded
  deleted, send your Research ID (Settings → Research recordings) or use *Delete my shared data* (below).
- **Delete my shared data** (Settings → Shared map): deletes this phone's device record on the server and with it the
  bump and pothole reports it sent, its links to spots (contributions), its crash reports and its training samples. Spots already merged into
  the shared map stay, with nothing linking them to this phone any more. What stays: the anonymous sign-in itself
  (with the sign-in service's own records) and the speed-limit day counts until they expire after 30 days. On the
  phone it clears the downloaded warnings, turns the shared map, real speed limits, the *live speed limit*, *Help improve detection*
  **and** *Research recordings* off (so you are asked again before any new lookup or upload), and keeps your own bump map
  and trips. Research files already uploaded are erased, unread, at the owner's next run of the research tool (at least
  weekly), with any copies already downloaded; research recordings still on the phone stay there (14 days at most).
- **Clear the map** (Settings → Your data): deletes the bumps, potholes, event log, trips and scores on the phone, and
  routes waiting for a lookup. It does not delete settings or debug recordings (those have their own *Delete
  recordings* button).
- **Delete everything on the phone:** Android Settings → Apps → Bump Beeper → Storage → *Clear storage*, or uninstall.

## Children
The app is meant for drivers and is not directed at children.

## Changes and contact
Changes to this policy are made in the public repository, where you can see every version. Questions: open an issue
at <https://github.com/Ahmedhesham2025/Speedbumb/issues>. **Issues are public**: never post your Research ID or anything
personal there. Deletion requests and private questions: **TODO-OWNER: private contact (for example an email address)
to be added by the owner**; until then, use *Delete my shared data* in the app.

## For store listings
Notes for the Play Store *Data safety* form and the F-Droid listing (store-docs to finalise):
- **Collected** (all optional, user-chosen, encrypted in transit):
  - *Location → Approximate location*: the ~1 km area for downloading spots (processed ephemerally, not stored).
  - *Location → Precise location*: bump/pothole points with *Share and receive*; trip routes with real speed limits
    (routes are processed ephemerally: not stored by our server, and TomTom's terms forbid storing results).
  - *App info and performance → Crash logs* (*Share and receive* only).
  - *Device or other IDs*: the anonymous sign-in ID.
  - *App info and performance → Other app performance data*: training samples (motion-sensor readings and the
    app's decisions) with *Help improve detection*. Purpose: app functionality (improving detection). Optional.
  - *App activity → Other actions* (to consider): the route-free trip summaries (score, harsh events) with *Help
    improve detection*.
  - *Device or other IDs* (to consider): the training pseudonym.
  - *Location → Approximate location*, with *Help improve detection*: the number of a public confirmed bump passed
    can hint at the area driven in.
  - *Precise location* used for auto-detect is processed **on the device only** and is not collected; it leaves the
    phone only through the opt-in features above.
  - Purposes: app functionality (crash logs: also analytics of crashes). Never ads or marketing.
  - *Location → Precise location*, with *Live speed limit & warning*: the last ~300 m of GPS points while driving
    (about every km, more often on a road change or when slow), shared with TomTom; processed ephemerally; optional;
    also in the background on trips that started by themselves.
  - With *Research recordings* (optional, stored, not ephemeral): *Location → Precise location* (the GPS route minus
    300 m at each end), *App activity → Other actions* (screen, unlock, lock and call state), *Device or other IDs*
    (the anonymous account ID; the phone model) and *App info and performance → Other* (all motion sensors). Purpose:
    app functionality (improving detection). Wi-Fi only; deletion on request or with *Delete my shared data*.
- **Shared:** *Location → Precise location* with a third party (**TomTom**), optional, processed ephemerally, only
  with real speed limits or the live speed limit on (the live one also in the background, on trips that started by
  themselves).
- **Tracking:** not applicable (no advertising ID, no cross-app tracking, no analytics SDK).
- **Deletion:** in the app (*Delete my shared data*, or turning *Help improve detection* off), and for research
  recordings on request with the Research ID; see what stays above.
- **Google Play, background location:** declare `ACCESS_BACKGROUND_LOCATION` for *Start recording when I drive* (core
  feature: notice driving and start recording without Bluetooth while the app is closed), with the in-app prominent
  disclosure shown before the permission request and a short video of it. The foreground service type is `location`
  (recording, and waiting for a drive with the quiet notification): fill in the Play Console foreground-service
  declaration for `FOREGROUND_SERVICE_LOCATION` with the same use and a video.
- **Google Play, battery exemption:** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is asked (with a reason, and only after the
  user turns auto-detect on) because the app must notice a drive and start recording while in the background; on many
  phones the battery optimiser stops that otherwise. The core function cannot be done with a push or a scheduled job.
- **Google Play, physical activity** (play edition only): Google activity recognition for in-vehicle detection.
- **F-Droid:** the app contacts a non-free network service (TomTom, via our server) only when the user opts in:
  declare the *NonFreeNet* anti-feature. Auto-detect adds **no new anti-feature**: the foss edition uses only Android's
  own sensors and location, and the training upload goes to the same server as the shared map.

---

# سياسة الخصوصية (بالعربي)

*مسودة للمراجعة. لو فيه أي اختلاف، النسخة الإنجليزي اللي فوق هي المرجع.*

بامب بيبر بينبّهك للمطبّات والحفر وبيدّي درجة لسواقتك. مجاني ومفتوح المصدر، ومن غير إعلانات ولا تتبّع. مش محتاج
تعمل حساب، ومفيش اسم ولا إيميل ولا رقم تليفون.

**اللي بيتحفظ على موبايلك:** خريطة المطبّات، رحلاتك ودرجاتك، والإعدادات. بتخرج لو إنت شاركتها أو صدّرتها، ولو
النسخ الاحتياطي بتاع أندرويد شغّال، أندرويد بينسخ الخريطة والرحلات والإعدادات على حساب جوجل بتاعك. التسجيلات وتقارير
الأعطال والطرق اللي مستنية البحث عمرها ما بتدخل النسخة الاحتياطية، ولا تسجيل الدخول المجهول ولا علامات «مستنية
رد» بتاعة «كنت إنت اللي سايق؟» (الرحلات نفسها بتتنسخ). عيّنات التدريب اللي لسه ما اتبعتتش ونقط المطبّات المستنية الرد
موجودين في قاعدة البيانات اللي بتتنسخ. الموبايل المسترجَع بيدخل كجهاز مجهول جديد عمره ما وافق على «ساعد في تحسين
الكشف»، فأول مرة يشتغل بعد الاسترجاع، وقبل ما يتصل بسيرفرنا، التطبيق بيقفل الاختيار ده ويمسح العيّنات اللي ما اتبعتتش
والنقط المستنية الرد، وبيقولك؛ وتقدر تفتحه تاني. اختيارك للخريطة المتشاركة وحدود السرعة بيفضل زي ما هو. التسجيلات (مقفولة من الأول) بقت فيها كمان سطور *power*: إمتى التطبيق قلّل الـ GPS
عشان العربية واقفة وإمتى رجّعه.

**ابدأ التسجيل لما أسوق (مقفول من الأول؛ الإعدادات ← التشغيل التلقائي):** لو فتحته، التطبيق بيعرف إنك بتسوق
ويبدأ يسجّل من غير بلوتوث، **حتى والتطبيق مقفول أو مش مستخدم**. قبل ما أندرويد يطلب إذن المكان، التطبيق بيقولك
بيجمع إيه وليه، وتقدر تقول «لأ، شكرًا».
- بيستخدم حسّاس الحركة، وبعده كشف GPS لكام دقيقة (3 ونص بالكتير) يقيس سرعتك، وتحديثات المكان اللي تطبيقات تانية طلبتها.
  **كل ده بيتحسب على الموبايل** ومفيش حاجة منه بتتبعت.
- نسخة جوجل بلاي بس: لو سمحت بـ«النشاط البدني»، خدمات جوجل بلاي هي اللي بتحدد النشاط (تحت سياسة خصوصية جوجل:
  <https://policies.google.com/privacy>)، والتطبيق بيوصله بس التغيير «في عربية» أو «ماشي». النسخة المفتوحة (F-Droid / GitHub) عمرها ما بتستخدمه.
- **المكان في الخلفية («السماح طوال الوقت»)** لازم عشان التسجيل بيبدأ والتطبيق مش على الشاشة، وبيُستخدم بس عشان
  يعرف إنك بتسوق ويسجّل المشوار.
- التسجيل اللي بدأ لوحده زي اللي إنت بدأته: نفس البيانات ونفس الخصائص، ومنها المكان التقريبي (حوالي 1 كم) أول
  التسجيل وآخر الرحلة لو اخترت «استقبل بس» أو «شارك واستقبل».
- **ممكن يبدأ في أتوبيس أو قطر أو عجلة**، فبعدها التطبيق بيسأل **«كنت إنت اللي سايق؟»**. لحد ما تقول «أيوه، أنا
  سقت»، نقط المطبّات وطريق حدود السرعة وعيّنات التدريب بتاعة الرحلة **بتفضل على الموبايل**. «لأ» بيمسحهم مع الرحلة
  والأماكن اللي هي بس لقتها. ولو ما ردّتش خلال **24 ساعة** عمرهم ما بيتبعتوا، وبيتمسحوا أول مزامنة أو أول ما التطبيق
  يشتغل بعدها (والرحلة نفسها بتفضل على الموبايل). الرحلة اللي بلوتوث العربية اتوصّل فيها بتتحسب متأكّدة.
  **استثناء:** لو «حد السرعة المباشر والتنبيه» شغّال، عمليات البحث بتاعته بتتبعت أثناء الرحلة، قبل ما ترد؛ مفيش حاجة
  منها بتتحفظ، فـ«لأ» مفيش حاجة يمسحها (ومش ممكن يرجّع عمليات بحث اتعملت خلاص).
  المكان التقريبي (حوالي 1 كم) أول وآخر الرحلة بيتبعت عادي لو اخترت «استقبل بس» أو «شارك واستقبل».
- **الإيقاف التلقائي:** التسجيل بيقف بعد ما العربية تركن عدد الدقايق اللي تختاره (5 من الأول، أو «أبدًا»)، بس بعد
  سواقة حقيقية، وعمره ما بيقف وبلوتوث العربية متوصّل. لو فيه وقت متحدد، بيقف كمان بعد دقيقة من ما جوجل يشوفك ماشي
  (نسخة جوجل بلاي)، أو بعد 15 دقيقة من غير GPS. وأيًا كان الإعداد، حتى «أبدًا»، التسجيل اللي بدأ لوحده وما وصلش
  لسرعة سواقة بيقف بعد 10 دقايق.
- بعد إعادة تشغيل الموبايل أو تحديث التطبيق بيرجع يشتغل، بس لو المفتاح ده مفتوح.
- **عشان تلغيه:** اقفل المفتاح، أو خلّي إذن المكان «أثناء استخدام التطبيق فقط» أو «رفض» من إعدادات أندرويد
  (الإعدادات ← التطبيقات ← Bump Beeper ← الأذونات ← الموقع). ساعتها المفتاح بيقول «محتاج إذن». و«النشاط البدني»
  بيتلغي بنفس الطريقة.

**التحقق من التحديثات (أوتوماتيك):** مرة في اليوم بالكتير بيسأل GitHub لو فيه نسخة أجدد، من غير أي بيانات عنك. ده
الحاجة الوحيدة اللي بتتبعت لينا أو لـ GitHub قبل ما تجاوب على سؤال الخريطة المتشاركة.

**خريطة الشوارع (لما تكون الخريطة على الشاشة):** الخريطة بتنزل من OpenFreeMap (<https://openfreemap.org>، مجاني،
وبيانات الخريطة © المساهمين في OpenStreetMap). زي أي موقع، OpenFreeMap بيشوف عنوان الإنترنت (IP) بتاعك والمنطقة اللي
بتتفرّج عليها (ومع خريطة شاشة السواقة: تقريبًا انت سايق فين). من غير حساب ولا مُعرّف، ومطبّاتك ورحلاتك مش بتتبعت؛
بتترسم على موبايلك. الخريطة اللي شفتها بتتحفظ على الموبايل (حوالي 50 ميجا) عشان تشتغل من غير نت. تقدر تقفل «اعرض
الخريطة وانت سايق» من الإعدادات.

**الخريطة المتشاركة (اختياري، بتتسأل مرة):** لحد ما تختار، مفيش حاجة بتتبعت لسيرفرنا.
- «استقبل بس»: مُعرّف مجهول (تسجيل دخول عشوائي بيعمله التطبيق، مش رقم تليفونك)، نسخة التطبيق والأندرويد، ومكان
  تقريبي (متقرّب لحوالي 1 كم) أول ما التسجيل يبدأ وآخر الرحلة، عشان ينزّل المطبّات اللي حواليك.
- «شارك واستقبل»: ده كمان + كل مطبّ أو حفرة بتلاقيها (المكان، الوقت، الاتجاه، السرعة، قوّتها)، عمره ما بيبعت
  طريقك، ولا أي حاجة في حدود 300 متر من أول أو آخر الرحلة. وتقارير الأعطال لو التطبيق وقع: وقت العطل بفرق التوقيت،
  نسخة التطبيق، الشركة والموديل، نسخة الأندرويد، اسم الـ thread، ورسالة الخطأ من غير مسارات ملفات ولا أرقام ممكن
  تكون إحداثيات. من غير مكان.

**حدود السرعة الحقيقية (مقفولة من الأول):** بعد ما توافق بس، وطول ما الخريطة المتشاركة شغّالة. بعد كل رحلة التطبيق
بيبعت طريق الرحلة (مكان دقيق، من غير أول وآخر 300 متر) لسيرفرنا، والسيرفر بيبعت النقط بس لـ TomTom عشان يعرف حدّ
السرعة. TomTom مش بياخد المُعرّف المجهول ولا عنوان الإنترنت (IP) بتاعك، والأوقات بتتغيّر لسنة 2000. سياسة خصوصية
TomTom: <https://www.tomtom.com/privacy/>. سيرفرنا مش بيحتفظ بحاجة غير عدد مرات البحث في اليوم، وبيتمسح بعد 30 يوم.
الطريق بيستنى على موبايلك لحد ما يتبحث عنه؛ بعد حوالي 48 ساعة عمره ما بيتبعت، وبيتمسح أول مرة التطبيق يشتغل بعدها.

**حد السرعة المباشر والتنبيه (مقفول من الأول، منفصل عن حدود السرعة الحقيقية):** بعد ما توافق على الملاحظة بتاعته
بس، وطول ما الخريطة المتشاركة شغّالة. وانت سايق، التطبيق بيبعت آخر كام نقطة GPS بتوعك (حوالي آخر 300 متر؛ مكان
دقيق) مع المُعرّف المجهول لسيرفرنا، والسيرفر بيبعت النقط بس لـ TomTom عشان يعرف حد السرعة بتاع الطريق. كل قد إيه:
كل حوالي كيلومتر، وأكتر لو دخلت طريق تاني أو بتسوق بالراحة (كل 30 ثانية بالكتير؛ حوالي كل دقيقتين وانت بطيء)،
وعمره ما بيبعت تحت 10 كم/س. مفيش حاجة بتتبعت في أول 300 متر من الرحلة ولا في حدود 300 متر من المكان اللي الرحلة
بدأت منه، بس آخر الرحلة مش ممكن نحميه، لأن التطبيق ميعرفش انت هتقف فين. TomTom مش بياخد المُعرّف المجهول ولا عنوان
الإنترنت (IP) بتاعك، والأوقات اللي بياخدها بتتغيّر لسنة 2000. سيرفرنا مش بيحتفظ بحاجة غير عدد مرات البحث في اليوم،
وبيتمسح بعد 30 يوم. الحد بيظهر جنب سرعتك (مع «© TomTom»)، ولو عدّيته بالهامش اللي اخترته (+5 أو +10 أو +20 كم/س)
لمدة 3 ثواني هتسمع نغمة وبعدها الحد بصوت. على الموبايل الحد بيفضل في الذاكرة بس، 5 دقايق بالكتير، وعمره ما بيتحفظ.
بيشتغل كمان في الرحلات اللي بدأت لوحدها، حتى قبل ما ترد على «كنت إنت اللي سايق؟». لحد 60 مرة بحث في اليوم، مشتركة مع
البحث اللي بعد الرحلة؛ البحث المباشر بيقف لما يفضل 4، عشان البحث اللي بعد الرحلة يفضل شغّال. تقدر تقفله في أي وقت.

**ساعد في تحسين الكشف (مقفول من الأول، منفصل عن الخريطة المتشاركة):** بيظهر بس بعد ما تجاوب على سؤال الخريطة
المتشاركة وبعد ما توافق على الملاحظة بتاعته.
- لكل مطبّ محتمل التطبيق بيحكم عليه: لحد 4 ثواني من قراءات حسّاس الحركة (ثانيتين قبل وثانيتين بعد؛ الهزّة،
  والدوران لو فيه جيروسكوب)،
  قرار التطبيق وسببه، السرعة، تغيير الاتجاه، دقة الـ GPS، والموبايل متحطّ فين. تاريخ المشوار بس من غير الساعة،
  ونسخة الأندرويد والتطبيق وماركة الموبايل (مش الموديل).
- لكل رحلة: التاريخ، المدة، المسافة، الأعداد، التنبيهات، أرقام الدرجة، والبطارية.
- من غير طريق ومن غير إحداثيات. معلومة المكان الوحيدة رقم مطبّ عام ومتأكّد عدّيت عليه، وعمره ما بيكون في حدود
  300 متر من أول أو آخر الرحلة. مع الوقت ده ممكن يلمّح للمناطق اللي بتسوق فيها.
- مُعرّف عشوائي بيتغيّر كل مرة تفتحه. على سيرفرنا بيتحفظ مع السجل المجهول بتاع الموبايل ده (عشان يتمسح لما
  تقفله)، والسجل ده مربوط كمان بمساهماته في الخريطة المتشاركة. العيّنات مش مربوطة بالرحلات.
- بيترفع بعد المشوار بساعة لـ 6 ساعات، حوالي 30 لـ 90 كيلوبايت. بيتحفظ 7 أيام على الموبايل لو ما اتبعتش، و12 شهر
  على السيرفر، بس لتحسين الكشف، وصاحب التطبيق بيحلّله أوفلاين، وعمره ما بيتباع.
- لو قفلته أو مسحت بياناتك المتشاركة بيتمسح من السيرفر على طول لو فيه نت، وإلا أول ما يتوصّل (الإعدادات بتقولك إنه
  مستني)، ما عدا نسخ مزوّد الخدمة الاحتياطية والسجلات اللي بتتمسح مع الوقت. ولو تسجيل الدخول المجهول اتغيّر، التطبيق
  بيقفله ويقولك، وتقدر تفتحه تاني بمُعرّف جديد.

**تسجيلات البحث (بتتسأل مرة أول ما تفتح التطبيق؛ الإعدادات ← تسجيلات البحث):** اختيار منفصل عن كل اللي فوق، بيتسأل
مرة على شاشة أول تشغيل («ساعد في تحسين الكشف بتسجيل كل الحسّاسات»، نسخة الموافقة 1) بعد سؤال الخريطة المتشاركة،
وبيفضل مقفول إلا لو قلت **أيوه**.
- **بيتسجّل وانت سايق:** كل حسّاسات الحركة لحد 200 مرة في الثانية، والضغط والإضاءة والقرب؛ كل قراءة GPS (المكان
  والسرعة والاتجاه والدقة) وعدد الأقمار؛ **إشارات استخدام الموبايل**: الشاشة شغّالة ولا لأ، فتح القفل، حالة القفل،
  حالة المكالمة والصوت رايح فين (بلوتوث أو سلك، عمره ما بيسجّل الكلام)، بلوتوث العربية، الشحن والبطارية؛ **الخطوات
  وكشف المشي والسواقة** (نسخة جوجل بلاي بس، بإذن «النشاط البدني»: عدّاد الخطوات في الموبايل وكشف النشاط بتاع جوجل)؛
  والعلامات اللي بتدوس عليها في وضع العلامات. كل ملف فيه كمان **تاريخ الرحلة ووقتها** (بالثانية، وفي اسمه كمان)، والرحلة
  بدأت إزاي (إنت، بلوتوث العربية أو كشف الحركة)، والموبايل متحطّ فين، و**شركة الموبايل والموديل** ولستة الحسّاسات
  بتاعته، ونسخة الأندرويد والتطبيق، ورقم ملفات عشوائي (بيتغيّر كل مرة تقول أيوه) ورقم الرحلة على الموبايل. **من غير مايك
  ولا كاميرا**، ومن غير اسم ولا إيميل ولا رقم تليفون.
- **على الموبايل:** التسجيل كامل، ومعاه طريق الـ GPS كله، بيتحفظ لحد **14 يوم** (7 أيام بعد ما يترفع) في مساحة
  التطبيق اللي عمرها ما بتدخل النسخة الاحتياطية ولا بتتنقل لموبايل جديد. «شارك التسجيلات» بيحفظ كمان ملف zip في
  **Downloads/BumpBeeper/research**، وتطبيقات تانية تقدر تقراه ومش بيتمسح بعد 14 يوم. التسجيلات اللي قبل آخر مرة قفلته
  عمرها ما بتترفع، وبتتمسح لما تفتحه تاني. بعد استرجاع نسخة احتياطية، تسجيل البحث بيتقفل والسؤال بيرجع تاني.
- **اللي بيطلع من الموبايل:** على **الواي فاي** بس، و**عمره ما بيطلع أول أو آخر الرحلة**: كل السطور، من كل نوع، اللي
  اتسجّلت قبل ما العربية تكون مشيت 300 متر وبعدت 300 متر (خط مستقيم) عن مكان بداية الرحلة بتتشال، وكمان كل اللي بعد
  آخر نقطة بعيدة أكتر من 300 متر سواقة عن الآخر وأكتر من 300 متر عن مكان نهاية الرحلة. يعني الموبايل وهو راكن في البيت
  مش بيبعت أي حاجة من الوقت ده، حتى لو الـ GPS بيتهزّ. ومفيش حاجة من رحلة أقصر من 600 متر. (لو الطريق عدّى جنب
  البداية أو النهاية تاني في نص الرحلة، الجزء ده بيفضل.) الرحلات اللي بدأت لوحدها بتستنى ردّك على «كنت إنت اللي
  سايق؟»، و«لأ» بيمسح تسجيلاتها. حوالي 27–32 ميجا في كل ساعة سواقة.
- **فين ومين:** مخزن خاص على سيرفرنا (Supabase، فرانكفورت)، في فولدر باسم المُعرّف المجهول بتاعك، اللي بيظهر في
  الإعدادات باسم **رقم البحث**. الموبايل يقدر يضيف ملفات بس، عمره ما يقراها. صاحب التطبيق بينزّل الملفات للبحث (مرة في
  الأسبوع على الأقل) وده بيمسحها من السيرفر، وبيستخدمها بس لتحسين الكشف، وعمرها ما بتتباع ولا بتُستخدم للإعلانات.
  **TODO-OWNER: صاحب التطبيق بيحتفظ بالنسخ اللي نزّلها لحد 12 شهر** (صاحب التطبيق لسه هيأكّد المدة).
- **لو قفلته** التسجيل والرفع بيقفوا على طول. الملفات اللي اترفعت **بتفضل لحد ما تطلب مسحها**. الطريقة الخاصة هي
  «امسح بياناتي المتشاركة» (الإعدادات ← الخريطة المتشاركة): بيمسحها هي والنسخ اللي اتنزّلت أول مرة صاحب التطبيق يشغّل
  أداة البحث (مرة في الأسبوع على الأقل). وتقدر كمان تطلب من صاحب التطبيق برقم البحث بتاعك (الإعدادات ← تسجيلات البحث ←
  «انسخ رقم البحث») على العنوان الخاص اللي تحت. **الـ issues على GitHub عامة: عمرك ما تكتب رقم البحث بتاعك هناك.**

**السيرفر:** Supabase في فرانكفورت، ألمانيا، والبحث عن حدود السرعة بيشتغل في فرانكفورت كمان. تسجيلات البحث بتفضل في
مخزن خاص لحد ما صاحب التطبيق ينزّلها (مرة في الأسبوع على الأقل)، ومع «امسح بياناتي المتشاركة» بتتمسح أول مرة يشغّل
الأداة؛ سجل الرفع بتاعها (المُعرّف المجهول، اسم الملف بوقت بداية الرحلة، الحجم، ووقت الرفع) بيفضل بعد ما الملف يتمسح، لحد
ما تمسح بياناتك المتشاركة أو تطلب المسح. التقارير بتتدمج في
الخريطة وبتتمسح بعد حوالي يوم. سجل الموبايل، ومساهماته في الأماكن (عدد المرات، من غير أوقات)، وتقارير الأعطال بيفضلوا
لحد ما تمسح بياناتك المتشاركة. عيّنات التدريب وملخّصات الرحلات: 12 شهر على السيرفر أو لحد ما تقفل «ساعد في تحسين
الكشف» أو تمسح بياناتك المتشاركة؛ اللي لسه ما اتبعتش: 7 أيام على الموبايل. بيانات الرحلة المستنية «كنت إنت اللي
سايق؟»: لحد ما ترد، وبعد 24 ساعة عمرها ما بتتبعت (ما عدا بحث حد السرعة المباشر، اللي بيتبعت أثناء الرحلة ومش بيتحفظ). عدّادات الرفع اليومية (أعداد بس): يومين.

**اختياراتك:**
- تقدر تغيّر أو تلغي موافقتك في أي وقت من الإعدادات.
- «امسح بياناتي المتشاركة» (الإعدادات ← الخريطة المتشاركة) بيمسح سجل الموبايل ده على السيرفر، ومعاه التقارير
  اللي بعتها، وربطه بالأماكن، وتقارير الأعطال، وعيّنات التدريب. الأماكن اللي اتدمجت بتفضل من غير أي حاجة تربطها بالموبايل ده. اللي
  بيفضل: تسجيل الدخول المجهول نفسه، وعدّاد البحث اليومي لحد ما يتمسح بعد 30 يوم. وبيقفل الخريطة المتشاركة وحدود
  السرعة الحقيقية وحد السرعة المباشر و«ساعد في تحسين الكشف» وتسجيلات البحث، فهتتسأل تاني قبل أي بحث أو رفع جديد.
  ملفات البحث اللي اترفعت بتتمسح من غير ما تتقري أول مرة صاحب التطبيق يشغّل أداة البحث (مرة في الأسبوع على الأقل)،
  هي والنسخ اللي اتنزّلت؛ التسجيلات اللي لسه على الموبايل بتفضل عليه (14 يوم بالكتير).
- «امسح الخريطة» (الإعدادات ← بياناتك) بيمسح المطبّات والحفر والرحلات والدرجات اللي على الموبايل والطرق المستنية،
  مش الإعدادات ولا التسجيلات.
- عشان تمسح كل حاجة على الموبايل: إعدادات أندرويد ← التطبيقات ← Bump Beeper ← التخزين ← مسح التخزين، أو امسح
  التطبيق.

أسئلة: افتح issue على <https://github.com/Ahmedhesham2025/Speedbumb/issues>. **الـ issues عامة**: عمرك ما تكتب رقم
البحث بتاعك أو أي حاجة شخصية هناك. طلبات المسح والأسئلة الخاصة: **TODO-OWNER: عنوان تواصل خاص (زي إيميل) صاحب التطبيق
هيضيفه**؛ لحد ما يتضاف، استخدم «امسح بياناتي المتشاركة» في التطبيق.
