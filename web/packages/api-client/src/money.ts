/** Money is integer paise on the wire; the UI shows and edits rupees. */
export function paiseToRupees(paise: number | null | undefined): number {
  return Math.round(paise ?? 0) / 100;
}

export function rupeesToPaise(rupees: number | string | null | undefined): number {
  const n = typeof rupees === "string" ? Number(rupees) : (rupees ?? 0);
  if (!Number.isFinite(n)) return 0;
  return Math.round(n * 100);
}

const INR = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 0, maximumFractionDigits: 2 });

export function formatPaise(paise: number | null | undefined): string {
  return INR.format(paiseToRupees(paise));
}
