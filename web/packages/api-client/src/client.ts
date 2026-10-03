import { toApiError } from "./problem";
import type * as T from "./types";

export interface ApiClientOptions {
  /**
   * Where requests go. In the browser this is the BFF proxy ("/api/proxy"); the proxy maps
   * `/api/proxy/<service>/v1/...` to `<API_BASE_URL>/api/<service>/v1/...`.
   */
  baseUrl?: string;
  fetch?: typeof fetch;
  /** Extra headers for every request (e.g. Authorization when called server-side). */
  headers?: Record<string, string>;
}

type Query = Record<string, string | number | boolean | null | undefined>;

export interface RequestOptions {
  query?: Query;
  body?: unknown;
  signal?: AbortSignal;
  headers?: Record<string, string>;
}

export function buildQuery(query?: Query): string {
  if (!query) return "";
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === "") continue;
    params.set(k, String(v));
  }
  const s = params.toString();
  return s ? `?${s}` : "";
}

export function createApiClient(options: ApiClientOptions = {}) {
  const baseUrl = (options.baseUrl ?? "/api/proxy").replace(/\/+$/, "");
  const doFetch = options.fetch ?? ((...args: Parameters<typeof fetch>) => fetch(...args));

  async function request<R>(method: string, path: string, opts: RequestOptions = {}): Promise<R> {
    const headers: Record<string, string> = { Accept: "application/json, application/problem+json", ...options.headers, ...opts.headers };
    let body: BodyInit | undefined;
    if (opts.body instanceof FormData) {
      body = opts.body; // the browser sets the multipart boundary
    } else if (opts.body !== undefined) {
      headers["Content-Type"] = "application/json";
      body = JSON.stringify(opts.body);
    }
    const res = await doFetch(`${baseUrl}/${path.replace(/^\/+/, "")}${buildQuery(opts.query)}`, {
      method,
      headers,
      body,
      signal: opts.signal,
      credentials: "same-origin",
      cache: "no-store",
    });
    if (!res.ok) throw await toApiError(res);
    if (res.status === 204) return undefined as R;
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as R;
  }

  const get = <R>(path: string, query?: Query, signal?: AbortSignal) => request<R>("GET", path, { query, signal });
  const post = <R>(path: string, body?: unknown, query?: Query) => request<R>("POST", path, { body, query });
  const put = <R>(path: string, body?: unknown) => request<R>("PUT", path, { body });
  const del = <R = void>(path: string) => request<R>("DELETE", path);

  const S = "society/v1";
  const I = "identity/v1";

  return {
    request,
    identity: {
      me: () => get<T.Me>(`${I}/me`),
      permissions: () => get<T.MePermissions>(`${I}/me/permissions`),
      startMfaSetup: () => post<T.MfaSetup>(`${I}/me/mfa/setup`),
      confirmMfa: (code: string) => post<void>(`${I}/me/mfa/confirm`, { code }),
    },
    society: {
      societies: {
        list: () => get<T.SocietyProfile[]>(`${S}/societies`),
        /** Platform admin, no active society: the BFF omits X-Society-Id for this call. */
        onboard: (body: T.OnboardSocietyBody) =>
          request<T.SocietyProfile>("POST", `${S}/societies`, { body, headers: { "X-Sos-Society": "none" } }),
      },
      profile: {
        get: () => get<T.SocietyProfile>(`${S}/society`),
        update: (body: T.SocietyProfileUpdate) => put<T.SocietyProfile>(`${S}/society`, body),
      },
      settings: {
        get: () => get<T.SocietySettings>(`${S}/society/settings`),
        update: (patch: T.SocietySettingsPatch) => put<T.SocietySettings>(`${S}/society/settings`, patch),
      },
      towers: {
        list: () => get<T.Tower[]>(`${S}/towers`),
        create: (body: T.TowerBody) => post<T.Tower>(`${S}/towers`, body),
        update: (id: T.Uuid, body: T.TowerBody) => put<T.Tower>(`${S}/towers/${id}`, body),
      },
      flats: {
        list: (towerId?: T.Uuid) => get<T.Flat[]>(`${S}/flats`, { towerId }),
        create: (body: T.FlatCreateBody) => post<T.Flat>(`${S}/flats`, body),
        update: (id: T.Uuid, body: T.FlatUpdateBody) => put<T.Flat>(`${S}/flats/${id}`, body),
      },
      locations: {
        list: () => get<T.Location[]>(`${S}/locations`),
        create: (body: T.LocationBody) => post<T.Location>(`${S}/locations`, body),
        update: (id: T.Uuid, body: T.LocationBody) => put<T.Location>(`${S}/locations/${id}`, body),
      },
      facilities: {
        list: () => get<T.Facility[]>(`${S}/facilities`),
        create: (body: T.FacilityBody) => post<T.Facility>(`${S}/facilities`, body),
        update: (id: T.Uuid, body: T.FacilityBody) => put<T.Facility>(`${S}/facilities/${id}`, body),
      },
      parking: {
        list: (flatId?: T.Uuid) => get<T.ParkingSlot[]>(`${S}/parking-slots`, { flatId }),
        create: (body: T.ParkingSlotBody) => post<T.ParkingSlot>(`${S}/parking-slots`, body),
        assign: (id: T.Uuid, flatId: T.Uuid | null) =>
          put<T.ParkingSlot>(`${S}/parking-slots/${id}/assignment`, { flatId }),
      },
      members: {
        list: (q: { flatId?: T.Uuid; includeEnded?: boolean } = {}) => get<T.Membership[]>(`${S}/members`, q),
        add: (body: T.MemberAddBody) => post<T.Membership>(`${S}/members`, body),
        end: (membershipId: T.Uuid, body: T.MemberEndBody = {}) =>
          post<T.Membership>(`${S}/members/${membershipId}/end`, body),
      },
      me: {
        flats: () => get<T.Membership[]>(`${S}/me/flats`),
        setDirectoryOptIn: (optIn: boolean) => put<unknown>(`${S}/me/directory`, { optIn }),
      },
      directory: {
        list: (towerId?: T.Uuid) => get<T.DirectoryEntry[]>(`${S}/directory`, { towerId }),
      },
      vehicles: {
        list: (flatId?: T.Uuid) => get<T.Vehicle[]>(`${S}/vehicles`, { flatId }),
        add: (body: T.VehicleBody) => post<T.Vehicle>(`${S}/vehicles`, body),
        remove: (id: T.Uuid) => del(`${S}/vehicles/${id}`),
      },
      staff: {
        list: (flatId?: T.Uuid) => get<T.DomesticStaff[]>(`${S}/domestic-staff`, { flatId }),
        add: (body: T.DomesticStaffCreateBody) => post<T.DomesticStaff>(`${S}/domestic-staff`, body),
        update: (id: T.Uuid, body: T.DomesticStaffUpdateBody) => put<T.DomesticStaff>(`${S}/domestic-staff/${id}`, body),
        linkFlat: (id: T.Uuid, flatId: T.Uuid) => post<T.DomesticStaff>(`${S}/domestic-staff/${id}/flats/${flatId}`),
        unlinkFlat: (id: T.Uuid, flatId: T.Uuid) => del<T.DomesticStaff | undefined>(`${S}/domestic-staff/${id}/flats/${flatId}`),
      },
      imports: {
        list: () => get<T.ImportJob[]>(`${S}/imports`),
        get: (id: T.Uuid) => get<T.ImportJob>(`${S}/imports/${id}`),
        upload: (file: File | Blob, dryRun: boolean, fileName?: string) => {
          const form = new FormData();
          form.append("file", file, fileName ?? (file instanceof File ? file.name : "import.xlsx"));
          return request<T.ImportJob>("POST", `${S}/imports`, { body: form, query: { dryRun } });
        },
        /** Plain URL for an <a download> link (goes through the same BFF proxy). */
        templateUrl: () => `${baseUrl}/${S}/imports/template`,
      },
    },
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
