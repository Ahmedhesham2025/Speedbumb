// Owner tool, run on the owner's PC at least weekly (never in CI). First it ERASES, unread, the research files of users
// who ran "Delete my shared data" (forget_me) or are named with --forget (deletion on request): their whole Storage
// folder, ledger rows and local copies. Then it downloads the other uploaded files, deletes them from Storage (Storage
// API, never SQL) and frees their room, so paused uploads resume. Needs SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY
// (secret or legacy service_role key) in the environment; never commit, paste or log the key. Usage: supabase/README.md.

const NAME = /^[0-9a-f-]{36}\/rr_[0-9a-f]{8}_[0-9]{8}T[0-9]{6}_[0-9]{1,4}\.csv\.gz$/;
const USER = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const STALE_MS = 7 * 24 * 3600 * 1000; // a reservation still without a file after a week was never uploaded
const MB = 1024 * 1024;
type Row = { name: string; bytes: number; created_at: string };

function fail(message: string): never {
  console.error(`${message}\nusage: download_and_clear.ts --out <folder> [--dry-run] [--forget <user-id>]`);
  Deno.exit(2);
}
let out = "", dryRun = false, forget = "";
for (let i = 0; i < Deno.args.length; i++) {
  const arg = Deno.args[i];
  if (arg === "--dry-run") dryRun = true;
  else if (arg === "--out") out = Deno.args[++i] ?? fail("--out needs a folder");
  else if (arg === "--forget") forget = (Deno.args[++i] ?? fail("--forget needs a user id")).toLowerCase();
  else fail(`unknown argument: ${arg}`);
}
if (forget && !USER.test(forget)) fail("--forget needs a user id like 0b8c6f9e-1d2a-4c3b-9e8f-7a6b5c4d3e2f");
if (!out && !dryRun) fail("--out is required: a folder outside the repo (real drives are never committed)");
const base = (Deno.env.get("SUPABASE_URL") ?? "").replace(/\/+$/, "");
const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
if (!/^(https:\/\/[^/]+|http:\/\/(127\.0\.0\.1|localhost)(:\d+)?)$/.test(base) || !key) {
  fail("set SUPABASE_URL (https://<project>.supabase.co) and SUPABASE_SERVICE_ROLE_KEY");
}
// Legacy service_role keys are JWTs and also go in Authorization; new sb_secret_ keys only in apikey.
const auth: Record<string, string> = key.startsWith("eyJ") ? { apikey: key, Authorization: `Bearer ${key}` } : { apikey: key };

async function call(path: string, method = "GET", body?: unknown): Promise<Response> {
  const headers = body === undefined ? auth : { ...auth, "Content-Type": "application/json" };
  return await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
}
async function ok<T>(res: Response, what: string): Promise<T> {
  const text = await res.text();
  if (!res.ok) throw new Error(`${what}: HTTP ${res.status} ${text.slice(0, 300)}`);
  return (text ? JSON.parse(text) : null) as T;
}
async function select<T>(table: string, query: string): Promise<T[]> { // all pages
  const rows: T[] = [];
  for (let offset = 0;; offset += 1000) {
    const page = await ok<T[]>(await call(`/rest/v1/${table}?${query}&limit=1000&offset=${offset}`), `read ${table}`);
    rows.push(...page);
    if (page.length < 1000) return rows;
  }
}
async function deleteFiles(names: string[]) {
  for (let i = 0; i < names.length; i += 100) {
    await ok(await call("/storage/v1/object/research", "DELETE", { prefixes: names.slice(i, i + 100) }), "delete files");
  }
}
async function markCleared(names: string[]): Promise<number> {
  if (names.length === 0) return 0;
  return await ok<number>(await call("/rest/v1/rpc/research_mark_cleared", "POST", { names }), "mark cleared");
}

// Erasure lists the user's Storage folder (so files the ledger no longer tracks go too) and deletes it unread, then the
// user's ledger rows, the forget_me marker and any local copies.
async function erase(uid: string) {
  const names: string[] = [];
  for (let offset = 0;; offset += 1000) {
    const page = await ok<{ name: string; id: string | null }[]>(
      await call("/storage/v1/object/list/research", "POST", { prefix: uid, limit: 1000, offset }), "list files");
    names.push(...page.filter((o) => o.id).map((o) => `${uid}/${o.name}`));
    if (page.length < 1000) break;
  }
  await deleteFiles(names);
  await ok(await call(`/rest/v1/research_uploads?name=like.${uid}/*`, "DELETE"), "delete ledger rows");
  await ok(await call(`/rest/v1/research_forgotten?uid=eq.${uid}`, "DELETE"), "delete forget_me marker");
  await Deno.remove(`${out}/${uid}`, { recursive: true }).catch((e) => { if (!(e instanceof Deno.errors.NotFound)) throw e; });
  console.log(`erased ${uid}: ${names.length} file(s) deleted from Storage unread, ledger rows and local copies removed`);
}

// To erase: --forget, or everyone queued by forget_me plus any ledger rows that lost their device.
const erasing = new Set(forget ? [forget] : [
  ...(await select<{ uid: string }>("research_forgotten", "select=uid&order=uid")).map((r) => r.uid),
  ...(await select<{ name: string }>("research_uploads", "select=name&device_id=is.null&order=name"))
    .map((r) => r.name.split("/")[0]),
].filter((uid) => USER.test(uid)));
for (const uid of erasing) {
  if (dryRun) console.log(`would erase ${uid} (Delete my shared data or a deletion request), nothing downloaded`);
  else await erase(uid);
}
if (forget) Deno.exit(0);

const rows = await select<Row>(
  "research_uploads", "select=name,bytes,created_at&cleared_at=is.null&device_id=not.is.null&order=created_at,name");
const reserved = rows.reduce((sum, r) => sum + r.bytes, 0);
console.log(`${rows.length} un-cleared file(s), ${(reserved / MB).toFixed(1)} MB reserved (uploads pause at research_cap_mb, default 900 MB)`);
if (dryRun) {
  for (const r of rows) console.log(`  ${r.name}  ${(r.bytes / MB).toFixed(2)} MB  reserved ${r.created_at}`);
  console.log("Downloads count toward the free plan's 5 GB/month egress: about 4 full clears a month.");
  Deno.exit(0);
}
let errors = 0, saved = 0, cleared = 0;
for (let i = 0; i < rows.length; i += 50) {
  const done: string[] = []; // downloaded now: delete from Storage, then clear
  const gone: string[] = []; // no file in Storage: saved by an earlier run, or never uploaded
  for (const r of rows.slice(i, i + 50)) {
    if (!NAME.test(r.name)) { errors++; console.error(`skipped, unexpected name: ${r.name}`); continue; }
    const path = `${out}/${r.name}`;
    const res = await call(`/storage/v1/object/research/${r.name}`);
    if (!res.ok) {
      const text = await res.text();
      if (res.status === 404 || /not.?found|NoSuchKey/i.test(text)) {
        // Younger reservations without a file may still be uploading: leave them for a later run.
        const local = await Deno.stat(path).then(() => true, () => false);
        if (local || Date.now() - Date.parse(r.created_at) > STALE_MS) gone.push(r.name);
      } else { errors++; console.error(`${r.name}: HTTP ${res.status} ${text.slice(0, 200)}`); }
      continue;
    }
    const data = new Uint8Array(await res.arrayBuffer());
    if (data.length > r.bytes) console.warn(`warning: ${r.name} has ${data.length} bytes, only ${r.bytes} were reserved`);
    if (data[0] !== 0x1f || data[1] !== 0x8b) console.warn(`warning: ${r.name} is not gzip`);
    await Deno.mkdir(path.slice(0, path.lastIndexOf("/")), { recursive: true });
    await Deno.writeFile(`${path}.part`, data);
    await Deno.rename(`${path}.part`, path);
    done.push(r.name);
    saved += data.length;
  }
  await deleteFiles(done);
  cleared += await markCleared([...done, ...gone]);
}
console.log(`saved ${(saved / MB).toFixed(1)} MB to ${out}, cleared ${cleared} reservation(s)${errors ? `, ${errors} error(s)` : ""}`);
if (errors) Deno.exit(1);
