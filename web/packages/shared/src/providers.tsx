"use client";

import * as React from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { ApiClientProvider, makeQueryClient } from "@societyos/api-client/react";

/** TanStack Query + API client. A 401 from the BFF means the session is gone: go to sign-in. */
export function AppProviders({ children, loginPath = "/login" }: { children: React.ReactNode; loginPath?: string }) {
  const [client] = React.useState(() =>
    makeQueryClient(() => {
      if (typeof window !== "undefined" && !window.location.pathname.startsWith(loginPath)) {
        const next = encodeURIComponent(window.location.pathname + window.location.search);
        window.location.assign(`${loginPath}?next=${next}&expired=1`);
      }
    }),
  );
  return (
    <QueryClientProvider client={client}>
      <ApiClientProvider>{children}</ApiClientProvider>
    </QueryClientProvider>
  );
}
