@file:Suppress("DEPRECATION")   // RemoteSpot still carries the shared map's old kind and side

package app.bumpbeeper

/**
 * Simulated drives that check the behaviour end to end.
 * Run from Android Studio (BumpEngineTest) — no phone needed.
 */
object Scenarios {
    var log: (String) -> Unit = { println(it) }

    private fun check(ok: Boolean, msg: String) {
        if (!ok) throw AssertionError(msg)
    }

    private fun f1(x: Double) = formatFixed(x, 0)

    private fun describe(name: String, r: TripResult) {
        log(
            "  $name: new=${r.newBumps} knownHits=${r.knownHits} beeps=${r.beepBumpIds.size} " +
                "beepDist=${r.beepTrueDistM.map { f1(it) }} misses=${r.stats.misses} rejected=${r.rejected}"
        )
    }

    /** 3 real bumps + 1 one-off jolt. Drive 4 times: learn, then beep, then the one-off gets muted. */
    fun learnThenBeep() {
        log("learnThenBeep")
        val sim = Simulator(42)
        val store = MemoryStore()
        val real = listOf(500.0, 1100.0, 1600.0)
        val zone = listOf(800.0)

        val t1 = sim.drive(store, DriveSpec(bumpsAt = real, oneOffJoltsAt = listOf(800.0), slowZonesAt = zone), tripId = 1)
        describe("trip 1", t1)
        check(t1.beepBumpIds.isEmpty(), "trip 1 must not beep (nothing known yet), got ${t1.beepBumpIds.size}")
        check(t1.newBumps == 4, "trip 1 should record 3 bumps + the one-off jolt, got ${t1.newBumps}")

        for (trip in 2..4) {
            val r = sim.drive(store, DriveSpec(bumpsAt = real, slowZonesAt = zone), tripId = trip.toLong())
            describe("trip $trip", r)
            val expectBeeps = if (trip <= 3) 4 else 3   // one-off spot muted after 3 passes with 1 hit
            check(r.beepBumpIds.size == expectBeeps, "trip $trip: expected $expectBeeps beeps, got ${r.beepBumpIds.size}")
            check(r.newBumps == 0, "trip $trip: no new bumps expected, got ${r.newBumps}")
            check(r.knownHits == 3, "trip $trip: expected 3 known hits, got ${r.knownHits}")
            for (d in r.beepTrueDistM) check(d in 40.0..160.0, "trip $trip: beep came at ${f1(d)} m, expected 40–160 m ahead")
        }

        check(store.saved.size == 4, "expected 4 spots on the map, got ${store.saved.size}")
        for (p in real) {
            val truth = sim.point(p, false)
            val b = store.saved.minByOrNull { Geo.distance(it.lat, it.lon, truth[0], truth[1]) }!!
            val err = Geo.distance(b.lat, b.lon, truth[0], truth[1])
            log("  bump @${f1(p)} m: hits ${b.hits}/${b.passes}, position error ${formatFixed(err, 1)} m")
            check(b.hits == 4 && b.passes == 4, "bump at $p: expected 4/4, got ${b.hits}/${b.passes}")
            check(err < 8.0, "bump at $p: position error ${f1(err)} m too big")
        }
        val ghostTruth = sim.point(800.0, false)
        val ghost = store.saved.minByOrNull { Geo.distance(it.lat, it.lon, ghostTruth[0], ghostTruth[1]) }!!
        log("  one-off @800 m: hits ${ghost.hits}/${ghost.passes}, misses ${ghost.misses}, muted=${ghost.isMuted(EngineConfig())}")
        check(ghost.hits == 1 && ghost.passes == 4 && ghost.misses == 3, "one-off: expected 1/4 with 3 misses, got ${ghost.hits}/${ghost.passes} m=${ghost.misses}")
        check(ghost.isMuted(EngineConfig()), "one-off spot should be muted")
        check(store.saved.all { it.hits <= it.passes }, "hits must never exceed passes")
    }

    /** Bumps learned eastbound must not beep westbound; the westbound side is learned on its own. */
    fun otherDirection() {
        log("otherDirection")
        val sim = Simulator(7)
        val store = MemoryStore()
        val real = listOf(500.0, 1100.0, 1600.0)
        sim.drive(store, DriveSpec(bumpsAt = real), tripId = 1)
        val e2 = sim.drive(store, DriveSpec(bumpsAt = real), tripId = 2)
        describe("east 2", e2)
        check(e2.beepBumpIds.size == 3, "east trip 2 should beep 3 times, got ${e2.beepBumpIds.size}")

        val w1 = sim.drive(store, DriveSpec(westbound = true, bumpsAt = real), tripId = 3)
        describe("west 1", w1)
        check(w1.beepBumpIds.isEmpty(), "first westbound trip must not beep for eastbound bumps, got ${w1.beepBumpIds.size}")
        check(w1.newBumps == 3, "first westbound trip should record 3 new bumps, got ${w1.newBumps}")

        val w2 = sim.drive(store, DriveSpec(westbound = true, bumpsAt = real), tripId = 4)
        describe("west 2", w2)
        check(w2.beepBumpIds.size == 3, "second westbound trip should beep 3 times, got ${w2.beepBumpIds.size}")

        val east = store.saved.filter { Geo.angleDiff(it.heading, 90.0) < 45 }
        check(east.size == 3 && east.all { it.passes == 2 }, "eastbound bumps should still have 2 passes each: ${east.map { it.passes }}")
    }

    /** A passenger grabbing the phone must not create a bump: its jolts are rejected as "handled". */
    fun handlingIgnored() {
        log("handlingIgnored")
        val sim = Simulator(3)
        val store = MemoryStore()
        val r = sim.drive(store, DriveSpec(slowZonesAt = listOf(600.0), handlingAt = 600.0))
        describe("drive", r)
        check(r.newBumps == 0, "handling the phone created ${r.newBumps} bump(s)")
        check("handled" in r.rejected, "expected a handled rejection, got ${r.rejected}")
    }

    /**
     * Owner: "When I hold the phone, it counted as a bump." A real bump crossed while the phone is held still in a hand
     * (8 s, no shaking at that moment) is not learned either: the hand's reading is not the road's. Before E3 only the
     * pick-up and the put-down were rejected, and this jolt became a spot.
     */
    fun bumpWhileHoldingIsNotLearned() {
        log("bumpWhileHoldingIsNotLearned")
        val store = MemoryStore()
        val r = Simulator(91).drive(store, DriveSpec(holdsAt = listOf(560.0 to 8.0), bumpsAt = listOf(600.0), cruiseKmh = 30.0))
        describe("bump while holding", r)
        check(r.newBumps == 0 && store.saved.isEmpty(), "nothing is learned while the phone is held, got ${r.newBumps}")
        check(r.rejected.size == 3 && r.rejected.all { it == "handled" }, "pick-up, bump and put-down rejected as handled: ${r.rejected}")
        check(r.driving.phoneUse == 1, "and it is phone use, got ${r.driving.phoneUse}")
    }

    /**
     * A jostle is not handling: a jolt 1.2 s after a 0.8 s jostle in the pocket (the phone shifting while braking for a
     * bump) is learned. After real handling the 2 s calm and 2 s margin stay: a jolt 1.2 s after a 5 s hold, or during
     * it, is rejected.
     */
    fun joltsAroundHandling() {
        log("joltsAroundHandling")
        val pocket = DrivingConfig().apply { placement = "pocket" }
        val v = 40 / 3.6
        fun drive(spec: DriveSpec) = Simulator(101).drive(MemoryStore(), spec, drivingCfg = pocket)
        val jostle = drive(DriveSpec(cruiseKmh = 40.0, jostlesAt = listOf(700.0), jostleHeldS = 0.3, oneOffJoltsAt = listOf(700.0 + 2.0 * v)))
        describe("jolt 1.2 s after a 0.8 s jostle", jostle)
        check(jostle.newBumps == 1, "the jolt after the jostle is learned: ${jostle.rejected}")
        check(jostle.rejected == listOf("phone_moving"), "only the jostle's own jolt is rejected: ${jostle.rejected}")
        val after = drive(DriveSpec(cruiseKmh = 40.0, holdsAt = listOf(700.0 to 5.0), oneOffJoltsAt = listOf(700.0 + 6.8 * v)))
        describe("jolt 1.2 s after a 5 s hold", after)
        check(after.newBumps == 0 && after.rejected.last() == "handled", "rejected as handled: ${after.rejected}")
        val during = drive(DriveSpec(cruiseKmh = 40.0, holdsAt = listOf(700.0 to 5.0), oneOffJoltsAt = listOf(700.0 + 3.0 * v)))
        describe("jolt during a 5 s hold", during)
        check(during.newBumps == 0 && during.rejected.last() == "handled", "rejected as handled: ${during.rejected}")
    }

    /**
     * A known bump passed while the phone is held is neither a hit nor a miss: the pass is not counted ("pass_handled").
     * The next pass, phone at rest, counts again.
     */
    fun passWhileHandledNotCounted() {
        log("passWhileHandledNotCounted")
        val store = MemoryStore()
        val t1 = Simulator(91).drive(store, DriveSpec(bumpsAt = listOf(600.0), cruiseKmh = 30.0), tripId = 1)
        describe("learn", t1)
        check(t1.newBumps == 1 && store.saved.size == 1, "trip 1 learns the bump: ${t1.newBumps}")
        val before = store.saved[0].copy()
        val t2 = Simulator(92).drive(store, DriveSpec(holdsAt = listOf(560.0 to 8.0), bumpsAt = listOf(600.0), cruiseKmh = 30.0), tripId = 2)
        describe("passed while held", t2)
        val b = store.saved[0]
        check(t2.knownHits == 0 && t2.stats.misses == 0, "no hit and no miss while held: ${t2.knownHits} hits, ${t2.stats.misses} misses")
        check(b.hits == before.hits && b.passes == before.passes && b.misses == before.misses, "the pass is not counted: ${b.hits}/${b.passes}/${b.misses}")
        check(store.events.any { it.type == "pass_handled" && it.tripId == 2L }, "the pass is logged as pass_handled")
        val t3 = Simulator(93).drive(store, DriveSpec(bumpsAt = listOf(600.0), cruiseKmh = 30.0), tripId = 3)
        describe("passed at rest", t3)
        check(t3.knownHits == 1 && store.saved[0].passes == before.passes + 1, "the next pass counts: ${t3.knownHits} hits, ${store.saved[0].passes} passes")
    }

    /** Door slam while parked, and a jolt with no GPS yet: both ignored. */
    fun parkedAndNoGps() {
        log("parkedAndNoGps")
        val store = MemoryStore()
        val reasons = ArrayList<String>()
        val listener = object : EngineListener {
            override fun onJoltRejected(peak: Double, reason: String) { reasons.add(reason) }
        }
        val eng = BumpEngine(EngineConfig(), store, listener, { 0L })
        // No GPS yet, big jolt.
        for (i in 0 until 200) eng.onAccel(i * 20L, 0.0, 0.0, 9.81 + if (i in 50..53) 8.0 else 0.0)
        // Parked (speed 0), door slam.
        for (i in 200 until 500) {
            val t = i * 20L
            if (i % 50 == 0) eng.onFix(Fix(t, 30.0444, 31.2357, 0.0, Double.NaN, 5.0))
            eng.onAccel(t, 0.0, 0.0, 9.81 + if (i in 300..303) 8.0 else 0.0)
        }
        log("  rejected: $reasons")
        check(store.saved.isEmpty(), "nothing should be recorded")
        check(reasons == listOf("no_gps", "too_slow"), "expected [no_gps, too_slow], got $reasons")
    }

    /**
     * After the warning you crawl over the bump at 8 km/h and feel nothing: that must NOT count as a miss,
     * or the app would mute real bumps precisely because it warned you about them.
     * A bump that was really removed (you pass at normal bump speed and feel nothing) does get muted.
     */
    fun crawlVersusRemoved() {
        log("crawlVersusRemoved")
        val sim = Simulator(11)
        val store = MemoryStore()
        sim.drive(store, DriveSpec(bumpsAt = listOf(500.0, 1100.0)), tripId = 1)
        for (trip in 2..5) {
            // 500: crawled over at 8 km/h, nothing felt. 1100: removed; driver still slows to 15 but nothing is there.
            val r = sim.drive(store, DriveSpec(crawlAt = listOf(500.0), silentBumpsAt = listOf(1100.0)), tripId = trip.toLong())
            describe("trip $trip", r)
            check(r.beepBumpIds.isNotEmpty(), "trip $trip: the crawled-over bump should still beep")
        }
        val crawled = store.saved.minByOrNull { Geo.distance(it.lat, it.lon, sim.point(500.0, false)[0], sim.point(500.0, false)[1]) }!!
        val removed = store.saved.minByOrNull { Geo.distance(it.lat, it.lon, sim.point(1100.0, false)[0], sim.point(1100.0, false)[1]) }!!
        log("  crawled: ${crawled.hits}/${crawled.passes} muted=${crawled.isMuted(EngineConfig())}; removed: ${removed.hits}/${removed.passes} muted=${removed.isMuted(EngineConfig())}")
        check(!crawled.isMuted(EngineConfig()) && crawled.misses == 0, "crawled-over bump must stay active with 0 misses (got ${crawled.misses})")
        check(removed.isMuted(EngineConfig()), "removed bump should be muted after repeated misses")
    }

    /** "Mute last beep" makes a bump silent for good. */
    fun userMute() {
        log("userMute")
        val sim = Simulator(5)
        val store = MemoryStore()
        val real = listOf(500.0, 1100.0)
        sim.drive(store, DriveSpec(bumpsAt = real), tripId = 1)
        val listener = object : EngineListener {}
        val eng = BumpEngine(EngineConfig(), store, listener, { 0L })
        val target = store.saved.first()
        check(eng.muteBump(target.id) != null, "muteBump should find the bump")
        val r = sim.drive(store, DriveSpec(bumpsAt = real), tripId = 2)
        describe("trip 2", r)
        check(r.beepBumpIds.size == 1 && target.id !in r.beepBumpIds, "only the un-muted bump should beep, got ${r.beepBumpIds}")
        check(r.knownHits == 2, "muted bump should still be recorded as hit")
    }

    /** A spot felt once is a "maybe" (soft); felt again on a later trip it is confirmed (full). */
    fun softThenFull() {
        log("softThenFull")
        val sim = Simulator(81)
        val store = MemoryStore()
        val cfg = EngineConfig()
        val spec = DriveSpec(bumpsAt = listOf(500.0, 1100.0, 1600.0))
        val t1 = sim.drive(store, spec, tripId = 1)
        describe("trip 1", t1)
        val s1 = t1.stats
        check(t1.newBumps == 3 && s1.soft == 3 && s1.full == 0, "trip 1: 3 new spots, all soft, got new=${t1.newBumps} soft=${s1.soft} full=${s1.full}")
        check(s1.mild + s1.moderate + s1.strong == 3, "each felt spot is counted in one band: ${s1.mild}/${s1.moderate}/${s1.strong}")
        check(store.saved.all { it.confidence(cfg) == Confidence.SOFT && it.lastBand != null && it.sevIndex >= 3.0 }, "stored soft, with a band")

        val t2 = sim.drive(store, spec, tripId = 2)
        describe("trip 2", t2)
        check(t2.knownHits == 3 && t2.stats.full == 3 && t2.stats.soft == 0, "felt again: full, got soft=${t2.stats.soft} full=${t2.stats.full}")
        check(store.saved.all { it.confidence(cfg) == Confidence.FULL }, "stored as confirmed")
    }

    /** An old pothole spot (from before v2) is only a soft "maybe" until this engine feels it again. */
    fun legacySpotSoftUntilFelt() {
        log("legacySpotSoftUntilFelt")
        val sim = Simulator(82)
        val store = MemoryStore()
        val cfg = EngineConfig()
        sim.drive(store, DriveSpec(bumpsAt = listOf(800.0)), tripId = 1)
        // What an upgraded phone loads: felt 3 of 3 times by the old version, as a pothole.
        store.saved.single().apply { hits = 3; passes = 3; nPos = 3; legacy = true }
        check(store.saved.single().confidence(cfg) == Confidence.SOFT, "an old pothole spot starts soft")

        val t2 = sim.drive(store, DriveSpec(silentBumpsAt = listOf(800.0)), tripId = 2)   // driven over, nothing felt
        describe("trip 2 (not felt)", t2)
        val s2 = store.saved.single()
        check(t2.warnings.size == 1 && s2.legacy && s2.confidence(cfg) == Confidence.SOFT, "warned, still soft: legacy=${s2.legacy} ${s2.hits}/${s2.passes}")

        val t3 = sim.drive(store, DriveSpec(bumpsAt = listOf(800.0)), tripId = 3)   // felt again
        describe("trip 3 (felt)", t3)
        val s3 = store.saved.single()
        check(t3.knownHits == 1 && !s3.legacy && s3.confidence(cfg) == Confidence.FULL, "felt again: an ordinary full bump, legacy=${s3.legacy} ${s3.hits}/${s3.passes}")
        check(t3.stats.full == 1 && t3.stats.soft == 0, "counted as full: full=${t3.stats.full} soft=${t3.stats.soft}")
    }

    private fun nearest(store: MemoryStore, sim: Simulator, p: Double, westbound: Boolean = false): Bump {
        val truth = sim.point(p, westbound)
        return store.saved.minByOrNull { Geo.distance(it.lat, it.lon, truth[0], truth[1]) }!!
    }

    /** The sound a confirmed spot warns with: its band's. */
    private fun bandSound(b: Bump, cfg: EngineConfig) = when (b.severity(cfg)) {
        Severity.MILD -> WarnSound.MILD
        Severity.MODERATE -> WarnSound.MODERATE
        Severity.STRONG -> WarnSound.STRONG
    }

    /**
     * A dip (the jolt goes down first, the car rolls) is a bump like any other: no type and no side, its shape is only
     * kept in the notes. Felt once it warns with the soft beep, felt twice by its band; a 7 m/s² dip is strong.
     */
    fun dipIsABump() {
        log("dipIsABump")
        val sim = Simulator(21)
        val store = MemoryStore()
        val cfg = EngineConfig()
        val spec = DriveSpec(bumpsAt = listOf(400.0, 1500.0), dipsAt = listOf(900.0), cruiseKmh = 40.0)

        val t1 = sim.drive(store, spec, tripId = 1)
        describe("trip 1", t1)
        check(t1.newBumps == 3, "trip 1 should record 2 bumps + the dip, got ${t1.newBumps}")
        check(store.saved.none { it.legacy }, "new spots are never old pothole spots")
        // The shape is still measured (the gyroscope was used), as a diagnostic only.
        val notes = store.events.filter { it.type == "new_bump" }.map { it.note }
        log("  notes: $notes")
        check(notes.all { it.startsWith("bump sev=") && "conf=soft" in it && "roll/pitch" in it }, "band, confidence and shape in every note: $notes")
        check(notes.none { "pothole" in it || "side=" in it || "looks=" in it }, "no type or side in the notes: $notes")
        val dip = nearest(store, sim, 900.0)
        check(dip.severity(cfg) == Severity.STRONG, "a 7 m/s² dip is strong, got ${dip.severity(cfg)} (${formatFixed(dip.sevIndex, 1)})")

        val t2 = sim.drive(store, spec, tripId = 2)
        describe("trip 2", t2)
        check(t2.warnings.size == 3 && t2.warnings.all { it.sound == WarnSound.SOFT }, "trip 2: felt once, so 3 soft warnings: ${t2.warnings.map { it.sound }}")

        val bands = listOf(400.0, 900.0, 1500.0).map { bandSound(nearest(store, sim, it), cfg) }
        val t3 = sim.drive(store, spec, tripId = 3)
        describe("trip 3", t3)
        check(t3.warnings.map { it.sound } == bands, "trip 3: confirmed, each by its band: ${t3.warnings.map { it.sound }}, expected $bands")
        check(bands[1] == WarnSound.STRONG, "the dip warns by voice (strong)")
    }

    /**
     * Every felt spot is counted by its band, and soft vs full: a bump, two 7 m/s² dips (one given with the old pothole
     * name, which still makes a bump) and a 4.2 m/s² dip. Felt once they are soft; felt again, full and by their band.
     */
    @Suppress("DEPRECATION")
    fun severityCountsAndSounds() {
        log("severityCountsAndSounds")
        val sim = Simulator(41)
        val store = MemoryStore()
        val cfg = EngineConfig()
        val spec = DriveSpec(
            bumpsAt = listOf(300.0), dipsAt = listOf(700.0), potholesLeftAt = listOf(1100.0),
            smallDipsAt = listOf(1500.0), cruiseKmh = 40.0,
        )
        val t1 = sim.drive(store, spec, tripId = 1)
        describe("trip 1", t1)
        val s1 = t1.stats
        log("    bands mild=${s1.mild} moderate=${s1.moderate} strong=${s1.strong}, soft=${s1.soft} full=${s1.full}")
        store.events.filter { it.type == "new_bump" }.forEach { log("    new: ${it.note}") }
        check(t1.newBumps == 4 && s1.soft == 4 && s1.full == 0, "trip 1: 4 new spots, all soft, got new=${t1.newBumps} soft=${s1.soft} full=${s1.full}")
        check(s1.mild + s1.moderate + s1.strong == 4, "each felt spot is counted in one band")
        val dips = listOf(700.0, 1100.0).map { nearest(store, sim, it) }
        val small = nearest(store, sim, 1500.0)
        check(dips.all { it.severity(cfg) == Severity.STRONG && !it.legacy }, "the 7 m/s² dips are strong bumps: ${dips.map { it.severity(cfg) }}")
        check(small.severity(cfg) == Severity.MODERATE, "the 4.2 m/s² dip is moderate, got ${small.severity(cfg)} (${formatFixed(small.sevIndex, 2)})")
        check(s1.strong >= 2 && s1.moderate >= 1, "trip counts by band: ${s1.mild}/${s1.moderate}/${s1.strong}")

        val t2 = sim.drive(store, spec, tripId = 2)
        describe("trip 2", t2)
        check(t2.newBumps == 0, "trip 2 must not record anything new (all spots are known), got ${t2.newBumps}")
        check(t2.warnings.map { it.sound } == List(4) { WarnSound.SOFT }, "trip 2: each felt once so far, 4 soft warnings: ${t2.warnings.map { it.sound }}")
        check(t2.stats.full == 4 && t2.stats.soft == 0, "felt again: all full, got soft=${t2.stats.soft} full=${t2.stats.full}")
        check(t2.warnings.last().spot.id == small.id, "the last warning is the small dip")

        val bands = listOf(300.0, 700.0, 1100.0, 1500.0).map { bandSound(nearest(store, sim, it), cfg) }
        val t3 = sim.drive(store, spec, tripId = 3)
        describe("trip 3", t3)
        check(t3.warnings.map { it.sound } == bands, "trip 3: confirmed, each by its band: ${t3.warnings.map { it.sound }}, expected $bands")
        check(bands.drop(1) == listOf(WarnSound.STRONG, WarnSound.STRONG, WarnSound.MODERATE), "dips strong, the small dip moderate: $bands")
    }

    /** Without a gyroscope the jolts are learned all the same, with what the jolt alone shows (up or down first). */
    fun noGyroStillLearns() {
        log("noGyroStillLearns")
        val sim = Simulator(22)
        val store = MemoryStore()
        val r = sim.drive(store, DriveSpec(bumpsAt = listOf(500.0), dipsAt = listOf(1100.0), cruiseKmh = 40.0, gyro = false))
        describe("trip 1", r)
        check(r.newBumps == 2, "expected 2 new spots, got ${r.newBumps}")
        val notes = store.events.filter { it.type == "new_bump" }.map { it.note }
        log("  notes: $notes")
        check(notes.all { "no-gyro" in it }, "judged without the gyroscope: $notes")
        check(notes.map { "first=down" in it } == listOf(false, true), "the bump goes up first, the dip down first: $notes")
    }

    /**
     * At 70 km/h a dip is rejected as too fast like any other jolt (before v2 a clear pothole was still recorded):
     * only up to [EngineConfig.fastJoltMaxKmh] does a clearly shaped jolt still count ([fastBumpVersusRoadJoint]).
     * (The slow zone gives one braking + speeding up, which the engine needs to learn which way is forward.)
     */
    fun fastDipRejected() {
        log("fastDipRejected")
        val sim = Simulator(31)
        val store = MemoryStore()
        val spec = DriveSpec(dipsAt = listOf(1100.0), oneOffJoltsAt = listOf(750.0), slowZonesAt = listOf(350.0), cruiseKmh = 70.0)
        val t1 = sim.drive(store, spec, tripId = 1)
        describe("trip 1", t1)
        store.events.filter { it.type == "rejected" }.forEach { log("    rejected ${f1(it.speedKmh)} km/h ${it.note}") }
        check(t1.newBumps == 0 && store.saved.isEmpty(), "nothing is recorded at 70 km/h, got ${t1.newBumps}")
        check(t1.rejected.count { it == "too_fast" } == 2, "the dip and the plain jolt are both too fast: ${t1.rejected}")
    }

    /** Already driving slowly → no warning (logged as beep_quiet). With the setting at 0 it warns again. */
    fun quietWhenSlow() {
        log("quietWhenSlow")
        val sim = Simulator(13)
        val store = MemoryStore()
        val spec = DriveSpec(bumpsAt = listOf(500.0, 1200.0), cruiseKmh = 18.0, bumpKmh = 10.0)
        val t1 = sim.drive(store, spec, tripId = 1)
        describe("trip 1", t1)
        check(t1.newBumps == 2, "trip 1 should learn 2 bumps, got ${t1.newBumps}")

        val t2 = sim.drive(store, spec, tripId = 2)
        describe("trip 2 (quiet below 20)", t2)
        check(t2.beepBumpIds.isEmpty(), "at 18 km/h nothing should warn, got ${t2.beepBumpIds.size}")
        val quiet = store.events.count { it.tripId == 2L && it.type == "beep_quiet" }
        check(quiet == 2, "expected 2 beep_quiet events, got $quiet")

        val always = EngineConfig().apply { quietBelowKmh = 0.0 }
        val t3 = sim.drive(store, spec, always, tripId = 3)
        describe("trip 3 (always warn)", t3)
        check(t3.beepBumpIds.size == 2, "with quiet off both bumps should warn, got ${t3.beepBumpIds.size}")
    }

    /** A miss records how strong the strongest nearby jolt was, so you can see if it was just below the trigger. */
    fun missReportsNearbyJolt() {
        log("missReportsNearbyJolt")
        val sim = Simulator(17)
        val store = MemoryStore()
        sim.drive(store, DriveSpec(bumpsAt = listOf(800.0)), tripId = 1)
        sim.drive(store, DriveSpec(silentBumpsAt = listOf(800.0)), tripId = 2)
        val miss = store.events.firstOrNull { it.tripId == 2L && it.type == "miss" }
        check(miss != null, "trip 2 should log a miss")
        log("  miss note: ${miss!!.note}")
        check(!miss.peak.isNaN() && miss.peak > 0.0 && miss.peak < 3.0, "miss should carry the nearby jolt (below trigger), got ${miss.peak}")
    }

    private fun describeDriving(name: String, d: DrivingStats) {
        log(
            "  $name: score=${d.score()} km=${formatFixed(d.distanceM / 1000, 1)} speeding=${formatFixed(d.speedingShare * 100, 0)}% " +
                "brakes=${d.harshBrakes} accels=${d.harshAccels} corners=${d.harshCorners} swerves=${d.swerves} bumpsFast=${d.bumpsFast} phone=${d.phoneUse} " +
                "ignored brakes=${d.brakesIgnored} accels=${d.accelsIgnored}",
        )
    }

    /** Calm driving (slowing for bumps and a junction, under the limit) scores high with no events. */
    fun calmDrivingScoresHigh() {
        log("calmDrivingScoresHigh")
        val sim = Simulator(51)
        val r = sim.drive(MemoryStore(), DriveSpec(bumpsAt = listOf(500.0, 1100.0, 1600.0), slowZonesAt = listOf(800.0)))
        describeDriving("calm", r.driving)
        val d = r.driving
        check(d.harshBrakes + d.harshAccels + d.harshCorners + d.swerves + d.bumpsFast + d.phoneUse == 0, "calm driving should have no events")
        check(d.speedingS == 0.0, "never above 90 km/h")
        check(d.score() >= 95, "calm driving should score 95+, got ${d.score()}")
        check(d.distanceM in 1800.0..2200.0, "distance should be about 2 km, got ${d.distanceM}")
    }

    /** Speeding at 100 in a 90 and two emergency stops: both stops counted, score drops a lot. */
    fun speedingAndHardBraking() {
        log("speedingAndHardBraking")
        val sim = Simulator(52)
        val store = MemoryStore()
        val r = sim.drive(store, DriveSpec(cruiseKmh = 100.0, hardBrakesAt = listOf(900.0, 1600.0)))
        describeDriving("aggressive", r.driving)
        store.events.filter { it.type in setOf("harsh_brake", "harsh_accel", "speeding") }.forEach {
            log("    ${it.type} at ${formatFixed(it.speedKmh, 0)} km/h: ${it.note}")
        }
        r.forwardTrace.forEach { log("    $it") }
        val d = r.driving
        check(d.harshBrakes == 2, "expected 2 harsh brakes, got ${d.harshBrakes}")
        check(d.harshAccels == 0, "speeding up again normally is not harsh, got ${d.harshAccels}")
        check(d.speedingShare > 0.4, "most of the trip is above 90 km/h, got ${d.speedingShare}")
        check(store.events.any { it.type == "speeding" }, "a speeding episode should be logged")
        check(d.score() in 0..70, "this trip should score 70 or less, got ${d.score()}")
    }

    /**
     * Two sudden left-right swerves at 50 km/h are counted as swerves (not as harsh cornering).
     * Default placement ("unknown", what the app ships with): each push must be backed by the GPS heading, and a 0.7 s
     * push barely shows in 1 Hz GPS with ±3° noise, so with this seed only one of the two is confirmed. That recall
     * drop is the price of not blaming pocket movement on the driver. "Mounted" trusts the gyroscope unless the GPS
     * turns the other way, and finds both.
     */
    fun swerving() {
        log("swerving")
        val r = Simulator(53).drive(MemoryStore(), DriveSpec(swervesAt = listOf(600.0, 1300.0)))
        describeDriving("swerves", r.driving)
        check(r.driving.swerves >= 1, "expected at least 1 swerve, got ${r.driving.swerves}")
        check(r.driving.harshCorners == 0, "swerves shouldn't also count as harsh cornering, got ${r.driving.harshCorners}")
        check(r.driving.score() < 95, "swerving should cost points, got ${r.driving.score()}")

        val m = Simulator(53).drive(MemoryStore(), DriveSpec(swervesAt = listOf(600.0, 1300.0)), drivingCfg = DrivingConfig().apply { placement = "mounted" })
        describeDriving("swerves, mounted", m.driving)
        check(m.driving.swerves == 2, "mounted: expected 2 swerves, got ${m.driving.swerves}")
        check(m.driving.harshCorners == 0, "mounted: swerves shouldn't also count as harsh cornering, got ${m.driving.harshCorners}")
    }

    /** Taking known speed bumps at 40 km/h counts against the score; at 15 km/h it doesn't. */
    fun speedBumpsTakenFast() {
        log("speedBumpsTakenFast")
        val sim = Simulator(54)
        val store = MemoryStore()
        val t1 = sim.drive(store, DriveSpec(bumpsAt = listOf(500.0, 1200.0)), tripId = 1)
        describeDriving("slow over bumps", t1.driving)
        check(t1.driving.bumpsFast == 0, "15 km/h over bumps is fine, got ${t1.driving.bumpsFast}")
        val t2 = sim.drive(store, DriveSpec(bumpsAt = listOf(500.0, 1200.0), bumpKmh = 40.0), tripId = 2)
        describeDriving("fast over bumps", t2.driving)
        check(t2.driving.bumpsFast == 2, "both bumps at 40 km/h should count, got ${t2.driving.bumpsFast}")
    }

    /** Picking up the phone and holding it 5 s while driving is phone use: no bump is learned from it, no harsh event counted. */
    fun phoneHandledWhileDriving() {
        log("phoneHandledWhileDriving")
        val sim = Simulator(3)
        val r = sim.drive(MemoryStore(), DriveSpec(slowZonesAt = listOf(600.0), handlingAt = 600.0))
        describeDriving("phone", r.driving)
        val d = r.driving
        check(d.phoneUse == 1, "expected 1 phone use, got ${d.phoneUse}")
        check(r.newBumps == 0, "the handling must not be learned as a bump, got ${r.newBumps}")
        check(d.harshBrakes + d.harshAccels + d.harshCorners + d.swerves == 0, "no harsh events while handled")
    }

    /**
     * The phone twisting in a pocket makes the gyroscope "turn" hard while the GPS heading stays straight:
     * not harsh cornering, in any placement. (Before the GPS cross-check each twist counted as one.)
     */
    fun pocketShiftNotACorner() {
        log("pocketShiftNotACorner")
        for (place in listOf("unknown", "pocket", "mounted")) {
            val r = Simulator(61).drive(MemoryStore(), DriveSpec(pocketTwistsAt = listOf(700.0, 1400.0)), drivingCfg = DrivingConfig().apply { placement = place })
            describeDriving("twists, $place", r.driving)
            r.forwardTrace.lastOrNull()?.let { log("    $it") }
            check(r.driving.harshCorners == 0, "$place: a phone twisting in a pocket is not harsh cornering, got ${r.driving.harshCorners}")
            check(r.driving.swerves == 0, "$place: a phone twisting in a pocket is not a swerve, got ${r.driving.swerves}")
            // Twisting both ways (40° and straight back) looks like a left-right push: still not the car.
            val w = Simulator(61).drive(MemoryStore(), DriveSpec(wigglesAt = listOf(700.0, 1400.0)), drivingCfg = DrivingConfig().apply { placement = place })
            describeDriving("wiggles, $place", w.driving)
            check(w.driving.harshCorners == 0 && w.driving.swerves == 0, "$place: wiggles: corners ${w.driving.harshCorners}, swerves ${w.driving.swerves}")
        }
    }

    /** A real sharp turn (and the turn back) is confirmed by the GPS heading and counted, even in pocket mode. */
    fun sharpTurnCounted() {
        log("sharpTurnCounted")
        val r = Simulator(62).drive(MemoryStore(), DriveSpec(sharpTurnsAt = listOf(700.0)), drivingCfg = DrivingConfig().apply { placement = "pocket" })
        describeDriving("sharp turn", r.driving)
        r.forwardTrace.lastOrNull()?.let { log("    $it") }
        check(r.driving.harshCorners == 2, "the turn and the turn back should both count, got ${r.driving.harshCorners}")
        check(r.driving.swerves == 0, "two turns 6 s apart are not a swerve, got ${r.driving.swerves}")
    }

    /**
     * Pocket mode only excuses jostles: holding the phone counts in any placement, a pocket too. Taken out and held 5 s is
     * phone use, picked up and put straight back (held 1 s) is not. Four jostles while driving turn pocket mode on by
     * themselves; they are not phone use, the hold later is.
     */
    fun pocketModeExcusesOnlyJostles() {
        log("pocketModeExcusesOnlyJostles")
        val pocket = DrivingConfig().apply { placement = "pocket" }
        val r1 = Simulator(3).drive(MemoryStore(), DriveSpec(slowZonesAt = listOf(600.0), handlingAt = 600.0), drivingCfg = pocket)
        describeDriving("pocket, held 5 s", r1.driving)
        check(r1.driving.phoneUse == 1, "held 5 s: phone use even in a pocket, got ${r1.driving.phoneUse}")
        check(r1.newBumps == 0, "the handling jolts are rejected: ${r1.rejected}")

        val r2 = Simulator(63).drive(MemoryStore(), DriveSpec(holdsAt = listOf(700.0 to 1.0)), drivingCfg = pocket)
        describeDriving("pocket, held 1 s", r2.driving)
        check(r2.driving.phoneUse == 0, "held 1 s in pocket mode is a jostle, got ${r2.driving.phoneUse}")
        check("phone_moving" in r2.rejected && "handled" !in r2.rejected, "a pocket jostle's jolt is phone_moving: ${r2.rejected}")

        val r3 = Simulator(63).drive(MemoryStore(), DriveSpec(jostlesAt = listOf(300.0, 420.0, 540.0, 660.0), handlingAt = 1300.0))
        describeDriving("loose phone", r3.driving)
        r3.forwardTrace.lastOrNull()?.let { log("    $it") }
        check(r3.pocketMode, "four jostles in half a minute should turn pocket mode on")
        check(r3.driving.phoneUse == 1, "the jostles are not phone use, the later hold is: got ${r3.driving.phoneUse}")
    }

    /** Unlocking the phone while driving is phone use when it isn't in a holder (here a pocket), even without a pick-up. */
    fun unlockInPocketIsPhoneUse() {
        log("unlockInPocketIsPhoneUse")
        var unlocked = false
        val spec = DriveSpec(slowZonesAt = listOf(300.0), phoneSignals = { _, s, _, sig, tMs ->
            if (!unlocked && s >= 900.0) {
                sig.unlockedAtMs = tMs
                sig.screenOn = true
                unlocked = true
            }
        })
        val r = Simulator(71).drive(MemoryStore(), spec, drivingCfg = DrivingConfig().apply { placement = "pocket" })
        describeDriving("unlocked in a pocket", r.driving)
        check(r.driving.phoneUse == 1, "an unlock while driving is phone use, got ${r.driving.phoneUse}")
    }

    /**
     * Navigation: a phone in a holder with its screen on for 10 minutes, unlocked at the start, just after a bump, just
     * after a slow zone and once more, is never phone use: placement "mounted", or "unknown" (10 drives), where it must
     * be judged mounted for at least 95 % of the time after the first minute.
     */
    fun mountedScreenOnIsNoPhoneUse() {
        log("mountedScreenOnIsNoPhoneUse")
        for ((place, seeds) in listOf("mounted" to listOf(72L), "unknown" to (72L..81L).toList())) for (seed in seeds) {
            val unlocks = ArrayDeque(listOf(600.0, 1010.0, 2070.0, 2500.0))
            val spec = DriveSpec(
                roadM = 8000.0, bumpsAt = listOf(1000.0, 3000.0, 5500.0), slowZonesAt = listOf(2000.0, 4500.0, 7000.0),
                phoneSignals = { _, s, _, sig, tMs ->
                    sig.screenOn = true
                    if (unlocks.isNotEmpty() && s >= unlocks.first()) {
                        sig.unlockedAtMs = tMs
                        unlocks.removeFirst()
                    }
                },
            )
            val r = Simulator(seed).drive(MemoryStore(), spec, drivingCfg = DrivingConfig().apply { placement = place })
            describeDriving("$place #$seed, screen on, mounted ${formatFixed(r.mountedShare * 100, 1)} %", r.driving)
            check(r.driving.movingS >= 600.0, "10 minutes of driving, got ${r.driving.movingS} s")
            check(r.driving.phoneUse == 0, "$place #$seed: navigation in a holder is not phone use, got ${r.driving.phoneUse}")
            check(r.phone?.state == PhoneState.STABLE_MOUNTED, "$place #$seed: the phone stays in its holder: ${r.phone?.state}")
            check(r.mountedShare >= 0.95, "$place #$seed: mounted only ${r.mountedShare}")
        }
    }

    /** A holder that slips 20° with the screen on: handled ~10 s at most, never phone use, mounted again (cup holder: loose). */
    fun slipWithTheScreenOnIsNoPhoneUse() {
        log("slipWithTheScreenOnIsNoPhoneUse")
        for (place in listOf("mounted", "unknown", "cupholder")) {
            val spec = DriveSpec(
                roadM = 3000.0, slipsAt = listOf(600.0 to 20.0), bumpsAt = listOf(1000.0, 2200.0),
                phoneSignals = { _, _, _, sig, _ -> sig.screenOn = true },
            )
            val r = Simulator(241).drive(MemoryStore(), spec, drivingCfg = DrivingConfig().apply { placement = place })
            describeDriving("$place, slipped, handled ${formatFixed(r.handledS, 1)} s", r.driving)
            check(r.driving.phoneUse == 0, "$place: a slipped holder is not phone use, got ${r.driving.phoneUse}")
            check(r.handledS <= 12.0 && r.phone?.state != PhoneState.HANDLED, "$place: handled ${r.handledS} s, ${r.phone?.state}")
            val want = if (place == "cupholder") PhoneState.STABLE_LOOSE else PhoneState.STABLE_MOUNTED
            check(r.phone?.state == want, "$place: expected $want after the slip, got ${r.phone?.state}")
            check(r.newBumps == 2, "$place: both bumps after the slip are learned: ${r.newBumps}, rejected ${r.rejected}")
        }
    }

    /** Unlocks and calls count by the speed at that moment: an unlock at a red light, or a call made while stopped, is not phone use. */
    fun stoppedIsNoPhoneUse() {
        log("stoppedIsNoPhoneUse")
        for (call in listOf(false, true)) {
            var stoppedAt = -1.0
            var unlocked = false
            val spec = DriveSpec(stopsAt = listOf(800.0 to 20.0), phoneSignals = { t, s, v, sig, tMs ->
                if (stoppedAt < 0 && s >= 790.0 && v <= 0.0) stoppedAt = t
                if (!call && stoppedAt >= 0 && !unlocked && t >= stoppedAt + 4.0) {
                    sig.unlockedAtMs = tMs
                    sig.screenOn = true
                    unlocked = true
                }
                if (call) {
                    val on = stoppedAt >= 0 && t >= stoppedAt + 2.0 && t < stoppedAt + 12.0   // a 10 s call, ended before driving on
                    sig.handheldCall = on
                    sig.screenOn = on
                }
            })
            val place = if (call) "unknown" else "pocket"
            val r = Simulator(231).drive(MemoryStore(), spec, drivingCfg = DrivingConfig().apply { placement = place })
            describeDriving(if (call) "call while stopped" else "unlock while stopped", r.driving)
            check(stoppedAt > 0, "the car should stop")
            check(r.driving.phoneUse == 0, "${if (call) "a call" else "an unlock"} while stopped is not phone use, got ${r.driving.phoneUse}")
        }
    }

    /**
     * One bar in every placement: a 1 s hold is not phone use, a 2 s hold is. In a pocket, 1.8 s of motion with no other
     * sign is a jostle; taken out of the pocket (proximity clears, light comes up) and held 2 s is phone use.
     */
    fun holdLengthInEveryPlacement() {
        log("holdLengthInEveryPlacement")
        for ((secs, want) in listOf(1.0 to 0, 2.0 to 1)) for (place in listOf("pocket", "cupholder", "unknown", "mounted")) {
            val r = Simulator(201).drive(MemoryStore(), DriveSpec(holdsAt = listOf(700.0 to secs), cruiseKmh = 30.0), drivingCfg = DrivingConfig().apply { placement = place })
            describeDriving("held $secs s, $place", r.driving)
            check(r.driving.phoneUse == want, "held $secs s, $place: expected $want phone use, got ${r.driving.phoneUse}")
        }
        val pocket = DrivingConfig().apply { placement = "pocket" }
        val motion = Simulator(211).drive(MemoryStore(), DriveSpec(jostlesAt = listOf(700.0), jostleHeldS = 1.3, cruiseKmh = 30.0), drivingCfg = pocket)
        describeDriving("pocket motion 1.8 s", motion.driving)
        check(motion.driving.phoneUse == 0, "1.8 s of motion in a pocket is a jostle, got ${motion.driving.phoneUse}")
        val out = DriveSpec(holdsAt = listOf(700.0 to 2.0), cruiseKmh = 30.0, phoneSignals = { _, s, _, sig, _ ->
            sig.proximityNear = s < 700.0
            sig.lux = if (s < 700.0) 0.0 else 300.0
        })
        val r = Simulator(221).drive(MemoryStore(), out, drivingCfg = pocket)
        describeDriving("out of the pocket, 2 s", r.driving)
        check(r.driving.phoneUse == 1, "taken out of the pocket and held 2 s is phone use, got ${r.driving.phoneUse}")
    }

    /** Without a lock screen every screen-on reads as an unlock: alone (a notification) it is nothing, with a pick-up it is phone use. */
    fun noLockScreenUnlockNeedsMotion() {
        log("noLockScreenUnlockNeedsMotion")
        for (picked in listOf(false, true)) {
            var on = false
            val spec = DriveSpec(holdsAt = if (picked) listOf(800.0 to 0.5) else emptyList(), phoneSignals = { _, s, _, sig, tMs ->
                sig.keyguardPresent = false
                if (!on && s >= 800.0) {
                    sig.unlockedAtMs = tMs
                    sig.screenOn = true
                    on = true
                }
            })
            val r = Simulator(251).drive(MemoryStore(), spec, drivingCfg = DrivingConfig().apply { placement = "cupholder" })
            describeDriving(if (picked) "no lock screen, picked up" else "no lock screen, notification", r.driving)
            check(r.driving.phoneUse == (if (picked) 1 else 0), "picked up: $picked, phone use ${r.driving.phoneUse}")
        }
    }

    /** A hand-held call while driving is phone use, once per call; the same call through the car's Bluetooth is not. */
    fun handHeldCallIsPhoneUse() {
        log("handHeldCallIsPhoneUse")
        fun call(handHeld: Boolean) = DriveSpec(phoneSignals = { _, s, _, sig, _ ->
            val on = s >= 600.0 && s < 1000.0   // about 30 s at 50 km/h
            sig.handheldCall = on && handHeld
            sig.screenOn = on
        })
        val held = Simulator(73).drive(MemoryStore(), call(true))
        describeDriving("hand-held call", held.driving)
        check(held.driving.phoneUse == 1, "a 30 s hand-held call while driving is phone use once, got ${held.driving.phoneUse}")
        val bt = Simulator(73).drive(MemoryStore(), call(false), drivingCfg = DrivingConfig().apply { placement = "mounted" })
        describeDriving("Bluetooth call", bt.driving)
        check(bt.driving.phoneUse == 0, "a Bluetooth call is not phone use, got ${bt.driving.phoneUse}")
    }

    /**
     * Braking must show in the GPS speed: the emergency stops of [speedingAndHardBraking] do and count, but the phone
     * feeling 5 m/s² of braking for 1.2 s while the car keeps its speed (sliding, or tipping in a pocket) doesn't.
     */
    fun brakingNeedsTheGpsSpeed() {
        log("brakingNeedsTheGpsSpeed")
        val real = Simulator(52).drive(MemoryStore(), DriveSpec(cruiseKmh = 100.0, hardBrakesAt = listOf(900.0, 1600.0)))
        describeDriving("emergency stops", real.driving)
        check(real.driving.harshBrakes == 2 && real.driving.brakesIgnored == 0, "both real stops count: ${real.driving.harshBrakes}")
        val spikes = Simulator(74).drive(MemoryStore(), DriveSpec(slowZonesAt = listOf(300.0), brakeSpikesAt = listOf(1000.0, 1500.0)))
        describeDriving("brake spikes, steady GPS", spikes.driving)
        check(spikes.driving.harshBrakes == 0, "the GPS speed didn't drop: no harsh brake, got ${spikes.driving.harshBrakes}")
        check(spikes.driving.brakesIgnored == 2, "both spikes were felt, then ignored: ${spikes.driving.brakesIgnored}")
    }

    /** Picking the phone up 4 times in 5 minutes is phone use 4 times, and must not turn pocket mode on. */
    fun repeatedHandlingIsPhoneUse() {
        log("repeatedHandlingIsPhoneUse")
        val r = Simulator(66).drive(MemoryStore(), DriveSpec(handlingAt = 200.0, moreHandlingsAt = listOf(600.0, 1000.0, 1400.0), cruiseKmh = 30.0))
        describeDriving("4 handlings", r.driving)
        r.forwardTrace.lastOrNull()?.let { log("    $it") }
        check(!r.pocketMode, "holding the phone is not a loose phone: pocket mode must stay off")
        check(r.driving.phoneUse == 4, "each handling (48 s apart) is phone use, got ${r.driving.phoneUse}")
    }

    /**
     * Above the bump speed (50 km/h) but under 60: a speed bump taken at 55 km/h rocks the car and is learned;
     * a road joint at the same speed is a sharp jolt that barely rocks it, and stays rejected as too_fast.
     * (The slow zone gives one braking + speeding up, which the engine needs to learn which way is forward.)
     */
    fun fastBumpVersusRoadJoint() {
        log("fastBumpVersusRoadJoint")
        val store = MemoryStore()
        val bump = Simulator(64).drive(store, DriveSpec(bumpsAt = listOf(1100.0), bumpKmh = 55.0, cruiseKmh = 55.0, slowZonesAt = listOf(350.0)))
        describe("bump at 55", bump)
        store.events.filter { it.type == "new_bump" || it.type == "rejected" }.forEach { log("    ${it.type} ${f1(it.speedKmh)} km/h ${it.note}") }
        check(bump.newBumps == 1 && "too_fast" !in bump.rejected, "a speed bump at 55 km/h should be learned: new=${bump.newBumps} ${bump.rejected}")
        check(store.saved.single().severity(EngineConfig()) == Severity.STRONG, "at 55 km/h it hits hard: strong, got ${store.saved.single().sevIndex}")

        val store2 = MemoryStore()
        val seam = Simulator(65).drive(store2, DriveSpec(seamsAt = listOf(1100.0), cruiseKmh = 55.0, slowZonesAt = listOf(350.0)))
        describe("road joint at 55", seam)
        store2.events.filter { it.type == "new_bump" || it.type == "rejected" }.forEach { log("    ${it.type} ${f1(it.speedKmh)} km/h ${it.note}") }
        check(seam.newBumps == 0, "a road joint at 55 km/h must not be learned, got ${seam.newBumps}")
        check("too_fast" in seam.rejected, "the road joint should be rejected as too_fast: ${seam.rejected}")
    }

    // ---------- shared online map ----------

    /** A shared-map spot at road position [p] (eastbound unless [westbound]). */
    private fun remoteAt(
        sim: Simulator, id: Long, p: Double, kind: BumpKind = BumpKind.BUMP, side: Side = Side.UNKNOWN,
        severity: Double = 5.0, westbound: Boolean = false, devices: Int = 3,
    ): RemoteSpot {
        val q = sim.point(p, westbound)
        return RemoteSpot(id, q[0], q[1], if (westbound) 270.0 else 90.0, kind, side, severity, nDevices = devices)
    }

    /** Shared spots: their severity gives the band, two or more phones make them full, a shared pothole is an old (soft) spot. */
    fun remoteStandIns() {
        log("remoteStandIns")
        val sim = Simulator(83)
        val cfg = EngineConfig()
        val spots = listOf(
            remoteAt(sim, 1, 300.0, severity = 3.0),
            remoteAt(sim, 2, 700.0, severity = 4.2),
            remoteAt(sim, 3, 1100.0, severity = 7.0),
            remoteAt(sim, 4, 1500.0, BumpKind.POTHOLE, severity = 7.0),
            remoteAt(sim, 5, 1800.0, severity = 4.2, devices = 1),
        )
        val t1 = sim.drive(MemoryStore(), DriveSpec(), tripId = 1, spotSource = ListSpotSource(spots))
        describe("trip 1", t1)
        check(t1.warnings.map { BumpEngine.remoteSpotId(it.spot.id) } == listOf(1L, 2L, 3L, 4L, 5L), "each shared spot warns once: ${t1.beepBumpIds}")
        val got = t1.warnings.map { Triple(it.spot.severity(cfg), it.spot.confidence(cfg), it.spot.legacy) }
        val want = listOf(
            Triple(Severity.MILD, Confidence.FULL, false), Triple(Severity.MODERATE, Confidence.FULL, false),
            Triple(Severity.STRONG, Confidence.FULL, false), Triple(Severity.STRONG, Confidence.SOFT, true),
            Triple(Severity.MODERATE, Confidence.SOFT, false),
        )
        check(got == want, "band, confidence, old pothole: $got")
    }

    private fun remoteBeeps(store: MemoryStore, trip: Long) =
        store.events.filter { it.tripId == trip && it.type == "beep" && it.note.startsWith("remote") }

    /**
     * A fresh phone with an empty map warns before every bump on its very first drive, from the shared map.
     * On the next drive its own learned spots take over: each bump warns once, not twice.
     */
    fun remoteSpotsWarnFirstDrive() {
        log("remoteSpotsWarnFirstDrive")
        val sim = Simulator(61)
        val store = MemoryStore()
        val real = listOf(500.0, 1100.0, 1600.0)
        val source = ListSpotSource(real.mapIndexed { i, p -> remoteAt(sim, 100L + i, p) })

        val t1 = sim.drive(store, DriveSpec(bumpsAt = real), tripId = 1, spotSource = source)
        describe("trip 1", t1)
        check(source.calls >= 2, "the shared map should be asked again while driving, asked ${source.calls}x")
        check(t1.beepBumpIds.size == 3, "trip 1 should warn before all 3 shared bumps, got ${t1.beepBumpIds.size}")
        check(
            t1.beepBumpIds.map { BumpEngine.remoteSpotId(it) } == listOf(100L, 101L, 102L),
            "trip 1: one warning per shared bump, in order: ${t1.beepBumpIds}",
        )
        for (d in t1.beepTrueDistM) check(d in 40.0..160.0, "trip 1: warning came at ${f1(d)} m, expected 40–160 m ahead")
        val logged = remoteBeeps(store, 1)
        check(
            logged.map { it.bumpId } == listOf(100L, 101L, 102L),
            "beeps logged with note 'remote' and the server ids: ${logged.map { "${it.bumpId} ${it.note}" }}",
        )
        check(t1.newBumps == 3 && store.saved.size == 3, "only the 3 felt spots are stored locally, got ${store.saved.size}")
        check(store.saved.all { it.id > 0 && it.passes == 1 }, "shared spots must not be stored or count passes")

        val t2 = sim.drive(store, DriveSpec(bumpsAt = real), tripId = 2, spotSource = source)
        describe("trip 2", t2)
        check(t2.beepBumpIds.size == 3, "trip 2: each bump warns once (own spot wins over its shared twin), got ${t2.beepBumpIds.size}")
        check(t2.beepBumpIds.all { it > 0 }, "trip 2 warnings must come from the local spots: ${t2.beepBumpIds}")
        check(remoteBeeps(store, 2).isEmpty(), "no remote beep when a local twin exists")
    }

    /** A spot you muted (by hand, or automatically because it is gone) also silences its shared twin. */
    fun localMuteSuppressesRemote() {
        log("localMuteSuppressesRemote")
        val sim = Simulator(62)
        val store = MemoryStore()
        sim.drive(store, DriveSpec(bumpsAt = listOf(500.0, 1100.0, 1600.0)), tripId = 1)
        // 1600 was removed: three clean passes auto-mute it.
        for (trip in 2..4) {
            sim.drive(store, DriveSpec(bumpsAt = listOf(500.0, 1100.0), silentBumpsAt = listOf(1600.0)), tripId = trip.toLong())
        }
        val gone = nearest(store, sim, 1600.0)
        check(gone.isMuted(EngineConfig()) && !gone.userMuted, "1600 should be auto-muted, got ${gone.hits}/${gone.passes}")
        // 500: "Mute last beep".
        val muted = nearest(store, sim, 500.0)
        BumpEngine(EngineConfig(), store, object : EngineListener {}, { 0L }).muteBump(muted.id)
        val kept = nearest(store, sim, 1100.0)

        val source = ListSpotSource(listOf(remoteAt(sim, 1, 500.0), remoteAt(sim, 2, 1100.0), remoteAt(sim, 3, 1600.0)))
        val spec = DriveSpec(bumpsAt = listOf(500.0, 1100.0), silentBumpsAt = listOf(1600.0))
        val t5 = sim.drive(store, spec, tripId = 5, spotSource = source)
        describe("trip 5", t5)
        check(t5.beepBumpIds == listOf(kept.id), "only the un-muted local spot should warn, got ${t5.beepBumpIds}")
        check(remoteBeeps(store, 5).isEmpty(), "muted spots must silence their shared twins")
    }

    /**
     * Shared potholes are spots from before v2: whatever their jolt, both warn with the soft "maybe" beep (there is
     * no "warn for potholes" switch any more).
     */
    fun remoteOldPotholesAreSoft() {
        log("remoteOldPotholesAreSoft")
        val sim = Simulator(63)
        val small = remoteAt(sim, 7, 700.0, BumpKind.POTHOLE, Side.RIGHT, severity = 4.2)
        val harsh = remoteAt(sim, 8, 1300.0, BumpKind.POTHOLE, Side.LEFT, severity = 7.0)
        val t1 = sim.drive(MemoryStore(), DriveSpec(cruiseKmh = 40.0), tripId = 1, spotSource = ListSpotSource(listOf(small, harsh)))
        describe("trip 1", t1)
        check(t1.beepBumpIds.map { BumpEngine.remoteSpotId(it) } == listOf(7L, 8L), "both shared potholes should warn, got ${t1.beepBumpIds}")
        check(t1.warnings.map { it.sound } == listOf(WarnSound.SOFT, WarnSound.SOFT), "both soft: ${t1.warnings.map { it.sound }}")
    }

    /** The observation outbox gets jolt / known_hit / pass_clear with sane values, and nothing for crawled-over passes. */
    fun observationsRecorded() {
        log("observationsRecorded")
        val sim = Simulator(64)
        val store = MemoryStore()
        val sink = ListSink()
        fun near(o: Observation, p: Double, m: Double = 12.0): Boolean {
            val q = sim.point(p, false)
            return Geo.distance(o.lat, o.lon, q[0], q[1]) <= m
        }
        fun sane(o: Observation) {
            check(o.clientId.length == 36 && o.clientId[8] == '-', "clientId should be a UUID: ${o.clientId}")
            check(Geo.angleDiff(o.heading, 90.0) < 20.0, "heading should be eastbound: ${o.heading}")
            check(o.wallTimeMs >= 1_700_000_000_000L, "wall time from the engine clock: ${o.wallTimeMs}")
            check(o.kind in setOf("jolt", "known_hit", "pass_clear"), "unknown kind ${o.kind}")
        }
        fun show(name: String, os: List<Observation>) =
            log("  $name: ${os.map { "${it.kind} ${f1(it.speedKmh)} km/h peak ${formatFixed(it.peak, 1)}" }}")

        sim.drive(store, DriveSpec(bumpsAt = listOf(500.0, 1100.0, 1600.0)), tripId = 1, observationSink = sink)
        val o1 = sink.got.toList()
        show("trip 1", o1)
        check(o1.map { it.kind } == listOf("jolt", "jolt", "jolt"), "trip 1: one jolt per new bump, got ${o1.map { it.kind }}")
        for ((o, p) in o1.zip(listOf(500.0, 1100.0, 1600.0))) {
            sane(o)
            check(near(o, p), "jolt should be at the bump at $p")
            check(o.speedKmh in 5.0..30.0 && o.peak >= 3.0 && o.kindScore < 0.0, "jolt values: ${o.speedKmh} km/h, peak ${o.peak}, score ${o.kindScore}")
        }
        check(o1.map { it.clientId }.toSet().size == 3, "clientIds must be unique")

        // 500 crawled over (pass_slow: nothing), 1100 removed (miss: pass_clear), 1600 felt again (known_hit).
        sink.got.clear()
        val spec2 = DriveSpec(crawlAt = listOf(500.0), silentBumpsAt = listOf(1100.0), bumpsAt = listOf(1600.0))
        sim.drive(store, spec2, tripId = 2, observationSink = sink)
        val o2 = sink.got.toList()
        show("trip 2", o2)
        check(store.events.any { it.tripId == 2L && it.type == "pass_slow" }, "trip 2 should have a pass_slow at 500")
        check(o2.map { it.kind } == listOf("pass_clear", "known_hit"), "trip 2: pass_clear then known_hit, got ${o2.map { it.kind }}")
        o2.forEach { sane(it) }
        check(o2.none { near(it, 500.0, 60.0) }, "a crawled-over pass (pass_slow) must not be reported")
        val clear = o2[0]
        check(
            near(clear, 1100.0) && clear.speedKmh >= 12.0 && clear.peak < 3.0,
            "pass_clear at 1100, informative speed, no jolt: ${clear.speedKmh} km/h peak ${clear.peak}",
        )
        val hit = o2[1]
        check(near(hit, 1600.0) && hit.peak >= 3.0 && hit.speedKmh in 5.0..30.0, "known_hit at 1600: ${hit.speedKmh} km/h peak ${hit.peak}")

        // A shared spot driven over cleanly is reported too (that is how removed bumps leave the shared map).
        // Fresh phone: the bump at 1600 is new to it.
        sink.got.clear()
        val source = ListSpotSource(listOf(remoteAt(sim, 9, 1300.0)))
        sim.drive(MemoryStore(), DriveSpec(bumpsAt = listOf(1600.0)), tripId = 3, spotSource = source, observationSink = sink)
        val o3 = sink.got.toList()
        show("trip 3", o3)
        check(
            o3.map { it.kind } == listOf("pass_clear", "jolt") && near(o3[0], 1300.0, 2.0) && near(o3[1], 1600.0),
            "trip 3: pass_clear at the shared spot, then a jolt at 1600: ${o3.map { it.kind }}",
        )
    }

    /**
     * Your own spot and its shared twin can sit up to [EngineConfig.matchRadiusM] apart (GPS lag, jolt timing).
     * 18 m apart at 24 km/h, two warnings would be 2.7 s apart (an audible double beep): there must be one,
     * and muting your spot must silence the twin.
     */
    fun remoteTwin18m() {
        log("remoteTwin18m")
        val sim = Simulator(67)
        val store = MemoryStore()
        val spec = DriveSpec(bumpsAt = listOf(800.0), cruiseKmh = 24.0)
        sim.drive(store, spec, tripId = 1)
        val local = store.saved.single()
        val q = Geo.move(local.lat, local.lon, 90.0, 18.0)
        val source = ListSpotSource(listOf(RemoteSpot(4, q[0], q[1], 90.0, BumpKind.BUMP, Side.UNKNOWN, 5.0, 2)))

        val t2 = sim.drive(store, spec, tripId = 2, spotSource = source)
        describe("trip 2 (twin 18 m ahead)", t2)
        check(t2.beepBumpIds == listOf(local.id), "exactly one warning, from your own spot: ${t2.beepBumpIds}")
        check(remoteBeeps(store, 2).isEmpty(), "the shared twin must not warn")

        BumpEngine(EngineConfig(), store, object : EngineListener {}, { 0L }).muteBump(local.id)
        val t3 = sim.drive(store, spec, tripId = 3, spotSource = source)
        describe("trip 3 (own spot muted)", t3)
        check(t3.beepBumpIds.isEmpty() && remoteBeeps(store, 3).isEmpty(), "muted spot must silence its twin 18 m away: ${t3.beepBumpIds}")
    }

    /** "Mute last beep" after a shared warning stores a muted spot of your own there: silent from then on. */
    fun muteSharedSpot() {
        log("muteSharedSpot")
        val sim = Simulator(68)
        val store = MemoryStore()
        val shared = remoteAt(sim, 5, 800.0)
        val source = ListSpotSource(listOf(shared))

        val t1 = sim.drive(store, DriveSpec(), tripId = 1, spotSource = source, muteEveryBeep = true)
        describe("trip 1 (mute the shared beep)", t1)
        check(t1.beepBumpIds.map { BumpEngine.remoteSpotId(it) } == listOf(5L), "trip 1 warns once for the shared spot: ${t1.beepBumpIds}")
        val mine = store.saved.single()
        check(mine.userMuted && mine.hits == 0, "a muted spot of your own is stored (hits ${mine.hits}, muted ${mine.userMuted})")
        check(Geo.distance(mine.lat, mine.lon, shared.lat, shared.lon) < 0.5 && !mine.legacy && mine.sevIndex == shared.severity, "it sits on the shared spot, same severity")
        val mute = store.events.single { it.type == "user_mute" }
        check(mute.bumpId == mine.id && mute.note == "remote 5", "user_mute logged for the new spot: ${mute.bumpId} ${mute.note}")

        for (trip in 2L..3L) {
            val r = sim.drive(store, DriveSpec(), tripId = trip, spotSource = source)
            describe("trip $trip", r)
            check(r.beepBumpIds.isEmpty() && remoteBeeps(store, trip).isEmpty(), "trip $trip: the muted shared spot must stay silent: ${r.beepBumpIds}")
        }
        check(store.saved.size == 1, "no extra spots, got ${store.saved.size}")
    }

    // ---------- sounds: first-pass tick, distinct warnings, groups ----------

    /** Every new spot gives a soft tick on the first pass, but never more than one per [EngineConfig.tickGapMs]. */
    fun firstPassTick() {
        log("firstPassTick")
        val spec = DriveSpec(bumpsAt = listOf(500.0, 1100.0, 1600.0))
        val t1 = Simulator(71).drive(MemoryStore(), spec, tripId = 1)
        describe("trip 1", t1)
        check(t1.newBumps == 3 && t1.ticks == 3, "3 new spots should tick 3 times, got new=${t1.newBumps} ticks=${t1.ticks}")
        check(t1.warnings.isEmpty(), "nothing known yet: no warnings, got ${t1.warnings.size}")

        val store = MemoryStore()
        val slowTicks = EngineConfig().apply { tickGapMs = 10 * 60_000L }
        val r = Simulator(71).drive(store, spec, slowTicks, tripId = 1)
        check(r.newBumps == 3 && r.ticks == 1, "rate limit: 3 new spots but 1 tick expected, got new=${r.newBumps} ticks=${r.ticks}")
        val t2 = Simulator(72).drive(store, spec, tripId = 2)
        check(t2.newBumps == 0 && t2.ticks == 0, "known spots don't tick, got ${t2.ticks}")

        // Spots the shared map already warned about are confirmed, not new: no tick.
        val sim = Simulator(76)
        val source = ListSpotSource(listOf(500.0, 1100.0, 1600.0).mapIndexed { i, p -> remoteAt(sim, 200L + i, p) })
        val t3 = sim.drive(MemoryStore(), spec, tripId = 3, spotSource = source)
        check(t3.newBumps == 3 && t3.warnings.size == 3, "3 shared bumps warned and recorded, got new=${t3.newBumps} warnings=${t3.warnings.size}")
        check(t3.ticks == 0, "no tick for spots the shared map knew, got ${t3.ticks}")
    }

    /** Each band has its own sound: mild one beep, moderate two, strong the voice; a "maybe" spot one soft beep. */
    fun warningSounds() {
        log("warningSounds")
        val sim = Simulator(73)
        val source = ListSpotSource(
            listOf(
                remoteAt(sim, 1, 400.0, severity = 3.0),
                remoteAt(sim, 2, 800.0, severity = 4.2),
                remoteAt(sim, 3, 1200.0, severity = 5.2),
                remoteAt(sim, 4, 1600.0, severity = 5.2, devices = 1),
            )
        )
        val t1 = sim.drive(MemoryStore(), DriveSpec(cruiseKmh = 50.0), tripId = 1, spotSource = source)
        describe("trip 1", t1)
        val sounds = t1.warnings.map { it.sound }
        check(sounds == listOf(WarnSound.MILD, WarnSound.MODERATE, WarnSound.STRONG, WarnSound.SOFT), "sounds: $sounds")
        check(t1.warnings.all { it.cluster == null }, "spots 400 m apart are not a group")
    }

    /** Three learned bumps 60 m apart: one "3 bumps ahead" instead of three warnings; a lone bump still beeps. */
    fun groupOfBumps() {
        log("groupOfBumps")
        val sim = Simulator(74)
        val store = MemoryStore()
        val spec = DriveSpec(bumpsAt = listOf(600.0, 660.0, 720.0, 1400.0))
        val t1 = sim.drive(store, spec, tripId = 1)
        describe("trip 1", t1)
        check(t1.newBumps == 4, "trip 1 should learn 4 bumps, got ${t1.newBumps}")

        // Quiet-below off, so without grouping all 4 would warn even at bump speed.
        val always = EngineConfig().apply { quietBelowKmh = 0.0 }
        val t2 = sim.drive(store, spec, always, tripId = 2)
        describe("trip 2", t2)
        check(t2.warnings.size == 2, "a group warning + the lone bump expected, got ${t2.warnings.size}")
        val g = t2.warnings[0].cluster
        check(g != null && g.count == 3 && !g.anyFull, "first warning should be a group of 3 bumps, each felt once so far")
        check(t2.warnings[1].cluster == null, "the lone bump is not a group")
        val grouped = store.events.count { it.tripId == 2L && it.type == "beep_grouped" }
        check(grouped == 2, "the 2 following bumps stay silent (beep_grouped), got $grouped")
        check(Phrases.cluster("en", g!!.count, anyStrong = false) == "3 bumps ahead.", "group phrase")

        val noGroups = EngineConfig().apply { quietBelowKmh = 0.0; clusterMinExtra = 99 }
        val t3 = sim.drive(store, spec, noGroups, tripId = 3)
        check(t3.warnings.size == 4, "without grouping every bump warns, got ${t3.warnings.size}")
    }

    /** A group (shared spots) says that one of them is strong; muted spots don't count towards a group. */
    fun groupNamesStrongBump() {
        log("groupNamesStrongBump")
        val sim = Simulator(75)
        val spots = listOf(
            remoteAt(sim, 1, 800.0, severity = 3.0),
            remoteAt(sim, 2, 860.0, severity = 7.0),
            remoteAt(sim, 3, 920.0, BumpKind.POTHOLE, severity = 4.2),
        )
        val store = MemoryStore()
        val t1 = sim.drive(store, DriveSpec(cruiseKmh = 50.0), tripId = 1, spotSource = ListSpotSource(spots))
        describe("trip 1", t1)
        check(t1.warnings.size == 1, "one group warning expected, got ${t1.warnings.size}")
        val g = t1.warnings[0].cluster
        check(g != null && g.count == 3 && g.maxSeverity == Severity.STRONG && g.anyFull, "group of 3 with a strong bump in it")
        check(t1.warnings[0].sound == WarnSound.MILD, "the first spot keeps its own sound (mild)")
        check(Phrases.cluster("en", g!!.count, g.maxSeverity == Severity.STRONG) == "3 bumps ahead, one strong.", "group phrase")
        check(store.events.count { it.tripId == 1L && it.type == "beep_grouped" } == 2, "the other two stay silent")

        // Mute the strong one: only 2 spots left, too few for a group, so both warn on their own.
        val fresh = MemoryStore()
        val e = BumpEngine(EngineConfig(), fresh, object : EngineListener {}, { 0L }, spotSource = ListSpotSource(spots))
        e.onFix(Fix(0, spots[1].lat, spots[1].lon, 10.0, 90.0, 5.0))
        check(e.muteBump(-2L - 2L) != null, "muting the shared strong bump should store a muted spot")
        val t2 = sim.drive(fresh, DriveSpec(cruiseKmh = 50.0), tripId = 2, spotSource = ListSpotSource(spots))
        describe("trip 2", t2)
        check(t2.warnings.size == 2 && t2.warnings.all { it.cluster == null }, "2 single warnings expected, got ${t2.warnings.map { it.cluster?.count }}")
    }

    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "calmDrivingScoresHigh" to ::calmDrivingScoresHigh,
        "speedingAndHardBraking" to ::speedingAndHardBraking,
        "swerving" to ::swerving,
        "speedBumpsTakenFast" to ::speedBumpsTakenFast,
        "phoneHandledWhileDriving" to ::phoneHandledWhileDriving,
        "pocketShiftNotACorner" to ::pocketShiftNotACorner,
        "sharpTurnCounted" to ::sharpTurnCounted,
        "pocketModeExcusesOnlyJostles" to ::pocketModeExcusesOnlyJostles,
        "unlockInPocketIsPhoneUse" to ::unlockInPocketIsPhoneUse,
        "mountedScreenOnIsNoPhoneUse" to ::mountedScreenOnIsNoPhoneUse,
        "handHeldCallIsPhoneUse" to ::handHeldCallIsPhoneUse,
        "brakingNeedsTheGpsSpeed" to ::brakingNeedsTheGpsSpeed,
        "slipWithTheScreenOnIsNoPhoneUse" to ::slipWithTheScreenOnIsNoPhoneUse,
        "passWhileHandledNotCounted" to ::passWhileHandledNotCounted,
        "stoppedIsNoPhoneUse" to ::stoppedIsNoPhoneUse,
        "holdLengthInEveryPlacement" to ::holdLengthInEveryPlacement,
        "noLockScreenUnlockNeedsMotion" to ::noLockScreenUnlockNeedsMotion,
        "repeatedHandlingIsPhoneUse" to ::repeatedHandlingIsPhoneUse,
        "fastBumpVersusRoadJoint" to ::fastBumpVersusRoadJoint,
        "dipIsABump" to ::dipIsABump,
        "severityCountsAndSounds" to ::severityCountsAndSounds,
        "noGyroStillLearns" to ::noGyroStillLearns,
        "fastDipRejected" to ::fastDipRejected,
        "quietWhenSlow" to ::quietWhenSlow,
        "missReportsNearbyJolt" to ::missReportsNearbyJolt,
        "learnThenBeep" to ::learnThenBeep,
        "otherDirection" to ::otherDirection,
        "handlingIgnored" to ::handlingIgnored,
        "bumpWhileHoldingIsNotLearned" to ::bumpWhileHoldingIsNotLearned,
        "joltsAroundHandling" to ::joltsAroundHandling,
        "parkedAndNoGps" to ::parkedAndNoGps,
        "crawlVersusRemoved" to ::crawlVersusRemoved,
        "userMute" to ::userMute,
        "softThenFull" to ::softThenFull,
        "legacySpotSoftUntilFelt" to ::legacySpotSoftUntilFelt,
        "remoteStandIns" to ::remoteStandIns,
        "remoteSpotsWarnFirstDrive" to ::remoteSpotsWarnFirstDrive,
        "localMuteSuppressesRemote" to ::localMuteSuppressesRemote,
        "remoteOldPotholesAreSoft" to ::remoteOldPotholesAreSoft,
        "observationsRecorded" to ::observationsRecorded,
        "remoteTwin18m" to ::remoteTwin18m,
        "muteSharedSpot" to ::muteSharedSpot,
        "firstPassTick" to ::firstPassTick,
        "warningSounds" to ::warningSounds,
        "groupOfBumps" to ::groupOfBumps,
        "groupNamesStrongBump" to ::groupNamesStrongBump,
    )
}
