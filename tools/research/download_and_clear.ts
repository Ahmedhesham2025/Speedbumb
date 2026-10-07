// Owner tool: download the research recordings that phones uploaded, delete them from Storage (through the Storage
// API, never SQL) and free their room in the quota, so paused uploads resume. Nothing else ever deletes research files.
// Run it on the owner's PC only, never in CI. It reads SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY (the project's secret
// key or legacy service_role key) from the environment: never commit, paste or log the key. See supabase/README.md,
// "Research recordings".
//
//   deno run --allow-net --allow-env=SUPABASE_URL,SUPABASE_SERVICE_ROLE_KEY --allow-read=<folder> --allow-write=<folder>
//     tools/research/download_and_clear.ts --out <folder> [--dry-run] [--forget <user-id>]
//
// --dry-run lists what is waiting and changes nothing. --forget <user-id> handles a deletion request: that user's
// files are deleted from Storage without downloading them, their ledger rows go, and so do local copies in <folder>.

const NAME = /^[0-9a-f-]{36}\/rr_[0-9a-f]{8}_[0-9]{8}T[0-9]{6}_[0-9]{1,4}\.csv\.gz$/;
const USER = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const DAY_MS = 24 * 3600 * 1000; // a reservation still without a file after a day was never uploaded
const MB = 1024 * 1024;
type Row = { name: string; bytes: number; created_at: string };

function fail(message: string): never {
  console.error(message);
  console.error("usage: download_and_clear.ts --out <folder> [--dry-run] [--forget <user-id>]");
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
if (!out && !dryRun && !forget) fail("--out is required: a folder outside the repo (real drives are never committed)");

const base = (Deno.env.get("SUPABASE_URL") ?? "").replace(/\/+$/, "");
const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
if (!/^(https:\/\/[^/]+|http:\/\/(127\.0\.0\.1|localhost)(:\d+)?)$/.test(base) || !key) {
  fail("set SUPABASE_URL (https://<project>.supabase.co) and SUPABASE_SERVICE_ROLE_KEY");
}
// Legacy service_role keys are JWTs and also go in Authorization; new sb_secret_ keys only in apikey.
const auth: Record<string, string> = key.startsWith("eyJ") ? { apikey: key, Authorization: `Bearer ${key}` } : { apikey: key };

async function call(path: string, method = "GET", body?: unknown): Promise<Response> {
  const headers: Record<string, string> = { ...auth };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  return await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
}
async function ok<T>(res: Response, what: string): Promise<T> {
  const text = await res.text();
  if (!res.ok) throw new Error(`${what}: HTTP ${res.status} ${text.slice(0, 300)}`);
  return (text ? JSON.parse(text) : null) as T;
}
const fileUrl = (name: string) => `/storage/v1/object/research/${name.split("/").map(encodeURIComponent).join("/")}`;
const exists = (path: string) => Deno.stat(path).then(() => true, () => false);

async function ledger(filter: string): Promise<Row[]> {
  const rows: Row[] = [];
  for (let offset = 0;; offset += 1000) {
    const page = await ok<Row[]>(
      await call(`/rest/v1/research_uploads?select=name,bytes,created_at&${filter}&order=created_at&limit=1000&offset=${offset}`),
      "read the ledger",
    );
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

if (forget) {
  const rows = await ledger(`name=like.${forget}/*&cleared_at=is.null`);
  console.log(`${rows.length} file(s) of ${forget} still in Storage${dryRun ? " (dry run: nothing deleted)" : ""}`);
  if (!dryRun) {
    const names = rows.map((r) => r.name);
    await deleteFiles(names);
    await markCleared(names);
    await ok(await call(`/rest/v1/research_uploads?name=like.${forget}/*&cleared_at=not.is.null`, "DELETE"), "delete ledger rows");
    if (out) await Deno.remove(`${out}/${forget}`, { recursive: true }).catch(() => {});
    console.log(`done. Also delete any copies of ${forget}'s files kept outside ${out || "the download folder"}.`);
  }
  Deno.exit(0);
}

const rows = await ledger("cleared_at=is.null");
const reserved = rows.reduce((sum, r) => sum + r.bytes, 0);
console.log(`${rows.length} un-cleared file(s), ${(reserved / MB).toFixed(1)} MB reserved (uploads pause at research_cap_mb, default 900 MB)`);
if (dryRun) {
  for (const r of rows) console.log(`  ${r.name}  ${(r.bytes / MB).toFixed(2)} MB  reserved ${r.created_at}`);
  Deno.exit(0);
}

let errors = 0, saved = 0, cleared = 0;
for (let i = 0; i < rows.length; i += 50) {
  const done: string[] = []; // downloaded now: delete from Storage, then clear
  const gone: string[] = []; // no file in Storage: saved by an earlier run, or never uploaded
  for (const r of rows.slice(i, i + 50)) {
    if (!NAME.test(r.name)) {
      console.error(`skipped, unexpected name: ${r.name}`);
      errors++;
      continue;
    }
    const path = `${out}/${r.name}`;
    const res = await call(fileUrl(r.name));
    if (!res.ok) {
      const text = await res.text();
      if (res.status === 404 || /not.?found|NoSuchKey/i.test(text)) {
        // Younger reservations without a file may still be uploading: leave them for the next run.
        if (await exists(path) || Date.now() - Date.parse(r.created_at) > DAY_MS) gone.push(r.name);
      } else {
        console.error(`${r.name}: HTTP ${res.status} ${text.slice(0, 200)}`);
        errors++;
      }
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
