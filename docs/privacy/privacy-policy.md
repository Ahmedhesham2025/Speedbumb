# Bump Beeper privacy policy

*Draft for review (security-privacy). Last updated: 2026-10-03. Arabic version below.*

Bump Beeper warns you about speed bumps and potholes and scores your driving. It is free, open source (GPL-3.0,
<https://github.com/Ahmedhesham2025/Speedbumb>), and has **no ads, no analytics and no trackers**. You never sign up:
there is no name, email or phone number anywhere in the app.

## What is kept on your phone
Your bump map, your trips and scores, your settings, and debug recordings (if you turn them on) are kept on the phone.
They leave it when **you** share or export them (Share, Save all, Export recordings), and through **Android backup**:
if backup is on for your phone, Android copies the bump map, trips, scores and settings to your Google account
backup. Debug recordings, crash files and routes waiting for a speed-limit lookup are never put in the cloud backup
(recordings do move along in a direct phone-to-phone transfer). The shared-map sign-in is never backed up either.

## What leaves your phone

### 1. Update check (automatic)
At most once a day the app asks GitHub (<https://github.com>) whether a newer version exists. Nothing about you, your
phone or your drives is sent; GitHub sees an ordinary web request (your internet address and the app version). This is
the only thing the app sends before you answer the shared-map question.

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
| Speed-limit lookups | Only a daily count per anonymous ID, deleted after 30 days |
| Anonymous sign-in (the ID itself, and the sign-in service's records) | Not deleted by the app yet |
| Route waiting for a lookup (on the phone) | Until looked up; never sent after about 48 hours |

## Your choices
- **Change or withdraw consent** at any time: Settings → Shared map (*Receive only*, *Share and receive*, or *Off*), and
  the *Use real speed limits* switch.
- **Delete my shared data** (Settings → Shared map): deletes this phone's device record on the server and with it the
  bump and pothole reports it sent, its links to spots (contributions) and its crash reports. Spots already merged into
  the shared map stay, with nothing linking them to this phone any more. What stays: the anonymous sign-in itself
  (with the sign-in service's own records) and the speed-limit day counts until they expire after 30 days. On the
  phone it clears the downloaded warnings, turns the shared map **and** real speed limits off (so you are asked again
  before any new lookup), and keeps your own bump map and trips.
- **Clear the map** (Settings → Your data): deletes the bumps, potholes, event log, trips and scores on the phone, and
  routes waiting for a lookup. It does not delete settings or debug recordings (those have their own *Delete
  recordings* button).
- **Delete everything on the phone:** Android Settings → Apps → Bump Beeper → Storage → *Clear storage*, or uninstall.

## Children
The app is meant for drivers and is not directed at children.

## Changes and contact
Changes to this policy are made in the public repository, where you can see every version. Questions: open an issue
at <https://github.com/Ahmedhesham2025/Speedbumb/issues>.

## For store listings
Notes for the Play Store *Data safety* form and the F-Droid listing (store-docs to finalise):
- **Collected** (all optional, user-chosen, encrypted in transit):
  - *Location → Approximate location*: the ~1 km area for downloading spots (processed ephemerally, not stored).
  - *Location → Precise location*: bump/pothole points with *Share and receive*; trip routes with real speed limits
    (routes are processed ephemerally: not stored by our server, and TomTom's terms forbid storing results).
  - *App info and performance → Crash logs* (*Share and receive* only).
  - *Device or other IDs*: the anonymous sign-in ID.
  - Purposes: app functionality (crash logs: also analytics of crashes). Never ads or marketing.
- **Shared:** *Location → Precise location* with a third party (**TomTom**), optional, processed ephemerally, only
  with real speed limits on.
- **Tracking:** not applicable (no advertising ID, no cross-app tracking, no analytics SDK).
- **Deletion:** in the app (*Delete my shared data*); see what stays above.
- **F-Droid:** the app contacts a non-free network service (TomTom, via our server) only when the user opts in:
  declare the *NonFreeNet* anti-feature.

---

# سياسة الخصوصية (بالعربي)

*مسودة للمراجعة. لو فيه أي اختلاف، النسخة الإنجليزي اللي فوق هي المرجع.*

بامب بيبر بينبّهك للمطبّات والحفر وبيدّي درجة لسواقتك. مجاني ومفتوح المصدر، ومن غير إعلانات ولا تتبّع. مش محتاج
تعمل حساب، ومفيش اسم ولا إيميل ولا رقم تليفون.

**اللي بيتحفظ على موبايلك:** خريطة المطبّات، رحلاتك ودرجاتك، والإعدادات. بتخرج لو إنت شاركتها أو صدّرتها، ولو
النسخ الاحتياطي بتاع أندرويد شغّال، أندرويد بينسخ الخريطة والرحلات والإعدادات على حساب جوجل بتاعك. التسجيلات وتقارير
الأعطال والطرق اللي مستنية البحث عمرها ما بتدخل النسخة الاحتياطية.

**التحقق من التحديثات (أوتوماتيك):** مرة في اليوم بالكتير بيسأل GitHub لو فيه نسخة أجدد، من غير أي بيانات عنك. ده
الحاجة الوحيدة اللي بتتبعت قبل ما تجاوب على سؤال الخريطة المتشاركة.

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

**السيرفر:** Supabase في فرانكفورت، ألمانيا، والبحث عن حدود السرعة بيشتغل في فرانكفورت كمان. التقارير بتتدمج في
الخريطة وبتتمسح بعد حوالي يوم. سجل الموبايل، ومساهماته في الأماكن (عدد المرات، من غير أوقات)، وتقارير الأعطال بيفضلوا
لحد ما تمسح بياناتك المتشاركة.

**اختياراتك:**
- تقدر تغيّر أو تلغي موافقتك في أي وقت من الإعدادات.
- «امسح بياناتي المتشاركة» (الإعدادات ← الخريطة المتشاركة) بيمسح سجل الموبايل ده على السيرفر، ومعاه التقارير
  اللي بعتها، وربطه بالأماكن، وتقارير الأعطال. الأماكن اللي اتدمجت بتفضل من غير أي حاجة تربطها بالموبايل ده. اللي
  بيفضل: تسجيل الدخول المجهول نفسه، وعدّاد البحث اليومي لحد ما يتمسح بعد 30 يوم. وبيقفل الخريطة المتشاركة وحدود
  السرعة الحقيقية، فهتتسأل تاني قبل أي بحث جديد.
- «امسح الخريطة» (الإعدادات ← بياناتك) بيمسح المطبّات والحفر والرحلات والدرجات اللي على الموبايل والطرق المستنية،
  مش الإعدادات ولا التسجيلات.
- عشان تمسح كل حاجة على الموبايل: إعدادات أندرويد ← التطبيقات ← Bump Beeper ← التخزين ← مسح التخزين، أو امسح
  التطبيق.

أسئلة: افتح issue على <https://github.com/Ahmedhesham2025/Speedbumb/issues>.
