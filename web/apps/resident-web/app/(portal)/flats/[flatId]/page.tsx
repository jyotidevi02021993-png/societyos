"use client";

import { use } from "react";
import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import { Alert, PageHeader } from "@societyos/ui";
import { QueryState, StaffPanel, VehiclesPanel, humanize, usePermissions } from "@societyos/shared";
import { useMyFlats } from "@/components/use-my-flats";

export default function FlatPage({ params }: { params: Promise<{ flatId: string }> }) {
  const { flatId } = use(params);
  const { can } = usePermissions();
  const flats = useMyFlats();
  const membership = flats.data?.find((m) => m.flatId === flatId && m.active !== false);
  // Residents hold household:manage for their own flats; the service enforces ownership.
  const canManage = can("household:manage") || can("member:manage");

  return (
    <>
      <Link href="/" className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" aria-hidden="true" /> My flats
      </Link>
      <QueryState isLoading={flats.isLoading} error={flats.error} onRetry={() => void flats.refetch()} />
      {flats.data && !membership ? <Alert variant="warning">This flat is not one of yours.</Alert> : null}
      {membership ? (
        <>
          <PageHeader title={`Flat ${membership.flatLabel}`} description={`You are a ${humanize(membership.kind).toLowerCase()} here.`} />
          <div className="grid gap-6">
            <VehiclesPanel flatId={flatId} canManage={canManage} />
            <StaffPanel flatId={flatId} canManage={canManage} />
          </div>
        </>
      ) : null}
    </>
  );
}
