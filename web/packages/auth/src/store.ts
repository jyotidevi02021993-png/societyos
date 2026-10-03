import type { AuthConfig } from "./config";
import type { SessionData } from "./session";

/** Server-side session storage. Values hold the refresh token, so they never leave the server. */
export interface SessionStore {
  readonly kind: "redis" | "memory";
  get(id: string): Promise<SessionData | null>;
  set(id: string, data: SessionData, ttlSeconds: number): Promise<void>;
  /** Extends the TTL without rewriting (sliding expiry). */
  touch(id: string, ttlSeconds: number): Promise<void>;
  delete(id: string): Promise<void>;
}

/** In-process store for tests and for local dev when Redis is not running. */
export class MemorySessionStore implements SessionStore {
  readonly kind = "memory" as const;
  private readonly map = new Map<string, { data: SessionData; expiresAt: number }>();

  constructor(private readonly now: () => number = Date.now) {}

  async get(id: string): Promise<SessionData | null> {
    const hit = this.map.get(id);
    if (!hit) return null;
    if (hit.expiresAt <= this.now()) {
      this.map.delete(id);
      return null;
    }
    return structuredClone(hit.data);
  }

  async set(id: string, data: SessionData, ttlSeconds: number): Promise<void> {
    this.map.set(id, { data: structuredClone(data), expiresAt: this.now() + ttlSeconds * 1000 });
  }

  async touch(id: string, ttlSeconds: number): Promise<void> {
    const hit = this.map.get(id);
    if (hit) hit.expiresAt = this.now() + ttlSeconds * 1000;
  }

  async delete(id: string): Promise<void> {
    this.map.delete(id);
  }

  get size(): number {
    return this.map.size;
  }
}

/** Minimal slice of ioredis used here (so tests can pass a fake). */
export interface RedisLike {
  get(key: string): Promise<string | null>;
  set(key: string, value: string, mode: "EX", seconds: number): Promise<unknown>;
  expire(key: string, seconds: number): Promise<unknown>;
  del(key: string): Promise<unknown>;
}

export class RedisSessionStore implements SessionStore {
  readonly kind = "redis" as const;

  constructor(
    private readonly redis: RedisLike,
    private readonly prefix: string,
  ) {}

  private key(id: string): string {
    return `${this.prefix}${id}`;
  }

  async get(id: string): Promise<SessionData | null> {
    const raw = await this.redis.get(this.key(id));
    if (!raw) return null;
    try {
      return JSON.parse(raw) as SessionData;
    } catch {
      return null;
    }
  }

  async set(id: string, data: SessionData, ttlSeconds: number): Promise<void> {
    await this.redis.set(this.key(id), JSON.stringify(data), "EX", ttlSeconds);
  }

  async touch(id: string, ttlSeconds: number): Promise<void> {
    await this.redis.expire(this.key(id), ttlSeconds);
  }

  async delete(id: string): Promise<void> {
    await this.redis.del(this.key(id));
  }
}

// One store per app per process. Kept on globalThis because Next.js dev bundles each route
// separately, and module-level singletons would otherwise be duplicated.
type Registry = Map<string, Promise<SessionStore>>;
const g = globalThis as unknown as { __sosSessionStores?: Registry };

export function getSessionStore(config: AuthConfig): Promise<SessionStore> {
  g.__sosSessionStores ??= new Map();
  const key = `${config.app}|${config.sessionStore}|${config.redisUrl}`;
  let store = g.__sosSessionStores.get(key);
  if (!store) {
    store = createStore(config).catch((err: unknown) => {
      g.__sosSessionStores?.delete(key); // retry on the next request
      throw err;
    });
    g.__sosSessionStores.set(key, store);
  }
  return store;
}

/** Test hook. */
export function setSessionStoreForTests(config: AuthConfig, store: SessionStore): void {
  g.__sosSessionStores ??= new Map();
  g.__sosSessionStores.set(`${config.app}|${config.sessionStore}|${config.redisUrl}`, Promise.resolve(store));
}

async function createStore(config: AuthConfig): Promise<SessionStore> {
  const prefix = `bff:${config.app}:session:`;
  if (config.sessionStore === "memory") return new MemorySessionStore();
  const { default: Redis } = await import("ioredis");
  const redis = new Redis(config.redisUrl, {
    lazyConnect: true,
    connectTimeout: 1500,
    maxRetriesPerRequest: 2,
    enableOfflineQueue: true,
  });
  redis.on("error", (err: Error) => {
    if (process.env.NODE_ENV !== "test") console.warn(`[bff] Redis error: ${err.message}`);
  });
  try {
    await redis.connect();
    await redis.ping();
    return new RedisSessionStore(redis as unknown as RedisLike, prefix);
  } catch (err) {
    redis.disconnect();
    const production = process.env.NODE_ENV === "production";
    if (config.sessionStore === "redis" || production) {
      throw new Error(`Session store: Redis at ${config.redisUrl} is not reachable (${(err as Error).message})`);
    }
    console.warn(
      `[bff] Redis at ${config.redisUrl} is not reachable; using an in-memory session store (dev only: sessions are lost on restart).`,
    );
    return new MemorySessionStore();
  }
}
