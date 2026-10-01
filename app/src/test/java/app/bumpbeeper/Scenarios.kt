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

    /** At 70 km/h: a clear pothole is still recorded; an ordinary jolt is rejected as too fast for a speed bump. */
    fun fastPothole() {
        log("fastPothole")
        val sim = Simulator(31)
        val store = MemoryStore()
        val spec1 = DriveSpec(potholesAt = listOf(1000.0), oneOffJoltsAt = listOf(600.0), cruiseKmh = 70.0)
        val t1 = sim.drive(store, spec1, tripId = 1)
        describe("trip 1", t1)
        t1.forwardTrace.forEach { log("    $it") }
        check(t1.newBumps == 1, "only the pothole should be recorded at 70 km/h, got ${t1.newBumps}")
        check("too_fast" in t1.rejected, "the plain jolt at 70 km/h should be rejected as too_fast: ${t1.rejected}")
        check(store.saved.single().kind == BumpKind.POTHOLE, "the recorded spot should be a pothole")

        val t2 = sim.drive(store, DriveSpec(potholesAt = listOf(1000.0), cruiseKmh = 70.0), tripId = 2)
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

    fun all(): List<Pair<String, () -> Unit>> = listOf(
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
    )
}
