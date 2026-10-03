"use client";

import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Dumbbell, Pencil, Plus } from "lucide-react";
import { FACILITY_KINDS, formatPaise, paiseToRupees, rupeesToPaise, type Facility, type FacilityBody } from "@societyos/api-client";
import { qk, useApi } from "@societyos/api-client/react";
import { Badge, Button, Card, CardContent, CardHeader, CardTitle, Checkbox, Dialog, EmptyState, Field, Input, Label, Select } from "@societyos/ui";
import { ProblemAlert, QueryState, applyServerFieldErrors, humanize, usePermissions } from "@societyos/shared";
import { SocietyPage } from "@/components/society-page";

const int = (min: number, max: number) => z.coerce.number<string | number>().int("Whole number").min(min, `At least ${min}`).max(max, `At most ${max}`);

const facilitySchema = z
  .object({
    kind: z.enum(FACILITY_KINDS),
    name: z.string().trim().min(1, "Enter a name").max(80),
    capacity: int(1, 10000),
    chargeable: z.boolean(),
    chargeRupees: z.coerce.number<string | number>().min(0, "Cannot be negative").max(1_000_000),
    slotMinutes: int(15, 1440),
    maxAdvanceDays: int(0, 365),
    maxPerFlatPerWeek: int(0, 100),
    active: z.boolean(),
  })
  .refine((v) => !v.chargeable || v.chargeRupees > 0, { path: ["chargeRupees"], message: "Enter the charge per slot" });

type FacilityInput = z.input<typeof facilitySchema>;
type FacilityValues = z.output<typeof facilitySchema>;

function toFacilityBody(v: FacilityValues): FacilityBody {
  return {
    kind: v.kind,
    name: v.name,
    capacity: v.capacity,
    chargeable: v.chargeable,
    chargePaise: v.chargeable ? rupeesToPaise(v.chargeRupees) : 0,
    bookingRules: { slotMinutes: v.slotMinutes, maxAdvanceDays: v.maxAdvanceDays, maxPerFlatPerWeek: v.maxPerFlatPerWeek },
    status: v.active ? "ACTIVE" : "INACTIVE",
  };
}

export default function FacilitiesPage() {
  const api = useApi();
  const { can, activeSocietyId } = usePermissions();
  const canManage = can("society:manage");
  const list = useQuery({ queryKey: qk.facilities(activeSocietyId), queryFn: () => api.society.facilities.list(), enabled: !!activeSocietyId });
  const [editing, setEditing] = React.useState<{ facility?: Facility } | null>(null);

  return (
    <SocietyPage
      title="Facilities"
      description="Bookable amenities and their booking rules."
      anyOf={["society:view"]}
      actions={
        canManage ? (
          <Button onClick={() => setEditing({})}>
            <Plus aria-hidden="true" /> Facility
          </Button>
        ) : null
      }
    >
      <QueryState isLoading={list.isLoading} error={list.error} onRetry={() => void list.refetch()} />
      {list.data?.length === 0 ? <EmptyState icon={Dumbbell} title="No facilities" description="Add the clubhouse, gym, pool, courts…" /> : null}
      <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {list.data?.map((f) => (
          <li key={f.id}>
            <Card className="h-full">
              <CardHeader className="flex-row items-start justify-between gap-2">
                <div className="grid gap-1">
                  <CardTitle>{f.name}</CardTitle>
                  <div className="flex flex-wrap gap-1">
                    <Badge variant="secondary">{humanize(f.kind)}</Badge>
                    <Badge variant={f.status === "ACTIVE" ? "success" : "outline"}>{humanize(f.status)}</Badge>
                  </div>
                </div>
                {canManage ? (
                  <Button variant="ghost" size="icon" onClick={() => setEditing({ facility: f })} aria-label={`Edit ${f.name}`}>
                    <Pencil aria-hidden="true" />
                  </Button>
                ) : null}
              </CardHeader>
              <CardContent>
                <dl className="grid grid-cols-2 gap-x-3 gap-y-1 text-sm">
                  <dt className="text-muted-foreground">Capacity</dt>
                  <dd>{f.capacity}</dd>
                  <dt className="text-muted-foreground">Charge</dt>
                  <dd>{f.chargeable ? `${formatPaise(f.chargePaise)} / slot` : "Free"}</dd>
                  <dt className="text-muted-foreground">Slot</dt>
                  <dd>{f.bookingRules?.slotMinutes ?? "—"} min</dd>
                  <dt className="text-muted-foreground">Book ahead</dt>
                  <dd>{f.bookingRules?.maxAdvanceDays ?? "—"} days</dd>
                  <dt className="text-muted-foreground">Per flat / week</dt>
                  <dd>{f.bookingRules?.maxPerFlatPerWeek ?? "—"}</dd>
                </dl>
              </CardContent>
            </Card>
          </li>
        ))}
      </ul>
      {editing ? <FacilityDialog facility={editing.facility} onClose={() => setEditing(null)} /> : null}
    </SocietyPage>
  );
}

function FacilityDialog({ facility, onClose }: { facility?: Facility; onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const { activeSocietyId } = usePermissions();
  const form = useForm<FacilityInput, unknown, FacilityValues>({
    resolver: zodResolver(facilitySchema),
    defaultValues: {
      kind: facility?.kind ?? "CLUBHOUSE",
      name: facility?.name ?? "",
      capacity: facility?.capacity ?? 20,
      chargeable: facility?.chargeable ?? false,
      chargeRupees: paiseToRupees(facility?.chargePaise),
      slotMinutes: facility?.bookingRules?.slotMinutes ?? 60,
      maxAdvanceDays: facility?.bookingRules?.maxAdvanceDays ?? 14,
      maxPerFlatPerWeek: facility?.bookingRules?.maxPerFlatPerWeek ?? 3,
      active: (facility?.status ?? "ACTIVE") === "ACTIVE",
    },
  });
  const chargeable = form.watch("chargeable");
  const save = useMutation({
    mutationFn: (v: FacilityValues) => (facility ? api.society.facilities.update(facility.id, toFacilityBody(v)) : api.society.facilities.create(toFacilityBody(v))),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.facilities(activeSocietyId) });
      onClose();
    },
    onError: (err) => applyServerFieldErrors(err, form.setError, ["kind", "name", "capacity", "chargeable"]),
  });
  const e = form.formState.errors;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={facility ? `Edit ${facility.name}` : "Add facility"}>
      <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="grid gap-4" noValidate>
        {save.error ? <ProblemAlert error={save.error} /> : null}
        <div className="grid grid-cols-2 gap-3">
          <Field id="fac-name" label="Name" required error={e.name?.message} className="col-span-2">
            {(a) => <Input {...a} {...form.register("name")} />}
          </Field>
          <Field id="fac-kind" label="Kind" required>
            {(a) => (
              <Select {...a} {...form.register("kind")}>
                {FACILITY_KINDS.map((k) => (
                  <option key={k} value={k}>
                    {humanize(k)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field id="fac-capacity" label="Capacity" required error={e.capacity?.message}>
            {(a) => <Input {...a} type="number" min={1} {...form.register("capacity")} />}
          </Field>
        </div>
        <fieldset className="grid gap-3 rounded-md border p-3">
          <legend className="px-1 text-sm font-medium">Charges</legend>
          <div className="flex items-center gap-2">
            <Checkbox id="fac-chargeable" {...form.register("chargeable")} />
            <Label htmlFor="fac-chargeable">Chargeable</Label>
          </div>
          {chargeable ? (
            <Field id="fac-charge" label="Charge per slot (₹)" required error={e.chargeRupees?.message}>
              {(a) => <Input {...a} type="number" min={0} step="0.01" {...form.register("chargeRupees")} />}
            </Field>
          ) : null}
        </fieldset>
        <fieldset className="grid grid-cols-3 gap-3 rounded-md border p-3">
          <legend className="px-1 text-sm font-medium">Booking rules</legend>
          <Field id="fac-slot" label="Slot (min)" required error={e.slotMinutes?.message}>
            {(a) => <Input {...a} type="number" min={15} step={15} {...form.register("slotMinutes")} />}
          </Field>
          <Field id="fac-advance" label="Book ahead (days)" required error={e.maxAdvanceDays?.message}>
            {(a) => <Input {...a} type="number" min={0} {...form.register("maxAdvanceDays")} />}
          </Field>
          <Field id="fac-week" label="Per flat / week" required error={e.maxPerFlatPerWeek?.message}>
            {(a) => <Input {...a} type="number" min={0} {...form.register("maxPerFlatPerWeek")} />}
          </Field>
        </fieldset>
        <div className="flex items-center gap-2">
          <Checkbox id="fac-active" {...form.register("active")} />
          <Label htmlFor="fac-active">Open for bookings</Label>
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={save.isPending}>
            {facility ? "Save" : "Add facility"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
