# 05 — Security, tenancy and data protection

## 1. Authentication

| Actor | Method | Tokens |
|---|---|---|
| Resident, guard, technician, vendor (mobile) | Phone OTP (SMS; WhatsApp fallback) + **device binding** | Access JWT 15 min; refresh token 30 days, rotated on each use, bound to `device_id` |
| Admin users (web) | Email + password (Argon2id) + **TOTP MFA**; SSO (OIDC) for FM companies in Phase 2 | Access JWT 15 min; refresh in an HTTP-only cookie via the web BFF, 12 h sliding |
| Gate edge agent | Client certificate + device credentials | Service JWT 1 h |
| Service to service | OAuth2 client credentials from identity-service | Service JWT 5 min, `aud` = target service |
| Super Admin support access | MFA + reason + time-boxed grant | Every action audited, visible to the society |

Refresh-token reuse detection: if a rotated token is presented again, the whole device
session is revoked (it was probably stolen).

## 2. JWT

Signed **RS256** by identity-service. Keys rotate every 90 days; JWKS at
`/.well-known/jwks.json` publishes current and previous keys.

```json
{
  "iss": "https://auth.societyos.in",
  "sub": "0191ab22-…",               // user id
  "sid": "0191aa10-…",               // active society (writes go here)
  "sids": ["0191aa10-…", "0191bb20-…"], // readable societies (multi-site FM users)
  "roles": ["ESTATE_MANAGER"],
  "pv": 17,                          // permission version: cache key for permissions
  "did": "device-uuid",
  "amr": ["otp"],
  "exp": 1790000000, "jti": "…"
}
```

- Permissions are **not** put in the token (the list would be too large and go stale).
  Services resolve `module:action` from `pv` + Redis cache, with identity-service as source.
- `POST /auth/switch-society` issues a new token for another society the user belongs to.

## 3. Authorisation

- **Gateway:** token valid, not revoked, `X-Society-Id` ∈ `sids`.
- **Service:** Spring Security resource server re-validates the JWT (zero trust inside
  the cluster). Method security: `@PreAuthorize("@perm.has('jobcard:approve')")`.
- **Row level:** PostgreSQL RLS on `society_id` ([03](03-data-postgres.md#3-multi-tenancy-with-row-level-security)).
- **Object level:** ownership checks in the service (a resident only sees their own
  flat's bills; a technician only sees job cards assigned to them; a vendor only sees
  their assigned work).
- **Field level:** response DTOs per role. Guards and vendors get masked phone numbers
  (`98XXXXXX21`) and never see billing data.

Role matrix (from SDD), enforced as permission bundles:

| Role | Key grants | Explicitly denied |
|---|---|---|
| SUPER_ADMIN | society:onboard, plan:configure, support:access | Editing a society's financial records |
| RWA_COMMITTEE | budget:approve, po:approve, mis:view, notice:publish, poll:create | Field work |
| ESTATE_MANAGER | all operations, signoff:daily, jobcard:approve | Editing locked financial entries |
| FACILITY_MANAGER | utilities, checklist, staff deployment | Spend above threshold |
| ENGINEER / TECHNICIAN | jobcard:work, reading:record, spare:issue-request | Finance, resident data beyond the ticket |
| HOUSEKEEPING | checklist:execute, attendance:self | PO |
| GUARD | gate:*, incident:report, sos:raise | Billing |
| ACCOUNTS | bill:*, payment:*, expense:*, vendorpayment:* | Asset and PM data changes |
| VENDOR | assigned jobcard:work, amc:visit, invoice:submit | Anything outside assigned work |
| RESIDENT_* | gatepass:*, own bill:view/pay, complaint:*, booking:*, community:* | Other residents' data beyond the opt-in directory |

## 4. Multi-site (facility-management companies)

`organisation` + `org_membership` in identity. An FM manager's token carries
`sids` = all societies their org manages and they're assigned to. RLS reads accept the
array; writes need an explicit `sid` switch. dashboard-service exposes
`/dashboard/portfolio` over `sids`.

## 5. Data protection (DPDP Act 2023 + Rules 2025, Agreement §9–12)

| Requirement | Implementation |
|---|---|
| Notice and consent | `consent` records per purpose and version; consent screen at first login; withdrawal in Account settings; visitors get a printed/QR notice at the gate plus an SMS link |
| Purpose limitation and minimisation | Events carry IDs, not PII; guards see masked data; AI receives redacted text |
| Data-principal rights | `/me/data-export`, correction via profile, erasure request → workflow → per-service `privacy.erasure.requested` event; each service anonymises its rows (financial records kept, legal-retention flag) |
| Retention | Per-society settings: visitor photos and gate logs (default 180 days), CCTV references, notifications 90 days; nightly purge jobs emit `*.purged` for audit |
| India hosting | ap-south-1 primary, ap-south-2 DR; no cross-border subprocessors without approval (subprocessor register in docs) |
| Breach notification | Incident runbook: notify customer within 6 h for critical incidents (Agreement §12); security events streamed to SIEM |
| Children and sensitive data | Family members under 18 flagged; ID documents are encrypted fields (see below), never in events or logs |
| No reuse or training | Customer data is never used to train shared models (Agreement §3.3); enforced in ai-service config per society |

## 6. Encryption

- TLS 1.2+ everywhere, and mTLS between pods with Linkerd (Phase 2).
- At rest: RDS, S3, EBS, MSK and ElastiCache encrypted with KMS.
- **Application-level field encryption** for phone numbers, ID-proof numbers and TOTP
  secrets: AES-256-GCM with KMS data keys (envelope), plus a deterministic HMAC column
  (`phone_hash`) for lookups.
- Secrets live in AWS Secrets Manager and are mounted with External Secrets Operator.
  None go in Git or env files outside local dev.

## 7. Audit

- All state-changing events go to audit-service, which keeps an append-only store with a
  hash chain.
- For audited entities (bills, payments, ledger, job-card approvals, POs, compliance
  certificates, role changes), events include `before` / `after` snapshots of the changed fields.
- Security audit: logins, failed OTPs, token revocations, permission changes, data
  exports and support access go to a separate security log (Agreement §11).
- **Locked records:** once `locked_at` is set (approved job card, issued receipt,
  closed bill run), updates are rejected by a DB trigger. Corrections go through
  reversal entries that carry a reason.

## 8. Application security

OWASP ASVS L2 as the target. Semgrep + SpotBugs/FindSecBugs + OWASP Dependency-Check +
Trivy image scan in CI; ZAP baseline against staging; an annual third-party penetration
test. Payments use the gateway's hosted checkout, so card and UPI data never reach our
servers (out of PCI-DSS scope). Webhooks are verified by HMAC signature and deduplicated
by `webhook_event` PK.
