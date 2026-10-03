"use client";

import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ParkingSquare, Plus } from "lucide-react";
import { PARKING_KINDS, type ParkingSlot } from "@societyos/api-client";
import { qk, useApi } from "@societyos/api-client/react";
import { Badge, Button, Card, Dialog, EmptyState, Field, Input, Select, Table, TBody, TD, TH, THead, TR } from "@societyos/ui";
import { ProblemAlert, QueryState, applyServerFieldErrors, humanize, usePermissions } from "@societyos/shared";
import { SocietyPage, useFlats } from "@/components/society-page";

const slotSchema = z.object({
  code: z.string().trim().min(1, "Enter the slot code, e.g. B1-014").max(20),
  kind: z.enum(PARKING_KINDS),
});

export default function ParkingPage() {
  const api = useApi();
  const { can, activeSocietyId } = usePermissions();
  const canManage = can("society:manage");
  const flats = useFlats();
  const [flatFilter, setFlatFilter] = React.useState("");
  const [creating, setCreating] = React.useState(false);
  const [assigning, setAssigning] = React.useState<ParkingSlot | null>(null);
  const slots = useQuery({
    queryKey: [...qk.parking(activeSocietyId), flatFilter || "all"],
    queryFn: () => api.society.parking.list(flatFilter || undefined),
    enabled: !!activeSocietyId,
  });

  return (
    <SocietyPage
      title="Parking"
      description="Parking slots and which flat each is assigned to."
      anyOf={["society:view"]}
      actions={
        canManage ? (
          <Button onClick={() => setCreating(true)}>
            <Plus aria-hidden="true" /> Slot
          </Button>
        ) : null
      }
    >
      <div className="grid gap-4">
        <div className="grid w-56 gap-1">
          <label htmlFor="parking-flat" className="text-xs font-medium">
            Assigned to flat
          </label>
          <Select id="parking-flat" value={flatFilter} onChange={(e) => setFlatFilter(e.target.value)}>
            <option value="">Any</option>
            {flats.data?.map((f) => (
              <option key={f.id} value={f.id}>
                {f.label}
              </option>
            ))}
          </Select>
        </div>
        <QueryState isLoading={slots.isLoading} error={slots.error} onRetry={() => void slots.refetch()} />
        {slots.data?.length === 0 ? <EmptyState icon={ParkingSquare} title="No parking slots" /> : null}
        {slots.data && slots.data.length > 0 ? (
          <Card>
            <Table caption="Parking slots">
              <THead>
                <TR>
                  <TH>Slot</TH>
                  <TH>Kind</TH>
                  <TH>Assigned to</TH>
                  {canManage ? <TH className="text-right">Actions</TH> : null}
                </TR>
              </THead>
              <TBody>
                {slots.data.map((s) => (
                  <TR key={s.id}>
                    <TD className="font-mono font-medium">{s.code}</TD>
                    <TD>
                      <Badge variant="secondary">{humanize(s.kind)}</Badge>
                    </TD>
                    <TD>{s.flatLabel ?? (s.flatId ? "—" : <span className="text-muted-foreground">Unassigned</span>)}</TD>
                    {canManage ? (
                      <TD className="text-right">
                        <Button size="sm" variant="ghost" onClick={() => setAssigning(s)} aria-label={`Change assignment of slot ${s.code}`}>
                          {s.flatId ? "Reassign" : "Assign"}
                        </Button>
                      </TD>
                    ) : null}
                  </TR>
                ))}
              </TBody>
            </Table>
          </Card>
        ) : null}
      </div>
      {creating ? <CreateSlotDialog onClose={() => setCreating(false)} /> : null}
      {assigning ? <AssignDialog slot={assigning} flats={flats.data ?? []} onClose={() => setAssigning(null)} /> : null}
    </SocietyPage>
  );
}

function CreateSlotDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const { activeSocietyId } = usePermissions();
  const form = useForm<z.infer<typeof slotSchema>>({ resolver: zodResolver(slotSchema), defaultValues: { code: "", kind: "COVERED" } });
  const save = useMutation({
    mutationFn: (v: z.infer<typeof slotSchema>) => api.society.parking.create(v),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.parking(activeSocietyId) });
      onClose();
    },
    onError: (err) => applyServerFieldErrors(err, form.setError, ["code", "kind"]),
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title="Add parking slot">
      <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="grid gap-4" noValidate>
        {save.error ? <ProblemAlert error={save.error} /> : null}
        <div className="grid grid-cols-2 gap-3">
          <Field id="slot-code" label="Code" required error={form.formState.errors.code?.message}>
            {(a) => <Input {...a} {...form.register("code")} />}
          </Field>
          <Field id="slot-kind" label="Kind" required>
            {(a) => (
              <Select {...a} {...form.register("kind")}>
                {PARKING_KINDS.map((k) => (
                  <option key={k} value={k}>
                    {humanize(k)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={save.isPending}>
            Add slot
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function AssignDialog({ slot, flats, onClose }: { slot: ParkingSlot; flats: { id: string; label: string }[]; onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const { activeSocietyId } = usePermissions();
  const [flatId, setFlatId] = React.useState(slot.flatId ?? "");
  const save = useMutation({
    mutationFn: (target: string | null) => api.society.parking.assign(slot.id, target),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.parking(activeSocietyId) });
      onClose();
    },
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={`Slot ${slot.code}`} description="Assign the slot to a flat, or free it.">
      <form
        className="grid gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (flatId) save.mutate(flatId);
        }}
      >
        {save.error ? <ProblemAlert error={save.error} /> : null}
        <Field id="assign-flat" label="Flat" required>
          {(a) => (
            <Select {...a} value={flatId} onChange={(e) => setFlatId(e.target.value)}>
              <option value="">Choose a flat…</option>
              {flats.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <div className="flex flex-wrap justify-end gap-2">
          {slot.flatId ? (
            <Button variant="outline" onClick={() => save.mutate(null)} disabled={save.isPending}>
              Unassign
            </Button>
          ) : null}
          <Button type="submit" loading={save.isPending} disabled={!flatId || flatId === slot.flatId}>
            Assign
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
