"use client";

import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Car, Plus, Trash2 } from "lucide-react";
import { VEHICLE_KINDS, type Vehicle } from "@societyos/api-client";
import { qk, useApi } from "@societyos/api-client/react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  ConfirmDialog,
  EmptyState,
  Field,
  Input,
  Select,
  Table,
  TBody,
  TD,
  TH,
  THead,
  TR,
} from "@societyos/ui";
import { errorMessage } from "../deps";
import { applyServerFieldErrors, blankToUndefined, humanize } from "../forms";
import { ProblemAlert, QueryState } from "../problem-alert";
import { usePermissions } from "../session";

export const vehicleSchema = z.object({
  regNo: z
    .string()
    .transform((v) => v.toUpperCase().replace(/[\s.]/g, ""))
    .pipe(z.string().regex(/^[A-Z0-9-]{4,15}$/, "Use the registration number, e.g. KA01AB1234")),
  kind: z.enum(VEHICLE_KINDS),
  rfidTag: z.string().max(64, "Too long").optional(),
});
type VehicleForm = z.input<typeof vehicleSchema>;
type VehicleValues = z.output<typeof vehicleSchema>;

export function VehiclesPanel({ flatId, canManage, title = "Vehicles" }: { flatId?: string; canManage: boolean; title?: string }) {
  const api = useApi();
  const qc = useQueryClient();
  const { activeSocietyId } = usePermissions();
  const key = qk.vehicles(activeSocietyId, flatId);
  const list = useQuery({ queryKey: key, queryFn: () => api.society.vehicles.list(flatId) });
  const [adding, setAdding] = React.useState(false);
  const [removing, setRemoving] = React.useState<Vehicle | null>(null);

  const form = useForm<VehicleForm, unknown, VehicleValues>({
    resolver: zodResolver(vehicleSchema),
    defaultValues: { regNo: "", kind: "CAR", rfidTag: "" },
  });

  const add = useMutation({
    mutationFn: (v: VehicleValues) =>
      api.society.vehicles.add({ flatId: flatId!, regNo: v.regNo, kind: v.kind, rfidTag: blankToUndefined(v.rfidTag) }),
    onSuccess: () => {
      form.reset({ regNo: "", kind: "CAR", rfidTag: "" });
      setAdding(false);
      void qc.invalidateQueries({ queryKey: qk.vehicles(activeSocietyId).slice(0, 3) });
    },
    onError: (err) => applyServerFieldErrors(err, form.setError, ["regNo", "kind", "rfidTag"]),
  });

  const remove = useMutation({
    mutationFn: (id: string) => api.society.vehicles.remove(id),
    onSuccess: () => {
      setRemoving(null);
      void qc.invalidateQueries({ queryKey: qk.vehicles(activeSocietyId).slice(0, 3) });
    },
  });

  const idp = `veh-${flatId ?? "all"}`;

  return (
    <Card>
      <CardHeader className="flex-row items-start justify-between gap-3">
        <div className="grid gap-1">
          <CardTitle>{title}</CardTitle>
          <CardDescription>Registered vehicles are recognised at the gate.</CardDescription>
        </div>
        {canManage && flatId ? (
          <Button size="sm" variant="outline" onClick={() => setAdding((v) => !v)} aria-expanded={adding} aria-controls={`${idp}-form`}>
            <Plus aria-hidden="true" /> Add vehicle
          </Button>
        ) : null}
      </CardHeader>
      <CardContent className="grid gap-4">
        {adding && flatId ? (
          <form
            id={`${idp}-form`}
            onSubmit={form.handleSubmit((v) => add.mutate(v))}
            className="grid gap-3 rounded-md border bg-muted/30 p-3 sm:grid-cols-4 sm:items-end"
            noValidate
          >
            <Field id={`${idp}-reg`} label="Registration no." required error={form.formState.errors.regNo?.message}>
              {(a) => <Input {...a} autoComplete="off" placeholder="KA01AB1234" {...form.register("regNo")} />}
            </Field>
            <Field id={`${idp}-kind`} label="Type" required>
              {(a) => (
                <Select {...a} {...form.register("kind")}>
                  {VEHICLE_KINDS.map((k) => (
                    <option key={k} value={k}>
                      {humanize(k)}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
            <Field id={`${idp}-rfid`} label="RFID tag" error={form.formState.errors.rfidTag?.message}>
              {(a) => <Input {...a} autoComplete="off" {...form.register("rfidTag")} />}
            </Field>
            <div className="flex gap-2">
              <Button type="submit" loading={add.isPending}>
                Save
              </Button>
              <Button type="button" variant="ghost" onClick={() => setAdding(false)}>
                Cancel
              </Button>
            </div>
            {add.error ? (
              <div className="sm:col-span-4">
                <ProblemAlert error={add.error} />
              </div>
            ) : null}
          </form>
        ) : null}

        <QueryState isLoading={list.isLoading} error={list.error} onRetry={() => void list.refetch()} loadingLabel="Loading vehicles…" />
        {list.data && list.data.length === 0 ? (
          <EmptyState icon={Car} title="No vehicles" description={canManage && flatId ? "Add the household's cars and bikes." : undefined} />
        ) : null}
        {list.data && list.data.length > 0 ? (
          <Table caption="Vehicles">
            <THead>
              <TR>
                <TH>Registration</TH>
                <TH>Type</TH>
                {!flatId ? <TH>Flat</TH> : null}
                <TH>RFID</TH>
                {canManage ? <TH className="text-right">Actions</TH> : null}
              </TR>
            </THead>
            <TBody>
              {list.data.map((v) => (
                <TR key={v.id}>
                  <TD className="font-mono font-medium">{v.regNo}</TD>
                  <TD>
                    <Badge variant="secondary">{humanize(v.kind)}</Badge>
                  </TD>
                  {!flatId ? <TD>{v.flatLabel ?? "—"}</TD> : null}
                  <TD className="font-mono text-xs">{v.rfidTag ?? "—"}</TD>
                  {canManage ? (
                    <TD className="text-right">
                      <Button size="sm" variant="ghost" onClick={() => setRemoving(v)} aria-label={`Remove vehicle ${v.regNo}`}>
                        <Trash2 aria-hidden="true" /> Remove
                      </Button>
                    </TD>
                  ) : null}
                </TR>
              ))}
            </TBody>
          </Table>
        ) : null}
      </CardContent>
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => {
          if (!o) {
            setRemoving(null);
            remove.reset();
          }
        }}
        title="Remove vehicle?"
        description={`${removing?.regNo ?? ""} will no longer be recognised at the gate.`}
        confirmLabel="Remove"
        destructive
        loading={remove.isPending}
        error={remove.error ? errorMessage(remove.error) : null}
        onConfirm={() => removing && remove.mutate(removing.id)}
      />
    </Card>
  );
}
