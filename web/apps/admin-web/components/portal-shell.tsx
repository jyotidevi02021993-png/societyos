"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { LogOut, Menu, X } from "lucide-react";
import { Badge, Button, ThemeToggle, cn } from "@societyos/ui";
import { SocietySwitcher, useLogout, usePermissions, useSession } from "@societyos/shared";
import { visibleNav } from "@/lib/nav";

export function PortalShell({ children }: { children: React.ReactNode }) {
  const session = useSession();
  const { isPlatformAdmin } = usePermissions();
  const pathname = usePathname();
  const logout = useLogout("/login");
  const [open, setOpen] = React.useState(false);
  const nav = visibleNav(session.permissions, isPlatformAdmin);

  React.useEffect(() => setOpen(false), [pathname]);

  const sidebar = (
    <nav aria-label="Main" className="grid gap-5 p-3">
      {nav.length === 0 ? <p className="px-2 text-sm text-muted-foreground">No sections available in this society.</p> : null}
      {nav.map((section) => {
        const Icon = section.icon;
        return (
          <div key={section.id} className="grid gap-1">
            <p className="flex items-center gap-2 px-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              <Icon className="size-3.5" aria-hidden="true" />
              {section.label}
              {section.phase2 ? <span className="font-normal normal-case">(Phase 2)</span> : null}
            </p>
            <ul className="grid gap-0.5">
              {section.items.map((item) => {
                const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
                return (
                  <li key={item.href}>
                    <Link
                      href={item.href}
                      aria-current={active ? "page" : undefined}
                      className={cn(
                        "flex items-center justify-between rounded-md px-2 py-1.5 text-sm hover:bg-accent focus-visible:outline-2 focus-visible:outline-ring",
                        active && "bg-accent font-medium text-accent-foreground",
                      )}
                    >
                      {item.label}
                      {!item.built ? (
                        <Badge variant="outline" className="text-[10px] text-muted-foreground">
                          soon
                        </Badge>
                      ) : null}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </div>
        );
      })}
    </nav>
  );

  return (
    <div className="flex min-h-dvh">
      <aside className="hidden w-64 shrink-0 border-r bg-sidebar text-sidebar-foreground lg:block">
        <div className="sticky top-0 max-h-dvh overflow-y-auto">
          <div className="px-5 py-4 font-semibold tracking-tight">
            SocietyOS <span className="text-muted-foreground">Admin</span>
          </div>
          {sidebar}
        </div>
      </aside>

      {open ? (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="Menu">
          <div className="absolute inset-0 bg-black/40" onClick={() => setOpen(false)} aria-hidden="true" />
          <div className="absolute inset-y-0 left-0 w-72 overflow-y-auto bg-sidebar shadow-lg">
            <div className="flex items-center justify-between px-4 py-3">
              <span className="font-semibold">SocietyOS</span>
              <Button variant="ghost" size="icon" onClick={() => setOpen(false)} aria-label="Close menu" autoFocus>
                <X aria-hidden="true" />
              </Button>
            </div>
            {sidebar}
          </div>
        </div>
      ) : null}

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex items-center gap-3 border-b bg-background/95 px-4 py-2 backdrop-blur">
          <Button variant="ghost" size="icon" className="lg:hidden" onClick={() => setOpen(true)} aria-label="Open menu" aria-expanded={open}>
            <Menu aria-hidden="true" />
          </Button>
          <SocietySwitcher className="w-full max-w-xs" />
          <div className="ml-auto flex items-center gap-2">
            <span className="hidden text-sm text-muted-foreground sm:inline">
              {session.name ?? "Signed in"}
              {isPlatformAdmin ? (
                <Badge variant="secondary" className="ml-2">
                  Platform admin
                </Badge>
              ) : null}
            </span>
            <ThemeToggle />
            <Button variant="ghost" size="sm" onClick={() => logout.mutate()} loading={logout.isPending}>
              <LogOut aria-hidden="true" /> Sign out
            </Button>
          </div>
        </header>
        <main id="main" className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 sm:px-6">
          {children}
        </main>
      </div>
    </div>
  );
}
