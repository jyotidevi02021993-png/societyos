// Re-exports so shared modules import api-client pieces from one place.
export { toApiError, errorMessage, ApiError } from "@societyos/api-client";
export type { SessionInfo, Uuid } from "@societyos/api-client";
export { qk } from "@societyos/api-client/react";
import { qk } from "@societyos/api-client/react";
export const qkSession = qk.session;
