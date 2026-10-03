"use client";

import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { ApiError, toApiError, type SessionInfo } from "@societyos/api-client";
import { Alert, Button, Card, CardContent, CardDescription, CardHeader, CardTitle, Field, Input } from "@societyos/ui";
import { ProblemAlert } from "@societyos/shared";

export const credentialsSchema = z.object({
  email: z.string().trim().min(1, "Enter your e-mail").email("Enter a valid e-mail address"),
  password: z.string().min(1, "Enter your password"),
});

export const totpSchema = z.object({
  totp: z
    .string()
    .trim()
    .regex(/^\d{6}$/, "Enter the 6-digit code from your authenticator app"),
});

type Credentials = z.infer<typeof credentialsSchema>;
type Totp = z.infer<typeof totpSchema>;

export interface LoginResponse {
  session: SessionInfo;
  newUser: boolean;
}

async function login(body: Credentials & { totp?: string }): Promise<LoginResponse> {
  const res = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    credentials: "same-origin",
  });
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as LoginResponse;
}

/**
 * Step 1: e-mail + password. If identity-service answers 401 MFA_REQUIRED, step 2 asks for
 * the TOTP and resends all three (identity has no separate MFA challenge endpoint).
 */
export function LoginForm({
  next,
  expired,
  onSignedIn,
}: {
  next?: string;
  expired?: boolean;
  /** Test hook; defaults to a full navigation so the server layout reloads the session. */
  onSignedIn?: (r: LoginResponse) => void;
}) {
  const [step, setStep] = React.useState<"credentials" | "totp">("credentials");
  const [creds, setCreds] = React.useState<Credentials | null>(null);
  const [error, setError] = React.useState<unknown>(null);
  const totpRef = React.useRef<HTMLInputElement | null>(null);

  const credsForm = useForm<Credentials>({ resolver: zodResolver(credentialsSchema), defaultValues: { email: "", password: "" } });
  const totpForm = useForm<Totp>({ resolver: zodResolver(totpSchema), defaultValues: { totp: "" } });

  const done = (r: LoginResponse) => {
    if (onSignedIn) return onSignedIn(r);
    const target = r.session.mfaSetupRequired ? "/mfa" : (next ?? "/");
    window.location.assign(target);
  };

  const submitCreds = credsForm.handleSubmit(async (values) => {
    setError(null);
    try {
      done(await login(values));
    } catch (err) {
      if (err instanceof ApiError && err.code === "MFA_REQUIRED") {
        setCreds(values);
        setStep("totp");
        setTimeout(() => totpRef.current?.focus(), 0);
        return;
      }
      setError(err);
    }
  });

  const submitTotp = totpForm.handleSubmit(async ({ totp }) => {
    if (!creds) return;
    setError(null);
    try {
      done(await login({ ...creds, totp }));
    } catch (err) {
      if (err instanceof ApiError && err.code === "MFA_INVALID") {
        totpForm.setError("totp", { type: "server", message: err.message });
        totpForm.setValue("totp", "");
        totpRef.current?.focus();
        return;
      }
      setError(err);
    }
  });

  const totpReg = totpForm.register("totp");

  return (
    <Card className="w-full max-w-sm">
      <CardHeader>
        <CardTitle>{step === "credentials" ? "Sign in to SocietyOS" : "Two-step verification"}</CardTitle>
        <CardDescription>
          {step === "credentials" ? "For society staff and administrators." : "Enter the 6-digit code from your authenticator app."}
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        {expired && step === "credentials" && !error ? <Alert variant="info">Your session ended. Please sign in again.</Alert> : null}
        {error ? <ProblemAlert error={error} /> : null}

        {step === "credentials" ? (
          <form onSubmit={submitCreds} className="grid gap-4" noValidate aria-label="Sign in">
            <Field id="email" label="E-mail" required error={credsForm.formState.errors.email?.message}>
              {(a) => <Input {...a} type="email" autoComplete="username" autoFocus {...credsForm.register("email")} />}
            </Field>
            <Field id="password" label="Password" required error={credsForm.formState.errors.password?.message}>
              {(a) => <Input {...a} type="password" autoComplete="current-password" {...credsForm.register("password")} />}
            </Field>
            <Button type="submit" loading={credsForm.formState.isSubmitting} className="w-full">
              Continue
            </Button>
          </form>
        ) : (
          <form onSubmit={submitTotp} className="grid gap-4" noValidate aria-label="Two-step verification">
            <Field id="totp" label="Authenticator code" required error={totpForm.formState.errors.totp?.message}>
              {(a) => (
                <Input
                  {...a}
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={6}
                  className="text-center font-mono text-lg tracking-[0.4em]"
                  {...totpReg}
                  ref={(el) => {
                    totpReg.ref(el);
                    totpRef.current = el;
                  }}
                />
              )}
            </Field>
            <Button type="submit" loading={totpForm.formState.isSubmitting} className="w-full">
              Verify and sign in
            </Button>
            <Button
              type="button"
              variant="link"
              onClick={() => {
                setStep("credentials");
                setError(null);
                totpForm.reset();
              }}
            >
              Use a different account
            </Button>
          </form>
        )}
      </CardContent>
    </Card>
  );
}
