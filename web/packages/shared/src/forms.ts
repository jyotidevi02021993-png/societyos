import type { FieldValues, Path, UseFormSetError } from "react-hook-form";
import { ApiError } from "@societyos/api-client";

/**
 * Puts server-side VALIDATION_FAILED field errors on the matching form fields.
 * Returns true when at least one field error was applied.
 */
export function applyServerFieldErrors<T extends FieldValues>(err: unknown, setError: UseFormSetError<T>, fields: readonly string[]): boolean {
  if (!(err instanceof ApiError)) return false;
  let applied = false;
  for (const fe of err.fieldErrors) {
    const name = fe.field.split(".")[0] ?? fe.field;
    if (fields.includes(name)) {
      setError(name as Path<T>, { type: "server", message: fe.message });
      applied = true;
    }
  }
  return applied;
}

/** "" → undefined, "12" → 12; for optional numeric text inputs. */
export function optionalInt(value: string | undefined | null): number | undefined {
  if (value === undefined || value === null || value.trim() === "") return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? Math.trunc(n) : undefined;
}

export function blankToUndefined(value: string | undefined | null): string | undefined {
  const v = value?.trim();
  return v ? v : undefined;
}

/** Formats enum values like UNDER_RENOVATION → "Under renovation". */
export function humanize(value: string | null | undefined): string {
  if (!value) return "";
  const s = value.replace(/_/g, " ").toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString("en-IN", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
}
