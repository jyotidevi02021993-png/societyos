/**
 * Every request/response shape the web apps use, in one file.
 *
 * Hand-written for now (there are no OpenAPI files under contracts/openapi yet). When the
 * specs land, replace this file with openapi-typescript output and keep the exported names
 * as aliases so the rest of the code does not change.
 *
 * Identity shapes mirror identity-service (AuthController, AuthService.AuthResult,
 * UserService.Me, MeController.Permissions). Society shapes mirror society-service.
 */

export type Uuid = string;
/** ISO-8601 instant, e.g. "2026-09-29T10:15:30Z". */
export type Instant = string;
/** ISO-8601 local date, e.g. "2026-09-29". */
export type LocalDate = string;

// ---------------------------------------------------------------------------------------
// Errors (RFC 7807 problem+json with SocietyOS extensions)
// ---------------------------------------------------------------------------------------

export interface FieldError {
  field: string;
  message: string;
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  /** Stable machine-readable code, e.g. MFA_REQUIRED, TOWER_CODE_EXISTS, VALIDATION_FAILED. */
  code?: string;
  traceId?: string;
  /** Present on VALIDATION_FAILED. */
  errors?: FieldError[];
  [key: string]: unknown;
}

// ---------------------------------------------------------------------------------------
// identity-service: /api/identity/v1/auth/** and /api/identity/v1/me/**
// ---------------------------------------------------------------------------------------

export type DevicePlatform = "ANDROID" | "IOS" | "WEB";

export interface OtpRequestBody {
  /** E.164, e.g. +919876543210 */
  phone: string;
  lang?: "en" | "hi";
}

export interface OtpRequested {
  expiresInSeconds: number;
}

export interface OtpVerifyBody {
  phone: string;
  code: string;
  device: { platform: DevicePlatform; name?: string; pushToken?: string };
}

export interface PasswordLoginBody {
  email: string;
  password: string;
  /** 6-digit authenticator code; required when the account has MFA (server answers MFA_REQUIRED). */
  totp?: string;
}

export interface SocietyRoles {
  societyId: Uuid;
  roles: string[];
}

/** AuthService.AuthResult. `refreshToken` is absent on switch-society (Jackson non_null). */
export interface AuthResult {
  accessToken: string;
  accessTokenExpiresAt: Instant;
  refreshToken?: string;
  userId: Uuid;
  name?: string;
  newUser: boolean;
  platformAdmin: boolean;
  mfaSetupRequired: boolean;
  activeSocietyId?: Uuid;
  societies: SocietyRoles[];
}

export interface Me {
  id: Uuid;
  name?: string;
  phone?: string;
  email?: string;
  preferredLang?: string;
  platformAdmin: boolean;
  mfaEnabled: boolean;
  lastSocietyId?: Uuid;
  societies: SocietyRoles[];
}

export interface MePermissions {
  societyId?: Uuid;
  permissions: string[];
}

export interface MfaSetup {
  secret: string;
  otpauthUri: string;
}

// ---------------------------------------------------------------------------------------
// BFF session (what /api/session returns to the browser; never contains tokens)
// ---------------------------------------------------------------------------------------

export interface SessionSociety {
  societyId: Uuid;
  roles: string[];
}

export interface SessionInfo {
  userId: Uuid;
  name?: string;
  platformAdmin: boolean;
  mfaSetupRequired: boolean;
  activeSocietyId?: Uuid;
  societies: SessionSociety[];
  /** Roles in the active society (plus SUPER_ADMIN for platform admins). */
  roles: string[];
  /** module:action codes in the active society. */
  permissions: string[];
}

// ---------------------------------------------------------------------------------------
// society-service: /api/society/v1/**
// ---------------------------------------------------------------------------------------

export type SocietyStatus = "ONBOARDING" | "ACTIVE" | "SUSPENDED" | "CLOSED" | (string & {});

export interface SocietyProfile {
  id: Uuid;
  name: string;
  legalName?: string | null;
  address?: string | null;
  city: string;
  state: string;
  pin?: string | null;
  timezone: string;
  status: SocietyStatus;
}

export interface SocietyProfileUpdate {
  name: string;
  legalName?: string | null;
  address?: string | null;
  city: string;
  state: string;
  pin?: string | null;
  timezone: string;
}

export interface SocietySettingsValues {
  gateApprovalTimeoutSeconds: number;
  visitorRetentionDays: number;
  gateLogRetentionDays: number;
  notificationRetentionDays: number;
  /** 1-28 */
  billingDueDay: number;
  lateFeeGraceDays: number;
  directoryEnabled: boolean;
  features: Record<string, boolean>;
}

export interface SocietySettings {
  societyId: Uuid;
  settings: SocietySettingsValues;
}

export type SocietySettingsPatch = Partial<SocietySettingsValues>;

export interface OnboardSocietyBody {
  name: string;
  legalName?: string;
  address?: string;
  city: string;
  state: string;
  pin?: string;
  timezone?: string;
  settings?: SocietySettingsPatch;
}

export interface Tower {
  id: Uuid;
  name: string;
  code: string;
  floorsCount: number;
}

export interface TowerBody {
  name: string;
  code: string;
  floorsCount: number;
}

export const FLAT_STATUSES = ["OCCUPIED", "VACANT", "UNDER_RENOVATION"] as const;
export type FlatStatus = (typeof FLAT_STATUSES)[number];

export interface Flat {
  id: Uuid;
  towerId: Uuid;
  number: string;
  label: string;
  floor: number;
  areaSqft?: number | null;
  flatType?: string | null;
  status: FlatStatus;
}

export interface FlatCreateBody {
  towerId: Uuid;
  number: string;
  floor: number;
  areaSqft?: number | null;
  flatType?: string | null;
}

export interface FlatUpdateBody {
  floor: number;
  areaSqft?: number | null;
  flatType?: string | null;
  status: FlatStatus;
}

export const LOCATION_KINDS = [
  "PUMP_ROOM",
  "BASEMENT",
  "ELECTRICAL_ROOM",
  "PLANT_ROOM",
  "DG_ROOM",
  "STP",
  "WTP",
  "LIFT_ROOM",
  "TERRACE",
  "GATE",
  "COMMON_AREA",
  "PARKING",
  "GARDEN",
  "OTHER",
] as const;
export type LocationKind = (typeof LOCATION_KINDS)[number];

export interface Location {
  id: Uuid;
  kind: LocationKind;
  name: string;
  towerId?: Uuid | null;
  parentId?: Uuid | null;
}

export interface LocationBody {
  kind: LocationKind;
  name: string;
  towerId?: Uuid | null;
  parentId?: Uuid | null;
}

export const FACILITY_KINDS = ["CLUBHOUSE", "GYM", "POOL", "COURT", "GUEST_ROOM", "HALL", "OTHER"] as const;
export type FacilityKind = (typeof FACILITY_KINDS)[number];

export interface BookingRules {
  slotMinutes: number;
  maxAdvanceDays: number;
  maxPerFlatPerWeek: number;
}

export interface FacilityBody {
  kind: FacilityKind;
  name: string;
  capacity: number;
  chargeable: boolean;
  /** Money is always paise on the wire. */
  chargePaise: number;
  bookingRules: BookingRules;
  status: "ACTIVE" | "INACTIVE";
}

export interface Facility extends FacilityBody {
  id: Uuid;
}

export const PARKING_KINDS = ["COVERED", "OPEN", "BASEMENT", "VISITOR"] as const;
export type ParkingKind = (typeof PARKING_KINDS)[number];

export interface ParkingSlot {
  id: Uuid;
  code: string;
  kind: ParkingKind;
  flatId?: Uuid | null;
  flatLabel?: string | null;
}

export interface ParkingSlotBody {
  code: string;
  kind: ParkingKind;
}

export const MEMBER_KINDS = ["OWNER", "TENANT", "FAMILY"] as const;
export type MemberKind = (typeof MEMBER_KINDS)[number];

export interface Membership {
  membershipId: Uuid;
  flatId: Uuid;
  flatLabel: string;
  residentId: Uuid;
  userId?: Uuid | null;
  residentName: string;
  kind: MemberKind;
  fromDate?: LocalDate | null;
  toDate?: LocalDate | null;
  isPrimary: boolean;
  active: boolean;
}

export interface MemberAddBody {
  flatId: Uuid;
  phone: string;
  name: string;
  kind: MemberKind;
  fromDate?: LocalDate;
  isPrimary: boolean;
}

export interface MemberEndBody {
  toDate?: LocalDate;
}

export interface DirectoryEntry {
  residentId: Uuid;
  name: string;
  flats: { flatId?: Uuid; towerId?: Uuid; flatLabel: string; kind: MemberKind }[];
}

export const VEHICLE_KINDS = ["CAR", "BIKE", "OTHER"] as const;
export type VehicleKind = (typeof VEHICLE_KINDS)[number];

export interface Vehicle {
  id: Uuid;
  flatId: Uuid;
  flatLabel?: string | null;
  regNo: string;
  kind: VehicleKind;
  rfidTag?: string | null;
}

export interface VehicleBody {
  flatId: Uuid;
  regNo: string;
  kind: VehicleKind;
  rfidTag?: string;
}

export const STAFF_KINDS = ["MAID", "COOK", "DRIVER", "NANNY", "OTHER"] as const;
export type StaffKind = (typeof STAFF_KINDS)[number];
export type KycStatus = "PENDING" | "VERIFIED" | "REJECTED";
export type StaffStatus = "ACTIVE" | "BLOCKED";

export interface DomesticStaff {
  id: Uuid;
  name: string;
  kind: StaffKind;
  phoneMasked?: string | null;
  photoMediaId?: Uuid | null;
  kycStatus: KycStatus;
  status: StaffStatus;
  flatIds: Uuid[];
}

export interface DomesticStaffCreateBody {
  name: string;
  kind: StaffKind;
  phone: string;
  photoMediaId?: Uuid;
  flatIds: Uuid[];
}

export interface DomesticStaffUpdateBody {
  name: string;
  kind: StaffKind;
  photoMediaId?: Uuid | null;
  kycStatus?: KycStatus;
  status?: StaffStatus;
}

export type ImportStatus =
  | "PENDING"
  | "RUNNING"
  | "VALIDATION_FAILED"
  | "COMPLETED"
  | "COMPLETED_WITH_ERRORS"
  | "FAILED";

export const IMPORT_TERMINAL: readonly ImportStatus[] = [
  "VALIDATION_FAILED",
  "COMPLETED",
  "COMPLETED_WITH_ERRORS",
  "FAILED",
];

export interface ImportError {
  sheet: string;
  row: number;
  column?: string | null;
  message: string;
}

export interface ImportReport {
  towers: number;
  flats: number;
  residents: number;
  errors: ImportError[];
}

export interface ImportJob {
  id: Uuid;
  status: ImportStatus;
  dryRun: boolean;
  fileName: string;
  report?: ImportReport | null;
  createdAt: Instant;
  finishedAt?: Instant | null;
}
