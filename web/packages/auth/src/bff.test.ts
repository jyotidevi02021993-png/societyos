import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@societyos/api-client";
import { createBff } from "./bff";
import { loadAuthConfig } from "./config";
import type { IdentityClient } from "./identity";
import { buildUpstreamHeaders, resolveUpstreamPath } from "./proxy";
import { sessionFromAuthResult } from "./session";
import { MemorySessionStore } from "./store";
import { SOCIETY_A, SOCIETY_B, authResult } from "./test-utils";

const ORIGIN = "http://localhost:3000";

describe("proxy path mapping", () => {
  it("maps /api/proxy/<service>/v1/... to the gateway's /api/<service>/v1/...", () => {
    expect(resolveUpstreamPath(["society", "v1", "towers"])).toBe("/api/society/v1/towers");
    expect(resolveUpstreamPath(["society", "v1", "parking-slots", "abc", "assignment"])).toBe("/api/society/v1/parking-slots/abc/assignment");
    expect(resolveUpstreamPath(["identity", "v1", "me", "permissions"])).toBe("/api/identity/v1/me/permissions");
  });

  it("refuses internal endpoints, identity auth endpoints and path tricks", () => {
    expect(resolveUpstreamPath(["society", "v1", "internal", "x"])).toBeNull();
    expect(resolveUpstreamPath(["identity", "v1", "auth", "token", "refresh"])).toBeNull();
    expect(resolveUpstreamPath(["identity", "v1", "me", "mfa", "confirm"])).toBeNull();
    expect(resolveUpstreamPath(["society", "v1", "..", "..", "admin"])).toBeNull();
    expect(resolveUpstreamPath(["Society", "v1", "towers"])).toBeNull();
    expect(resolveUpstreamPath(["society", "towers"])).toBeNull();
  });
});

describe("proxy auth headers", () => {
  it("attaches the bearer token and active society, and drops browser credentials", () => {
    const incoming = new Headers({
      cookie: "sos_admin_session=abc",
      authorization: "Bearer from-browser",
      "x-society-id": SOCIETY_B,
      "content-type": "application/json",
      "idempotency-key": "k1",
    });
    const h = buildUpstreamHeaders(incoming, { accessToken: "server-token", activeSocietyId: SOCIETY_A });
    expect(h.get("authorization")).toBe("Bearer server-token");
    expect(h.get("x-society-id")).toBe(SOCIETY_A);
    expect(h.get("cookie")).toBeNull();
    expect(h.get("content-type")).toBe("application/json");
    expect(h.get("idempotency-key")).toBe("k1");
  });

  it("omits X-Society-Id when the call is explicitly society-less or there is no active society", () => {
    const none = buildUpstreamHeaders(new Headers({ "x-sos-society": "none" }), { accessToken: "t", activeSocietyId: SOCIETY_A });
    expect(none.get("x-society-id")).toBeNull();
    const noActive = buildUpstreamHeaders(new Headers(), { accessToken: "t" });
    expect(noActive.get("x-society-id")).toBeNull();
  });
});

function fakeIdentity(now: () => number): IdentityClient & { refresh: ReturnType<typeof vi.fn> } {
  let n = 1;
  return {
    passwordLogin: vi.fn(async () => authResult({ now: now(), refreshToken: "r-login" })),
    requestOtp: vi.fn(async () => ({ expiresInSeconds: 300 })),
    verifyOtp: vi.fn(async () => authResult({ now: now(), refreshToken: "r-otp" })),
    refresh: vi.fn(async () => {
      n += 1;
      return authResult({ now: now(), refreshToken: `r-${n}`, tag: `refreshed-${n}` });
    }),
    switchSociety: vi.fn(async (_t: string, sid: string) => authResult({ now: now(), refreshToken: null, society: sid })),
    logout: vi.fn(async () => undefined),
    permissions: vi.fn(async () => ({ societyId: SOCIETY_A, permissions: ["society:view", "society:manage"] })),
    confirmMfa: vi.fn(async () => undefined),
  };
}

describe("BFF", () => {
  let now: number;
  let store: MemorySessionStore;
  let identity: ReturnType<typeof fakeIdentity>;
  let upstream: ReturnType<typeof vi.fn>;
  const config = loadAuthConfig("admin", { API_BASE_URL: "http://gw:8000", NODE_ENV: "test" });

  const make = () => createBff({ config, store, identity, fetch: upstream as unknown as typeof fetch, now: () => now });

  beforeEach(() => {
    now = Date.parse("2026-09-29T10:00:00Z");
    store = new MemorySessionStore(() => now);
    identity = fakeIdentity(() => now);
    upstream = vi.fn(async () => new Response(JSON.stringify([{ id: "t1" }]), { status: 200, headers: { "content-type": "application/json" } }));
  });

  async function seed(id = "s".repeat(43)) {
    await store.set(id, sessionFromAuthResult(authResult({ now, refreshToken: "r-1" }), now), 3600);
    return id;
  }
  const cookie = (id: string) => `${config.cookieName}=${id}`;
  const ctx = (...path: string[]) => ({ params: Promise.resolve({ path }) });

  it("login sets an HttpOnly SameSite=Strict cookie and never returns tokens", async () => {
    const bff = make();
    const res = await bff.routes.passwordLogin(
      new Request(`${ORIGIN}/api/auth/login`, {
        method: "POST",
        headers: { "content-type": "application/json", origin: ORIGIN, host: "localhost:3000" },
        body: JSON.stringify({ email: "a@b.in", password: "pw", totp: "123456" }),
      }),
    );
    expect(res.status).toBe(200);
    const setCookie = res.headers.get("set-cookie")!;
    expect(setCookie).toMatch(/HttpOnly/);
    expect(setCookie).toMatch(/SameSite=Strict/);
    const text = await res.text();
    expect(text).not.toContain("r-login");
    expect(text).not.toContain("accessToken");
    expect(JSON.parse(text).session.permissions).toEqual(["society:manage", "society:view"]);
    expect(identity.passwordLogin).toHaveBeenCalledWith("a@b.in", "pw", "123456");
  });

  it("passes MFA_REQUIRED through as problem+json", async () => {
    identity.passwordLogin = vi.fn(async () => {
      throw new ApiError(401, { code: "MFA_REQUIRED", detail: "Enter the code from your authenticator app" });
    });
    const res = await make().routes.passwordLogin(
      new Request(`${ORIGIN}/api/auth/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ email: "a@b.in", password: "pw" }) }),
    );
    expect(res.status).toBe(401);
    expect(res.headers.get("content-type")).toBe("application/problem+json");
    expect(await res.json()).toMatchObject({ code: "MFA_REQUIRED", detail: "Enter the code from your authenticator app" });
  });

  it("rejects cross-site state-changing requests", async () => {
    const id = await seed();
    const res = await make().routes.proxy(
      new Request(`${ORIGIN}/api/proxy/society/v1/towers`, { method: "POST", headers: { cookie: cookie(id), origin: "https://evil.example", host: "localhost:3000" }, body: "{}" }),
      ctx("society", "v1", "towers"),
    );
    expect(res.status).toBe(403);
    expect(upstream).not.toHaveBeenCalled();
  });

  it("proxies with the session's access token and society, keeping the query string", async () => {
    const id = await seed();
    const res = await make().routes.proxy(new Request(`${ORIGIN}/api/proxy/society/v1/flats?towerId=t1`, { headers: { cookie: cookie(id) } }), ctx("society", "v1", "flats"));
    expect(res.status).toBe(200);
    const [url, init] = upstream.mock.calls[0]!;
    expect(url).toBe("http://gw:8000/api/society/v1/flats?towerId=t1");
    const h = init.headers as Headers;
    expect(h.get("authorization")).toMatch(/^Bearer ey/);
    expect(h.get("x-society-id")).toBe(SOCIETY_A);
  });

  it("returns 401 and clears the cookie without a session", async () => {
    const res = await make().routes.proxy(new Request(`${ORIGIN}/api/proxy/society/v1/towers`), ctx("society", "v1", "towers"));
    expect(res.status).toBe(401);
    expect(res.headers.get("set-cookie")).toMatch(/Max-Age=0/);
  });

  it("refreshes an expiring token once, even for parallel requests", async () => {
    const id = await seed();
    now += 15 * 60_000; // token expired
    const bff = make();
    const reqs = Array.from({ length: 3 }, () =>
      bff.routes.proxy(new Request(`${ORIGIN}/api/proxy/society/v1/towers`, { headers: { cookie: cookie(id) } }), ctx("society", "v1", "towers")),
    );
    const results = await Promise.all(reqs);
    expect(results.map((r) => r.status)).toEqual([200, 200, 200]);
    expect(identity.refresh).toHaveBeenCalledTimes(1);
    expect(identity.refresh).toHaveBeenCalledWith("r-1");
    expect((await store.get(id))!.refreshToken).toBe("r-2");
  });

  it("retries once with a refreshed token when the gateway answers 401", async () => {
    const id = await seed();
    upstream.mockResolvedValueOnce(new Response(JSON.stringify({ code: "TOKEN_REVOKED" }), { status: 401 }));
    const res = await make().routes.proxy(new Request(`${ORIGIN}/api/proxy/society/v1/towers`, { headers: { cookie: cookie(id) } }), ctx("society", "v1", "towers"));
    expect(res.status).toBe(200);
    expect(identity.refresh).toHaveBeenCalledTimes(1);
    expect(upstream).toHaveBeenCalledTimes(2);
    const first = (upstream.mock.calls[0]![1].headers as Headers).get("authorization");
    const second = (upstream.mock.calls[1]![1].headers as Headers).get("authorization");
    expect(second).not.toBe(first);
  });

  it("ends the session when the refresh token is rejected", async () => {
    const id = await seed();
    now += 15 * 60_000;
    identity.refresh.mockRejectedValueOnce(new ApiError(401, { code: "REFRESH_TOKEN_REUSED" }));
    const res = await make().routes.proxy(new Request(`${ORIGIN}/api/proxy/society/v1/towers`, { headers: { cookie: cookie(id) } }), ctx("society", "v1", "towers"));
    expect(res.status).toBe(401);
    expect(await store.get(id)).toBeNull();
  });

  it("switches society only to one the user belongs to", async () => {
    const id = await seed();
    const bff = make();
    const bad = await bff.routes.switchSociety(
      new Request(`${ORIGIN}/api/auth/switch-society`, { method: "POST", headers: { cookie: cookie(id) }, body: JSON.stringify({ societyId: "0191cc30-0000-7000-8000-00000000000c" }) }),
    );
    expect(bad.status).toBe(403);
    const ok = await bff.routes.switchSociety(
      new Request(`${ORIGIN}/api/auth/switch-society`, { method: "POST", headers: { cookie: cookie(id) }, body: JSON.stringify({ societyId: SOCIETY_B }) }),
    );
    expect(ok.status).toBe(200);
    expect((await ok.json()).activeSocietyId).toBe(SOCIETY_B);
    const stored = (await store.get(id))!;
    expect(stored.activeSocietyId).toBe(SOCIETY_B);
    expect(stored.refreshToken).toBe("r-1");
  });

  it("logout revokes at identity-service and deletes the session", async () => {
    const id = await seed();
    const res = await make().routes.logout(new Request(`${ORIGIN}/api/auth/logout`, { method: "POST", headers: { cookie: cookie(id) } }));
    expect(res.status).toBe(204);
    expect(identity.logout).toHaveBeenCalledTimes(1);
    expect(await store.get(id)).toBeNull();
  });
});
