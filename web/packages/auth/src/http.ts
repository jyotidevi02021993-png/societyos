import type { ProblemDetail } from "@societyos/api-client";
import type { AuthConfig } from "./config";

export function parseCookies(header: string | null | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  if (!header) return out;
  for (const part of header.split(";")) {
    const i = part.indexOf("=");
    if (i < 0) continue;
    const k = part.slice(0, i).trim();
    const v = part.slice(i + 1).trim();
    if (!k || k in out) continue;
    try {
      out[k] = decodeURIComponent(v);
    } catch {
      out[k] = v;
    }
  }
  return out;
}

/** HTTP-only, SameSite=Strict session cookie (Secure outside local dev). */
export function sessionCookie(config: AuthConfig, value: string, maxAgeSeconds: number = config.sessionTtlSeconds): string {
  const parts = [
    `${config.cookieName}=${value}`,
    "Path=/",
    "HttpOnly",
    "SameSite=Strict",
    `Max-Age=${Math.max(0, Math.floor(maxAgeSeconds))}`,
  ];
  if (maxAgeSeconds <= 0) parts.push("Expires=Thu, 01 Jan 1970 00:00:00 GMT");
  if (config.secureCookie) parts.push("Secure");
  return parts.join("; ");
}

export function clearSessionCookie(config: AuthConfig): string {
  return sessionCookie(config, "", 0);
}

const NO_STORE = { "Cache-Control": "no-store" };

export function json(data: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", ...NO_STORE, ...headers },
  });
}

export function problem(status: number, code: string, detail: string, extra: Partial<ProblemDetail> = {}, headers: Record<string, string> = {}): Response {
  const body: ProblemDetail = { type: `https://docs.societyos.in/errors/${code}`, title: code, status, code, detail, ...extra };
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/problem+json", ...NO_STORE, ...headers },
  });
}

/**
 * CSRF defence in depth on top of SameSite=Strict: state-changing requests must come from
 * our own origin. Browsers always send Origin on cross-origin POSTs and Sec-Fetch-Site on
 * modern engines.
 */
export function isSameOrigin(req: Request): boolean {
  const method = req.method.toUpperCase();
  if (method === "GET" || method === "HEAD" || method === "OPTIONS") return true;
  const site = req.headers.get("sec-fetch-site");
  if (site && site !== "same-origin" && site !== "none") return false;
  const origin = req.headers.get("origin");
  if (!origin) return true;
  let originHost: string;
  try {
    originHost = new URL(origin).host;
  } catch {
    return false;
  }
  const host = req.headers.get("x-forwarded-host")?.split(",")[0]?.trim() || req.headers.get("host") || new URL(req.url).host;
  return originHost === host;
}
