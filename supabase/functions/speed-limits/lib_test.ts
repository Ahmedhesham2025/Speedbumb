// deno test supabase/functions/speed-limits/  (offline: TomTom, auth and the quota RPC are fakes)
import { assert, assertEquals, assertThrows } from "jsr:@std/assert@1";
import { BadInput, buildTomTomRequest, type Deps, handle, mapResponse, parsePoints, type Quota } from "./lib.ts";

const T0 = 1_696_000_000_000;
// Desert test coordinates, ~110 m apart along a meridian.
const pts = (n: number, step = 0.001) => Array.from({ length: n }, (_, i) => ({ lat: 24 + i * step, lon: 29, t: T0 + i * 1000 }));

// ---------------------------------------------------------------- input validation

Deno.test("accepts 2 and 5000 points", () => {
  assertEquals(parsePoints({ points: pts(2) }).length, 2);
  assertEquals(parsePoints({ points: pts(5000, 0.00015) }).length, 5000); // ~83 km
});

Deno.test("rejects bad input", () => {
  const bad: unknown[] = [
    null,
    {},
    { points: "x" },
    { points: pts(1) },
    { points: pts(5001, 0.00001) },
    { points: [{ lat: 24, lon: 29, t: T0 }, { lat: "24", lon: 29, t: T0 }] },
    { points: [{ lat: 24, lon: 29, t: T0 }, { lat: 91, lon: 29, t: T0 }] },
    { points: [{ lat: 24, lon: 29, t: T0 }, { lat: 24, lon: 181, t: T0 }] },
    { points: [{ lat: 24, lon: 29, t: T0 }, { lat: 24, lon: 29, t: NaN }] },
    { points: [{ lat: 24, lon: 29, t: T0 / 1000 }, { lat: 24, lon: 29, t: T0 / 1000 + 1 }] }, // seconds, not ms
    { points: [{ lat: 24, lon: 29, t: T0 }, { lat: 24.001, lon: 29, t: T0 - 1 }] }, // out of order
    { points: [{ lat: 24, lon: 29, t: T0 }, { lat: 24.1, lon: 29, t: T0 + 1 }] }, // 11 km gap
    { points: pts(1000, 0.001) }, // ~111 km route
  ];
  for (const b of bad) assertThrows(() => parsePoints(b), BadInput);
});

Deno.test("error messages never contain coordinates", () => {
  try {
    parsePoints({ points: [{ lat: 24.123456, lon: 29.654321, t: T0 }, { lat: 95.5, lon: 29, t: T0 }] });
  } catch (e) {
    assert(!/24\.12|29\.65|95\.5/.test((e as Error).message));
  }
});

// ---------------------------------------------------------------- TomTom request

Deno.test("builds a POST with GeoJSON points [lon, lat], ISO timestamps and the speed-limit fields", () => {
  const { url, init } = buildTomTomRequest(pts(2), "TEST-KEY");
  const u = new URL(url);
  assertEquals(u.origin + u.pathname, "https://api.tomtom.com/snapToRoads/1");
  assertEquals(u.searchParams.get("key"), "TEST-KEY");
  assertEquals(
    u.searchParams.get("fields"),
    "{projectedPoints{properties{routeIndex,snapResult}},route{properties{speedLimits{value,unit,type}}}}",
  );
  assertEquals(init.method, "POST");
  const body = JSON.parse(init.body as string);
  assertEquals(body.points[1], {
    type: "Feature",
    geometry: { type: "Point", coordinates: [29, 24.001] },
    properties: { timestamp: "2023-09-29T15:06:41.000Z" },
  });
});

// ---------------------------------------------------------------- response mapping

const FIXTURE = {
  route: [
    { properties: { speedLimits: { value: 60, unit: "kmph", type: "Maximum" } } },
    { properties: { speedLimits: { value: 45, unit: "mph", type: "Maximum" } } },
    { properties: {} },
    { properties: { speedLimits: { value: 30, unit: "kmph", type: "Recommended" } } },
  ],
  projectedPoints: [
    { properties: { routeIndex: 0, snapResult: "Matched" } },
    { properties: { routeIndex: 1, snapResult: "Matched" } },
    { properties: { routeIndex: null, snapResult: "OffRoad" } },
    { properties: { routeIndex: 2, snapResult: "Matched" } },
    { properties: { routeIndex: 3, snapResult: "Matched" } },
    { properties: { routeIndex: 9, snapResult: "Matched" } },
    { properties: { routeIndex: 0, snapResult: "MaxDistanceExceeded" } },
  ],
};

Deno.test("maps projected points to limits: km/h, mph converted, unsnapped and unknown are null", () => {
  assertEquals(mapResponse(FIXTURE, 7), [
    { i: 0, kmh: 60 },
    { i: 1, kmh: 72 }, // 45 mph
    { i: 2, kmh: null }, // off road
    { i: 3, kmh: null }, // no speed limit on that road
    { i: 4, kmh: null }, // advisory only
    { i: 5, kmh: null }, // bad index
    { i: 6, kmh: null },
  ]);
});

Deno.test("a response with the wrong number of points is unusable", () => {
  assertThrows(() => mapResponse(FIXTURE, 8));
  assertThrows(() => mapResponse({ route: [] }, 2));
});

// ---------------------------------------------------------------- handler, quota, errors

function fakeDeps(over: Partial<Deps> = {}) {
  const calls = { fetch: 0, quota: 0, urls: [] as string[] };
  const deps: Deps = {
    apiKey: "TEST-KEY",
    userId: () => Promise.resolve("user-1"),
    takeQuota: () => {
      calls.quota++;
      return Promise.resolve("ok" as Quota);
    },
    fetch: (url) => {
      calls.fetch++;
      calls.urls.push(String(url));
      return Promise.resolve(Response.json({
        route: [{ properties: { speedLimits: { value: 80, unit: "kmph", type: "Maximum" } } }],
        projectedPoints: [
          { properties: { routeIndex: 0, snapResult: "Matched" } },
          { properties: { routeIndex: null, snapResult: "OffRoad" } },
        ],
      }));
    },
    ...over,
  };
  return { deps, calls };
}
const post = (body: unknown) => new Request("http://x/speed-limits", { method: "POST", body: JSON.stringify(body) });

Deno.test("200 with one limit per point, source and attribution", async () => {
  const { deps, calls } = fakeDeps();
  const res = await handle(post({ points: pts(2) }), deps);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), {
    source: "tomtom",
    attribution: "© TomTom",
    limits: [{ i: 0, kmh: 80 }, { i: 1, kmh: null }],
  });
  assertEquals([calls.quota, calls.fetch], [1, 1]);
});

Deno.test("no user is 401, bad input is 400, neither spends quota", async () => {
  const a = fakeDeps({ userId: () => Promise.resolve(null) });
  assertEquals((await handle(post({ points: pts(2) }), a.deps)).status, 401);
  const b = fakeDeps();
  assertEquals((await handle(post({ points: pts(1) }), b.deps)).status, 400);
  assertEquals((await handle(new Request("http://x", { method: "POST", body: "{" }), b.deps)).status, 400);
  assertEquals((await handle(new Request("http://x"), b.deps)).status, 405);
  assertEquals([a.calls.quota + b.calls.quota, a.calls.fetch + b.calls.fetch], [0, 0]);
});

Deno.test("over a quota is 429 and TomTom is not called", async () => {
  for (const q of ["user", "project"] as Quota[]) {
    const { deps, calls } = fakeDeps({ takeQuota: () => Promise.resolve(q) });
    const res = await handle(post({ points: pts(2) }), deps);
    assertEquals(res.status, 429);
    assertEquals(await res.json(), { error: "quota" });
    assertEquals(calls.fetch, 0);
  }
});

Deno.test("missing key, quota failure, TomTom error, timeout or bad shape are 503 without leaking the key or URL", async () => {
  const cases: Partial<Deps>[] = [
    { apiKey: undefined },
    { apiKey: "" },
    { takeQuota: () => Promise.reject(new Error("db down")) },
    { fetch: () => Promise.resolve(new Response("Developer Inactive TEST-KEY", { status: 403 })) },
    { fetch: () => Promise.resolve(new Response("{}", { status: 429 })) },
    { fetch: () => Promise.reject(new DOMException("timed out", "TimeoutError")) },
    { fetch: () => Promise.resolve(Response.json({ route: [] })) },
  ];
  for (const c of cases) {
    const { deps } = fakeDeps(c);
    const res = await handle(post({ points: pts(2) }), deps);
    assertEquals(res.status, 503);
    const text = await res.text();
    assertEquals(JSON.parse(text), { error: "unavailable" });
    assert(!text.includes("TEST-KEY") && !text.includes("tomtom.com"));
  }
});

Deno.test("a missing key does not spend quota", async () => {
  const { deps, calls } = fakeDeps({ apiKey: undefined });
  await handle(post({ points: pts(2) }), deps);
  assertEquals(calls.quota, 0);
});
