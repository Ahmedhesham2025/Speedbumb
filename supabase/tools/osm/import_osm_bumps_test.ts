// Offline test of the Overpass -> osm_bumps parsing (no network). Run: deno test supabase/tools/osm/
import { QUERY, toBumps } from "./import_osm_bumps.ts";

function assertEquals(actual: unknown, expected: unknown) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a !== e) throw new Error(`expected ${e}\n  actual ${a}`);
}

Deno.test("nodes by position, ways by centre, legacy highway=speed_bump, others dropped", () => {
  const rows = toBumps({ elements: [
    { type: "node", id: 1, lat: 30.0, lon: 31.0, tags: { traffic_calming: "hump" } },
    { type: "way", id: 2, center: { lat: 30.1, lon: 31.1 }, tags: { traffic_calming: "table" } },
    { type: "node", id: 3, lat: 30.2, lon: 31.2, tags: { highway: "speed_bump" } },
    { type: "node", id: 4, lat: 30.3, lon: 31.3, tags: { traffic_calming: "chicane" } },   // not a bump
    { type: "relation", id: 5, center: { lat: 30.4, lon: 31.4 }, tags: { traffic_calming: "bump" } },
    { type: "way", id: 6, tags: { traffic_calming: "bump" } },                             // no centre
    { type: "node", id: 7, lat: 51.5, lon: -0.1, tags: { traffic_calming: "bump" } },      // outside the box
    { type: "node", id: 1, lat: 30.0, lon: 31.0, tags: { traffic_calming: "hump" } },      // repeated
  ] });
  assertEquals(rows, [
    { osm_id: "n1", lat: 30.0, lon: 31.0, kind: "hump" },
    { osm_id: "w2", lat: 30.1, lon: 31.1, kind: "table" },
    { osm_id: "n3", lat: 30.2, lon: 31.2, kind: "speed_bump" },
  ]);
});

Deno.test("one query, ways with their centre", () => {
  assertEquals(QUERY.includes("out center;") && QUERY.includes("highway=speed_bump"), true);
  assertEquals(toBumps({}), []);
});
