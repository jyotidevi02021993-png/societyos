"use client";

import { useQuery } from "@tanstack/react-query";
import { qk, useApi } from "@societyos/api-client/react";
import { usePermissions } from "@societyos/shared";

/** The caller's memberships in the active society (GET /v1/me/flats). */
export function useMyFlats() {
  const api = useApi();
  const { activeSocietyId } = usePermissions();
  return useQuery({
    queryKey: qk.myFlats(activeSocietyId),
    queryFn: () => api.society.me.flats(),
    enabled: !!activeSocietyId,
  });
}
