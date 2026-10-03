import { notFound } from "next/navigation";
import { ComingSoon, PageHeader } from "@societyos/ui";
import { findSection } from "@/lib/nav";

/** Every section that is not built yet (dashboard, security, assets, …): a clear placeholder. */
export default async function SectionPlaceholder({ params }: { params: Promise<{ section: string; rest?: string[] }> }) {
  const { section, rest } = await params;
  const s = findSection(section);
  if (!s) notFound();
  const href = `/${[section, ...(rest ?? [])].join("/")}`;
  const item = s.items.find((i) => i.href === href) ?? s.items[0];
  return (
    <>
      <PageHeader title={item?.label ?? s.label} description={s.label} />
      <ComingSoon
        title={item?.label ?? s.label}
        description={s.phase2 ? "Planned for Phase 2 of SocietyOS." : "This module's service is not live yet; it will appear here when it is."}
      />
    </>
  );
}
