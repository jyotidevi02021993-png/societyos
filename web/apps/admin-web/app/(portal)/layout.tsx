import { redirect } from "next/navigation";
import { getServerSession } from "@societyos/auth/next";
import { SessionProvider } from "@societyos/shared";
import { bff } from "@/lib/bff";
import { PortalShell } from "@/components/portal-shell";

export const dynamic = "force-dynamic";

export default async function PortalLayout({ children }: { children: React.ReactNode }) {
  const session = await getServerSession(bff);
  if (!session) redirect("/login");
  if (session.mfaSetupRequired && bff.config.requireMfa) redirect("/mfa");
  return (
    <SessionProvider initial={session}>
      <PortalShell>{children}</PortalShell>
    </SessionProvider>
  );
}
