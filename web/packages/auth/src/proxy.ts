import type { Uuid } from "@societyos/api-client";

/**
 * Maps `/api/proxy/<service>/v<N>/<rest>` segments to the gateway path
 * `/api/<service>/v<N>/<rest>`. Returns null for anything the browser must not reach
 * through the proxy: path tricks, service-internal endpoints, and identity's auth
 * endpoints (login/refresh/switch go through the dedicated BFF routes so tokens stay here).
 */
export function resolveUpstreamPath(segments: readonly string[]): string | null {
  if (segments.length < 3) return null;
  for (const s of segments) {
    if (!s || s === "." || s === ".." || s.includes("/") || s.includes("\\") || s.includes("\0")) return null;
  }
  const [service, version] = segments as [string, string, ...string[]];
  if (!/^[a-z][a-z0-9-]{1,40}$/.test(service)) return null;
  if (!/^v\d{1,2}$/.test(version)) return null;
  if (segments[2] === "internal") return null;
  if (service === "identity" && segments[2] === "auth") return null;
  if (service === "identity" && segments[2] === "me" && segments[3] === "mfa" && segments[4] === "confirm") return null;
  return `/api/${segments.map((s) => encodeURIComponent(s)).join("/")}`;
}

/** Request headers copied from the browser. Everything else (Cookie, Authorization...) is dropped. */
export const FORWARDED_REQUEST_HEADERS = [
  "accept",
  "accept-language",
  "content-type",
  "idempotency-key",
  "if-match",
  "if-none-match",
  "x-request-id",
  "traceparent",
] as const;

/** Response headers copied back to the browser. Set-Cookie from upstream never passes. */
export const FORWARDED_RESPONSE_HEADERS = [
  "content-type",
  "content-disposition",
  "cache-control",
  "etag",
  "last-modified",
  "location",
  "retry-after",
  "x-request-id",
  "x-trace-id",
] as const;

/**
 * Browser may send `X-Sos-Society: none` to call an endpoint without an active society
 * (platform onboarding). It can never pick a society the session does not have.
 */
export const SOCIETY_SCOPE_HEADER = "x-sos-society";

export interface ProxyAuth {
  accessToken: string;
  activeSocietyId?: Uuid;
}

export function buildUpstreamHeaders(incoming: Headers, auth: ProxyAuth): Headers {
  const out = new Headers();
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const v = incoming.get(name);
    if (v !== null) out.set(name, v);
  }
  out.set("authorization", `Bearer ${auth.accessToken}`);
  const scope = incoming.get(SOCIETY_SCOPE_HEADER)?.trim().toLowerCase();
  if (auth.activeSocietyId && scope !== "none") out.set("x-society-id", auth.activeSocietyId);
  if (!out.has("accept")) out.set("accept", "application/json, application/problem+json");
  return out;
}

export function buildResponseHeaders(upstream: Headers): Headers {
  const out = new Headers();
  for (const name of FORWARDED_RESPONSE_HEADERS) {
    const v = upstream.get(name);
    if (v !== null) out.set(name, v);
  }
  if (!out.has("cache-control")) out.set("cache-control", "no-store");
  return out;
}

export function upstreamUrl(apiBaseUrl: string, path: string, search: string): string {
  return `${apiBaseUrl.replace(/\/+$/, "")}${path}${search}`;
}
