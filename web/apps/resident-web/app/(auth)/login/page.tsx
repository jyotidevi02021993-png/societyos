import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getServerSession } from "@societyos/auth/next";
import { bff } from "@/lib/bff";
import { OtpLoginForm } from "@/components/otp-login-form";

export const metadata: Metadata = { title: "Sign in" };
export const dynamic = "force-dynamic";

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ next?: string; expired?: string }> }) {
  const { next, expired } = await searchParams;
  const safe = next && next.startsWith("/") && !next.startsWith("//") && !next.startsWith("/\\") ? next : undefined;
  if (await getServerSession(bff)) redirect(safe ?? "/");
  return <OtpLoginForm next={safe} expired={expired === "1"} />;
}
