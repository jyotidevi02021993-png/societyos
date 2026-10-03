import { redirect } from "next/navigation";
import { getServerSession } from "@societyos/auth/next";
import { bff } from "@/lib/bff";
import { homeHref } from "@/lib/nav";

export const dynamic = "force-dynamic";

export default async function Home() {
  const session = await getServerSession(bff);
  if (!session) redirect("/login");
  redirect(homeHref(session.permissions, session.platformAdmin || session.roles.includes("SUPER_ADMIN")));
}
