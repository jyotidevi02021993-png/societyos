/**
 * Design tokens. The CSS custom properties in ../styles.css are the source used at runtime;
 * this module exposes the same names for code that needs them (charts, canvas, emails).
 */
export const colorTokens = [
  "background",
  "foreground",
  "muted",
  "muted-foreground",
  "card",
  "card-foreground",
  "border",
  "input",
  "ring",
  "primary",
  "primary-foreground",
  "secondary",
  "secondary-foreground",
  "accent",
  "accent-foreground",
  "destructive",
  "destructive-foreground",
  "success",
  "success-foreground",
  "warning",
  "warning-foreground",
  "sidebar",
  "sidebar-foreground",
] as const;

export type ColorToken = (typeof colorTokens)[number];

export const cssVar = (token: ColorToken): string => `var(--${token})`;

export const radius = { sm: "0.375rem", md: "0.5rem", lg: "0.75rem" } as const;

export const breakpoints = { sm: 640, md: 768, lg: 1024, xl: 1280 } as const;
