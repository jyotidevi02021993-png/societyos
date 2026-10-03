"use client";

import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, CheckCircle2, Plus, UserMinus, Users } from "lucide-react";
import { STAFF_KINDS, toE164, type DomesticStaff } from "@societyos/api-client";
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
import { applyServerFieldErrors, humanize } from "../forms";
import { ProblemAlert, QueryState } from "../problem-alert";
import { usePermissions } from "../session";

export const staffSchema = z.object({
  name: z.string().trim().min(2, "Enter the name").max(120),
  kind: z.enum(STAFF_KINDS),
  phone: z
    .string()
    .transform((v, ctx) => {
      const e164 = toE164(v);
      if (!e164) {
        ctx.addIssue({ code: "custom", message: "Enter a valid mobile number" });
        return z.NEVER;
      }
      return e164;
    }),
});
type StaffForm = z.input<typeof staffSchema>;
type StaffValues = z.output<typeof staffSchema>;

const kycVariant = { PENDING: "warning", VERIFIED: "success", REJECTED: "destructive" } as const;

type PendingAction = { kind: "unlink" | "block" | "unblock"; staff: DomesticStaff };

/**
 * Domestic staff of one flat (or all flats for admins). Household managers can add staff and
 * remove them from their flat; society admins (canBlock) can also block/unblock at the gate.
 */
export function StaffPanel({ flatId, canManage, canBlock = false }: { flatId?: string; canManage: boolean; canBlock?: boolean }) {
  const api = useApi();
  const qc = useQueryClient();
  const { activeSocietyId } = usePermissions();
  const list = useQuery({ queryKey: qk.staff(activeSocietyId, flatId), queryFn: () => api.society.staff.list(flatId) });
  const [adding, setAdding] = React.useState(false);
  const [pending, setPending] = React.useState<PendingAction | null>(null);
  const invalidate = () => qc.invalidateQueries({ queryKey: qk.staff(activeSocietyId).slice(0, 3) });

  const form = useForm<StaffForm, unknown, StaffValues>({
    resolver: zodResolver(staffSchema),
    defaultValues: { name: "", kind: "MAID", phone: "" },
  });

  const add = useMutation({
    mutationFn: (v: StaffValues) => api.society.staff.add({ name: v.name, kind: v.kind, phone: v.phone, flatIds: [flatId!] }),
    onSuccess: () => {
      form.reset();
      setAdding(false);
      void invalidate();
    },
    onError: (err) => applyServerFieldErrors(err, form.setError, ["name", "kind", "phone"]),
  });

  const act = useMutation({
    mutationFn: async ({ kind, staff }: PendingAction) => {
      if (kind === "unlink") return api.society.staff.unlinkFlat(staff.id, flatId!);
      return api.society.staff.update(staff.id, {
        name: staff.name,
        kind: staff.kind,
        status: kind === "block" ? "BLOCKED" : "ACTIVE",
      });
    },
    onSuccess: () => {
      setPending(null);
      void invalidate();
    },
  });

  const idp = `staff-${flatId ?? "all"}`;
  const confirmText: Record<PendingAction["kind"], { title: string; body: string; label: string }> = {
    unlink: { title: "Remove from this flat?", body: "They will no longer be allowed in for this flat.", label: "Remove" },
    block: { title: "Block at the gate?", body: "Guards will stop entry for this person for every flat.", label: "Block" },
    unblock: { title: "Unblock?", body: "Entry will be allowed again for their flats.", label: "Unblock" },
  };

  return (
    <Card>
      <CardHeader className="flex-row items-start justify-between gap-3">
        <div className="grid gap-1">
          <CardTitle>Domestic staff</CardTitle>
          <CardDescription>Maids, cooks, drivers and nannies with daily entry.</CardDescription>
        </div>
        {canManage && flatId ? (
          <Button size="sm" variant="outline" onClick={() => setAdding((v) => !v)} aria-expanded={adding} aria-controls={`${idp}-form`}>
            <Plus aria-hidden="true" /> Add staff
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
            <Field id={`${idp}-name`} label="Name" required error={form.formState.errors.name?.message}>
              {(a) => <Input {...a} autoComplete="off" {...form.register("name")} />}
            </Field>
            <Field id={`${idp}-kind`} label="Role" required>
              {(a) => (
                <Select {...a} {...form.register("kind")}>
                  {STAFF_KINDS.map((k) => (
                    <option key={k} value={k}>
                      {humanize(k)}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
            <Field id={`${idp}-phone`} label="Mobile" required error={form.formState.errors.phone?.message}>
              {(a) => <Input {...a} type="tel" inputMode="tel" autoComplete="off" placeholder="98765 43210" {...form.register("phone")} />}
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

        <QueryState isLoading={list.isLoading} error={list.error} onRetry={() => void list.refetch()} loadingLabel="Loading staff…" />
        {list.data && list.data.length === 0 ? <EmptyState icon={Users} title="No domestic staff" /> : null}
        {list.data && list.data.length > 0 ? (
          <Table caption="Domestic staff">
            <THead>
              <TR>
                <TH>Name</TH>
                <TH>Role</TH>
                <TH>Mobile</TH>
                <TH>KYC</TH>
                <TH>Status</TH>
                {!flatId ? <TH>Flats</TH> : null}
                {canManage ? <TH className="text-right">Actions</TH> : null}
              </TR>
            </THead>
            <TBody>
              {list.data.map((s) => (
                <TR key={s.id}>
                  <TD className="font-medium">{s.name}</TD>
                  <TD>{humanize(s.kind)}</TD>
                  <TD className="font-mono text-xs">{s.phoneMasked ?? "—"}</TD>
                  <TD>
                    <Badge variant={kycVariant[s.kycStatus] ?? "secondary"}>{humanize(s.kycStatus)}</Badge>
                  </TD>
                  <TD>
                    <Badge variant={s.status === "BLOCKED" ? "destructive" : "success"}>{humanize(s.status)}</Badge>
                  </TD>
                  {!flatId ? <TD>{s.flatIds.length}</TD> : null}
                  {canManage ? (
                    <TD className="text-right">
                      <div className="flex justify-end gap-1">
                        {flatId ? (
                          <Button size="sm" variant="ghost" onClick={() => setPending({ kind: "unlink", staff: s })} aria-label={`Remove ${s.name} from this flat`}>
                            <UserMinus aria-hidden="true" /> Remove
                          </Button>
                        ) : null}
                        {canBlock ? (
                          s.status === "BLOCKED" ? (
                            <Button size="sm" variant="ghost" onClick={() => setPending({ kind: "unblock", staff: s })} aria-label={`Unblock ${s.name}`}>
                              <CheckCircle2 aria-hidden="true" /> Unblock
                            </Button>
                          ) : (
                            <Button size="sm" variant="ghost" onClick={() => setPending({ kind: "block", staff: s })} aria-label={`Block ${s.name}`}>
                              <Ban aria-hidden="true" /> Block
                            </Button>
                          )
                        ) : null}
                      </div>
                    </TD>
                  ) : null}
                </TR>
              ))}
            </TBody>
          </Table>
        ) : null}
      </CardContent>
      <ConfirmDialog
        open={!!pending}
        onOpenChange={(o) => {
          if (!o) {
            setPending(null);
            act.reset();
          }
        }}
        title={pending ? confirmText[pending.kind].title : ""}
        description={pending ? `${pending.staff.name}: ${confirmText[pending.kind].body}` : ""}
        confirmLabel={pending ? confirmText[pending.kind].label : "Confirm"}
        destructive={pending?.kind !== "unblock"}
        loading={act.isPending}
        error={act.error ? errorMessage(act.error) : null}
        onConfirm={() => pending && act.mutate(pending)}
      />
    </Card>
  );
}
