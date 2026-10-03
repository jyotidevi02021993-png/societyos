import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { getServerSession } from "@societyos/auth/next";
import { bff } from "@/lib/bff";
import { MfaSetup } from "@/components/mfa-setup";

export const metadata: Metadata = { title: "Set up two-step verification" };
export const dynamic = "force-dynamic";

export default async function MfaPage() {
  const session = await getServerSession(bff);
  if (!session) redirect("/login");
  if (!session.mfaSetupRequired || !bff.config.requireMfa) redirect("/");
  return <MfaSetup />;
}
