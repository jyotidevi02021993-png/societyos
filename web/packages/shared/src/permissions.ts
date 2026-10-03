/**
 * Permission checks for showing UI. The backend is the authority; this only hides what the
 * user cannot use.
 *
 * ALIASES lets one UI permission accept the code a service actually checks, while the
 * permission catalogue settles (e.g. society-service's early `society:read`/`flat:read`).
 */
export const PERMISSION_ALIASES: Record<string, string[]> = {
  "society:view": ["society:read", "tower:read", "flat:read", "society:manage"],
  "society:manage": ["tower:manage", "flat:manage"],
  "member:view": ["member:read", "member:manage"],
  "directory:view": ["directory:read"],
};

export function hasPermission(granted: readonly string[] | Set<string>, required: string): boolean {
  const set = granted instanceof Set ? granted : new Set(granted);
  if (set.has("*") || set.has(required)) return true;
  const [module] = required.split(":");
  if (module && set.has(`${module}:*`)) return true;
  return (PERMISSION_ALIASES[required] ?? []).some((alt) => set.has(alt));
}

export function hasAny(granted: readonly string[] | Set<string>, anyOf: readonly string[] | undefined): boolean {
  if (!anyOf || anyOf.length === 0) return true;
  return anyOf.some((p) => hasPermission(granted, p));
}
