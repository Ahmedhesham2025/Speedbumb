// Smoke test of download_and_clear.ts against a fake of the Supabase endpoints it uses (nothing real is touched):
//   deno test --allow-net=127.0.0.1 --allow-run --allow-read --allow-write supabase/tools/research/
const KEY = "eyJtest", A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const D = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
const f = (uid: string, n: number) => `${uid}/rr_0a1b2c3d_20261007T201500_${n}.csv.gz`;
const ago = (days: number) => new Date(Date.now() - days * 86400000).toISOString();
type Rec = Record<string, unknown>;
const db: Record<string, Rec[]> = {
  research_uploads: [
    { name: f(A, 1), device_id: A, bytes: 4, created_at: ago(0.1), cleared_at: null }, // uploaded
    { name: f(A, 2), device_id: A, bytes: 4, created_at: ago(8), cleared_at: null }, // never uploaded
    { name: f(A, 3), device_id: A, bytes: 4, created_at: ago(2), cleared_at: null }, // may still be uploading
    { name: f(B, 1), device_id: null, bytes: 4, created_at: ago(1), cleared_at: null }, // B ran forget_me
  ],
  research_forgotten: [{ uid: D }], // D ran forget_me too; its file is no longer in the ledger
};
const files = new Map([[f(A, 1), [0x1f, 0x8b, 0, 0]], [f(B, 1), [0x1f, 0x8b, 1, 1]], [f(D, 9), [0x1f, 0x8b, 2, 2]]]);
const log: string[] = [];
const match = (r: Rec, q: URLSearchParams) =>
  [...q].every(([k, v]) =>
    ["select", "order", "limit", "offset"].includes(k) ||
    (v === "is.null" ? r[k] === null : v === "not.is.null" ? r[k] !== null
      : v.startsWith("like.") ? String(r[k]).startsWith(v.slice(5, -1)) : String(r[k]) === v.slice(3))
  );

async function fake(req: Request): Promise<Response> {
  const url = new URL(req.url), p = url.pathname, q = url.searchParams, table = p.slice("/rest/v1/".length);
  log.push(`${req.method} ${decodeURIComponent(p)}`);
  if (req.headers.get("apikey") !== KEY || req.headers.get("authorization") !== `Bearer ${KEY}`) {
    return Response.json({ message: "Invalid API key" }, { status: 401 });
  }
  if (db[table] && req.method === "GET") {
    const off = Number(q.get("offset"));
    return Response.json(db[table].filter((r) => match(r, q)).slice(off, off + Number(q.get("limit"))));
  }
  if (db[table] && req.method === "DELETE") {
    db[table] = db[table].filter((r) => !match(r, q));
    return new Response(null, { status: 204 });
  }
  const body = req.method === "GET" ? {} : await req.json();
  if (p === "/rest/v1/rpc/research_mark_cleared") {
    const hit = db.research_uploads.filter((r) => body.names.includes(r.name) && r.cleared_at === null);
    for (const r of hit) r.cleared_at = "now";
    return Response.json(hit.length);
  }
  if (p === "/storage/v1/object/list/research") {
    const mine = [...files.keys()].filter((n) => n.startsWith(`${body.prefix}/`));
    return Response.json(mine.map((n) => ({ id: n, name: n.split("/")[1] })));
  }
  if (p === "/storage/v1/object/research" && req.method === "DELETE") {
    return Response.json(body.prefixes.filter((n: string) => files.delete(n)).map((name: string) => ({ name })));
  }
  const file = files.get(decodeURIComponent(p.slice("/storage/v1/object/research/".length)));
  return file ? new Response(new Uint8Array(file)) : Response.json({ error: "not_found" }, { status: 400 });
}

let port = 0;
async function tool(...args: string[]) {
  const script = new URL("./download_and_clear.ts", import.meta.url).href;
  const env = { SUPABASE_URL: `http://127.0.0.1:${port}`, SUPABASE_SERVICE_ROLE_KEY: KEY };
  const run = await new Deno.Command(Deno.execPath(), { args: ["run", "-A", script, ...args], env }).output();
  return { code: run.code, out: new TextDecoder().decode(run.stdout) + new TextDecoder().decode(run.stderr) };
}
const check = (ok: unknown, what: string) => { if (!ok) throw new Error(what); };
const missing = (path: string) => Deno.stat(path).then(() => false, () => true);

Deno.test("dry run, erasure of forgotten users, download + clear, --forget", async () => {
  const server = Deno.serve({ hostname: "127.0.0.1", port: 0, onListen() {} }, fake);
  port = server.addr.port;
  const out = await Deno.makeTempDir();
  await Deno.mkdir(`${out}/${B}`);
  await Deno.writeTextFile(`${out}/${B}/old.csv.gz`, "a copy downloaded before B ran forget_me");
  try {
    let r = await tool("--dry-run");
    check(r.code === 0 && r.out.includes(`would erase ${B}`) && r.out.includes(`would erase ${D}`), r.out);
    check(r.out.includes("3 un-cleared file(s)") && files.size === 3 && db.research_uploads.length === 4, "dry run");

    r = await tool("--out", out);
    check(r.code === 0, r.out);
    check(!log.some((l) => l.startsWith("GET /storage") && !l.includes(A)), "forgotten users' files are never read");
    check(files.size === 0 && db.research_forgotten.length === 0, "all files deleted, erasure queue empty");
    const ledger = db.research_uploads.map((x) => `${x.name}${x.cleared_at ? " cleared" : ""}`).sort().join();
    check(ledger === [`${f(A, 1)} cleared`, `${f(A, 2)} cleared`, f(A, 3)].join(),
      "A1 downloaded + cleared, A2 (a week old, no file) cleared, A3 kept, B's rows erased");
    check((await Deno.readFile(`${out}/${f(A, 1)}`)).length === 4 && await missing(`${out}/${B}`), "A1 saved, B's copy gone");

    r = await tool("--out", out, "--forget", A);
    check(r.code === 0 && db.research_uploads.length === 0 && await missing(`${out}/${A}`), `--forget: ${r.out}`);
  } finally {
    await server.shutdown();
    await Deno.remove(out, { recursive: true });
  }
});
