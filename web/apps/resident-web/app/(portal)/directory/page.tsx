"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BookUser, Search } from "lucide-react";
import { isApiError } from "@societyos/api-client";
import { qk, useApi } from "@societyos/api-client/react";
import { Alert, Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, EmptyState, Input, PageHeader, Select, Switch } from "@societyos/ui";
import { ProblemAlert, QueryState, humanize, usePermissions } from "@societyos/shared";
import { useMyFlats } from "@/components/use-my-flats";

export default function DirectoryPage() {
  const api = useApi();
  const qc = useQueryClient();
  const { can, activeSocietyId } = usePermissions();
  const canView = can("directory:view");
  const myFlats = useMyFlats();
  const [towerId, setTowerId] = React.useState("");
  const [q, setQ] = React.useState("");

  const all = useQuery({
    queryKey: qk.directory(activeSocietyId),
    queryFn: () => api.society.directory.list(),
    enabled: !!activeSocietyId && canView,
  });
  const disabled = isApiError(all.error, "DIRECTORY_DISABLED");

  const myResidentId = myFlats.data?.[0]?.residentId;
  const listed = !!myResidentId && !!all.data?.some((e) => e.residentId === myResidentId);
  const [optIn, setOptIn] = React.useState<boolean | null>(null);
  const shownOptIn = optIn ?? listed;

  const toggle = useMutation({
    mutationFn: (next: boolean) => api.society.me.setDirectoryOptIn(next),
    onMutate: (next) => setOptIn(next),
    onError: () => setOptIn(null),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["society", activeSocietyId, "directory"] }),
  });

  // Towers come from directory entries (flat → towerId); names from the flat label prefix.
  const towers = React.useMemo(() => {
    const m = new Map<string, string>();
    for (const e of all.data ?? []) for (const f of e.flats) if (f.towerId && !m.has(f.towerId)) m.set(f.towerId, f.flatLabel.split("-")[0] ?? f.flatLabel);
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [all.data]);

  const needle = q.trim().toLowerCase();
  const rows = (all.data ?? []).filter(
    (e) =>
      (!towerId || e.flats.some((f) => f.towerId === towerId)) &&
      (!needle || e.name.toLowerCase().includes(needle) || e.flats.some((f) => f.flatLabel.toLowerCase().includes(needle))),
  );

  return (
    <>
      <PageHeader title="Resident directory" description="Neighbours who chose to be listed." />
      <div className="grid gap-5">
        {!disabled ? (
          <Card>
            <CardHeader>
              <CardTitle>Your listing</CardTitle>
              <CardDescription>Only your name and flat are shown. Your phone number is never listed.</CardDescription>
            </CardHeader>
            <CardContent className="grid gap-3">
              <Switch
                label={shownOptIn ? "I am listed in the directory" : "List me in the directory"}
                checked={shownOptIn}
                disabled={toggle.isPending || (canView && all.isLoading)}
                onChange={(e) => toggle.mutate(e.target.checked)}
              />
              {toggle.error ? <ProblemAlert error={toggle.error} /> : null}
            </CardContent>
          </Card>
        ) : null}

        {!canView ? <Alert variant="info">The directory is not available for your role in this society.</Alert> : null}
        {disabled ? (
          <EmptyState icon={BookUser} title="The directory is turned off" description="Your society has not enabled the resident directory." />
        ) : null}
        {canView && !disabled ? (
          <>
            <div className="flex flex-wrap items-end gap-3">
              <div className="grid flex-1 gap-1">
                <label htmlFor="dir-q" className="text-xs font-medium">
                  Search
                </label>
                <div className="relative">
                  <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
                  <Input id="dir-q" type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name or flat" className="pl-8" />
                </div>
              </div>
              {towers.length > 1 ? (
                <div className="grid w-40 gap-1">
                  <label htmlFor="dir-tower" className="text-xs font-medium">
                    Tower
                  </label>
                  <Select id="dir-tower" value={towerId} onChange={(e) => setTowerId(e.target.value)}>
                    <option value="">All</option>
                    {towers.map(([id, name]) => (
                      <option key={id} value={id}>
                        {name}
                      </option>
                    ))}
                  </Select>
                </div>
              ) : null}
            </div>
            <QueryState isLoading={all.isLoading} error={all.error} onRetry={() => void all.refetch()} loadingLabel="Loading directory…" />
            {all.data && rows.length === 0 ? <EmptyState icon={BookUser} title={needle || towerId ? "No matches" : "Nobody is listed yet"} /> : null}
            {rows.length > 0 ? (
              <ul className="grid gap-2 sm:grid-cols-2" aria-label="Residents">
                {rows.map((e) => (
                  <li key={e.residentId}>
                    <Card className="p-4">
                      <p className="font-medium">{e.name}</p>
                      <div className="mt-1 flex flex-wrap gap-1">
                        {e.flats.map((f) => (
                          <Badge key={`${f.flatLabel}-${f.kind}`} variant="secondary">
                            {f.flatLabel} · {humanize(f.kind)}
                          </Badge>
                        ))}
                      </div>
                    </Card>
                  </li>
                ))}
              </ul>
            ) : null}
          </>
        ) : null}
      </div>
    </>
  );
}
