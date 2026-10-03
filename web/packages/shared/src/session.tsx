"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { toApiError, qkSession, type SessionInfo, type Uuid } from "./deps";
import { hasAny, hasPermission } from "./permissions";

async function fetchSession(): Promise<SessionInfo> {
  const res = await fetch("/api/session", { cache: "no-store", credentials: "same-origin" });
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as SessionInfo;
}

const SessionContext = React.createContext<SessionInfo | null>(null);

/**
 * Holds the browser-safe session (never tokens). Seeded by the server layout, then kept
 * fresh through /api/session.
 */
export function SessionProvider({ initial, children }: { initial: SessionInfo; children: React.ReactNode }) {
  const { data } = useQuery({
    queryKey: qkSession,
    queryFn: fetchSession,
    initialData: initial,
    staleTime: 60_000,
    refetchInterval: 5 * 60_000,
  });
  return <SessionContext.Provider value={data ?? initial}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionInfo {
  const s = React.useContext(SessionContext);
  if (!s) throw new Error("useSession must be used inside <SessionProvider>");
  return s;
}

/** Same as useSession but null outside a provider (for components also used on public pages). */
export function useOptionalSession(): SessionInfo | null {
  return React.useContext(SessionContext);
}

export function usePermissions() {
  const s = useSession();
  const set = React.useMemo(() => new Set(s.permissions), [s.permissions]);
  return {
    permissions: set,
    can: (p: string) => hasPermission(set, p),
    canAny: (anyOf: readonly string[]) => hasAny(set, anyOf),
    isPlatformAdmin: s.platformAdmin || s.roles.includes("SUPER_ADMIN"),
    activeSocietyId: s.activeSocietyId as Uuid | undefined,
  };
}

export function useSwitchSociety() {
  const qc = useQueryClient();
  const router = useRouter();
  return useMutation({
    mutationFn: async (societyId: Uuid) => {
      const res = await fetch("/api/auth/switch-society", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ societyId }),
        credentials: "same-origin",
      });
      if (!res.ok) throw await toApiError(res);
      return (await res.json()) as SessionInfo;
    },
    onSuccess: (info) => {
      // Nothing from the previous society may linger in the cache.
      qc.removeQueries({ predicate: (q) => q.queryKey[0] !== qkSession[0] });
      qc.setQueryData(qkSession, info);
      router.refresh();
    },
  });
}

export function useLogout(loginPath = "/login") {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      await fetch("/api/auth/logout", { method: "POST", credentials: "same-origin" });
    },
    onSettled: () => {
      qc.clear();
      window.location.assign(loginPath);
    },
  });
}

/** Renders children only when the user holds one of the permissions; otherwise a notice. */
export function RequirePermission({
  anyOf,
  children,
  fallback,
}: {
  anyOf: readonly string[];
  children: React.ReactNode;
  fallback?: React.ReactNode;
}) {
  const { canAny } = usePermissions();
  if (canAny(anyOf)) return <>{children}</>;
  return (
    <>
      {fallback ?? (
        <div role="alert" className="rounded-md border border-warning/50 bg-warning/15 p-4 text-sm">
          You do not have access to this page in the current society. Ask your society administrator for the{" "}
          <code className="font-mono text-xs">{anyOf.join(" or ")}</code> permission.
        </div>
      )}
    </>
  );
}
