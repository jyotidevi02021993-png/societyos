"use client";

import { createContext, createElement, useContext, type ReactNode } from "react";
import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "./problem";
import { createApiClient, type ApiClient } from "./client";
import type { Uuid } from "./types";

const ApiContext = createContext<ApiClient | null>(null);

export function ApiClientProvider({ client, children }: { client?: ApiClient; children: ReactNode }) {
  return createElement(ApiContext.Provider, { value: client ?? defaultClient() }, children);
}

let browserClient: ApiClient | undefined;
function defaultClient(): ApiClient {
  browserClient ??= createApiClient({ baseUrl: "/api/proxy" });
  return browserClient;
}

export function useApi(): ApiClient {
  return useContext(ApiContext) ?? defaultClient();
}

/** Query keys, scoped by active society so switching never shows another society's cache. */
export const qk = {
  session: ["session"] as const,
  societies: ["societies"] as const,
  profile: (sid?: Uuid) => ["society", sid, "profile"] as const,
  settings: (sid?: Uuid) => ["society", sid, "settings"] as const,
  towers: (sid?: Uuid) => ["society", sid, "towers"] as const,
  flats: (sid?: Uuid, towerId?: Uuid) => ["society", sid, "flats", towerId ?? "all"] as const,
  locations: (sid?: Uuid) => ["society", sid, "locations"] as const,
  facilities: (sid?: Uuid) => ["society", sid, "facilities"] as const,
  parking: (sid?: Uuid) => ["society", sid, "parking"] as const,
  members: (sid?: Uuid, flatId?: Uuid, includeEnded?: boolean) =>
    ["society", sid, "members", flatId ?? "all", includeEnded ? "ended" : "active"] as const,
  vehicles: (sid?: Uuid, flatId?: Uuid) => ["society", sid, "vehicles", flatId ?? "all"] as const,
  staff: (sid?: Uuid, flatId?: Uuid) => ["society", sid, "staff", flatId ?? "all"] as const,
  imports: (sid?: Uuid) => ["society", sid, "imports"] as const,
  importJob: (sid: Uuid | undefined, id: Uuid) => ["society", sid, "imports", id] as const,
  myFlats: (sid?: Uuid) => ["society", sid, "me", "flats"] as const,
  directory: (sid?: Uuid, towerId?: Uuid) => ["society", sid, "directory", towerId ?? "all"] as const,
  societyRoot: (sid?: Uuid) => ["society", sid] as const,
};

/** Shared defaults: no retries on 4xx, a session-expired 401 sends the user to sign in. */
export function makeQueryClient(onUnauthorized?: () => void): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        retry: (count, err) => {
          if (err instanceof ApiError && err.status >= 400 && err.status < 500) {
            if (err.status === 401) onUnauthorized?.();
            return false;
          }
          return count < 2;
        },
      },
      mutations: {
        onError: (err) => {
          if (err instanceof ApiError && err.status === 401) onUnauthorized?.();
        },
      },
    },
  });
}
