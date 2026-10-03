"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { useApi, qk } from "@societyos/api-client/react";
import { Select } from "@societyos/ui";
import { errorMessage } from "./deps";
import { useSession, useSwitchSociety } from "./session";

export function shortId(id: string): string {
  return id.slice(0, 8);
}

/**
 * Picks the active society. Options come from the token's societies; names come from
 * society-service (GET /v1/societies) when it answers, otherwise a short id is shown.
 * Platform admins can also pick any society society-service lists for them.
 */
export function SocietySwitcher({ className, id = "society-switcher" }: { className?: string; id?: string }) {
  const session = useSession();
  const api = useApi();
  const switcher = useSwitchSociety();
  const societies = useQuery({
    queryKey: qk.societies,
    queryFn: () => api.society.societies.list(),
    staleTime: 5 * 60_000,
    retry: false,
  });

  const names = new Map((societies.data ?? []).map((s) => [s.id, s.name]));
  const ids = new Set(session.societies.map((s) => s.societyId));
  if (session.activeSocietyId) ids.add(session.activeSocietyId);
  if (session.platformAdmin) for (const s of societies.data ?? []) ids.add(s.id);
  const options = [...ids].map((sid) => ({ id: sid, name: names.get(sid) ?? `Society ${shortId(sid)}` }));
  options.sort((a, b) => a.name.localeCompare(b.name));

  if (options.length === 0) {
    return <span className="text-sm text-muted-foreground">No society yet</span>;
  }

  return (
    <div className={className}>
      <label htmlFor={id} className="sr-only">
        Active society
      </label>
      <Select
        id={id}
        value={session.activeSocietyId ?? ""}
        disabled={switcher.isPending || options.length < 2}
        onChange={(e) => {
          if (e.target.value && e.target.value !== session.activeSocietyId) switcher.mutate(e.target.value);
        }}
        aria-describedby={switcher.error ? `${id}-error` : undefined}
      >
        {!session.activeSocietyId ? <option value="">Choose a society…</option> : null}
        {options.map((o) => (
          <option key={o.id} value={o.id}>
            {o.name}
          </option>
        ))}
      </Select>
      {switcher.error ? (
        <p id={`${id}-error`} role="alert" className="mt-1 text-xs text-destructive">
          {errorMessage(switcher.error)}
        </p>
      ) : null}
    </div>
  );
}
