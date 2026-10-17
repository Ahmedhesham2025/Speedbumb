// Owner tool, run on the owner's PC at most weekly (never in CI): imports OpenStreetMap speed bumps in Egypt's bounding
// box into public.osm_bumps with ONE Overpass API query (a descriptive User-Agent, no retries in a loop), then deletes
// the bumps the run no longer saw. Data © OpenStreetMap contributors, ODbL (supabase/README.md, OSM speed bumps).
// Needs SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY in the environment; never commit, paste or log the key.
// Usage: import_osm_bumps.ts [--dry-run] [--force]   (--force: run although the last import is under 6 days old)

export const OVERPASS_URL = "https://overpass-api.de/api/interpreter";
export const USER_AGENT = "BumpBeeper-osm-import/1.0 (+https://github.com/Ahmedhesham2025/Speedbumb)";
// south, west, north, east: Egypt's bounding box (the database accepts lat 21.5..32, lon 24.5..37.5).
export const BBOX = "22.0,24.6,31.8,37.0";
const CALMING = "^(bump|hump|table|cushion|yes)$";
export const QUERY = `[out:json][timeout:180][bbox:${BBOX}];
(node[traffic_calming~"${CALMING}"];way[traffic_calming~"${CALMING}"];node[highway=speed_bump];way[highway=speed_bump];);
out center;`;
const MIN_GAP_MS = 6 * 24 * 3600 * 1000;

export type OsmBump = { osm_id: string; lat: number; lon: number; kind: string };
type Element = { type?: string; id?: number; lat?: number; lon?: number; center?: { lat?: number; lon?: number };
  tags?: Record<string, string> };

/** Overpass JSON -> rows for osm_bumps_import: nodes by position, ways by their centre; duplicates and others dropped. */
export function toBumps(json: { elements?: Element[] }): OsmBump[] {
  const out = new Map<string, OsmBump>();
  for (const e of json.elements ?? []) {
    if ((e.type !== "node" && e.type !== "way") || !Number.isSafeInteger(e.id)) continue;
    const lat = e.type === "node" ? e.lat : e.center?.lat;
    const lon = e.type === "node" ? e.lon : e.center?.lon;
    if (typeof lat !== "number" || typeof lon !== "number") continue;
    if (lat < 21.5 || lat > 32 || lon < 24.5 || lon > 37.5) continue;
    const calming = e.tags?.traffic_calming;
    const kind = calming && /^(bump|hump|table|cushion|yes)$/.test(calming)
      ? calming
      : e.tags?.highway === "speed_bump" ? "speed_bump" : "";
    if (!kind) continue;
    const id = `${e.type === "node" ? "n" : "w"}${e.id}`;
    out.set(id, { osm_id: id, lat, lon, kind });
  }
  return [...out.values()];
}

function fail(message: string): never {
  console.error(`${message}\nusage: import_osm_bumps.ts [--dry-run] [--force]`);
  Deno.exit(2);
}

async function main() {
  let dryRun = false, force = false;
  for (const arg of Deno.args) {
    if (arg === "--dry-run") dryRun = true;
    else if (arg === "--force") force = true;
    else fail(`unknown argument: ${arg}`);
  }
  const base = (Deno.env.get("SUPABASE_URL") ?? "").replace(/\/+$/, "");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  if (!/^(https:\/\/[^/]+|http:\/\/(127\.0\.0\.1|localhost)(:\d+)?)$/.test(base) || !key) {
    fail("set SUPABASE_URL (https://<project>.supabase.co) and SUPABASE_SERVICE_ROLE_KEY");
  }
  const auth: Record<string, string> = key.startsWith("eyJ") ? { apikey: key, Authorization: `Bearer ${key}` } : { apikey: key };
  const rpc = async <T>(name: string, body: unknown): Promise<T> => {
    const res = await fetch(`${base}/rest/v1/rpc/${name}`, {
      method: "POST", headers: { ...auth, "Content-Type": "application/json" }, body: JSON.stringify(body),
    });
    const text = await res.text();
    if (!res.ok) throw new Error(`${name}: HTTP ${res.status} ${text.slice(0, 300)}`);
    return JSON.parse(text) as T;
  };

  // Weekly at most: Overpass is a shared, donated service.
  const status = await rpc<{ count: number; last_import: string | null }>("osm_bumps_status", {});
  const last = status.last_import ? Date.parse(status.last_import) : 0;
  if (!force && Date.now() - last < MIN_GAP_MS) {
    console.log(`last import ${status.last_import} (${status.count} bumps) is under 6 days old; nothing to do (--force to run)`);
    return;
  }
  const runAt = new Date().toISOString();
  const res = await fetch(OVERPASS_URL, {
    method: "POST",
    headers: { "User-Agent": USER_AGENT, "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ data: QUERY }),
  });
  if (!res.ok) throw new Error(`Overpass: HTTP ${res.status} (busy? try again in a few hours, not in a loop)`);
  const bumps = toBumps(await res.json());
  console.log(`Overpass: ${bumps.length} bumps in the box (${status.count} stored now)`);
  // A broken or empty answer must not wipe the table.
  if (bumps.length < 0.5 * status.count) throw new Error("fewer than half of the stored bumps came back; not importing");
  if (dryRun) return;
  let taken = 0;
  for (let i = 0; i < bumps.length; i += 5000) {
    taken += await rpc<number>("osm_bumps_import", { batch: bumps.slice(i, i + 5000), run_at: runAt });
  }
  const pruned = await rpc<number>("osm_bumps_prune", { run_at: runAt });
  console.log(`imported ${taken}, removed ${pruned} that OSM no longer has. © OpenStreetMap contributors (ODbL)`);
}

if (import.meta.main) await main();
