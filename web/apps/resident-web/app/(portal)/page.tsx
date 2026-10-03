"use client";

import Link from "next/link";
import { ChevronRight, Home } from "lucide-react";
import { Badge, Card, EmptyState, PageHeader } from "@societyos/ui";
import { QueryState, formatDate, humanize, useSession } from "@societyos/shared";
import { useMyFlats } from "@/components/use-my-flats";

export default function MyFlatsPage() {
  const session = useSession();
  const flats = useMyFlats();
  const active = (flats.data ?? []).filter((m) => m.active !== false);

  return (
    <>
      <PageHeader title={session.name ? `Hello, ${session.name.split(" ")[0]}` : "My flats"} description="Your homes in this society." />
      {!session.activeSocietyId ? (
        <EmptyState
          icon={Home}
          title="You are not linked to a society yet"
          description="Ask your society office to add your mobile number to your flat. Then sign in again."
        />
      ) : null}
      <QueryState isLoading={flats.isLoading} error={flats.error} onRetry={() => void flats.refetch()} loadingLabel="Loading your flats…" />
      {flats.data && active.length === 0 ? <EmptyState icon={Home} title="No flats" description="You are not a current member of any flat here." /> : null}
      <ul className="grid gap-3 sm:grid-cols-2">
        {active.map((m) => (
          <li key={m.membershipId}>
            <Link
              href={`/flats/${m.flatId}`}
              className="block rounded-lg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
              aria-label={`Flat ${m.flatLabel}, ${humanize(m.kind)}. Manage vehicles and staff`}
            >
              <Card className="flex items-center justify-between gap-3 p-4 transition-colors hover:bg-muted/40">
                <div className="grid gap-1">
                  <p className="text-lg font-semibold">{m.flatLabel}</p>
                  <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                    <Badge variant="secondary">{humanize(m.kind)}</Badge>
                    {m.isPrimary ? <Badge variant="outline">Primary</Badge> : null}
                    {m.fromDate ? <span>since {formatDate(m.fromDate)}</span> : null}
                  </div>
                </div>
                <ChevronRight className="size-5 text-muted-foreground" aria-hidden="true" />
              </Card>
            </Link>
          </li>
        ))}
      </ul>
    </>
  );
}
