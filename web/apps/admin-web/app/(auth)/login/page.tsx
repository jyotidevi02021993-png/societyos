import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getServerSession } from "@societyos/auth/next";
import { bff } from "@/lib/bff";
import { LoginForm } from "@/components/login-form";

export const metadata: Metadata = { title: "Sign in" };
export const dynamic = "force-dynamic";

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ next?: string; expired?: string }> }) {
  const { next, expired } = await searchParams;
  const session = await getServerSession(bff);
  if (session) redirect(safeNext(next) ?? "/");
  return <LoginForm next={safeNext(next)} expired={expired === "1"} />;
}

/** Only same-app relative paths; never an open redirect. */
function safeNext(next: string | undefined): string | undefined {
  return next && next.startsWith("/") && !next.startsWith("//") && !next.startsWith("/\\") ? next : undefined;
}
