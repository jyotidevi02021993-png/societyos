/**
 * Normalises an Indian mobile number typed by a person into E.164 (+91XXXXXXXXXX).
 * Accepts "98765 43210", "09876543210", "+91 98765-43210". Other +CC numbers pass through.
 * Returns null when it cannot be a valid number.
 */
export function toE164(input: string, defaultCountry = "91"): string | null {
  const raw = input.trim();
  if (!raw) return null;
  const digits = raw.replace(/[^\d]/g, "");
  if (raw.startsWith("+")) {
    return /^[1-9]\d{7,14}$/.test(digits) ? `+${digits}` : null;
  }
  let local = digits;
  if (local.length === 11 && local.startsWith("0")) local = local.slice(1);
  if (local.length === 12 && local.startsWith(defaultCountry)) local = local.slice(defaultCountry.length);
  if (defaultCountry === "91") return /^[6-9]\d{9}$/.test(local) ? `+91${local}` : null;
  return /^\d{6,14}$/.test(local) ? `+${defaultCountry}${local}` : null;
}
