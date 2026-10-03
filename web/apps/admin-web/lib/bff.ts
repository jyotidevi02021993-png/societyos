import "server-only";
import { createBffFromEnv } from "@societyos/auth";

/** The admin portal's BFF: sessions in Redis, refresh token never leaves the server. */
export const bff = createBffFromEnv("admin", { deviceName: "Admin web portal" });
