"use client";

import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { SocietyProfile, SocietySettingsValues } from "@societyos/api-client";
import { qk, useApi } from "@societyos/api-client/react";
import { Alert, Badge, Button, Card, CardContent, CardDescription, CardHeader, CardTitle, Field, Input, Switch, Textarea } from "@societyos/ui";
import { ProblemAlert, QueryState, applyServerFieldErrors, blankToUndefined, humanize, usePermissions } from "@societyos/shared";
import { SocietyPage } from "@/components/society-page";

const profileSchema = z.object({
  name: z.string().trim().min(1, "Enter the society name").max(150),
  legalName: z.string().trim().max(200).optional(),
  address: z.string().trim().max(500).optional(),
  city: z.string().trim().min(1, "Enter the city").max(80),
  state: z.string().trim().min(1, "Enter the state").max(80),
  pin: z
    .string()
    .trim()
    .regex(/^(\d{6})?$/, "PIN code has 6 digits")
    .optional(),
  timezone: z.string().trim().min(1, "Enter an IANA time zone, e.g. Asia/Kolkata"),
});

const n = (min: number, max: number) => z.coerce.number<string | number>().int("Whole number").min(min, `At least ${min}`).max(max, `At most ${max}`);
const settingsSchema = z.object({
  gateApprovalTimeoutSeconds: n(10, 3600),
  visitorRetentionDays: n(1, 3650),
  gateLogRetentionDays: n(1, 3650),
  notificationRetentionDays: n(1, 3650),
  billingDueDay: n(1, 28),
  lateFeeGraceDays: n(0, 90),
  directoryEnabled: z.boolean(),
  features: z.record(z.string(), z.boolean()),
});

export default function ProfilePage() {
  const api = useApi();
  const { can, activeSocietyId } = usePermissions();
  const canManage = can("society:manage");
  const canSettings = canManage || can("settings:manage");
  const profile = useQuery({ queryKey: qk.profile(activeSocietyId), queryFn: () => api.society.profile.get(), enabled: !!activeSocietyId });
  const settings = useQuery({ queryKey: qk.settings(activeSocietyId), queryFn: () => api.society.settings.get(), enabled: !!activeSocietyId });

  return (
    <SocietyPage title="Profile & settings" description="Society details and operating rules." anyOf={["society:view", "society:manage"]}>
      <div className="grid gap-6">
        <QueryState isLoading={profile.isLoading} error={profile.error} onRetry={() => void profile.refetch()} />
        {profile.data ? <ProfileForm key={profile.data.id} profile={profile.data} readOnly={!canManage} /> : null}
        <QueryState isLoading={settings.isLoading} error={settings.error} onRetry={() => void settings.refetch()} />
        {settings.data ? <SettingsForm key={settings.data.societyId} values={settings.data.settings} readOnly={!canSettings} /> : null}
      </div>
    </SocietyPage>
  );
}

function ProfileForm({ profile, readOnly }: { profile: SocietyProfile; readOnly: boolean }) {
  const api = useApi();
  const qc = useQueryClient();
  const { activeSocietyId } = usePermissions();
  const form = useForm<z.infer<typeof profileSchema>>({
    resolver: zodResolver(profileSchema),
    defaultValues: {
      name: profile.name,
      legalName: profile.legalName ?? "",
      address: profile.address ?? "",
      city: profile.city,
      state: profile.state,
      pin: profile.pin ?? "",
      timezone: profile.timezone || "Asia/Kolkata",
    },
  });
  const save = useMutation({
    mutationFn: (v: z.infer<typeof profileSchema>) =>
      api.society.profile.update({
        name: v.name,
        legalName: blankToUndefined(v.legalName) ?? null,
        address: blankToUndefined(v.address) ?? null,
        city: v.city,
        state: v.state,
        pin: blankToUndefined(v.pin) ?? null,
        timezone: v.timezone,
      }),
    onSuccess: (p) => {
      qc.setQueryData(qk.profile(activeSocietyId), p);
      void qc.invalidateQueries({ queryKey: qk.societies });
      form.reset(form.getValues());
    },
    onError: (err) => applyServerFieldErrors(err, form.setError, ["name", "legalName", "address", "city", "state", "pin", "timezone"]),
  });
  const e = form.formState.errors;
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          Society profile <Badge variant={profile.status === "ACTIVE" ? "success" : "secondary"}>{humanize(profile.status)}</Badge>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="grid gap-4" noValidate>
          <fieldset disabled={readOnly} className="grid gap-4 sm:grid-cols-2">
            <Field id="p-name" label="Name" required error={e.name?.message}>
              {(a) => <Input {...a} {...form.register("name")} />}
            </Field>
            <Field id="p-legal" label="Legal name" error={e.legalName?.message}>
              {(a) => <Input {...a} {...form.register("legalName")} />}
            </Field>
            <Field id="p-address" label="Address" error={e.address?.message} className="sm:col-span-2">
              {(a) => <Textarea {...a} rows={2} {...form.register("address")} />}
            </Field>
            <Field id="p-city" label="City" required error={e.city?.message}>
              {(a) => <Input {...a} {...form.register("city")} />}
            </Field>
            <Field id="p-state" label="State" required error={e.state?.message}>
              {(a) => <Input {...a} {...form.register("state")} />}
            </Field>
            <Field id="p-pin" label="PIN code" error={e.pin?.message}>
              {(a) => <Input {...a} inputMode="numeric" maxLength={6} {...form.register("pin")} />}
            </Field>
            <Field id="p-tz" label="Time zone" required error={e.timezone?.message}>
              {(a) => <Input {...a} {...form.register("timezone")} />}
            </Field>
          </fieldset>
          {save.error ? <ProblemAlert error={save.error} /> : null}
          {save.isSuccess && !form.formState.isDirty ? <Alert variant="success">Profile saved.</Alert> : null}
          {!readOnly ? (
            <div>
              <Button type="submit" loading={save.isPending} disabled={!form.formState.isDirty}>
                Save profile
              </Button>
            </div>
          ) : null}
        </form>
      </CardContent>
    </Card>
  );
}

function SettingsForm({ values, readOnly }: { values: SocietySettingsValues; readOnly: boolean }) {
  const api = useApi();
  const qc = useQueryClient();
  const { activeSocietyId } = usePermissions();
  type In = z.input<typeof settingsSchema>;
  type Out = z.output<typeof settingsSchema>;
  const form = useForm<In, unknown, Out>({
    resolver: zodResolver(settingsSchema),
    defaultValues: { ...values, features: values.features ?? {} },
  });
  const save = useMutation({
    // PUT is a partial patch: send only what changed.
    mutationFn: (v: Out) => {
      const dirty = form.formState.dirtyFields as Partial<Record<keyof Out, unknown>>;
      const patch: Partial<Out> = {};
      for (const k of Object.keys(v) as (keyof Out)[]) if (dirty[k]) (patch as Record<string, unknown>)[k] = v[k];
      return api.society.settings.update(patch);
    },
    onSuccess: (s) => {
      qc.setQueryData(qk.settings(activeSocietyId), s);
      form.reset({ ...s.settings, features: s.settings.features ?? {} });
    },
    onError: (err) => applyServerFieldErrors(err, form.setError, Object.keys(settingsSchema.shape)),
  });
  const e = form.formState.errors;
  const features = Object.keys(values.features ?? {}).sort();
  const numberField = (name: Exclude<keyof Out, "directoryEnabled" | "features">, label: string, hint?: string) => (
    <Field id={`s-${name}`} label={label} hint={hint} required error={e[name]?.message}>
      {(a) => <Input {...a} type="number" {...form.register(name)} />}
    </Field>
  );
  return (
    <Card>
      <CardHeader>
        <CardTitle>Settings</CardTitle>
        <CardDescription>Gate, retention (DPDP) and billing rules for this society.</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="grid gap-5" noValidate>
          <fieldset disabled={readOnly} className="grid gap-5">
            <div className="grid gap-4 sm:grid-cols-3">
              {numberField("gateApprovalTimeoutSeconds", "Gate approval timeout (s)", "How long a resident has to approve a visitor")}
              {numberField("billingDueDay", "Billing due day", "Day of month, 1–28")}
              {numberField("lateFeeGraceDays", "Late fee grace (days)")}
              {numberField("visitorRetentionDays", "Visitor data kept (days)")}
              {numberField("gateLogRetentionDays", "Gate logs kept (days)")}
              {numberField("notificationRetentionDays", "Notifications kept (days)")}
            </div>
            <Switch label="Resident directory enabled (residents can opt in to be listed)" {...form.register("directoryEnabled")} />
            {features.length ? (
              <fieldset className="grid gap-2 rounded-md border p-3">
                <legend className="px-1 text-sm font-medium">Features</legend>
                <div className="grid gap-2 sm:grid-cols-2">
                  {features.map((f) => (
                    <Switch key={f} label={humanize(f.replace(/([a-z])([A-Z])/g, "$1_$2"))} {...form.register(`features.${f}` as const)} />
                  ))}
                </div>
              </fieldset>
            ) : null}
          </fieldset>
          {save.error ? <ProblemAlert error={save.error} /> : null}
          {save.isSuccess && !form.formState.isDirty ? <Alert variant="success">Settings saved.</Alert> : null}
          {!readOnly ? (
            <div>
              <Button type="submit" loading={save.isPending} disabled={!form.formState.isDirty}>
                Save settings
              </Button>
            </div>
          ) : null}
        </form>
      </CardContent>
    </Card>
  );
}
