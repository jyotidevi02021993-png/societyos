"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { UserPlus, Users } from "lucide-react";
import type { Membership } from "@societyos/api-client";
import { qk, useApi } from "@societyos/api-client/react";
import { Badge, Button, Card, ConfirmDialog, EmptyState, Field, Input, Select, Switch, Table, TBody, TD, TH, THead, TR } from "@societyos/ui";
import { QueryState, errorMessage, formatDate, humanize, usePermissions } from "@societyos/shared";
import { SocietyPage, societyKey, useFlats } from "@/components/society-page";
import { AddMemberDialog } from "@/components/add-member-form";

export default function ResidentsPage() {
  const api = useApi();
  const qc = useQueryClient();
  const { can, activeSocietyId } = usePermissions();
  const canManage = can("member:manage");
  const flats = useFlats();
  const [flatId, setFlatId] = React.useState("");
  const [includeEnded, setIncludeEnded] = React.useState(false);
  const [adding, setAdding] = React.useState(false);
  const [ending, setEnding] = React.useState<Membership | null>(null);
  const [endDate, setEndDate] = React.useState("");

  const members = useQuery({
    queryKey: qk.members(activeSocietyId, flatId || undefined, includeEnded),
    queryFn: () => api.society.members.list({ flatId: flatId || undefined, includeEnded }),
    enabled: !!activeSocietyId,
  });

  const end = useMutation({
    mutationFn: (m: Membership) => api.society.members.end(m.membershipId, endDate ? { toDate: endDate } : {}),
    onSuccess: () => {
      setEnding(null);
      setEndDate("");
      void qc.invalidateQueries({ queryKey: societyKey(activeSocietyId, "members") });
    },
  });

  return (
    <SocietyPage
      title="Residents"
      description="Owners, tenants and family members of each flat."
      anyOf={["member:view", "member:manage"]}
      actions={
        canManage ? (
          <Button onClick={() => setAdding(true)} disabled={!flats.data?.length}>
            <UserPlus aria-hidden="true" /> Add resident
          </Button>
        ) : null
      }
    >
      <div className="grid gap-4">
        <div className="flex flex-wrap items-end gap-4">
          <div className="grid gap-1">
            <label htmlFor="member-flat" className="text-xs font-medium">
              Flat
            </label>
            <Select id="member-flat" value={flatId} onChange={(e) => setFlatId(e.target.value)} className="w-56">
              <option value="">All flats</option>
              {flats.data?.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.label}
                </option>
              ))}
            </Select>
          </div>
          <Switch label="Show ended memberships" checked={includeEnded} onChange={(e) => setIncludeEnded(e.target.checked)} />
        </div>

        <QueryState isLoading={members.isLoading} error={members.error} onRetry={() => void members.refetch()} loadingLabel="Loading residents…" />
        {members.data?.length === 0 ? (
          <EmptyState icon={Users} title="No residents" description={flatId ? "Nobody is linked to this flat yet." : "Add residents by phone number, or import them from Excel."} />
        ) : null}
        {members.data && members.data.length > 0 ? (
          <Card>
            <Table caption="Residents">
              <THead>
                <TR>
                  <TH>Name</TH>
                  <TH>Flat</TH>
                  <TH>Kind</TH>
                  <TH>From</TH>
                  <TH>To</TH>
                  <TH>Status</TH>
                  {canManage ? <TH className="text-right">Actions</TH> : null}
                </TR>
              </THead>
              <TBody>
                {members.data.map((m) => (
                  <TR key={m.membershipId}>
                    <TD className="font-medium">
                      {m.residentName}
                      {m.isPrimary ? (
                        <Badge variant="outline" className="ml-2">
                          Primary
                        </Badge>
                      ) : null}
                    </TD>
                    <TD>{m.flatLabel}</TD>
                    <TD>{humanize(m.kind)}</TD>
                    <TD>{formatDate(m.fromDate)}</TD>
                    <TD>{formatDate(m.toDate)}</TD>
                    <TD>
                      <Badge variant={m.active ? "success" : "secondary"}>{m.active ? "Active" : "Ended"}</Badge>
                    </TD>
                    {canManage ? (
                      <TD className="text-right">
                        {m.active ? (
                          <Button size="sm" variant="ghost" onClick={() => setEnding(m)} aria-label={`End membership of ${m.residentName} in ${m.flatLabel}`}>
                            End membership
                          </Button>
                        ) : null}
                      </TD>
                    ) : null}
                  </TR>
                ))}
              </TBody>
            </Table>
          </Card>
        ) : null}
      </div>

      {adding ? <AddMemberDialog flats={flats.data ?? []} defaultFlatId={flatId} onClose={() => setAdding(false)} /> : null}

      <ConfirmDialog
        open={!!ending}
        onOpenChange={(o) => {
          if (!o) {
            setEnding(null);
            end.reset();
          }
        }}
        title="End membership?"
        description={
          <span className="grid gap-3">
            <span>
              {ending?.residentName} will no longer be a {ending ? humanize(ending.kind).toLowerCase() : ""} of {ending?.flatLabel}.
            </span>
            <Field id="end-date" label="Last day (optional, defaults to today)">
              {(a) => <Input {...a} type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} />}
            </Field>
          </span>
        }
        confirmLabel="End membership"
        destructive
        loading={end.isPending}
        error={end.error ? errorMessage(end.error) : null}
        onConfirm={() => ending && end.mutate(ending)}
      />
    </SocietyPage>
  );
}
