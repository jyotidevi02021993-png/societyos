# society-service

Society master data (port 8082, `society_db`, publishes `sos.society.events.v1`): societies and
settings, towers, flats, locations, facilities, parking, residents and flat memberships, vehicles,
domestic staff, the opt-in directory and Excel bulk onboarding.

```bash
./mvnw test                   # unit + ArchUnit
./mvnw verify -Pintegration   # + Testcontainers (Postgres with RLS, Kafka, Redis)
```

## API (`/api/society/v1/...` through the gateway)

| Path | Permission | Notes |
|---|---|---|
| `POST /v1/societies` | SUPER_ADMIN, no active society | Onboards a society; `society.created` makes identity-service provision its roles |
| `GET /v1/societies` | signed in | Societies in the caller's token |
| `GET/PUT /v1/society`, `GET/PUT /v1/society/settings` | `society:view` / `society:manage` | Settings PUT is a partial patch |
| `/v1/towers`, `/v1/flats` | `society:view` / `society:manage` | A tower's code is locked once it has flats (it is in every label, e.g. `A-1203`) |
| `/v1/locations`, `/v1/facilities`, `/v1/parking-slots` | `society:view` / `society:manage` | Locations form a tree (no cycles); visitor slots are never allotted |
| `/v1/members` | `member:view` / `member:manage` | Add by phone: identity-service resolves the user; events carry only the user id |
| `GET /v1/me/flats`, `PUT /v1/me/directory` | signed in | The caller's own memberships and directory choice |
| `GET /v1/directory` | `directory:view` | Opt-in residents, names and flats only; off when `directoryEnabled` is false |
| `/v1/vehicles`, `/v1/domestic-staff` | `member:manage`, or `household:manage` for your own flat | `/lookup` for the gate (`gate:entry`); staff phones stored encrypted, shown masked |
| `POST /v1/imports` (+ `GET /v1/imports[/{id}]`, `/template`) | `import:run` | Async; see below |

## Rules worth knowing

- **Flat status follows occupancy.** The first owner or tenant makes a flat OCCUPIED and the last
  one leaving makes it VACANT (each change publishes `society.flat.updated`). UNDER_RENOVATION is set
  by a manager and occupancy leaves it alone.
- **Family needs a holder.** A FAMILY member can only join a flat that has an active owner or tenant.
  Only owners and tenants can be primary, with one primary per kind per flat.
- **One domestic staff record per person.** Registering a known phone again links the new flats to
  the existing record.
- **Excel import** (`Towers`, `Flats` and `Residents` sheets; get the template at `/v1/imports/template`):
  1. Every row is validated first. Any error gives `VALIDATION_FAILED` with sheet, row and column,
     and nothing is written.
  2. Towers and flats are then created in one transaction. Ones that already exist are skipped,
     so a file can be re-run.
  3. Residents are added one by one. A failing row is reported and the rest continue
     (`COMPLETED_WITH_ERRORS`).

  `dryRun=true` stops after validation. The job runs as the uploader, whose token has to stay valid
  until the residents are added (about 15 minutes), so split very large files. A job still open
  after 30 minutes (cut off by a restart) is marked FAILED.

## Consumes / calls

- identity-service `POST /v1/users/resolve` (OpenFeign, with the caller's token) to turn a phone into a user id.
- identity-service `/v1/me/permissions` (platform) for `@perm` checks.
