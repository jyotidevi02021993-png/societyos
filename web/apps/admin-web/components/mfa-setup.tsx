"use client";

import * as React from "react";
import { useMutation } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { toApiError } from "@societyos/api-client";
import { useApi } from "@societyos/api-client/react";
import { Button, Card, CardContent, CardDescription, CardHeader, CardTitle, Field, Input } from "@societyos/ui";
import { ProblemAlert } from "@societyos/shared";
import { totpSchema } from "./login-form";

/** First sign-in without MFA: enrol an authenticator (identity /v1/me/mfa/setup + confirm). */
export function MfaSetup() {
  const api = useApi();
  const setup = useMutation({ mutationFn: () => api.identity.startMfaSetup() });
  const form = useForm<{ totp: string }>({ resolver: zodResolver(totpSchema), defaultValues: { totp: "" } });
  const [error, setError] = React.useState<unknown>(null);

  const { mutate } = setup;
  React.useEffect(() => {
    mutate();
  }, [mutate]);

  const confirm = form.handleSubmit(async ({ totp }) => {
    setError(null);
    const res = await fetch("/api/auth/mfa/confirm", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: totp }),
      credentials: "same-origin",
    });
    if (!res.ok) {
      setError(await toApiError(res));
      return;
    }
    window.location.assign("/");
  });

  const secretGroups = setup.data?.secret.match(/.{1,4}/g)?.join(" ");

  return (
    <Card className="w-full max-w-md">
      <CardHeader>
        <CardTitle>Set up two-step verification</CardTitle>
        <CardDescription>Admin accounts need an authenticator app (Google Authenticator, Microsoft Authenticator, 1Password…).</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        {setup.error ? <ProblemAlert error={setup.error} /> : null}
        {setup.isPending ? <p className="text-sm text-muted-foreground" role="status">Preparing your key…</p> : null}
        {setup.data ? (
          <>
            <ol className="list-decimal space-y-2 pl-5 text-sm">
              <li>In your authenticator app, add an account with a setup key.</li>
              <li>
                Enter this key:{" "}
                <code className="select-all rounded bg-muted px-1.5 py-0.5 font-mono text-sm" aria-label={`Setup key ${setup.data.secret}`}>
                  {secretGroups}
                </code>{" "}
                (time-based). Or open{" "}
                <a className="text-primary underline" href={setup.data.otpauthUri}>
                  this link
                </a>{" "}
                on a phone with the app installed.
              </li>
              <li>Type the 6-digit code it shows.</li>
            </ol>
            <form onSubmit={confirm} className="grid gap-3" noValidate>
              {error ? <ProblemAlert error={error} /> : null}
              <Field id="mfa-code" label="Authenticator code" required error={form.formState.errors.totp?.message}>
                {(a) => <Input {...a} inputMode="numeric" autoComplete="one-time-code" maxLength={6} {...form.register("totp")} />}
              </Field>
              <Button type="submit" loading={form.formState.isSubmitting}>
                Turn on and continue
              </Button>
            </form>
          </>
        ) : null}
        {setup.error ? (
          <Button variant="outline" onClick={() => setup.mutate()}>
            Try again
          </Button>
        ) : null}
      </CardContent>
    </Card>
  );
}
