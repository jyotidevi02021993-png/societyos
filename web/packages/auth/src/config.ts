/** Server-only configuration for the BFF, read from the environment. */
export interface AuthConfig {
  /** Which portal this is; decides cookie name, session lifetime and login method. */
  app: "admin" | "resident";
  /** Gateway base URL, e.g. http://localhost:8000 (no trailing slash). */
  apiBaseUrl: string;
  cookieName: string;
  /** Session lifetime in seconds; sliding (extended on every authenticated request). */
  sessionTtlSeconds: number;
  /** Add the Secure attribute to the session cookie. */
  secureCookie: boolean;
  /** redis://host:port/db — where sessions (and the refresh token) live. */
  redisUrl: string;
  /** "redis" (required), "memory" (dev/tests) or "auto" (Redis, fall back to memory outside production). */
  sessionStore: "redis" | "memory" | "auto";
  /** Refresh the access token when it expires within this many seconds. */
  refreshSkewSeconds: number;
  /** How long cached permissions stay fresh before /v1/me/permissions is asked again. */
  permissionsTtlSeconds: number;
  /** admin-web: force TOTP enrolment before the portal opens. */
  requireMfa: boolean;
}

const HOURS = 3600;
const DAYS = 24 * HOURS;

function bool(v: string | undefined, dflt: boolean): boolean {
  if (v === undefined || v === "") return dflt;
  return ["1", "true", "yes", "on"].includes(v.toLowerCase());
}

function int(v: string | undefined, dflt: number): number {
  const n = v ? Number.parseInt(v, 10) : Number.NaN;
  return Number.isFinite(n) && n > 0 ? n : dflt;
}

export function loadAuthConfig(app: AuthConfig["app"], env: Record<string, string | undefined> = process.env): AuthConfig {
  const production = env.NODE_ENV === "production";
  const secureCookie = bool(env.SESSION_COOKIE_SECURE, production);
  const baseName = app === "admin" ? "sos_admin_session" : "sos_resident_session";
  const store = (env.SESSION_STORE ?? "auto").toLowerCase();
  return {
    app,
    apiBaseUrl: (env.API_BASE_URL ?? "http://localhost:8000").replace(/\/+$/, ""),
    // __Host- pins the cookie to this exact origin (needs Secure, Path=/ and no Domain).
    cookieName: env.SESSION_COOKIE_NAME ?? (secureCookie ? `__Host-${baseName}` : baseName),
    // Admin: 12 h sliding (05-security §1). Resident: 30 days, the refresh token's own lifetime.
    sessionTtlSeconds: int(env.SESSION_TTL_SECONDS, app === "admin" ? 12 * HOURS : 30 * DAYS),
    secureCookie,
    redisUrl: env.REDIS_URL ?? "redis://localhost:6379",
    sessionStore: store === "redis" || store === "memory" ? store : "auto",
    refreshSkewSeconds: int(env.ACCESS_TOKEN_REFRESH_SKEW_SECONDS, 60),
    permissionsTtlSeconds: int(env.PERMISSIONS_TTL_SECONDS, 300),
    requireMfa: app === "admin" && bool(env.ADMIN_REQUIRE_MFA, true),
  };
}
