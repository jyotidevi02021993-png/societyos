import { visibleNav } from "@/lib/nav";

/** Test helpers: section ids / item hrefs visible for a permission set. */
export const visible = (perms: string[], platformAdmin: boolean) => visibleNav(perms, platformAdmin).map((s) => s.id);
export const nav = (perms: string[], platformAdmin: boolean) => visibleNav(perms, platformAdmin).flatMap((s) => s.items.map((i) => i.href));
