"use client";

import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { MEMBER_KINDS, toE164, type Flat, type MemberAddBody, type Membership } from "@societyos/api-client";
import { useApi } from "@societyos/api-client/react";
import { Button, Checkbox, Dialog, Field, Input, Label, Select } from "@societyos/ui";
import { ProblemAlert, applyServerFieldErrors, humanize, usePermissions } from "@societyos/shared";

export const memberSchema = z.object({
  flatId: z.string().min(1, "Choose a flat"),
  name: z.string().trim().min(2, "Enter the resident's name").max(120),
  phone: z.string().transform((v, ctx) => {
    const e164 = toE164(v);
    if (!e164) {
      ctx.addIssue({ code: "custom", message: "Enter a valid mobile number, e.g. 98765 43210" });
      return z.NEVER;
    }
    return e164;
  }),
  kind: z.enum(MEMBER_KINDS),
  fromDate: z.string().optional(),
  isPrimary: z.boolean(),
});

export type MemberFormInput = z.input<typeof memberSchema>;
export type MemberFormValues = z.output<typeof memberSchema>;

export function toMemberBody(v: MemberFormValues): MemberAddBody {
  return { flatId: v.flatId, name: v.name, phone: v.phone, kind: v.kind, isPrimary: v.isPrimary, ...(v.fromDate ? { fromDate: v.fromDate } : {}) };
}

/** Add a resident by phone + name + kind. The pure form is exported for tests. */
export function AddMemberForm({
  flats,
  defaultFlatId,
  onSubmit,
  onCancel,
  submitting,
  error,
  formRef,
}: {
  flats: Pick<Flat, "id" | "label">[];
  defaultFlatId?: string;
  onSubmit: (body: MemberAddBody) => void;
  onCancel?: () => void;
  submitting?: boolean;
  error?: unknown;
  formRef?: React.Ref<{ setServerError: (err: unknown) => void }>;
}) {
  const form = useForm<MemberFormInput, unknown, MemberFormValues>({
    resolver: zodResolver(memberSchema),
    defaultValues: { flatId: defaultFlatId || flats[0]?.id || "", name: "", phone: "", kind: "OWNER", fromDate: "", isPrimary: false },
  });
  React.useImperativeHandle(formRef, () => ({
    setServerError: (err: unknown) => applyServerFieldErrors(err, form.setError, ["flatId", "name", "phone", "kind", "fromDate"]),
  }));
  const e = form.formState.errors;
  return (
    <form onSubmit={form.handleSubmit((v) => onSubmit(toMemberBody(v)))} className="grid gap-4" noValidate aria-label="Add resident">
      {error ? <ProblemAlert error={error} /> : null}
      <Field id="member-flat-id" label="Flat" required error={e.flatId?.message}>
        {(a) => (
          <Select {...a} {...form.register("flatId")}>
            {flats.map((f) => (
              <option key={f.id} value={f.id}>
                {f.label}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Field id="member-name" label="Name" required error={e.name?.message}>
        {(a) => <Input {...a} autoComplete="off" {...form.register("name")} />}
      </Field>
      <Field id="member-phone" label="Mobile number" required hint="They sign in with this number (OTP)." error={e.phone?.message}>
        {(a) => <Input {...a} type="tel" inputMode="tel" autoComplete="off" placeholder="98765 43210" {...form.register("phone")} />}
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field id="member-kind" label="Kind" required error={e.kind?.message}>
          {(a) => (
            <Select {...a} {...form.register("kind")}>
              {MEMBER_KINDS.map((k) => (
                <option key={k} value={k}>
                  {humanize(k)}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field id="member-from" label="From date" error={e.fromDate?.message}>
          {(a) => <Input {...a} type="date" {...form.register("fromDate")} />}
        </Field>
      </div>
      <div className="flex items-center gap-2">
        <Checkbox id="member-primary" {...form.register("isPrimary")} />
        <Label htmlFor="member-primary">Primary contact for this flat</Label>
      </div>
      <div className="flex justify-end gap-2">
        {onCancel ? (
          <Button variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        ) : null}
        <Button type="submit" loading={submitting}>
          Add resident
        </Button>
      </div>
    </form>
  );
}

export function AddMemberDialog({ flats, defaultFlatId, onClose }: { flats: Flat[]; defaultFlatId?: string; onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const { activeSocietyId } = usePermissions();
  const ref = React.useRef<{ setServerError: (err: unknown) => void }>(null);
  const add = useMutation<Membership, unknown, MemberAddBody>({
    mutationFn: (body) => api.society.members.add(body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["society", activeSocietyId, "members"] });
      onClose();
    },
    onError: (err) => ref.current?.setServerError(err),
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title="Add resident" description="Links a person to a flat. If they are new to SocietyOS, an account is created for their number.">
      <AddMemberForm flats={flats} defaultFlatId={defaultFlatId} onSubmit={(b) => add.mutate(b)} onCancel={onClose} submitting={add.isPending} error={add.error} formRef={ref} />
    </Dialog>
  );
}
