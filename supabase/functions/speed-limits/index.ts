// speed-limits: road speed limits for a stretch of a drive, from TomTom Snap to Roads.
// Phones never hold the TomTom key: it is the Supabase secret TOMTOM_API_KEY, read only here.
// Nothing TomTom returns is stored or logged (TomTom T&C 11.4); only call counters are kept.
import { createClient } from "jsr:@supabase/supabase-js@2";
import { handle } from "./lib.ts";

// Provided by the Edge runtime. The service role is needed only for take_speed_limit_quota().
const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false, autoRefreshToken: false },
});

Deno.serve((req) =>
  handle(req, {
    apiKey: Deno.env.get("TOMTOM_API_KEY"),
    // The gateway already checked the JWT (verify_jwt = true); this also rejects the bare anon key, which has no user.
    async userId(r) {
      const token = r.headers.get("Authorization")?.replace(/^Bearer\s+/i, "");
      if (!token) return null;
      const { data, error } = await admin.auth.getUser(token);
      return error ? null : data.user?.id ?? null;
    },
    async takeQuota(uid) {
      const { data, error } = await admin.rpc("take_speed_limit_quota", { uid });
      if (error || (data !== "ok" && data !== "user" && data !== "project")) throw new Error("quota rpc failed");
      return data;
    },
    fetch,
  })
);
