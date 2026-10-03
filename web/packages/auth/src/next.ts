import "server-only";
import { cookies } from "next/headers";
import type { SessionInfo } from "@societyos/api-client";
import type { Bff } from "./bff";

/**
 * Session for Server Components / layouts (read-only: cannot set cookies here).
 * Returns null when signed out or when the refresh token was rejected.
 */
export async function getServerSession(bff: Bff): Promise<SessionInfo | null> {
  const jar = await cookies();
  const id = jar.get(bff.config.cookieName)?.value;
  try {
    return await bff.sessionInfo(id);
  } catch (err) {
    console.warn(`[bff] session lookup failed: ${(err as Error).message}`);
    return null;
  }
}
