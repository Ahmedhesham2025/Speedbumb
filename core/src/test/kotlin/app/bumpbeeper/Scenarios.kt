package app.bumpbeeper

import java.util.Locale

/**
 * Simulated drives that check the behaviour end to end.
 * Run from Android Studio (BumpEngineTest) — no phone needed.
 */
object Scenarios {
    var log: (String) -> Unit = { println(it) }

    private fun check(ok: Boolean, msg: String) {
        if (!ok) throw AssertionError(msg)
    }

    private fun f1(x: Double) = String.format(Locale.US, "%.0f", x)

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
            log("  bump @${f1(p)} m: hits ${b.hits}/${b.passes}, position error ${String.format(Locale.US, "%.1f", err)} m")
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

    /** A passenger grabbing the phone must not create a bump. */
    fun handlingIgnored() {
        log("handlingIgnored")
        val sim = Simulator(3)
        val store = MemoryStore()
        val r = sim.drive(store, DriveSpec(slowZonesAt = listOf(600.0), handlingAt = 600.0))
        describe("drive", r)
        check(r.newBumps == 0, "handling the phone created ${r.newBumps} bump(s)")
        check("phone_moving" in r.rejected, "expected a phone_moving rejection, got ${r.rejected}")
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

    private fun nearest(store: MemoryStore, sim: Simulator, p: Double, westbound: Boolean = false): Bump {
        val truth = sim.point(p, westbound)
        return store.saved.minByOrNull { Geo.distance(it.lat, it.lon, truth[0], truth[1]) }!!
    }

    /** Speed bumps (car pitches, up first) and a pothole (car rolls, down first) are told apart and warned differently. */
    fun potholeVsBump() {
        log("potholeVsBump")
        val sim = Simulator(21)
        val store = MemoryStore()
        val spec = DriveSpec(bumpsAt = listOf(400.0, 1500.0), potholesAt = listOf(900.0), cruiseKmh = 40.0)

        val t1 = sim.drive(store, spec, tripId = 1)
        describe("trip 1", t1)
        check(t1.newBumps == 3, "trip 1 should record 2 bumps + 1 pothole, got ${t1.newBumps}")
        for (p in listOf(400.0, 1500.0)) {
            val b = nearest(store, sim, p)
            log("  @${f1(p)}: ${b.kind} score ${String.format(Locale.US, "%.2f", b.kindScore)}")
            check(b.kind == BumpKind.BUMP, "spot at $p should be a speed bump, got ${b.kind} (${b.kindScore})")
        }
        val hole = nearest(store, sim, 900.0)
        log("  @900: ${hole.kind} score ${String.format(Locale.US, "%.2f", hole.kindScore)}")
        check(hole.kind == BumpKind.POTHOLE, "spot at 900 should be a pothole, got ${hole.kind} (${hole.kindScore})")
        // The gyroscope clue must really have been used (not just up-first / down-first).
        val notes = store.events.filter { it.type == "new_bump" }.map { it.note }
        log("  notes: $notes")
        check(notes.all { "roll/pitch" in it }, "every new spot should be judged with the gyroscope: $notes")

        val t2 = sim.drive(store, spec, tripId = 2)
        describe("trip 2", t2)
        check(t2.beepBumpIds.size == 3, "trip 2 should warn 3 times, got ${t2.beepBumpIds.size}")
        check(t2.beepKinds.count { it == BumpKind.POTHOLE } == 1, "exactly one pothole warning expected: ${t2.beepKinds}")
        check(t2.beepKinds.count { it == BumpKind.BUMP } == 2, "two speed bump warnings expected: ${t2.beepKinds}")

        // "Warn for potholes" off: only the speed bumps warn.
        val noHoles = EngineConfig().apply { warnPotholes = false }
        val t3 = sim.drive(store, spec, noHoles, tripId = 3)
        describe("trip 3 (no pothole warnings)", t3)
        check(t3.beepBumpIds.size == 2 && BumpKind.POTHOLE !in t3.beepKinds, "only the 2 speed bumps should warn: ${t3.beepKinds}")
    }

    /**
     * Potholes: which wheel hits them (left/right) is learned, every pothole is counted,
     * and only the harsh ones warn (the small one is counted but silent).
     */
    fun potholeSidesAndCounts() {
        log("potholeSidesAndCounts")
        val sim = Simulator(41)
        val store = MemoryStore()
        val cfg = EngineConfig()
        val spec = DriveSpec(
            bumpsAt = listOf(300.0), potholesAt = listOf(700.0), potholesLeftAt = listOf(1100.0),
            smallPotholesAt = listOf(1500.0), cruiseKmh = 40.0,
        )
        val t1 = sim.drive(store, spec, tripId = 1)
        describe("trip 1", t1)
        log("    potholes hit=${t1.stats.potholes} new=${t1.stats.newPotholes} harsh=${t1.stats.harshPotholes}")
        store.events.filter { it.type == "new_bump" }.forEach { log("    new: ${it.note}") }
        t1.forwardTrace.forEach { log("    $it") }
        check(t1.newBumps == 4, "trip 1 should record 1 bump + 3 potholes, got ${t1.newBumps}")
        check(t1.stats.potholes == 3 && t1.stats.newPotholes == 3, "all 3 potholes should be counted: ${t1.stats.potholes}/${t1.stats.newPotholes}")
        check(t1.stats.harshPotholes == 2, "2 of them are harsh, got ${t1.stats.harshPotholes}")

        val right = nearest(store, sim, 700.0)
        val left = nearest(store, sim, 1100.0)
        val small = nearest(store, sim, 1500.0)
        for ((name, b) in listOf("right@700" to right, "left@1100" to left, "small@1500" to small)) {
            log(String.format(Locale.US, "  %s: %s side=%s (%.2f) peak=%.1f harsh=%b", name, b.kind, b.side, b.sideScore, b.peakAvg, b.isHarsh(cfg)))
        }
        check(right.kind == BumpKind.POTHOLE && right.side == Side.RIGHT && right.isHarsh(cfg), "700 should be a harsh pothole on the right")
        check(left.kind == BumpKind.POTHOLE && left.side == Side.LEFT && left.isHarsh(cfg), "1100 should be a harsh pothole on the left")
        check(small.kind == BumpKind.POTHOLE && !small.isHarsh(cfg), "1500 should be a small (not harsh) pothole")

        val t2 = sim.drive(store, spec, tripId = 2)
        describe("trip 2", t2)
        store.events.filter { it.tripId == 2L && it.type in setOf("new_bump", "hit", "miss", "hit_repeat") }.forEach {
            val truthDist = listOf(300.0, 700.0, 1100.0, 1500.0).minOf { p ->
                val q = sim.point(p, false); Geo.distance(it.lat, it.lon, q[0], q[1])
            }
            log(String.format(Locale.US, "    %s #%d d=%.1f truth=%.1f speed=%.0f peak=%.1f %s", it.type, it.bumpId, it.distanceM, truthDist, it.speedKmh, it.peak, it.note))
        }
        check(t2.newBumps == 0, "trip 2 must not record anything new (all spots are known), got ${t2.newBumps}")
        check(t2.stats.potholes == 3, "trip 2 should count all 3 potholes again, got ${t2.stats.potholes}")
        check(t2.beepKinds == listOf(BumpKind.BUMP, BumpKind.POTHOLE, BumpKind.POTHOLE),
            "trip 2: bump beep + 2 harsh pothole warnings, small one silent; got ${t2.beepKinds}")
        check(small.id !in t2.beepBumpIds, "the small pothole must not warn")
        check(Phrases.pothole("en", right.side) == "Pothole on the right. Keep left.", "right-side phrase")
        check(Phrases.pothole("en", left.side) == "Pothole on the left. Keep right.", "left-side phrase")
    }

    /** Without a gyroscope the up-first / down-first clue alone still separates them. */
    fun potholeVsBumpNoGyro() {
        log("potholeVsBumpNoGyro")
        val sim = Simulator(22)
        val store = MemoryStore()
        val r = sim.drive(store, DriveSpec(bumpsAt = listOf(500.0), potholesAt = listOf(1100.0), cruiseKmh = 40.0, gyro = false))
        describe("trip 1", r)
        check(r.newBumps == 2, "expected 2 new spots, got ${r.newBumps}")
        check(nearest(store, sim, 500.0).kind == BumpKind.BUMP, "500 should be a speed bump")
        check(nearest(store, sim, 1100.0).kind == BumpKind.POTHOLE, "1100 should be a pothole")
    }

    /**
     * At 70 km/h: a clear pothole is still recorded; an ordinary jolt is rejected as too fast for a speed bump.
     * (The slow zone gives one braking + speeding up, which the engine needs to learn which way is forward.)
     */
    fun fastPothole() {
        log("fastPothole")
        val sim = Simulator(31)
        val store = MemoryStore()
        val spec1 = DriveSpec(potholesAt = listOf(1100.0), oneOffJoltsAt = listOf(750.0), slowZonesAt = listOf(350.0), cruiseKmh = 70.0)
        val t1 = sim.drive(store, spec1, tripId = 1)
        describe("trip 1", t1)
        t1.forwardTrace.forEach { log("    $it") }
        check(t1.newBumps == 1, "only the pothole should be recorded at 70 km/h, got ${t1.newBumps}")
        check("too_fast" in t1.rejected, "the plain jolt at 70 km/h should be rejected as too_fast: ${t1.rejected}")
        check(store.saved.single().kind == BumpKind.POTHOLE, "the recorded spot should be a pothole")

        val t2 = sim.drive(store, DriveSpec(potholesAt = listOf(1100.0), slowZonesAt = listOf(350.0), cruiseKmh = 70.0), tripId = 2)
        describe("trip 2", t2)
        check(t2.beepKinds == listOf(BumpKind.POTHOLE), "trip 2 should give one pothole warning, got ${t2.beepKinds}")
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
        log(String.format(Locale.US,
            "  %s: score=%d km=%.1f speeding=%.0f%% brakes=%d accels=%d corners=%d swerves=%d bumpsFast=%d phone=%d",
            name, d.score(), d.distanceM / 1000, d.speedingShare * 100, d.harshBrakes, d.harshAccels, d.harshCorners,
            d.swerves, d.bumpsFast, d.phoneUse))
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
            log(String.format(Locale.US, "    %s at %.0f km/h: %s", it.type, it.speedKmh, it.note))
        }
        r.forwardTrace.forEach { log("    $it") }
        val d = r.driving
        check(d.harshBrakes == 2, "expected 2 harsh brakes, got ${d.harshBrakes}")
        check(d.harshAccels == 0, "speeding up again normally is not harsh, got ${d.harshAccels}")
        check(d.speedingShare > 0.4, "most of the trip is above 90 km/h, got ${d.speedingShare}")
        check(store.events.any { it.type == "speeding" }, "a speeding episode should be logged")
        check(d.score() in 0..70, "this trip should score 70 or less, got ${d.score()}")
    }

    /** Two sudden left-right swerves at 50 km/h are counted as swerves (not as harsh cornering). */
    fun swerving() {
        log("swerving")
        val sim = Simulator(53)
        val r = sim.drive(MemoryStore(), DriveSpec(swervesAt = listOf(600.0, 1300.0)))
        describeDriving("swerves", r.driving)
        check(r.driving.swerves == 2, "expected 2 swerves, got ${r.driving.swerves}")
        check(r.driving.harshCorners == 0, "swerves shouldn't also count as harsh cornering, got ${r.driving.harshCorners}")
        check(r.driving.score() < 95, "swerving should cost points, got ${r.driving.score()}")
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

    /** A passenger (or driver) picking up the phone while moving counts as phone use. */
    fun phoneHandledWhileDriving() {
        log("phoneHandledWhileDriving")
        val sim = Simulator(3)
        val r = sim.drive(MemoryStore(), DriveSpec(slowZonesAt = listOf(600.0), handlingAt = 600.0))
        describeDriving("phone", r.driving)
        check(r.driving.phoneUse == 1, "expected 1 phone use, got ${r.driving.phoneUse}")
    }

    // ---------- shared online map ----------

    /** A shared-map spot at road position [p] (eastbound unless [westbound]). */
    private fun remoteAt(
        sim: Simulator, id: Long, p: Double, kind: BumpKind = BumpKind.BUMP, side: Side = Side.UNKNOWN,
        severity: Double = 5.0, westbound: Boolean = false,
    ): RemoteSpot {
        val q = sim.point(p, westbound)
        return RemoteSpot(id, q[0], q[1], if (westbound) 270.0 else 90.0, kind, side, severity, nDevices = 3)
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

    /** Shared potholes follow the same rules: only harsh ones warn, and none with pothole warnings off. */
    fun remotePotholeRules() {
        log("remotePotholeRules")
        val sim = Simulator(63)
        val store = MemoryStore()
        val small = remoteAt(sim, 7, 700.0, BumpKind.POTHOLE, Side.RIGHT, severity = 4.2)
        val harsh = remoteAt(sim, 8, 1300.0, BumpKind.POTHOLE, Side.LEFT, severity = 7.0)
        val source = ListSpotSource(listOf(small, harsh))
        val spec = DriveSpec(cruiseKmh = 40.0)

        val t1 = sim.drive(store, spec, tripId = 1, spotSource = source)
        describe("trip 1", t1)
        check(t1.beepBumpIds.map { BumpEngine.remoteSpotId(it) } == listOf(8L), "only the harsh shared pothole should warn, got ${t1.beepBumpIds}")
        check(t1.beepKinds == listOf(BumpKind.POTHOLE), "it warns as a pothole, got ${t1.beepKinds}")

        val off = EngineConfig().apply { warnPotholes = false }
        val t2 = sim.drive(store, spec, off, tripId = 2, spotSource = source)
        describe("trip 2 (no pothole warnings)", t2)
        check(t2.beepBumpIds.isEmpty(), "pothole warnings off: no shared pothole warns, got ${t2.beepBumpIds}")
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
            log("  $name: ${os.map { "${it.kind} ${f1(it.speedKmh)} km/h peak ${String.format(Locale.US, "%.1f", it.peak)}" }}")

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

    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "calmDrivingScoresHigh" to ::calmDrivingScoresHigh,
        "speedingAndHardBraking" to ::speedingAndHardBraking,
        "swerving" to ::swerving,
        "speedBumpsTakenFast" to ::speedBumpsTakenFast,
        "phoneHandledWhileDriving" to ::phoneHandledWhileDriving,
        "potholeVsBump" to ::potholeVsBump,
        "potholeVsBumpNoGyro" to ::potholeVsBumpNoGyro,
        "fastPothole" to ::fastPothole,
        "quietWhenSlow" to ::quietWhenSlow,
        "missReportsNearbyJolt" to ::missReportsNearbyJolt,
        "learnThenBeep" to ::learnThenBeep,
        "otherDirection" to ::otherDirection,
        "handlingIgnored" to ::handlingIgnored,
        "parkedAndNoGps" to ::parkedAndNoGps,
        "crawlVersusRemoved" to ::crawlVersusRemoved,
        "userMute" to ::userMute,
        "remoteSpotsWarnFirstDrive" to ::remoteSpotsWarnFirstDrive,
        "localMuteSuppressesRemote" to ::localMuteSuppressesRemote,
        "remotePotholeRules" to ::remotePotholeRules,
        "observationsRecorded" to ::observationsRecorded,
    )
}
