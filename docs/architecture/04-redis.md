# 04 — Redis

Redis 7 holds **disposable or fast-expiring state only**. Losing Redis must never lose
business data: everything durable is in Postgres, and everything cross-service goes
through Kafka.

- **Local:** a single `redis:7` container.
- **Production:** ElastiCache for Redis, cluster mode on, 1 shard × 2 replicas to start,
  Multi-AZ auto-failover, TLS and AUTH enabled.
- **Client:** Spring Data Redis with Lettuce. **Redisson** is used only for distributed locks.

## Uses

| # | Use | Service(s) | Key pattern | TTL | Notes |
|---|---|---|---|---|---|
| 1 | OTP challenges | identity | `otp:{phoneHash}` → {codeHash, attempts} | 5 min | Max 5 attempts; 3 requests per 15 min per phone |
| 2 | Rate limiting | api-gateway | `rl:{route}:{userId\|ip}` | window | Spring Cloud Gateway `RedisRateLimiter` (token bucket) |
| 3 | Revoked access tokens | identity, gateway | `jwt:deny:{jti}` | until token expiry (≤ 15 min) | On logout, device revoke, role change |
| 4 | Permission cache | all services | `perm:{userId}:{societyId}` → set | 10 min | Evicted by `identity.role.*` events |
| 5 | Read-through cache | society, asset, community | `cache:{svc}:{entity}:{id}` | 5–60 min | Spring Cache `@Cacheable`; evicted on local write and on related events |
| 6 | Idempotency keys (HTTP) | all write APIs | `idem:{svc}:{userId}:{Idempotency-Key}` → response | 24 h | Mobile offline sync and payment create use `Idempotency-Key` header |
| 7 | WebSocket fan-out | realtime | channels `rt:user:{userId}`, `rt:society:{id}:{topic}` | — | Pub/sub between realtime pods |
| 8 | Online presence | realtime | `presence:{userId}` → podId | 60 s heartbeat | Decides push-only vs socket + push |
| 9 | Gate pending approvals | gate | `gate:pending:{entryId}` | 10 min | Lets the guard console show live countdowns without DB polling |
| 10 | Distributed locks | billing (bill run per society), media | `lock:{name}` (Redisson) | lease 5 min | db-scheduler handles cron leadership; Redis locks only for ad-hoc critical sections |
| 11 | Dashboard hot counters | dashboard | `dash:{societyId}:{metric}` | 1 day | Rebuilt from Postgres projection if missing |
| 12 | Session for web portals | admin-web / resident-web BFF | `sess:{id}` | 12 h sliding | Next.js stores refresh token server-side, see [09](09-web-nextjs.md) |

## Rules

1. Every key starts with its purpose and includes `societyId` when it holds tenant data.
2. Every key has a TTL. There are no permanent keys, and a CI check scans `RedisTemplate`
   calls for `expire`.
3. Serialisation is JSON via Jackson. Never Java serialisation.
4. Values hold no raw PII (hash phone numbers in keys).
5. Services degrade gracefully when Redis is down: cache miss → DB; rate limiter fails
   **open** for normal routes and **closed** for `/auth/otp`; realtime falls back to push only.

## Why not Redis for jobs and timers

SLA timers, PM generation and bill runs must survive restarts and be auditable, so they
live in Postgres through **db-scheduler** (clustered, one executor per task). Redis/BullMQ
from the earlier SDD is replaced for that reason ([ADR-0004](../adr/0004-scheduling-db-scheduler.md)).
