# Bump Beeper privacy policy

*Draft for review (security-privacy). Last updated: 2026-10-03. Arabic version below.*

Bump Beeper warns you about speed bumps and potholes and scores your driving. It is free, open source (GPL-3.0,
<https://github.com/Ahmedhesham2025/Speedbumb>), and has **no ads, no analytics and no trackers**. You never sign up:
there is no name, email or phone number anywhere in the app.

## What stays on your phone
Your bump map, your trips and scores, settings, and debug recordings (if you turn them on) are stored only on the
phone. They leave it only when **you** share or export them (Share, Save all, Export recordings). "Clear all data" in
Settings deletes them.

## What leaves your phone, and only if you choose it

### 1. Shared map (asked once; you can change it in Settings → Shared map)
Until you answer, the app sends nothing to our server.

| Choice | What is sent | Why |
|---|---|---|
| *Receive only* | An anonymous phone ID (a random account created by the app), the app and Android version, your answer, and a rough area (about 1 km) around where your last trip was | To download confirmed bumps and potholes near you |
| *Share and receive* | The above, plus each bump or pothole you find: place, time, direction, speed, how strong it was. **Never your route**, and nothing within 300 m of where a trip starts or ends. Plus crash reports if the app crashes (app version, phone model, the error; file paths and numbers that could be coordinates are removed first) | To build the shared map; to fix crashes |

### 2. Real speed limits (off by default; Settings → Driving score → Use real speed limits)
Only after you agree to the notice, and only while the shared map is on (otherwise the app stays offline).
- **What:** after each trip, the trip's route (sampled points with times), **without its first and last 300 m**,
  together with your anonymous phone ID so our server can enforce a daily limit of lookups.
- **Where:** to our server, which forwards only the points to **TomTom** to find the speed limit of each road.
  TomTom does not get your phone ID or your phone's internet address, and the times it gets are shifted to the year
  2000, so it does not learn when you drove. TomTom is a recipient of this data under its own privacy notice:
  <https://www.tomtom.com/privacy/>.
- **Why:** to score speeding against each road's real limit. The trip then shows "© TomTom".
- **How long:** our server keeps nothing from the lookup except a count of lookups per phone per day, deleted after
  30 days. On the phone the route waits until it is looked up, at most about **48 hours**, then it is deleted. It is
  also deleted at once if you turn the switch off, turn the shared map off, delete your shared data or clear all data.
  The limits themselves are saved only in your trip on the phone.

### 3. Update check
At most once a day the app asks GitHub (<https://github.com>) whether a newer version exists. Nothing about you, your
phone or your drives is sent; GitHub sees an ordinary web request (your internet address and the app version).

## Where the data is kept
Our server is [Supabase](https://supabase.com) in Frankfurt, Germany (EU). Like any web service it sees the internet
address a request comes from (Supabase's own request logs may hold it for a short time); our tables do not store it.

## How long

| Data | Kept |
|---|---|
| Bump and pothole reports | Merged into shared spots, then deleted about a day later. A spot keeps only counts (how many phones, hits), not who or when |
| Shared spots | As long as they are useful; they are not linked to you |
| Anonymous phone ID, app/Android version, your choice | Until you delete your shared data |
| Crash reports | Until you delete your shared data (no automatic expiry yet) |
| Speed-limit lookups | Nothing but a daily count, deleted after 30 days |
| Route waiting for a lookup (on the phone) | Until looked up, at most about 48 hours |

## Your choices
- **Change or withdraw consent** at any time: Settings → Shared map (*Receive only*, *Share and receive*, or off), and
  the *Use real speed limits* switch.
- **Delete your shared data ("forget me"):** Settings → Shared map → *Delete my shared data* deletes everything this
  phone sent (phone ID, reports, crash reports) from the server at once (the daily lookup counts expire after 30 days), clears the downloaded warnings
  and turns the shared map off. Spots already merged into the shared map stay, with nothing linking them to you.
- **Delete everything on the phone:** Settings → *Clear all data*, or uninstall the app.

## Children
The app is meant for drivers and is not directed at children.

## Changes and contact
Changes to this policy are made in the public repository, where you can see every version. Questions: open an issue
at <https://github.com/Ahmedhesham2025/Speedbumb/issues>.

## For store listings
Notes for the Play Store *Data safety* form and the F-Droid listing (store-docs to finalise):
- **Data collected** (optional, user-chosen): *Location → Approximate location* (the ~1 km download area) and
  *Precise location* (bump/pothole points with *Share and receive*; trip routes with real speed limits); *App info
  and performance → Crash logs* (*Share and receive* only); *Device or other IDs* (the anonymous phone ID).
  Purposes: app functionality; crash logs also for analytics of crashes. Not used for ads or marketing.
- **Data shared:** route points with **TomTom** (a third party) for speed-limit lookups, only with that switch on.
- Encrypted in transit (HTTPS). Users can request deletion in the app (*Delete my shared data*).
- No ads, no account, no tracking SDKs. F-Droid: the app contacts a non-free network service (TomTom, via our
  server) only when the user opts in, which should be declared as the *NonFreeNet* anti-feature.

---

# سياسة الخصوصية (بالعربي)

*مسودة للمراجعة. لو فيه أي اختلاف، النسخة الإنجليزي اللي فوق هي المرجع.*

بامب بيبر بينبّهك للمطبّات والحفر وبيدّي درجة لسواقتك. مجاني ومفتوح المصدر، ومن غير إعلانات ولا تتبّع. مش محتاج
تعمل حساب، ومفيش اسم ولا إيميل ولا رقم تليفون.

**اللي بيفضل على موبايلك:** خريطة المطبّات، رحلاتك ودرجاتك، والإعدادات. ما بتخرجش غير لو إنت شاركتها أو صدّرتها.
«مسح كل البيانات» في الإعدادات بيمسحها.

**الخريطة المشتركة (اختياري، بتتسأل مرة):** لحد ما تجاوب، التطبيق مش بيبعت حاجة لسيرفرنا.
- «استقبل بس»: رقم موبايل مجهول (حساب عشوائي بيعمله التطبيق)، نسخة التطبيق والأندرويد، ومنطقة تقريبية (حوالي 1 كم)
  عشان ينزّل المطبّات اللي حواليك.
- «شارك واستقبل»: ده كمان + كل مطبّ أو حفرة بتلاقيها (المكان، الوقت، الاتجاه، السرعة، قوّتها)، عمره ما بيبعت
  طريقك، ولا أي حاجة في حدود 300 متر من أول أو آخر الرحلة؛ وتقارير الأعطال لو التطبيق وقع (من غير مكان).

**حدود السرعة الحقيقية (مقفولة من الأول):** بعد ما توافق بس، وطول ما الخريطة المشتركة شغّالة. بعد كل رحلة التطبيق
بيبعت طريق الرحلة (من غير أول وآخر 300 متر) لسيرفرنا، والسيرفر بيبعت النقط بس لـ TomTom عشان يعرف حدّ السرعة.
TomTom مش بياخد رقم موبايلك ولا عنوانه على الإنترنت، والأوقات بتتغيّر لسنة 2000. سياسة خصوصية TomTom:
<https://www.tomtom.com/privacy/>. سيرفرنا مش بيحتفظ بحاجة غير عدد مرات البحث في اليوم، وبيتمسح بعد 30 يوم. الطريق
بيستنى على موبايلك لحد ما يتبحث عنه، أقصى حاجة حوالي 48 ساعة، وبعدين بيتمسح.

**التحقق من التحديثات:** مرة في اليوم بالكتير بيسأل GitHub لو فيه نسخة أجدد، من غير أي بيانات عنك.

**السيرفر:** Supabase في فرانكفورت، ألمانيا. التقارير بتتدمج في الخريطة وبتتمسح بعد حوالي يوم. تقارير الأعطال ورقم
الموبايل المجهول بيفضلوا لحد ما تمسح بياناتك المشتركة.

**اختياراتك:** تقدر تغيّر أو تلغي موافقتك في أي وقت من الإعدادات. «امسح بياناتي المشتركة» (الإعدادات ← الخريطة
المشتركة) بيمسح كل اللي الموبايل ده بعته من السيرفر فورًا. الأماكن اللي اتدمجت في الخريطة بتفضل، من غير أي حاجة
بتربطها بيك.

أسئلة: افتح issue على <https://github.com/Ahmedhesham2025/Speedbumb/issues>.
