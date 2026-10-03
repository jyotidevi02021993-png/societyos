#!/usr/bin/env node
// End-to-end smoke test for the local SocietyOS stack (docker compose + web dev servers).
//
//   node scripts/smoke-test.mjs                 # everything
//   node scripts/smoke-test.mjs --only=gateway  # health | gateway | web
//
// Env (defaults match infra/docker/docker-compose.yml):
//   GATEWAY_URL=http://localhost:18080  ADMIN_WEB_URL=http://localhost:3000  RESIDENT_WEB_URL=http://localhost:3001
//   ADMIN_EMAIL=admin@societyos.in  ADMIN_PASSWORD=ChangeMe!2026  RESIDENT_PHONE=+919876543210  OTP_CODE=123456
//   SOCIETY_ID=<uuid>   (default: first society in society_db, read with docker exec)
//   TIMEOUT_MS=90000
//
// Read-only: it logs in and calls GET endpoints; it never creates or changes data.
// Exit code 1 when any check fails.

import { execSync } from "node:child_process";

const env = process.env;
const GATEWAY = env.GATEWAY_URL ?? "http://localhost:18080";
const ADMIN_WEB = env.ADMIN_WEB_URL ?? "http://localhost:3000";
const RESIDENT_WEB = env.RESIDENT_WEB_URL ?? "http://localhost:3001";
const ADMIN_EMAIL = env.ADMIN_EMAIL ?? "admin@societyos.in";
const ADMIN_PASSWORD = env.ADMIN_PASSWORD ?? "ChangeMe!2026";
const RESIDENT_PHONE = env.RESIDENT_PHONE ?? "+919876543210";
const OTP_CODE = env.OTP_CODE ?? "123456";
const TIMEOUT_MS = Number(env.TIMEOUT_MS ?? 90_000);
const ONLY = process.argv.find((a) => a.startsWith("--only="))?.slice(7);

// Container health ports: compose publishes each service's port + 10000.
const SERVICES = {
  "service-registry": 8761, "config-server": 8888, "api-gateway": 8080, "identity-service": 8081,
  "society-service": 8082, "security-service": 8083, "billing-service": 8084, "asset-service": 8085,
  "ticket-service": 8086, "community-service": 8087, "workflow-service": 8088, "notification-service": 8089,
  "realtime-service": 8090, "media-service": 8091, "audit-service": 8092, "dashboard-service": 8093,
  "utility-service": 8094, "vendor-service": 8095, "compliance-service": 8096, "marketplace-service": 8098,
  "inventory-service": 8099,
};

// GET endpoints a society admin should be able to read (gateway paths, without /api prefix).
// `roleScoped` endpoints belong to another actor (guard, technician, vendor); 403 is expected there.
const ADMIN_READS = [
  "identity/v1/me", "identity/v1/me/permissions",
  "society/v1/society", "society/v1/society/settings", "society/v1/towers", "society/v1/flats",
  "society/v1/locations", "society/v1/facilities", "society/v1/parking-slots", "society/v1/members",
  "society/v1/vehicles", "society/v1/domestic-staff", "society/v1/imports", "society/v1/directory",
  "security/v1/entries", "security/v1/entries/pending", "security/v1/deliveries", "security/v1/gatepasses",
  "security/v1/incidents", "security/v1/shifts", "security/v1/sos", "security/v1/staff-attendance",
  "security/v1/vehicle-movements",
  "billing/v1/billing/settings", "billing/v1/billing/flats", "billing/v1/bill-runs", "billing/v1/bills",
  "billing/v1/charge-heads", "billing/v1/charges", "billing/v1/payments", "billing/v1/receipts",
  "billing/v1/expenses", "billing/v1/budgets",
  `billing/v1/ledger/entries?from=${new Date().getFullYear()}-01-01&to=${new Date().toISOString().slice(0, 10)}`,
  "community/v1/notices", "community/v1/polls", "community/v1/events", "community/v1/bookings",
  "community/v1/facilities",
  "ticket/v1/categories", "ticket/v1/complaints", "ticket/v1/jobcards", "ticket/v1/breakdowns",
  "asset/v1/assets", "asset/v1/categories", "asset/v1/pm-plans", "asset/v1/pm-tasks", "asset/v1/amcs",
  "asset/v1/alert-recipients",
  "utility/v1/meters", "utility/v1/readings", "utility/v1/checklist-templates", "utility/v1/checklist-runs",
  "utility/v1/signoffs",
  "vendor/v1/vendors", "vendor/v1/agents", "vendor/v1/rfqs", "vendor/v1/purchase-orders", "vendor/v1/grns",
  "vendor/v1/invoices",
  "workflow/v1/definitions", "workflow/v1/instances", "workflow/v1/approvals/inbox", "workflow/v1/sla-policies",
  "workflow/v1/sla-timers/active",
  "notification/v1/templates", "notification/v1/me/notification-preferences",
  "audit/v1/audit",
];
const ROLE_SCOPED = new Set([
  "security/v1/shifts/mine", "asset/v1/pm-tasks/mine", "ticket/v1/my-tasks",
  "vendor/v1/vendor-portal/me", "vendor/v1/vendor-portal/rfqs",
]);
const ADMIN_ROLE_SCOPED_READS = [...ROLE_SCOPED];

const RESIDENT_READS = [
  "identity/v1/me", "society/v1/me/flats", "society/v1/directory", "community/v1/notices",
  "community/v1/polls", "ticket/v1/complaints", "billing/v1/bills", "notification/v1/me/notification-preferences",
];

// ---------------------------------------------------------------------------------------------

const results = [];
const color = (c, s) => (process.stdout.isTTY ? `\x1b[${c}m${s}\x1b[0m` : s);
function record(group, name, status, detail = "") {
  results.push({ group, name, status, detail });
  const tag = status === "PASS" ? color(32, "PASS") : status === "WARN" ? color(33, "WARN") : color(31, "FAIL");
  console.log(`${tag}  ${group.padEnd(9)} ${name}${detail ? `  ${color(90, detail)}` : ""}`);
}

async function http(url, { method = "GET", headers = {}, body, jar } = {}) {
  const h = { Accept: "application/json", ...headers };
  if (body !== undefined) h["Content-Type"] = "application/json";
  if (jar?.cookie) h.Cookie = jar.cookie;
  const started = Date.now();
  const res = await fetch(url, {
    method, headers: h, body: body === undefined ? undefined : JSON.stringify(body),
    redirect: "manual", signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  if (jar) {
    const set = res.headers.getSetCookie?.() ?? [];
    for (const c of set) {
      const [pair] = c.split(";");
      const [k] = pair.split("=");
      const rest = (jar.cookie ?? "").split("; ").filter((x) => x && !x.startsWith(`${k}=`));
      jar.cookie = [...rest, pair].join("; ");
    }
  }
  const text = await res.text();
  let json;
  try { json = text ? JSON.parse(text) : undefined; } catch { /* not json */ }
  return { status: res.status, json, text, ms: Date.now() - started };
}

const brief = (r) => {
  const p = r.json;
  const why = p?.code ?? p?.title ?? p?.error ?? r.text?.slice(0, 80) ?? "";
  return `${r.status} ${why}`.trim() + ` (${r.ms} ms)`;
};

function classify(group, name, r, { roleScoped = false } = {}) {
  if (r.status >= 200 && r.status < 300) return record(group, name, "PASS", `${r.status} (${r.ms} ms)`);
  if (roleScoped && r.status === 403) return record(group, name, "WARN", `403 expected for this actor (${r.ms} ms)`);
  record(group, name, "FAIL", brief(r));
}

async function safe(group, name, fn) {
  try {
    return await fn();
  } catch (e) {
    record(group, name, "FAIL", e.name === "TimeoutError" ? `timeout after ${TIMEOUT_MS} ms` : e.message);
    return undefined;
  }
}

function discoverSocietyId() {
  if (env.SOCIETY_ID) return env.SOCIETY_ID;
  try {
    return execSync(
      `docker exec societyos-postgres-1 psql -U postgres -d society_db -tAc "select id from society order by created_at limit 1"`,
      { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] },
    ).trim() || undefined;
  } catch {
    return undefined;
  }
}

// ---------------------------------------------------------------------------------------------

async function healthChecks() {
  await Promise.all(Object.entries(SERVICES).map(([svc, port]) =>
    safe("health", svc, async () => {
      const r = await http(`http://localhost:${port + 10000}/actuator/health`);
      const up = r.json?.status === "UP";
      record("health", svc, up ? "PASS" : "FAIL", up ? `UP (${r.ms} ms)` : brief(r));
    })));
}

async function gatewayChecks(societyId) {
  const g = "gateway";
  const login = await safe(g, "admin password login", () =>
    http(`${GATEWAY}/api/identity/v1/auth/login`, { method: "POST", body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD } }));
  if (!login) return;
  if (login.status !== 200 || !login.json?.accessToken) return record(g, "admin password login", "FAIL", brief(login));
  record(g, "admin password login", "PASS", `${login.status} (${login.ms} ms)`);

  let token = login.json.accessToken;
  if (!societyId) {
    record(g, "switch society", "FAIL", "no society found; onboard one or set SOCIETY_ID");
  } else {
    const sw = await safe(g, "switch society", () => http(`${GATEWAY}/api/identity/v1/auth/switch-society`, {
      method: "POST", headers: { Authorization: `Bearer ${token}` }, body: { societyId } }));
    if (sw?.status === 200 && sw.json?.accessToken) {
      token = sw.json.accessToken;
      record(g, "switch society", "PASS", `${societyId} (${sw.ms} ms)`);
    } else if (sw) {
      record(g, "switch society", "FAIL", brief(sw));
    }
  }

  const auth = { Authorization: `Bearer ${token}`, ...(societyId ? { "X-Society-Id": societyId } : {}) };
  // Sequential on purpose: the local stack is memory-bound and parallel bursts trip circuit breakers.
  for (const path of ADMIN_READS) {
    await safe(g, `GET ${path}`, async () => classify(g, `GET ${path}`, await http(`${GATEWAY}/api/${path}`, { headers: auth })));
  }
  for (const path of ADMIN_ROLE_SCOPED_READS) {
    await safe(g, `GET ${path}`, async () =>
      classify(g, `GET ${path}`, await http(`${GATEWAY}/api/${path}`, { headers: auth }), { roleScoped: true }));
  }

  // Resident OTP login through the gateway.
  const otp = await safe(g, "resident OTP request", () =>
    http(`${GATEWAY}/api/identity/v1/auth/otp/request`, { method: "POST", body: { phone: RESIDENT_PHONE } }));
  if (!otp) return;
  classify(g, "resident OTP request", otp);
  const ver = await safe(g, "resident OTP verify", () => http(`${GATEWAY}/api/identity/v1/auth/otp/verify`, {
    method: "POST", body: { phone: RESIDENT_PHONE, code: OTP_CODE, device: { platform: "ANDROID", name: "smoke-test" } } }));
  if (!ver) return;
  if (ver.status !== 200) return record(g, "resident OTP verify", "FAIL", brief(ver));
  record(g, "resident OTP verify", "PASS", `${ver.status} (${ver.ms} ms)`);
  const rAuth = { Authorization: `Bearer ${ver.json.accessToken}` };
  const rSociety = ver.json.societies?.[0]?.societyId;
  if (rSociety) rAuth["X-Society-Id"] = rSociety;
  for (const path of RESIDENT_READS) {
    const needsSociety = !path.startsWith("identity/");
    await safe(g, `resident GET ${path}`, async () => {
      const r = await http(`${GATEWAY}/api/${path}`, { headers: rAuth });
      // A resident with no flat yet has no society; 403 is then the correct answer.
      classify(g, `resident GET ${path}`, r, { roleScoped: needsSociety && !rSociety });
    });
  }
}

async function webChecks(societyId) {
  const w = "web";
  for (const [name, base] of [["admin-web", ADMIN_WEB], ["resident-web", RESIDENT_WEB]]) {
    await safe(w, `${name} /login page`, async () => {
      const r = await http(`${base}/login`, { headers: { Accept: "text/html" } });
      record(w, `${name} /login page`, r.status === 200 ? "PASS" : "FAIL", r.status === 200 ? `200 (${r.ms} ms)` : brief(r));
    });
  }

  // Admin BFF: login → switch society → proxy reads.
  const jar = {};
  const origin = { Origin: ADMIN_WEB };
  const login = await safe(w, "admin BFF login", () => http(`${ADMIN_WEB}/api/auth/login`, {
    method: "POST", headers: origin, body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD }, jar }));
  if (login) {
    classify(w, "admin BFF login", login);
    if (societyId && login.status === 200) {
      const sw = await safe(w, "admin BFF switch society", () => http(`${ADMIN_WEB}/api/auth/switch-society`, {
        method: "POST", headers: origin, body: { societyId }, jar }));
      if (sw) classify(w, "admin BFF switch society", sw);
    }
    if (login.status === 200) {
      for (const path of ["society/v1/towers", "society/v1/flats", "society/v1/members?includeEnded=false",
        "society/v1/parking-slots", "society/v1/facilities", "society/v1/locations", "society/v1/imports",
        "society/v1/society", "society/v1/society/settings"]) {
        await safe(w, `admin proxy ${path}`, async () =>
          classify(w, `admin proxy ${path}`, await http(`${ADMIN_WEB}/api/proxy/${path}`, { headers: origin, jar })));
      }
      const home = await safe(w, "admin portal home", () => http(`${ADMIN_WEB}/`, { headers: { Accept: "text/html" }, jar }));
      if (home) record(w, "admin portal home", home.status < 400 ? "PASS" : "FAIL", `${home.status} (${home.ms} ms)`);
    }
  }

  // Resident BFF: OTP → session → proxy reads.
  const rjar = {};
  const rorigin = { Origin: RESIDENT_WEB };
  const req = await safe(w, "resident BFF OTP request", () => http(`${RESIDENT_WEB}/api/auth/otp/request`, {
    method: "POST", headers: rorigin, body: { phone: RESIDENT_PHONE }, jar: rjar }));
  if (!req) return;
  classify(w, "resident BFF OTP request", req);
  const ver = await safe(w, "resident BFF OTP verify", () => http(`${RESIDENT_WEB}/api/auth/otp/verify`, {
    method: "POST", headers: rorigin, body: { phone: RESIDENT_PHONE, code: OTP_CODE }, jar: rjar }));
  if (!ver) return;
  classify(w, "resident BFF OTP verify", ver);
  if (ver.status !== 200) return;
  const hasSociety = (ver.json?.session?.societies ?? []).length > 0;
  for (const path of ["identity/v1/me", "society/v1/me/flats", "society/v1/directory"]) {
    await safe(w, `resident proxy ${path}`, async () => classify(w, `resident proxy ${path}`,
      await http(`${RESIDENT_WEB}/api/proxy/${path}`, { headers: rorigin, jar: rjar }),
      { roleScoped: !hasSociety && !path.startsWith("identity/") }));
  }
}

// ---------------------------------------------------------------------------------------------

const societyId = discoverSocietyId();
console.log(`SocietyOS smoke test  gateway=${GATEWAY}  society=${societyId ?? "(none)"}\n`);
const t0 = Date.now();
if (!ONLY || ONLY === "health") await healthChecks();
if (!ONLY || ONLY === "gateway") await gatewayChecks(societyId);
if (!ONLY || ONLY === "web") await webChecks(societyId);

const count = (s) => results.filter((r) => r.status === s).length;
console.log(`\n${count("PASS")} passed, ${count("WARN")} warnings, ${count("FAIL")} failed in ${((Date.now() - t0) / 1000).toFixed(0)} s`);
const failed = results.filter((r) => r.status === "FAIL");
if (failed.length) {
  console.log("\nFailures:");
  for (const f of failed) console.log(`  ${f.group.padEnd(9)} ${f.name}  ${f.detail}`);
}
process.exit(failed.length ? 1 : 0);
