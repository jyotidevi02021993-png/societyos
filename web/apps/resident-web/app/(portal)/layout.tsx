import { redirect } from "next/navigation";
import { getServerSession } from "@societyos/auth/next";
import { SessionProvider } from "@societyos/shared";
import { bff } from "@/lib/bff";
import { ResidentShell } from "@/components/resident-shell";

export const dynamic = "force-dynamic";

export default async function PortalLayout({ children }: { children: React.ReactNode }) {
  const session = await getServerSession(bff);
  if (!session) redirect("/login");
  return (
    <SessionProvider initial={session}>
      <ResidentShell>{children}</ResidentShell>
    </SessionProvider>
  );
}
