import { describe, expect, it } from "vitest";
import {
  accessExpiry,
  applyAuthResult,
  decodeJwtClaims,
  isValidSessionId,
  needsRefresh,
  newSessionId,
  permissionsFresh,
  sessionFromAuthResult,
  toSessionInfo,
} from "./session";
import { MemorySessionStore } from "./store";
import { SOCIETY_A, SOCIETY_B, authResult } from "./test-utils";

const NOW = Date.parse("2026-09-29T10:00:00Z");

describe("session helpers", () => {
  it("creates unguessable, well-formed session ids", () => {
    const a = newSessionId();
    const b = newSessionId();
    expect(a).not.toBe(b);
    expect(isValidSessionId(a)).toBe(true);
    expect(isValidSessionId("../../etc")).toBe(false);
    expect(isValidSessionId(undefined)).toBe(false);
  });

  it("builds a session from a login result, keeping the refresh token server-side only", () => {
    const s = sessionFromAuthResult(authResult({ now: NOW, refreshToken: "r-123" }), NOW);
    expect(s.refreshToken).toBe("r-123");
    expect(s.activeSocietyId).toBe(SOCIETY_A);
    expect(s.roles).toEqual(["ESTATE_MANAGER"]);
    expect(s.amr).toEqual(["pwd", "mfa"]);
    expect(s.accessTokenExpiresAt).toBe(NOW + 900_000);

    const info = toSessionInfo(s);
    const json = JSON.stringify(info);
    expect(json).not.toContain("r-123");
    expect(json).not.toContain(s.accessToken);
    expect(info).not.toHaveProperty("refreshToken");
    expect(info).not.toHaveProperty("accessToken");
  });

  it("refuses a login result without a refresh token", () => {
    expect(() => sessionFromAuthResult(authResult({ now: NOW, refreshToken: null }), NOW)).toThrow(/refresh token/);
  });

  it("keeps the old refresh token when switch-society returns none, and drops cached permissions", () => {
    const s = { ...sessionFromAuthResult(authResult({ now: NOW, refreshToken: "r-1" }), NOW), permissions: { societyId: SOCIETY_A, list: ["society:view"], fetchedAt: NOW } };
    const next = applyAuthResult(s, authResult({ now: NOW, refreshToken: null, society: SOCIETY_B, roles: ["RWA_COMMITTEE"] }), NOW);
    expect(next.refreshToken).toBe("r-1");
    expect(next.activeSocietyId).toBe(SOCIETY_B);
    expect(next.roles).toEqual(["RWA_COMMITTEE"]);
    expect(next.permissions).toBeUndefined();
  });

  it("rotates the refresh token on refresh", () => {
    const s = sessionFromAuthResult(authResult({ now: NOW, refreshToken: "r-1" }), NOW);
    expect(applyAuthResult(s, authResult({ now: NOW, refreshToken: "r-2" }), NOW).refreshToken).toBe("r-2");
  });

  it("decides when to refresh with a skew", () => {
    const s = sessionFromAuthResult(authResult({ now: NOW, ttlSeconds: 900 }), NOW);
    expect(needsRefresh(s, 60, NOW)).toBe(false);
    expect(needsRefresh(s, 60, NOW + 839_000)).toBe(false);
    expect(needsRefresh(s, 60, NOW + 841_000)).toBe(true);
  });

  it("treats permissions as fresh only for the active society and within the TTL", () => {
    const base = sessionFromAuthResult(authResult({ now: NOW }), NOW);
    expect(permissionsFresh(base, 300, NOW)).toBe(false);
    const withPerms = { ...base, permissions: { societyId: SOCIETY_A, list: [], fetchedAt: NOW } };
    expect(permissionsFresh(withPerms, 300, NOW + 299_000)).toBe(true);
    expect(permissionsFresh(withPerms, 300, NOW + 301_000)).toBe(false);
    expect(permissionsFresh({ ...withPerms, activeSocietyId: SOCIETY_B }, 300, NOW)).toBe(false);
  });

  it("reads expiry from the JWT, or from ISO / epoch fallbacks", () => {
    expect(accessExpiry({ accessToken: "x.e30.y", accessTokenExpiresAt: "2026-09-29T10:15:00Z" }, NOW)).toBe(Date.parse("2026-09-29T10:15:00Z"));
    expect(accessExpiry({ accessToken: "x.e30.y", accessTokenExpiresAt: 1790000000 as unknown as string }, NOW)).toBe(1790000000_000);
    expect(decodeJwtClaims("garbage")).toEqual({});
  });
});

describe("MemorySessionStore", () => {
  it("expires entries and returns copies", async () => {
    let now = NOW;
    const store = new MemorySessionStore(() => now);
    const s = sessionFromAuthResult(authResult({ now: NOW }), NOW);
    await store.set("id", s, 10);
    const got = await store.get("id");
    got!.name = "changed";
    expect((await store.get("id"))!.name).toBe("Asha Rao");
    now += 11_000;
    expect(await store.get("id")).toBeNull();
  });
});
