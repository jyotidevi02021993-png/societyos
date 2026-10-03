import { randomBytes } from "node:crypto";
import type { AuthResult, SessionInfo, SessionSociety, Uuid } from "@societyos/api-client";

/**
 * What the BFF keeps per browser session, server-side only (Redis). The browser holds only
 * the opaque session id in an HTTP-only cookie.
 */
export interface SessionData {
  userId: Uuid;
  name?: string;
  platformAdmin: boolean;
  mfaSetupRequired: boolean;
  accessToken: string;
  /** epoch ms */
  accessTokenExpiresAt: number;
  refreshToken: string;
  /** The society the user chose. Kept across refreshes (refresh re-picks one server-side). */
  activeSocietyId?: Uuid;
  societies: SessionSociety[];
  /** Roles from the current access token (active society). */
  roles: string[];
  /** Authentication methods of the original login (pwd, mfa, otp). */
  amr: string[];
  permissions?: { societyId?: Uuid; list: string[]; fetchedAt: number };
  createdAt: number;
  updatedAt: number;
}

export function newSessionId(): string {
  return randomBytes(32).toString("base64url");
}

/** Session ids are 43 url-safe base64 chars; anything else is not ours. */
export function isValidSessionId(id: string | undefined | null): id is string {
  return typeof id === "string" && /^[A-Za-z0-9_-]{43}$/.test(id);
}

export interface JwtClaims {
  sub?: string;
  sid?: string;
  sids?: string[];
  roles?: string[];
  amr?: string[];
  exp?: number;
  [k: string]: unknown;
}

/**
 * Reads (does not verify) the claims of an access token we just received from
 * identity-service over a server-to-server call. Only used for display/expiry hints; the
 * gateway and services verify the signature on every request.
 */
export function decodeJwtClaims(token: string): JwtClaims {
  const part = token.split(".")[1];
  if (!part) return {};
  try {
    return JSON.parse(Buffer.from(part, "base64url").toString("utf8")) as JwtClaims;
  } catch {
    return {};
  }
}

/** accessTokenExpiresAt may arrive as an ISO string or epoch seconds; the JWT `exp` wins. */
export function accessExpiry(result: Pick<AuthResult, "accessToken" | "accessTokenExpiresAt">, now: number): number {
  const exp = decodeJwtClaims(result.accessToken).exp;
  if (typeof exp === "number") return exp * 1000;
  const raw: unknown = result.accessTokenExpiresAt;
  if (typeof raw === "number") return raw > 1e12 ? raw : raw * 1000;
  if (typeof raw === "string") {
    const asNum = Number(raw);
    if (Number.isFinite(asNum)) return asNum > 1e12 ? asNum : asNum * 1000;
    const t = Date.parse(raw);
    if (Number.isFinite(t)) return t;
  }
  return now + 5 * 60_000;
}

/** A fresh session from a login (password or OTP) result. */
export function sessionFromAuthResult(result: AuthResult, now: number = Date.now()): SessionData {
  if (!result.refreshToken) throw new Error("Login response has no refresh token");
  const claims = decodeJwtClaims(result.accessToken);
  return {
    userId: result.userId,
    name: result.name,
    platformAdmin: result.platformAdmin,
    mfaSetupRequired: result.mfaSetupRequired,
    accessToken: result.accessToken,
    accessTokenExpiresAt: accessExpiry(result, now),
    refreshToken: result.refreshToken,
    activeSocietyId: result.activeSocietyId,
    societies: normaliseSocieties(result.societies),
    roles: claims.roles ?? [],
    amr: claims.amr ?? [],
    createdAt: now,
    updatedAt: now,
  };
}

/**
 * Applies a refresh or switch-society result to an existing session. The refresh token is
 * replaced only when a new one is returned (switch-society returns none).
 */
export function applyAuthResult(session: SessionData, result: AuthResult, now: number = Date.now()): SessionData {
  const claims = decodeJwtClaims(result.accessToken);
  const societyChanged = (result.activeSocietyId ?? undefined) !== session.activeSocietyId;
  return {
    ...session,
    name: result.name ?? session.name,
    platformAdmin: result.platformAdmin,
    accessToken: result.accessToken,
    accessTokenExpiresAt: accessExpiry(result, now),
    refreshToken: result.refreshToken ?? session.refreshToken,
    activeSocietyId: result.activeSocietyId ?? undefined,
    societies: normaliseSocieties(result.societies ?? session.societies),
    roles: claims.roles ?? session.roles,
    permissions: societyChanged ? undefined : session.permissions,
    updatedAt: now,
  };
}

function normaliseSocieties(list: { societyId: Uuid; roles?: string[] | null }[] | undefined): SessionSociety[] {
  return (list ?? []).map((s) => ({ societyId: s.societyId, roles: [...(s.roles ?? [])].sort() }));
}

export function needsRefresh(session: SessionData, skewSeconds: number, now: number = Date.now()): boolean {
  return session.accessTokenExpiresAt - skewSeconds * 1000 <= now;
}

export function permissionsFresh(session: SessionData, ttlSeconds: number, now: number = Date.now()): boolean {
  const p = session.permissions;
  return !!p && p.societyId === session.activeSocietyId && now - p.fetchedAt < ttlSeconds * 1000;
}

/** The browser-safe view of a session: never includes a token. */
export function toSessionInfo(session: SessionData): SessionInfo {
  return {
    userId: session.userId,
    name: session.name,
    platformAdmin: session.platformAdmin,
    mfaSetupRequired: session.mfaSetupRequired,
    activeSocietyId: session.activeSocietyId,
    societies: session.societies,
    roles: session.roles,
    permissions: session.permissions?.societyId === session.activeSocietyId ? (session.permissions?.list ?? []) : [],
  };
}
