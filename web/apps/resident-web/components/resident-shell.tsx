"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { BookUser, Home, LogOut } from "lucide-react";
import { Button, ThemeToggle, cn } from "@societyos/ui";
import { SocietySwitcher, useLogout, useSession } from "@societyos/shared";

const LINKS = [
  { href: "/", label: "My flats", icon: Home },
  { href: "/directory", label: "Directory", icon: BookUser },
];

export function ResidentShell({ children }: { children: React.ReactNode }) {
  const session = useSession();
  const pathname = usePathname();
  const logout = useLogout("/login");
  const isActive = (href: string) => (href === "/" ? pathname === "/" || pathname.startsWith("/flats") : pathname.startsWith(href));

  return (
    <div className="flex min-h-dvh flex-col pb-16 sm:pb-0">
      <header className="sticky top-0 z-30 border-b bg-background/95 backdrop-blur">
        <div className="mx-auto flex max-w-4xl items-center gap-3 px-4 py-2">
          <Link href="/" className="font-semibold tracking-tight focus-visible:outline-2 focus-visible:outline-ring">
            SocietyOS
          </Link>
          <nav aria-label="Main" className="hidden gap-1 sm:flex">
            {LINKS.map((l) => (
              <Link
                key={l.href}
                href={l.href}
                aria-current={isActive(l.href) ? "page" : undefined}
                className={cn(
                  "rounded-md px-3 py-1.5 text-sm hover:bg-accent focus-visible:outline-2 focus-visible:outline-ring",
                  isActive(l.href) && "bg-accent font-medium",
                )}
              >
                {l.label}
              </Link>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-1">
            {session.societies.length > 1 ? <SocietySwitcher className="w-40 sm:w-56" /> : null}
            <ThemeToggle />
            <Button variant="ghost" size="sm" onClick={() => logout.mutate()} loading={logout.isPending} aria-label="Sign out">
              <LogOut aria-hidden="true" />
              <span className="hidden sm:inline">Sign out</span>
            </Button>
          </div>
        </div>
      </header>
      <main id="main" className="mx-auto w-full max-w-4xl flex-1 px-4 py-6">
        {children}
      </main>
      <nav aria-label="Main (mobile)" className="fixed inset-x-0 bottom-0 z-30 grid grid-cols-2 border-t bg-background sm:hidden">
        {LINKS.map((l) => {
          const Icon = l.icon;
          return (
            <Link
              key={l.href}
              href={l.href}
              aria-current={isActive(l.href) ? "page" : undefined}
              className={cn(
                "flex flex-col items-center gap-0.5 py-2 text-xs focus-visible:outline-2 focus-visible:outline-ring",
                isActive(l.href) ? "text-primary" : "text-muted-foreground",
              )}
            >
              <Icon className="size-5" aria-hidden="true" />
              {l.label}
            </Link>
          );
        })}
      </nav>
    </div>
  );
}
