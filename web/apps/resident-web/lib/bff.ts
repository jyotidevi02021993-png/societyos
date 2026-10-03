import "server-only";
import { createBffFromEnv } from "@societyos/auth";

/** The resident portal's BFF: OTP login, sessions in Redis, refresh token never leaves the server. */
export const bff = createBffFromEnv("resident", { deviceName: "Resident web portal" });
