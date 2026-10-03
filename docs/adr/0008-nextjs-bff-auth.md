# ADR-0008 — Next.js web portals with a backend-for-frontend session

- **Status:** Accepted (2026-09-28)

## Context
Refresh tokens kept in browser storage are exposed to XSS. The admin portal handles
financial data.

## Decision
Next.js route handlers act as a BFF. They log in against identity-service, keep the
refresh token server-side (session id in an HTTP-only, Secure, SameSite=Strict cookie;
session data in Redis), and attach the short-lived access token to API calls proxied to
the gateway. The browser only ever holds the session cookie.

## Consequences
Tokens never reach JavaScript. The web apps need Redis and run as Node servers, not
static exports.
