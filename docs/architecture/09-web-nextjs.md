# 09 — Web portals (Next.js)

Two apps share one design system and one generated API client.

| App | Audience | Rendering | Domain |
|---|---|---|---|
| `admin-web` | Super Admin, RWA, Estate/Facility Manager, Accounts, department heads | Desktop-first; SSR for shell, client components for grids | `admin.societyos.in` |
| `resident-web` | Owners (incl. non-resident), tenants | Responsive; SSR for dashboard and finance | `my.societyos.in` |

## 1. Stack

| Concern | Choice |
|---|---|
| Framework | Next.js 15 (App Router), React 19, TypeScript strict |
| Styling / UI | Tailwind CSS + shadcn/ui (Radix), shared in `packages/ui`, design tokens in `packages/ui/tokens` |
| Data fetching | TanStack Query on the client; server components call the API with the session token |
| API client | Generated from `contracts/openapi/*.yaml` with openapi-typescript + openapi-fetch (`packages/api-client`) |
| Forms | react-hook-form + zod (schemas generated from OpenAPI where possible) |
| Tables | TanStack Table (virtualised, server-side paging, column config saved per user) |
| Charts | Recharts / ECharts for MIS |
| Realtime | @stomp/stompjs for dashboard alerts |
| i18n | next-intl (en, hi) |
| Auth | Backend-for-frontend pattern: Next.js route handlers hold the refresh token in an HTTP-only, Secure, SameSite=Strict cookie (session id → Redis); the browser never sees the refresh token |
| Tests | Vitest + Testing Library, Playwright E2E, axe accessibility checks (WCAG 2.1 AA) |
| Package manager | pnpm workspaces + Turborepo |

## 2. Structure

```
apps/admin-web/
  app/
    (auth)/login, mfa
    (portal)/
      layout.tsx            ← sidebar with 9 sections, society switcher, portfolio view for FM users
      dashboard/            ← morning view (5 questions), KPIs, AI Estate Health Summary
      society/              towers-flats, residents, parking, facilities, imports
      security/             gate-activity, visitors, staff-vehicles, guards, incidents
      assets/               registry, qr-labels, pm-planner, breakdowns, job-cards, checklists
      utilities/            wtp, stp, water, pumps-tanks, dg, transformer, lifts, fire, hvac, energy      (Phase 2)
      estate-services/      housekeeping, garbage, fogging, gardening, civil-plumbing, clubhouse, gym     (Phase 2)
      community/            complaints, notices, polls, events, bookings
      finance/              bill-runs, collections, receipts, expenses, budgets, vendors, amc, inventory, purchase (partly Phase 2)
      governance/           compliance, documents, workforce, users-roles, workflows, audit, integrations, settings
  lib/ (session, api, permissions)
apps/resident-web/
  app/ dashboard, flats, finance, complaints, bookings, community, documents, visitors, account
packages/
  ui/            components, tokens, icons
  api-client/    generated TS clients + React Query hooks
  auth/          BFF session helpers shared by both apps
  config/        eslint, tsconfig, tailwind preset
```

The admin sidebar groups the requirement's 40-item menu (§51) into the SDD's nine
sections. Menu items are shown only if the user holds the permission (the permissions
list is fetched once per session from identity-service and cached).

## 3. Key screens

- **Morning dashboard:** a single `GET /api/dashboard/v1/morning`. Five panels: Not
  working · Due today · Overdue · Needs escalation · Today's ops and finance. Live
  updates arrive over `/topic/society.{id}.alerts`.
- **Job card board:** kanban by status plus a table view; SLA countdown chips.
- **Asset profile:** master data, QR, a timeline (history), costs, PM plan, warranty/AMC, documents.
- **Bill run wizard:** preview → exceptions → generate → publish.
- **Checklist and workflow builders:** JSON-schema-driven form builders (configurable, not coded).
- **Bulk import:** upload Excel → async validation report → fix → commit.

## 4. Deployment

Each app is a Docker image (`output: 'standalone'`) running on the same Kubernetes
cluster as the backend, with static assets on CloudFront. PR preview environments are
spun up per pull request against the shared dev backend.
