// Pure logic of the `speed-limits` Edge Function, kept free of Supabase imports so `deno test` can run it offline.
// Rules: never log coordinates; never store TomTom results (TomTom T&C 11.4); never echo the TomTom URL or key.

export const MAX_POINTS = 5000;
export const MAX_ROUTE_KM = 100; // TomTom Freemium limit per request
export const MAX_GAP_KM = 6; // TomTom: at most 6,000 m of road between consecutive points
export const MAX_BODY_BYTES = 1_000_000;
const TOMTOM_URL = "https://api.tomtom.com/snapToRoads/1";
// Only what we need: one projected point per input point, and each road element's speed limit.
const FIELDS = "{projectedPoints{properties{routeIndex,snapResult}},route{properties{speedLimits{value,unit,type}}}}";
const MPH_TO_KMH = 1.609344;

export interface Point {
  lat: number;
  lon: number;
  t: number;
}
export interface Limit {
  i: number;
  kmh: number | null;
}
export type Quota = "ok" | "user" | "project";

export interface Deps {
  apiKey: string | undefined;
  /** The signed-in Supabase user's id, or null if the JWT is not a user's. */
  userId(req: Request): Promise<string | null>;
  /** Counts one call; throws if the database can't be reached. */
  takeQuota(userId: string): Promise<Quota>;
  fetch: typeof fetch;
}

export class BadInput extends Error {}

export function haversineKm(a: Point, b: Point): number {
  const r = 6371.0088;
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLon = (b.lon - a.lon) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
}

const isNum = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v);

/** Checks the request body; throws BadInput with a message that never contains coordinates. */
export function parsePoints(body: unknown): Point[] {
  const raw = (body as { points?: unknown } | null)?.points;
  if (!Array.isArray(raw)) throw new BadInput("points must be an array");
  if (raw.length < 2 || raw.length > MAX_POINTS) throw new BadInput(`2 to ${MAX_POINTS} points are needed`);
  const pts: Point[] = [];
  let km = 0;
  for (let i = 0; i < raw.length; i++) {
    const p = raw[i] as Record<string, unknown> | null;
    if (!p || !isNum(p.lat) || !isNum(p.lon) || !isNum(p.t)) throw new BadInput(`point ${i}: lat, lon and t must be numbers`);
    if (p.lat < -90 || p.lat > 90 || p.lon < -180 || p.lon > 180) throw new BadInput(`point ${i}: out of range`);
    // t is epoch milliseconds; years 2000..2100 catch seconds-instead-of-millis mistakes.
    if (p.t < 946_684_800_000 || p.t > 4_102_444_800_000) throw new BadInput(`point ${i}: t must be epoch milliseconds`);
    const pt = { lat: p.lat, lon: p.lon, t: p.t };
    if (i > 0) {
      const prev = pts[i - 1];
      if (pt.t < prev.t) throw new BadInput(`point ${i}: points must be in time order`);
      const d = haversineKm(prev, pt);
      if (d > MAX_GAP_KM) throw new BadInput(`point ${i}: more than ${MAX_GAP_KM} km from the previous point`);
      km += d;
    }
    pts.push(pt);
  }
  if (km > MAX_ROUTE_KM) throw new BadInput(`route longer than ${MAX_ROUTE_KM} km`);
  return pts;
}

/** The TomTom Snap to Roads request (POST, GeoJSON points in the body). */
export function buildTomTomRequest(points: Point[], apiKey: string): { url: string; init: RequestInit } {
  const q = new URLSearchParams({
    key: apiKey,
    fields: FIELDS,
    vehicleType: "PassengerCar",
    measurementSystem: "metric",
  });
  const body = {
    points: points.map((p) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [p.lon, p.lat] },
      properties: { timestamp: new Date(p.t).toISOString() },
    })),
  };
  return {
    url: `${TOMTOM_URL}?${q}`,
    init: { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) },
  };
}

function toKmh(sl: unknown): number | null {
  // Documented as one object; accept an array too and use its first "Maximum" entry.
  const s = (Array.isArray(sl) ? sl.find((x) => x?.type === "Maximum") : sl) as Record<string, unknown> | undefined;
  if (!s || !isNum(s.value) || s.value <= 0) return null;
  // "Recommended" is advisory, not a legal limit: the score should not count it as speeding.
  if (s.type !== undefined && s.type !== "Maximum") return null;
  if (s.unit === "kmph") return Math.round(s.value);
  if (s.unit === "mph") return Math.round(s.value * MPH_TO_KMH);
  return null;
}

/** Maps a TomTom response to one limit per input point, in order. Throws if the shape is unusable. */
export function mapResponse(json: unknown, n: number): Limit[] {
  const r = json as { projectedPoints?: unknown; route?: unknown } | null;
  const pp = r?.projectedPoints;
  const route = Array.isArray(r?.route) ? r.route : [];
  if (!Array.isArray(pp) || pp.length !== n) throw new Error("unexpected TomTom response shape");
  return pp.map((p, i) => {
    const props = (p as { properties?: Record<string, unknown> } | null)?.properties;
    const idx = props?.routeIndex;
    if (props?.snapResult !== "Matched" || !Number.isInteger(idx) || (idx as number) < 0 || (idx as number) >= route.length) {
      return { i, kmh: null };
    }
    return { i, kmh: toKmh((route[idx as number] as { properties?: { speedLimits?: unknown } })?.properties?.speedLimits) };
  });
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

export async function handle(req: Request, deps: Deps): Promise<Response> {
  if (req.method !== "POST") return json(405, { error: "method" });

  const uid = await deps.userId(req).catch(() => null);
  if (!uid) return json(401, { error: "auth" });

  let points: Point[];
  try {
    const text = await req.text();
    if (text.length > MAX_BODY_BYTES) throw new BadInput("body too large");
    points = parsePoints(JSON.parse(text));
  } catch (e) {
    return json(400, { error: "invalid", message: e instanceof BadInput ? e.message : "body must be JSON" });
  }

  // No key: say so before spending anyone's quota.
  if (!deps.apiKey) {
    console.error("speed-limits: TOMTOM_API_KEY is not set");
    return json(503, { error: "unavailable" });
  }

  let quota: Quota;
  try {
    quota = await deps.takeQuota(uid);
  } catch {
    console.error("speed-limits: quota check failed");
    return json(503, { error: "unavailable" });
  }
  if (quota !== "ok") return json(429, { error: "quota" });

  try {
    const { url, init } = buildTomTomRequest(points, deps.apiKey);
    const res = await deps.fetch(url, { ...init, signal: AbortSignal.timeout(20_000) });
    if (!res.ok) {
      await res.body?.cancel();
      // Status only: the body and URL are never logged (key, coordinates).
      console.error(`speed-limits: TomTom answered ${res.status}`);
      return json(503, { error: "unavailable" });
    }
    const limits = mapResponse(await res.json(), points.length);
    return json(200, { source: "tomtom", attribution: "© TomTom", limits });
  } catch (e) {
    console.error(`speed-limits: TomTom call failed (${e instanceof Error ? e.name : "error"})`);
    return json(503, { error: "unavailable" });
  }
}
