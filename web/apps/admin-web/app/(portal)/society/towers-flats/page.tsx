"use client";

import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Building, Pencil, Plus } from "lucide-react";
import { FLAT_STATUSES, type Flat, type Tower } from "@societyos/api-client";
import { useApi } from "@societyos/api-client/react";
import { Badge, Button, Card, CardContent, CardHeader, CardTitle, Dialog, EmptyState, Field, Input, Select, Table, TBody, TD, TH, THead, TR } from "@societyos/ui";
import { ProblemAlert, QueryState, applyServerFieldErrors, humanize, usePermissions } from "@societyos/shared";
import { SocietyPage, societyKey, useFlats, useTowers } from "@/components/society-page";

const towerSchema = z.object({
  name: z.string().trim().min(1, "Enter a name").max(80),
  code: z.string().trim().min(1, "Enter a short code, e.g. A").max(10).regex(/^[A-Za-z0-9-]+$/, "Letters, digits and - only"),
  floorsCount: z.coerce.number<string | number>().int("Whole number").min(0).max(200),
});

const optionalNumber = z
  .union([z.string(), z.number()])
  .transform((v) => (v === "" || v === null ? undefined : Number(v)))
  .refine((v) => v === undefined || (Number.isInteger(v) && v > 0), "Positive whole number");

const flatSchema = z.object({
  towerId: z.string().min(1, "Choose a tower"),
  number: z.string().trim().min(1, "Enter the flat number").max(20),
  floor: z.coerce.number<string | number>().int().min(0, "0 or more").max(200),
  areaSqft: optionalNumber,
  flatType: z.string().trim().max(20).optional(),
  status: z.enum(FLAT_STATUSES),
});

const statusVariant = { OCCUPIED: "success", VACANT: "secondary", UNDER_RENOVATION: "warning" } as const;

export default function TowersFlatsPage() {
  const { can } = usePermissions();
  const canManage = can("society:manage");
  const towers = useTowers();
  const [towerFilter, setTowerFilter] = React.useState<string>("");
  const flats = useFlats(towerFilter || undefined);
  const [towerDialog, setTowerDialog] = React.useState<{ tower?: Tower } | null>(null);
  const [flatDialog, setFlatDialog] = React.useState<{ flat?: Flat } | null>(null);
  const towerById = new Map((towers.data ?? []).map((t) => [t.id, t]));

  return (
    <SocietyPage
      title="Towers & flats"
      description="The blocks of the society and every flat in them."
      anyOf={["society:view"]}
      actions={
        canManage ? (
          <>
            <Button variant="outline" onClick={() => setTowerDialog({})}>
              <Plus aria-hidden="true" /> Tower
            </Button>
            <Button onClick={() => setFlatDialog({})} disabled={!towers.data?.length}>
              <Plus aria-hidden="true" /> Flat
            </Button>
          </>
        ) : null
      }
    >
      <div className="grid gap-6">
        <section aria-labelledby="towers-h" className="grid gap-3">
          <h2 id="towers-h" className="text-sm font-semibold text-muted-foreground">
            Towers
          </h2>
          <QueryState isLoading={towers.isLoading} error={towers.error} onRetry={() => void towers.refetch()} />
          {towers.data?.length === 0 ? (
            <EmptyState
              icon={Building}
              title="No towers yet"
              description="Add the first tower, or use Excel import to load the whole society."
              action={canManage ? <Button onClick={() => setTowerDialog({})}>Add tower</Button> : undefined}
            />
          ) : null}
          <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {towers.data?.map((t) => (
              <li key={t.id}>
                <Card>
                  <CardHeader className="flex-row items-start justify-between gap-2">
                    <div>
                      <CardTitle>{t.name}</CardTitle>
                      <p className="text-xs text-muted-foreground">
                        Code {t.code} · {t.floorsCount} floors
                      </p>
                    </div>
                    {canManage ? (
                      <Button variant="ghost" size="icon" onClick={() => setTowerDialog({ tower: t })} aria-label={`Edit tower ${t.name}`}>
                        <Pencil aria-hidden="true" />
                      </Button>
                    ) : null}
                  </CardHeader>
                </Card>
              </li>
            ))}
          </ul>
        </section>

        <section aria-labelledby="flats-h" className="grid gap-3">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <h2 id="flats-h" className="text-sm font-semibold text-muted-foreground">
              Flats {flats.data ? `(${flats.data.length})` : ""}
            </h2>
            <div className="grid gap-1">
              <label htmlFor="tower-filter" className="text-xs font-medium">
                Filter by tower
              </label>
              <Select id="tower-filter" value={towerFilter} onChange={(e) => setTowerFilter(e.target.value)} className="w-48">
                <option value="">All towers</option>
                {towers.data?.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
              </Select>
            </div>
          </div>
          <QueryState isLoading={flats.isLoading} error={flats.error} onRetry={() => void flats.refetch()} loadingLabel="Loading flats…" />
          {flats.data?.length === 0 ? <EmptyState title="No flats" description={towerFilter ? "This tower has no flats yet." : undefined} /> : null}
          {flats.data && flats.data.length > 0 ? (
            <Card>
              <Table caption="Flats">
                <THead>
                  <TR>
                    <TH>Flat</TH>
                    <TH>Tower</TH>
                    <TH>Floor</TH>
                    <TH>Type</TH>
                    <TH>Area (sq ft)</TH>
                    <TH>Status</TH>
                    {canManage ? <TH className="text-right">Actions</TH> : null}
                  </TR>
                </THead>
                <TBody>
                  {flats.data.map((f) => (
                    <TR key={f.id}>
                      <TD className="font-medium">{f.label}</TD>
                      <TD>{towerById.get(f.towerId)?.name ?? "—"}</TD>
                      <TD>{f.floor}</TD>
                      <TD>{f.flatType ?? "—"}</TD>
                      <TD>{f.areaSqft ?? "—"}</TD>
                      <TD>
                        <Badge variant={statusVariant[f.status] ?? "secondary"}>{humanize(f.status)}</Badge>
                      </TD>
                      {canManage ? (
                        <TD className="text-right">
                          <Button variant="ghost" size="sm" onClick={() => setFlatDialog({ flat: f })} aria-label={`Edit flat ${f.label}`}>
                            <Pencil aria-hidden="true" /> Edit
                          </Button>
                        </TD>
                      ) : null}
                    </TR>
                  ))}
                </TBody>
              </Table>
            </Card>
          ) : null}
        </section>
      </div>

      {towerDialog ? <TowerDialog tower={towerDialog.tower} onClose={() => setTowerDialog(null)} /> : null}
      {flatDialog ? (
        <FlatDialog flat={flatDialog.flat} towers={towers.data ?? []} defaultTowerId={towerFilter} onClose={() => setFlatDialog(null)} />
      ) : null}
    </SocietyPage>
  );
}

function TowerDialog({ tower, onClose }: { tower?: Tower; onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const { activeSocietyId } = usePermissions();
  const form = useForm<z.input<typeof towerSchema>, unknown, z.output<typeof towerSchema>>({
    resolver: zodResolver(towerSchema),
    defaultValues: { name: tower?.name ?? "", code: tower?.code ?? "", floorsCount: tower?.floorsCount ?? 10 },
  });
  const save = useMutation({
    mutationFn: (v: z.output<typeof towerSchema>) => (tower ? api.society.towers.update(tower.id, v) : api.society.towers.create(v)),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: societyKey(activeSocietyId, "towers") });
      onClose();
    },
    onError: (err) => applyServerFieldErrors(err, form.setError, ["name", "code", "floorsCount"]),
  });
  const e = form.formState.errors;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={tower ? `Edit ${tower.name}` : "Add tower"}>
      <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="grid gap-4" noValidate>
        {save.error ? <ProblemAlert error={save.error} /> : null}
        <Field id="tower-name" label="Name" required error={e.name?.message}>
          {(a) => <Input {...a} placeholder="Tower A" {...form.register("name")} />}
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field id="tower-code" label="Code" required hint="Used in flat labels, e.g. A-101" error={e.code?.message}>
            {(a) => <Input {...a} {...form.register("code")} />}
          </Field>
          <Field id="tower-floors" label="Floors" required error={e.floorsCount?.message}>
            {(a) => <Input {...a} type="number" min={0} max={200} {...form.register("floorsCount")} />}
          </Field>
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={save.isPending}>
            {tower ? "Save" : "Add tower"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function FlatDialog({ flat, towers, defaultTowerId, onClose }: { flat?: Flat; towers: Tower[]; defaultTowerId?: string; onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const { activeSocietyId } = usePermissions();
  const form = useForm<z.input<typeof flatSchema>, unknown, z.output<typeof flatSchema>>({
    resolver: zodResolver(flatSchema),
    defaultValues: {
      towerId: flat?.towerId ?? defaultTowerId ?? towers[0]?.id ?? "",
      number: flat?.number ?? "",
      floor: flat?.floor ?? 0,
      areaSqft: flat?.areaSqft ?? "",
      flatType: flat?.flatType ?? "",
      status: flat?.status ?? "VACANT",
    },
  });
  const save = useMutation({
    mutationFn: (v: z.output<typeof flatSchema>) => {
      const flatType = v.flatType ? v.flatType : null;
      const areaSqft = v.areaSqft ?? null;
      return flat
        ? api.society.flats.update(flat.id, { floor: v.floor, areaSqft, flatType, status: v.status })
        : api.society.flats.create({ towerId: v.towerId, number: v.number, floor: v.floor, areaSqft, flatType });
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: societyKey(activeSocietyId, "flats") });
      onClose();
    },
    onError: (err) => applyServerFieldErrors(err, form.setError, ["towerId", "number", "floor", "areaSqft", "flatType", "status"]),
  });
  const e = form.formState.errors;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={flat ? `Edit flat ${flat.label}` : "Add flat"}>
      <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="grid gap-4" noValidate>
        {save.error ? <ProblemAlert error={save.error} /> : null}
        <div className="grid grid-cols-2 gap-3">
          <Field id="flat-tower" label="Tower" required error={e.towerId?.message}>
            {(a) => (
              <Select {...a} disabled={!!flat} {...form.register("towerId")}>
                {towers.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field id="flat-number" label="Flat number" required error={e.number?.message}>
            {(a) => <Input {...a} disabled={!!flat} placeholder="101" {...form.register("number")} />}
          </Field>
          <Field id="flat-floor" label="Floor" required error={e.floor?.message}>
            {(a) => <Input {...a} type="number" min={0} {...form.register("floor")} />}
          </Field>
          <Field id="flat-area" label="Area (sq ft)" error={e.areaSqft?.message}>
            {(a) => <Input {...a} type="number" min={1} {...form.register("areaSqft")} />}
          </Field>
          <Field id="flat-type" label="Type" hint="e.g. 2BHK" error={e.flatType?.message}>
            {(a) => <Input {...a} {...form.register("flatType")} />}
          </Field>
          {flat ? (
            <Field id="flat-status" label="Status" required>
              {(a) => (
                <Select {...a} {...form.register("status")}>
                  {FLAT_STATUSES.map((s) => (
                    <option key={s} value={s}>
                      {humanize(s)}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
          ) : null}
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={save.isPending}>
            {flat ? "Save" : "Add flat"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
