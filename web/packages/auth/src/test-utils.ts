import type { AuthResult } from "@societyos/api-client";

/** Unsigned JWT with the given claims (the BFF only reads claims; the gateway verifies). */
export function fakeJwt(claims: Record<string, unknown>): string {
  const enc = (o: unknown) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${enc({ alg: "RS256", typ: "JWT" })}.${enc(claims)}.sig`;
}

export const SOCIETY_A = "0191aa10-0000-7000-8000-00000000000a";
export const SOCIETY_B = "0191bb20-0000-7000-8000-00000000000b";
export const USER = "0191ab22-0000-7000-8000-000000000001";

export function authResult(opts: { now: number; ttlSeconds?: number; refreshToken?: string | null; society?: string; roles?: string[]; tag?: string }): AuthResult {
  const exp = Math.floor(opts.now / 1000) + (opts.ttlSeconds ?? 900);
  const society = opts.society ?? SOCIETY_A;
  return {
    accessToken: fakeJwt({ sub: USER, sid: society, sids: [SOCIETY_A, SOCIETY_B], roles: opts.roles ?? ["ESTATE_MANAGER"], amr: ["pwd", "mfa"], exp, tag: opts.tag ?? "t" }),
    accessTokenExpiresAt: new Date(exp * 1000).toISOString(),
    ...(opts.refreshToken === null ? {} : { refreshToken: opts.refreshToken ?? "refresh-1" }),
    userId: USER,
    name: "Asha Rao",
    newUser: false,
    platformAdmin: false,
    mfaSetupRequired: false,
    activeSocietyId: society,
    societies: [
      { societyId: SOCIETY_A, roles: ["ESTATE_MANAGER"] },
      { societyId: SOCIETY_B, roles: ["RWA_COMMITTEE"] },
    ],
  };
}
