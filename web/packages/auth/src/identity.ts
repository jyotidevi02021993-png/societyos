import { ApiError, toApiError, type AuthResult, type MePermissions, type OtpRequested, type Uuid } from "@societyos/api-client";

/**
 * Server-to-server calls from the BFF to identity-service through the gateway
 * (`/api/identity/v1/...` → identity `/v1/...`). Throws ApiError with the problem body.
 */
export interface IdentityClient {
  passwordLogin(email: string, password: string, totp?: string): Promise<AuthResult>;
  requestOtp(phone: string, lang?: "en" | "hi"): Promise<OtpRequested>;
  verifyOtp(phone: string, code: string, deviceName: string): Promise<AuthResult>;
  refresh(refreshToken: string): Promise<AuthResult>;
  switchSociety(accessToken: string, societyId: Uuid): Promise<AuthResult>;
  logout(accessToken: string): Promise<void>;
  permissions(accessToken: string, societyId?: Uuid): Promise<MePermissions>;
  confirmMfa(accessToken: string, code: string): Promise<void>;
}

export function createIdentityClient(apiBaseUrl: string, doFetch: typeof fetch = fetch): IdentityClient {
  const base = `${apiBaseUrl.replace(/\/+$/, "")}/api/identity/v1`;

  async function call<R>(path: string, init: { method?: string; body?: unknown; token?: string; societyId?: Uuid }): Promise<R> {
    const headers: Record<string, string> = { Accept: "application/json, application/problem+json" };
    if (init.body !== undefined) headers["Content-Type"] = "application/json";
    if (init.token) headers.Authorization = `Bearer ${init.token}`;
    if (init.societyId) headers["X-Society-Id"] = init.societyId;
    let res: Response;
    try {
      res = await doFetch(`${base}${path}`, {
        method: init.method ?? "POST",
        headers,
        body: init.body === undefined ? undefined : JSON.stringify(init.body),
        cache: "no-store",
        signal: AbortSignal.timeout(20_000),
      });
    } catch (err) {
      throw upstreamUnavailable(err);
    }
    if (!res.ok) throw await toApiError(res);
    if (res.status === 204) return undefined as R;
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as R;
  }

  return {
    passwordLogin: (email, password, totp) =>
      call<AuthResult>("/auth/login", { body: { email, password, ...(totp ? { totp } : {}) } }),
    requestOtp: (phone, lang) => call<OtpRequested>("/auth/otp/request", { body: { phone, ...(lang ? { lang } : {}) } }),
    verifyOtp: (phone, code, deviceName) =>
      call<AuthResult>("/auth/otp/verify", { body: { phone, code, device: { platform: "WEB", name: deviceName } } }),
    refresh: (refreshToken) => call<AuthResult>("/auth/token/refresh", { body: { refreshToken } }),
    switchSociety: (token, societyId) => call<AuthResult>("/auth/switch-society", { token, body: { societyId } }),
    logout: (token) => call<void>("/auth/logout", { token }),
    permissions: (token, societyId) => call<MePermissions>("/me/permissions", { method: "GET", token, societyId }),
    confirmMfa: (token, code) => call<void>("/me/mfa/confirm", { token, body: { code } }),
  };
}


export function upstreamUnavailable(err: unknown): ApiError {
  return new ApiError(503, {
    status: 503,
    code: "UPSTREAM_UNAVAILABLE",
    title: "UPSTREAM_UNAVAILABLE",
    detail: `The SocietyOS API is not reachable right now (${(err as Error)?.message ?? "network error"}).`,
  });
}
