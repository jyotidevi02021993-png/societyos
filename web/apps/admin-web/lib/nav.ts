import type { LucideIcon } from "lucide-react";
import {
  Building2,
  Coins,
  Gauge,
  Landmark,
  MessagesSquare,
  Rocket,
  Shield,
  Sparkles,
  Wrench,
  Zap,
} from "lucide-react";
import { hasAny } from "@societyos/shared/permissions";

export interface NavItem {
  label: string;
  href: string;
  /** Shown when the user holds any of these (module:action) in the active society. */
  anyOf: string[];
  built?: boolean;
}

export interface NavSection {
  id: string;
  label: string;
  icon: LucideIcon;
  items: NavItem[];
  /** Only for platform admins (SUPER_ADMIN), regardless of society permissions. */
  platformOnly?: boolean;
  phase2?: boolean;
}

/**
 * The nine sections of doc 09 §2. Only Society is built in this slice; the rest route to a
 * "coming soon" page but still respect permissions so the menu matches what each role gets.
 */
export const NAV: NavSection[] = [
  {
    id: "dashboard",
    label: "Dashboard",
    icon: Gauge,
    items: [{ label: "Morning view", href: "/dashboard", anyOf: ["dashboard:view", "mis:view"] }],
  },
  {
    id: "society",
    label: "Society",
    icon: Building2,
    items: [
      { label: "Towers & flats", href: "/society/towers-flats", anyOf: ["society:view"], built: true },
      { label: "Residents", href: "/society/residents", anyOf: ["member:view", "member:manage"], built: true },
      { label: "Parking", href: "/society/parking", anyOf: ["society:view"], built: true },
      { label: "Facilities", href: "/society/facilities", anyOf: ["society:view"], built: true },
      { label: "Locations", href: "/society/locations", anyOf: ["society:view"], built: true },
      { label: "Vehicles & staff", href: "/society/vehicles-staff", anyOf: ["member:manage", "member:view"], built: true },
      { label: "Excel import", href: "/society/imports", anyOf: ["import:run"], built: true },
      { label: "Profile & settings", href: "/society/profile", anyOf: ["society:view", "society:manage"], built: true },
    ],
  },
  {
    id: "security",
    label: "Security",
    icon: Shield,
    items: [
      { label: "Gate activity", href: "/security/gate-activity", anyOf: ["gate:view", "gate:*"] },
      { label: "Visitors", href: "/security/visitors", anyOf: ["visitor:view", "gate:view"] },
      { label: "Staff & vehicles", href: "/security/staff-vehicles", anyOf: ["gate:view"] },
      { label: "Guards", href: "/security/guards", anyOf: ["guard:manage", "gate:manage"] },
      { label: "Incidents", href: "/security/incidents", anyOf: ["incident:view", "incident:report"] },
    ],
  },
  {
    id: "assets",
    label: "Assets & maintenance",
    icon: Wrench,
    items: [
      { label: "Asset registry", href: "/assets/registry", anyOf: ["asset:view", "asset:manage"] },
      { label: "PM planner", href: "/assets/pm-planner", anyOf: ["pm:view", "pm:manage"] },
      { label: "Breakdowns", href: "/assets/breakdowns", anyOf: ["breakdown:view", "jobcard:view"] },
      { label: "Job cards", href: "/assets/job-cards", anyOf: ["jobcard:view", "jobcard:approve", "jobcard:work"] },
      { label: "Checklists", href: "/assets/checklists", anyOf: ["checklist:view", "checklist:manage"] },
    ],
  },
  {
    id: "utilities",
    label: "Utilities",
    icon: Zap,
    phase2: true,
    items: [{ label: "Plant rooms & readings", href: "/utilities", anyOf: ["utility:view", "reading:record"] }],
  },
  {
    id: "estate-services",
    label: "Estate services",
    icon: Sparkles,
    phase2: true,
    items: [{ label: "Housekeeping & amenities", href: "/estate-services", anyOf: ["housekeeping:view", "checklist:execute"] }],
  },
  {
    id: "community",
    label: "Community",
    icon: MessagesSquare,
    items: [
      { label: "Complaints", href: "/community/complaints", anyOf: ["complaint:view", "complaint:manage"] },
      { label: "Notices", href: "/community/notices", anyOf: ["notice:view", "notice:publish"] },
      { label: "Polls", href: "/community/polls", anyOf: ["poll:view", "poll:create"] },
      { label: "Bookings", href: "/community/bookings", anyOf: ["booking:view", "booking:manage"] },
    ],
  },
  {
    id: "finance",
    label: "Finance",
    icon: Coins,
    items: [
      { label: "Bill runs", href: "/finance/bill-runs", anyOf: ["bill:view", "bill:*"] },
      { label: "Collections", href: "/finance/collections", anyOf: ["payment:view", "payment:*"] },
      { label: "Expenses", href: "/finance/expenses", anyOf: ["expense:view", "expense:*"] },
      { label: "Budgets", href: "/finance/budgets", anyOf: ["budget:view", "budget:approve"] },
      { label: "Vendors & AMC", href: "/finance/vendors", anyOf: ["vendor:view", "amc:view"] },
      { label: "Purchase", href: "/finance/purchase", anyOf: ["po:view", "po:approve"] },
    ],
  },
  {
    id: "governance",
    label: "Governance",
    icon: Landmark,
    items: [
      { label: "Compliance", href: "/governance/compliance", anyOf: ["compliance:view"] },
      { label: "Documents", href: "/governance/documents", anyOf: ["document:view"] },
      { label: "Users & roles", href: "/governance/users-roles", anyOf: ["role:manage", "user:manage"] },
      { label: "Workflows", href: "/governance/workflows", anyOf: ["workflow:view", "workflow:manage"] },
      { label: "Audit log", href: "/governance/audit", anyOf: ["audit:view"] },
      { label: "Settings", href: "/governance/settings", anyOf: ["settings:manage"] },
    ],
  },
  {
    id: "platform",
    label: "Platform",
    icon: Rocket,
    platformOnly: true,
    items: [{ label: "Onboard society", href: "/platform/onboard", anyOf: [], built: true }],
  },
];

export interface VisibleNav extends NavSection {
  items: NavItem[];
}

/** Sections and items the user can see. A section with no visible items is hidden. */
export function visibleNav(permissions: readonly string[], isPlatformAdmin: boolean): VisibleNav[] {
  return NAV.flatMap((section) => {
    if (section.platformOnly) return isPlatformAdmin ? [section] : [];
    const items = section.items.filter((i) => hasAny(permissions, i.anyOf));
    return items.length ? [{ ...section, items }] : [];
  });
}

export function findSection(id: string): NavSection | undefined {
  return NAV.find((s) => s.id === id);
}

/** Where to land after sign-in: the first built page the user can open. */
export function homeHref(permissions: readonly string[], isPlatformAdmin: boolean): string {
  const nav = visibleNav(permissions, isPlatformAdmin);
  const built = nav.flatMap((s) => s.items).find((i) => i.built);
  return built?.href ?? nav[0]?.items[0]?.href ?? "/no-access";
}
