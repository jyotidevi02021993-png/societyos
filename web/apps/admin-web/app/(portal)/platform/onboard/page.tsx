"use client";

import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { SocietyProfile } from "@societyos/api-client";
import { qk, useApi } from "@societyos/api-client/react";
import { Alert, Button, Card, CardContent, Field, Input, PageHeader, Textarea } from "@societyos/ui";
import { ProblemAlert, applyServerFieldErrors, blankToUndefined, usePermissions, useSwitchSociety } from "@societyos/shared";

const schema = z.object({
  name: z.string().trim().min(2, "Enter the society name").max(150),
  legalName: z.string().trim().max(200).optional(),
  address: z.string().trim().max(500).optional(),
  city: z.string().trim().min(1, "Enter the city").max(80),
  state: z.string().trim().min(1, "Enter the state").max(80),
  pin: z
    .string()
    .trim()
    .regex(/^(\d{6})?$/, "PIN code has 6 digits")
    .optional(),
  timezone: z.string().trim().min(1).default("Asia/Kolkata"),
});

export default function OnboardPage() {
  const api = useApi();
  const qc = useQueryClient();
  const { isPlatformAdmin } = usePermissions();
  const switcher = useSwitchSociety();
  const [created, setCreated] = React.useState<SocietyProfile | null>(null);
  const form = useForm<z.input<typeof schema>, unknown, z.output<typeof schema>>({
    resolver: zodResolver(schema),
    defaultValues: { name: "", legalName: "", address: "", city: "", state: "", pin: "", timezone: "Asia/Kolkata" },
  });
  const onboard = useMutation({
    mutationFn: (v: z.output<typeof schema>) =>
      api.society.societies.onboard({
        name: v.name,
        legalName: blankToUndefined(v.legalName),
        address: blankToUndefined(v.address),
        city: v.city,
        state: v.state,
        pin: blankToUndefined(v.pin),
        timezone: v.timezone,
      }),
    onSuccess: (s) => {
      setCreated(s);
      form.reset();
      void qc.invalidateQueries({ queryKey: qk.societies });
    },
    onError: (err) => applyServerFieldErrors(err, form.setError, ["name", "legalName", "address", "city", "state", "pin", "timezone"]),
  });

  if (!isPlatformAdmin) {
    return (
      <>
        <PageHeader title="Onboard society" />
        <Alert variant="warning">Only platform administrators can onboard societies.</Alert>
      </>
    );
  }

  const e = form.formState.errors;
  return (
    <>
      <PageHeader title="Onboard society" description="Create a new society on SocietyOS. You can then switch to it and import its towers, flats and residents." />
      <div className="grid max-w-2xl gap-4">
        {created ? (
          <Alert variant="success" title={`${created.name} is created`}>
            <div className="mt-2 flex flex-wrap gap-2">
              <Button size="sm" loading={switcher.isPending} onClick={() => switcher.mutate(created.id, { onSuccess: () => window.location.assign("/society/imports") })}>
                Switch to it and import data
              </Button>
              <Button size="sm" variant="outline" onClick={() => setCreated(null)}>
                Onboard another
              </Button>
            </div>
            {switcher.error ? <ProblemAlert error={switcher.error} /> : null}
          </Alert>
        ) : null}
        <Card>
          <CardContent className="pt-5">
            <form onSubmit={form.handleSubmit((v) => onboard.mutate(v))} className="grid gap-4 sm:grid-cols-2" noValidate>
              <Field id="o-name" label="Name" required error={e.name?.message}>
                {(a) => <Input {...a} {...form.register("name")} />}
              </Field>
              <Field id="o-legal" label="Legal name" error={e.legalName?.message}>
                {(a) => <Input {...a} {...form.register("legalName")} />}
              </Field>
              <Field id="o-address" label="Address" error={e.address?.message} className="sm:col-span-2">
                {(a) => <Textarea {...a} rows={2} {...form.register("address")} />}
              </Field>
              <Field id="o-city" label="City" required error={e.city?.message}>
                {(a) => <Input {...a} {...form.register("city")} />}
              </Field>
              <Field id="o-state" label="State" required error={e.state?.message}>
                {(a) => <Input {...a} {...form.register("state")} />}
              </Field>
              <Field id="o-pin" label="PIN code" error={e.pin?.message}>
                {(a) => <Input {...a} inputMode="numeric" maxLength={6} {...form.register("pin")} />}
              </Field>
              <Field id="o-tz" label="Time zone" required error={e.timezone?.message}>
                {(a) => <Input {...a} {...form.register("timezone")} />}
              </Field>
              {onboard.error ? (
                <div className="sm:col-span-2">
                  <ProblemAlert error={onboard.error} />
                </div>
              ) : null}
              <div className="sm:col-span-2">
                <Button type="submit" loading={onboard.isPending}>
                  Onboard society
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      </div>
    </>
  );
}
