import { ApiError, toE164, type SessionInfo, type Uuid } from "@societyos/api-client";
import { loadAuthConfig, type AuthConfig } from "./config";
import { clearSessionCookie, isSameOrigin, json, parseCookies, problem, sessionCookie } from "./http";
import { createIdentityClient, upstreamUnavailable, type IdentityClient } from "./identity";
import {
  buildResponseHeaders,
  buildUpstreamHeaders,
  resolveUpstreamPath,
  upstreamUrl,
} from "./proxy";
import {
  applyAuthResult,
  isValidSessionId,
  needsRefresh,
  newSessionId,
  permissionsFresh,
  sessionFromAuthResult,
  toSessionInfo,
  type SessionData,
} from "./session";
import { getSessionStore, type SessionStore } from "./store";

export interface BffDeps {
  config: AuthConfig;
  store: SessionStore | (() => Promise<SessionStore>);
  identity: IdentityClient;
  /** Upstream fetch for the proxy (tests pass a fake). */
  fetch?: typeof fetch;
  now?: () => number;
  /** Device name registered with identity-service on OTP login. */
  deviceName?: string;
}

type RouteCtx = { params: Promise<{ path?: string[] }> };

const MAX_PROXY_BODY = 15 * 1024 * 1024;

// In-flight refreshes per session, shared across route bundles (see store.ts). Two parallel
// requests must never both spend the same refresh token: identity-service treats a reused
// token as theft and revokes the whole device session.
const g = globalThis as unknown as { __sosRefresh?: Map<string, Promise<SessionData | null>> };

export type Bff = ReturnType<typeof createBff>;

export function createBff(deps: BffDeps) {
  const { config, identity } = deps;
  const now = deps.now ?? Date.now;
  const upstreamFetch = deps.fetch ?? ((...a: Parameters<typeof fetch>) => fetch(...a));
  const inflight = (g.__sosRefresh ??= new Map());
  const storeP = (): Promise<SessionStore> =>
    typeof deps.store === "function" ? deps.store() : Promise.resolve(deps.store);

  async function store(): Promise<SessionStore> {
    try {
      return await storeP();
    } catch (err) {
      throw new ApiError(503, { status: 503, code: "SESSION_STORE_UNAVAILABLE", detail: (err as Error).message });
    }
  }

  function sessionIdFrom(req: Request): string | null {
    const id = parseCookies(req.headers.get("cookie"))[config.cookieName];
    return isValidSessionId(id) ? id : null;
  }

  /**
   * Loads a session and makes sure its access token is valid for at least the refresh skew.
   * Returns null when there is no session or the refresh token was rejected (session deleted).
   * @param staleToken force a refresh if the stored token is still this one (upstream said 401)
   */
  async function freshSession(id: string, staleToken?: string): Promise<SessionData | null> {
    const s = await (await store()).get(id);
    if (!s) return null;
    const mustRefresh = staleToken ? s.accessToken === staleToken : needsRefresh(s, config.refreshSkewSeconds, now());
    if (!mustRefresh) return s;

    const key = `${config.app}:${id}`;
    const running = inflight.get(key);
    if (running) return running;
    const p = refreshNow(id, staleToken).finally(() => inflight.delete(key));
    inflight.set(key, p);
    return p;
  }

  async function refreshNow(id: string, staleToken?: string): Promise<SessionData | null> {
    const st = await store();
    const latest = await st.get(id);
    if (!latest) return null;
    const still = staleToken ? latest.accessToken === staleToken : needsRefresh(latest, config.refreshSkewSeconds, now());
    if (!still) return latest;

    const desiredSociety = latest.activeSocietyId;
    let next: SessionData;
    try {
      next = applyAuthResult(latest, await identity.refresh(latest.refreshToken), now());
    } catch (err) {
      if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
        await st.delete(id);
        return null;
      }
      throw err;
    }
    // Refresh picks the user's remembered society; a platform admin working in a society they
    // are not a member of must be switched back explicitly.
    if (desiredSociety && next.activeSocietyId !== desiredSociety) {
      try {
        next = applyAuthResult(next, await identity.switchSociety(next.accessToken, desiredSociety), now());
      } catch {
        /* keep the society identity-service chose */
      }
    }
    await st.set(id, next, config.sessionTtlSeconds);
    return next;
  }

  async function withPermissions(id: string, s: SessionData): Promise<SessionData> {
    if (permissionsFresh(s, config.permissionsTtlSeconds, now())) return s;
    if (!s.activeSocietyId) {
      const next = { ...s, permissions: { societyId: undefined, list: [], fetchedAt: now() } };
      await (await store()).set(id, next, config.sessionTtlSeconds);
      return next;
    }
    try {
      const p = await identity.permissions(s.accessToken, s.activeSocietyId);
      const next: SessionData = {
        ...s,
        permissions: { societyId: s.activeSocietyId, list: [...(p.permissions ?? [])].sort(), fetchedAt: now() },
      };
      await (await store()).set(id, next, config.sessionTtlSeconds);
      return next;
    } catch (err) {
      console.warn(`[bff] could not load permissions: ${(err as Error).message}`);
      return s; // not cached, retried next time
    }
  }

  /** For Server Components and route handlers: the browser-safe session, or null. */
  async function sessionInfo(id: string | null | undefined): Promise<SessionInfo | null> {
    if (!isValidSessionId(id)) return null;
    const s = await freshSession(id);
    if (!s) return null;
    return toSessionInfo(await withPermissions(id, s));
  }

  function fromError(err: unknown): Response {
    if (err instanceof ApiError) {
      return problem(err.status, err.code, err.message, {
        ...(err.fieldErrors.length ? { errors: err.fieldErrors } : {}),
        ...(err.problem.traceId ? { traceId: err.problem.traceId } : {}),
      });
    }
    console.error("[bff] unexpected error", err);
    return problem(500, "BFF_ERROR", "Something went wrong on the portal server.");
  }

  async function readJson(req: Request): Promise<Record<string, unknown>> {
    try {
      const body: unknown = await req.json();
      return body && typeof body === "object" ? (body as Record<string, unknown>) : {};
    } catch {
      return {};
    }
  }

  const str = (v: unknown) => (typeof v === "string" ? v.trim() : "");

  async function startSession(req: Request, result: Awaited<ReturnType<IdentityClient["passwordLogin"]>>): Promise<Response> {
    const st = await store();
    const old = sessionIdFrom(req);
    if (old) await st.delete(old); // no session fixation: always a new id on login
    const id = newSessionId();
    let s = sessionFromAuthResult(result, now());
    await st.set(id, s, config.sessionTtlSeconds);
    s = await withPermissions(id, s);
    return json({ session: toSessionInfo(s), newUser: result.newUser }, 200, { "Set-Cookie": sessionCookie(config, id) });
  }

  function csrfGuard(req: Request): Response | null {
    return isSameOrigin(req) ? null : problem(403, "CSRF_REJECTED", "Cross-site request refused.");
  }

  function unauthenticated(): Response {
    return problem(401, "SESSION_EXPIRED", "Your session has expired. Please sign in again.", {}, {
      "Set-Cookie": clearSessionCookie(config),
    });
  }

  // ------------------------------------------------------------------ route handlers

  /** POST /api/auth/login {email, password, totp?} (admin). 401 MFA_REQUIRED → ask for the TOTP. */
  async function passwordLogin(req: Request): Promise<Response> {
    const bad = csrfGuard(req);
    if (bad) return bad;
    const body = await readJson(req);
    const email = str(body.email);
    const password = typeof body.password === "string" ? body.password : "";
    const totp = str(body.totp) || undefined;
    if (!email || !password) return problem(400, "VALIDATION_FAILED", "Enter your e-mail and password.");
    if (totp && !/^\d{6}$/.test(totp)) return problem(400, "MFA_INVALID", "The authenticator code has 6 digits.");
    try {
      return await startSession(req, await identity.passwordLogin(email, password, totp));
    } catch (err) {
      return fromError(err);
    }
  }

  /** POST /api/auth/otp/request {phone} (resident). */
  async function otpRequest(req: Request): Promise<Response> {
    const bad = csrfGuard(req);
    if (bad) return bad;
    const body = await readJson(req);
    const phone = toE164(str(body.phone));
    if (!phone) return problem(400, "INVALID_PHONE", "Enter a valid mobile number.");
    const lang = body.lang === "hi" ? "hi" : undefined;
    try {
      const r = await identity.requestOtp(phone, lang);
      return json({ phone, expiresInSeconds: r.expiresInSeconds });
    } catch (err) {
      return fromError(err);
    }
  }

  /** POST /api/auth/otp/verify {phone, code} (resident). */
  async function otpVerify(req: Request): Promise<Response> {
    const bad = csrfGuard(req);
    if (bad) return bad;
    const body = await readJson(req);
    const phone = toE164(str(body.phone));
    const code = str(body.code);
    if (!phone) return problem(400, "INVALID_PHONE", "Enter a valid mobile number.");
    if (!/^\d{4,8}$/.test(code)) return problem(400, "INVALID_OTP", "Enter the code from the SMS.");
    try {
      return await startSession(req, await identity.verifyOtp(phone, code, deps.deviceName ?? "Web portal"));
    } catch (err) {
      return fromError(err);
    }
  }

  /** POST /api/auth/logout: revokes the device session at identity-service and forgets ours. */
  async function logout(req: Request): Promise<Response> {
    const bad = csrfGuard(req);
    if (bad) return bad;
    const id = sessionIdFrom(req);
    if (id) {
      try {
        const st = await store();
        const s = await st.get(id);
        if (s) {
          await identity.logout(s.accessToken).catch((err: unknown) => {
            console.warn(`[bff] identity logout failed: ${(err as Error).message}`);
          });
          await st.delete(id);
        }
      } catch (err) {
        console.warn(`[bff] logout: ${(err as Error).message}`);
      }
    }
    return new Response(null, { status: 204, headers: { "Set-Cookie": clearSessionCookie(config), "Cache-Control": "no-store" } });
  }

  /** GET /api/session → SessionInfo (no tokens); slides the cookie. */
  async function session(req: Request): Promise<Response> {
    const id = sessionIdFrom(req);
    if (!id) return unauthenticated();
    try {
      const info = await sessionInfo(id);
      if (!info) return unauthenticated();
      await (await store()).touch(id, config.sessionTtlSeconds);
      return json(info, 200, { "Set-Cookie": sessionCookie(config, id) });
    } catch (err) {
      return fromError(err);
    }
  }

  /** POST /api/auth/switch-society {societyId} */
  async function switchSociety(req: Request): Promise<Response> {
    const bad = csrfGuard(req);
    if (bad) return bad;
    const id = sessionIdFrom(req);
    if (!id) return unauthenticated();
    const societyId = str((await readJson(req)).societyId) as Uuid;
    if (!/^[0-9a-f-]{36}$/i.test(societyId)) return problem(400, "INVALID_SOCIETY_ID", "Choose a society.");
    try {
      const s = await freshSession(id);
      if (!s) return unauthenticated();
      if (!s.platformAdmin && !s.societies.some((x) => x.societyId === societyId)) {
        return problem(403, "SOCIETY_NOT_ALLOWED", "You are not a member of this society.");
      }
      let next = applyAuthResult(s, await identity.switchSociety(s.accessToken, societyId), now());
      next = { ...next, activeSocietyId: next.activeSocietyId ?? societyId, permissions: undefined };
      await (await store()).set(id, next, config.sessionTtlSeconds);
      next = await withPermissions(id, next);
      return json(toSessionInfo(next));
    } catch (err) {
      return fromError(err);
    }
  }

  /** POST /api/auth/mfa/confirm {code}: finishes TOTP enrolment and clears the session flag. */
  async function mfaConfirm(req: Request): Promise<Response> {
    const bad = csrfGuard(req);
    if (bad) return bad;
    const id = sessionIdFrom(req);
    if (!id) return unauthenticated();
    const code = str((await readJson(req)).code);
    if (!/^\d{6}$/.test(code)) return problem(400, "MFA_INVALID", "The authenticator code has 6 digits.");
    try {
      const s = await freshSession(id);
      if (!s) return unauthenticated();
      await identity.confirmMfa(s.accessToken, code);
      const next = { ...s, mfaSetupRequired: false, updatedAt: now() };
      await (await store()).set(id, next, config.sessionTtlSeconds);
      return json(toSessionInfo(next));
    } catch (err) {
      return fromError(err);
    }
  }

  /**
   * /api/proxy/[...path]: every browser API call. Attaches the access token and X-Society-Id,
   * refreshes the token server-side (and retries once if the gateway still says 401).
   */
  async function proxy(req: Request, ctx: RouteCtx): Promise<Response> {
    const bad = csrfGuard(req);
    if (bad) return bad;
    const { path = [] } = await ctx.params;
    const upstreamPath = resolveUpstreamPath(path);
    if (!upstreamPath) return problem(404, "NOT_FOUND", "Unknown API path.");
    const id = sessionIdFrom(req);
    if (!id) return unauthenticated();

    const method = req.method.toUpperCase();
    let body: ArrayBuffer | undefined;
    if (method !== "GET" && method !== "HEAD") {
      const len = Number(req.headers.get("content-length") ?? "0");
      if (len > MAX_PROXY_BODY) return problem(413, "PAYLOAD_TOO_LARGE", "The file is too large (max 15 MB).");
      body = await req.arrayBuffer();
      if (body.byteLength > MAX_PROXY_BODY) return problem(413, "PAYLOAD_TOO_LARGE", "The file is too large (max 15 MB).");
      if (body.byteLength === 0) body = undefined;
    }
    const url = upstreamUrl(config.apiBaseUrl, upstreamPath, new URL(req.url).search);

    try {
      let s = await freshSession(id);
      if (!s) return unauthenticated();
      const send = (auth: SessionData) =>
        upstreamFetch(url, {
          method,
          headers: buildUpstreamHeaders(req.headers, auth),
          body,
          redirect: "manual",
          cache: "no-store",
          signal: AbortSignal.timeout(30_000),
        }).catch((err: unknown) => {
          throw upstreamUnavailable(err);
        });

      let res = await send(s);
      if (res.status === 401) {
        // Token revoked or expired early: refresh once and retry.
        const retried = await freshSession(id, s.accessToken);
        if (!retried) return unauthenticated();
        if (retried.accessToken !== s.accessToken) {
          s = retried;
          res = await send(s);
        }
      }
      void (await store()).touch(id, config.sessionTtlSeconds).catch(() => undefined);
      return new Response(method === "HEAD" || res.status === 204 || res.status === 304 ? null : res.body, {
        status: res.status,
        headers: buildResponseHeaders(res.headers),
      });
    } catch (err) {
      return fromError(err);
    }
  }

  return {
    config,
    sessionIdFrom,
    freshSession,
    sessionInfo,
    routes: { passwordLogin, otpRequest, otpVerify, logout, session, switchSociety, mfaConfirm, proxy },
  };
}

/** Wires a BFF from environment variables (API_BASE_URL, REDIS_URL, SESSION_*). */
export function createBffFromEnv(app: AuthConfig["app"], opts: { deviceName?: string } = {}): Bff {
  const config = loadAuthConfig(app);
  return createBff({
    config,
    store: () => getSessionStore(config),
    identity: createIdentityClient(config.apiBaseUrl),
    deviceName: opts.deviceName,
  });
}
