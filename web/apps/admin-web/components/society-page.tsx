"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { qk, useApi } from "@societyos/api-client/react";
import type { Flat, Tower } from "@societyos/api-client";
import { EmptyState, PageHeader } from "@societyos/ui";
import { RequirePermission, usePermissions } from "@societyos/shared";

/** Page frame for Society screens: header, permission gate, "pick a society" state. */
export function SocietyPage({
  title,
  description,
  actions,
  anyOf,
  children,
}: {
  title: string;
  description?: string;
  actions?: React.ReactNode;
  anyOf: string[];
  children: React.ReactNode;
}) {
  const { activeSocietyId } = usePermissions();
  return (
    <>
      <PageHeader title={title} description={description} actions={activeSocietyId ? actions : undefined} />
      {!activeSocietyId ? (
        <EmptyState title="No active society" description="Choose a society in the switcher at the top, or onboard one first." />
      ) : (
        <RequirePermission anyOf={anyOf}>{children}</RequirePermission>
      )}
    </>
  );
}

export function useTowers() {
  const api = useApi();
  const { activeSocietyId } = usePermissions();
  return useQuery<Tower[]>({
    queryKey: qk.towers(activeSocietyId),
    queryFn: () => api.society.towers.list(),
    enabled: !!activeSocietyId,
  });
}

export function useFlats(towerId?: string) {
  const api = useApi();
  const { activeSocietyId } = usePermissions();
  return useQuery<Flat[]>({
    queryKey: qk.flats(activeSocietyId, towerId),
    queryFn: () => api.society.flats.list(towerId),
    enabled: !!activeSocietyId,
  });
}

/** Invalidate everything cached for the active society under a sub-key, e.g. "flats". */
export function societyKey(sid: string | undefined, part: string): readonly unknown[] {
  return ["society", sid, part];
}
