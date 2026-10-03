"use client";

import * as React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { ApiError, toApiError, toE164, type SessionInfo } from "@societyos/api-client";
import { Alert, Button, Card, CardContent, CardDescription, CardHeader, CardTitle, Field, Input } from "@societyos/ui";
import { ProblemAlert } from "@societyos/shared";

export const phoneSchema = z.object({
  phone: z.string().refine((v) => toE164(v) !== null, "Enter a valid 10-digit mobile number"),
});
export const codeSchema = z.object({
  code: z
    .string()
    .trim()
    .regex(/^\d{4,8}$/, "Enter the code from the SMS"),
});

async function post<T>(url: string, body: unknown): Promise<T> {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    credentials: "same-origin",
  });
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as T;
}

function maskPhone(e164: string): string {
  return e164.replace(/^(\+\d{2})(\d{2})\d+(\d{2})$/, "$1 $2••••••$3");
}

/** Phone OTP sign-in: request a code, then verify it (identity /v1/auth/otp/*, via the BFF). */
export function OtpLoginForm({
  next,
  expired,
  onSignedIn,
}: {
  next?: string;
  expired?: boolean;
  onSignedIn?: (session: SessionInfo) => void;
}) {
  const [phone, setPhone] = React.useState<string | null>(null);
  const [expiresIn, setExpiresIn] = React.useState<number>(0);
  const [resendAt, setResendAt] = React.useState<number>(0);
  const [now, setNow] = React.useState(() => Date.now());
  const [error, setError] = React.useState<unknown>(null);

  const phoneForm = useForm<z.infer<typeof phoneSchema>>({ resolver: zodResolver(phoneSchema), defaultValues: { phone: "" } });
  const codeForm = useForm<z.infer<typeof codeSchema>>({ resolver: zodResolver(codeSchema), defaultValues: { code: "" } });

  React.useEffect(() => {
    if (!phone) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [phone]);

  const request = async (raw: string) => {
    setError(null);
    try {
      const r = await post<{ phone: string; expiresInSeconds: number }>("/api/auth/otp/request", { phone: raw });
      setPhone(r.phone);
      setExpiresIn(r.expiresInSeconds);
      setResendAt(Date.now() + 30_000);
      codeForm.reset({ code: "" });
    } catch (err) {
      setError(err);
    }
  };

  const submitPhone = phoneForm.handleSubmit(({ phone: raw }) => request(raw));

  const submitCode = codeForm.handleSubmit(async ({ code }) => {
    if (!phone) return;
    setError(null);
    try {
      const r = await post<{ session: SessionInfo }>("/api/auth/otp/verify", { phone, code });
      if (onSignedIn) onSignedIn(r.session);
      else window.location.assign(next ?? "/");
    } catch (err) {
      if (err instanceof ApiError && err.status >= 400 && err.status < 500 && /OTP/.test(err.code)) {
        codeForm.setError("code", { type: "server", message: err.message });
        return;
      }
      setError(err);
    }
  });

  const secondsToResend = Math.max(0, Math.ceil((resendAt - now) / 1000));

  return (
    <Card className="w-full max-w-sm">
      <CardHeader>
        <CardTitle>{phone ? "Enter the code" : "Sign in with your mobile"}</CardTitle>
        <CardDescription>
          {phone
            ? `We sent a code to ${maskPhone(phone)}. It is valid for ${Math.round(expiresIn / 60) || 1} minutes.`
            : "Use the number registered with your society office."}
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        {expired && !phone && !error ? <Alert variant="info">Your session ended. Please sign in again.</Alert> : null}
        {error ? <ProblemAlert error={error} /> : null}
        {!phone ? (
          <form onSubmit={submitPhone} className="grid gap-4" noValidate aria-label="Request code">
            <Field id="phone" label="Mobile number" required error={phoneForm.formState.errors.phone?.message}>
              {(a) => (
                <div className="flex">
                  <span className="inline-flex items-center rounded-l-md border border-r-0 border-input bg-muted px-3 text-sm text-muted-foreground" aria-hidden="true">
                    +91
                  </span>
                  <Input {...a} type="tel" inputMode="tel" autoComplete="tel-national" autoFocus placeholder="98765 43210" className="rounded-l-none" {...phoneForm.register("phone")} />
                </div>
              )}
            </Field>
            <Button type="submit" loading={phoneForm.formState.isSubmitting} className="w-full">
              Send code
            </Button>
          </form>
        ) : (
          <form onSubmit={submitCode} className="grid gap-4" noValidate aria-label="Verify code">
            <Field id="code" label="One-time code" required error={codeForm.formState.errors.code?.message}>
              {(a) => (
                <Input
                  {...a}
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  autoFocus
                  maxLength={8}
                  className="text-center font-mono text-lg tracking-[0.4em]"
                  {...codeForm.register("code")}
                />
              )}
            </Field>
            <Button type="submit" loading={codeForm.formState.isSubmitting} className="w-full">
              Verify and sign in
            </Button>
            <div className="flex items-center justify-between text-sm">
              <Button variant="link" className="px-0" onClick={() => setPhone(null)}>
                Change number
              </Button>
              <Button variant="link" className="px-0" disabled={secondsToResend > 0} onClick={() => void request(phone)}>
                {secondsToResend > 0 ? `Resend in ${secondsToResend}s` : "Resend code"}
              </Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  );
}
