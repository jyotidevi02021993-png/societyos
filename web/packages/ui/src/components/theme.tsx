"use client";

import * as React from "react";
import { Monitor, Moon, Sun } from "lucide-react";
import { cn } from "../cn";

export type ThemeChoice = "light" | "dark" | "system";
const KEY = "sos-theme";

/**
 * Inline <script> for <head>: applies the saved theme before first paint (no flash).
 * Render with dangerouslySetInnerHTML; it contains no user input.
 */
export const themeInitScript = `(function(){try{var t=localStorage.getItem('${KEY}')||'system';var d=t==='dark'||(t==='system'&&matchMedia('(prefers-color-scheme: dark)').matches);document.documentElement.classList.toggle('dark',d);}catch(e){}})();`;

function apply(choice: ThemeChoice) {
  const dark = choice === "dark" || (choice === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.classList.toggle("dark", dark);
}

export function ThemeToggle({ className }: { className?: string }) {
  const [choice, setChoice] = React.useState<ThemeChoice>("system");

  React.useEffect(() => {
    const saved = (localStorage.getItem(KEY) as ThemeChoice | null) ?? "system";
    setChoice(saved);
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => {
      if (((localStorage.getItem(KEY) as ThemeChoice | null) ?? "system") === "system") apply("system");
    };
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  const next: Record<ThemeChoice, ThemeChoice> = { light: "dark", dark: "system", system: "light" };
  const Icon = choice === "light" ? Sun : choice === "dark" ? Moon : Monitor;

  return (
    <button
      type="button"
      onClick={() => {
        const n = next[choice];
        setChoice(n);
        try {
          localStorage.setItem(KEY, n);
        } catch {
          /* private mode */
        }
        apply(n);
      }}
      className={cn(
        "inline-flex size-9 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-accent-foreground focus-visible:outline-2 focus-visible:outline-ring",
        className,
      )}
      aria-label={`Theme: ${choice}. Switch to ${next[choice]}`}
      title={`Theme: ${choice}`}
    >
      <Icon className="size-4" aria-hidden="true" />
    </button>
  );
}
