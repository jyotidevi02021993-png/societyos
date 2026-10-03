import * as React from "react";
import { AlertCircle, CheckCircle2, Construction, Inbox, Info } from "lucide-react";
import { cn } from "../cn";
import { Spinner } from "./spinner";

type AlertVariant = "info" | "error" | "success" | "warning";

const alertStyles: Record<AlertVariant, string> = {
  info: "border-primary/30 bg-primary/5 text-foreground",
  error: "border-destructive/40 bg-destructive/10 text-foreground",
  success: "border-success/40 bg-success/10 text-foreground",
  warning: "border-warning/50 bg-warning/15 text-foreground",
};

const alertIcons: Record<AlertVariant, React.ComponentType<{ className?: string }>> = {
  info: Info,
  error: AlertCircle,
  success: CheckCircle2,
  warning: AlertCircle,
};

export function Alert({
  variant = "info",
  title,
  children,
  className,
  code,
}: {
  variant?: AlertVariant;
  title?: React.ReactNode;
  children?: React.ReactNode;
  className?: string;
  /** Machine code from problem+json; shown small for support. */
  code?: string;
}) {
  const Icon = alertIcons[variant];
  return (
    <div
      role={variant === "error" ? "alert" : "status"}
      className={cn("flex gap-3 rounded-md border p-3 text-sm", alertStyles[variant], className)}
    >
      <Icon className={cn("mt-0.5 size-4 shrink-0", variant === "error" && "text-destructive", variant === "success" && "text-success")} aria-hidden="true" />
      <div className="grid gap-1">
        {title ? <p className="font-medium">{title}</p> : null}
        {children ? <div>{children}</div> : null}
        {code ? <p className="font-mono text-xs text-muted-foreground">{code}</p> : null}
      </div>
    </div>
  );
}

export function LoadingState({ label = "Loading…", className }: { label?: string; className?: string }) {
  return (
    <div className={cn("flex items-center justify-center gap-3 py-12 text-sm text-muted-foreground", className)}>
      <Spinner label="" />
      <span role="status">{label}</span>
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action,
  icon: Icon = Inbox,
  className,
}: {
  title: string;
  description?: React.ReactNode;
  action?: React.ReactNode;
  icon?: React.ComponentType<{ className?: string }>;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed px-6 py-12 text-center", className)}>
      <Icon className="size-8 text-muted-foreground" aria-hidden="true" />
      <p className="font-medium">{title}</p>
      {description ? <p className="max-w-md text-sm text-muted-foreground">{description}</p> : null}
      {action ? <div className="mt-2">{action}</div> : null}
    </div>
  );
}

export function ErrorState({
  message,
  code,
  onRetry,
  className,
}: {
  message: string;
  code?: string;
  onRetry?: () => void;
  className?: string;
}) {
  return (
    <div className={cn("grid gap-3 py-6", className)}>
      <Alert variant="error" title="Could not load this" code={code}>
        {message}
      </Alert>
      {onRetry ? (
        <div>
          <button
            type="button"
            onClick={onRetry}
            className="inline-flex h-8 items-center rounded-md border border-input bg-card px-3 text-xs font-medium hover:bg-accent focus-visible:outline-2 focus-visible:outline-ring"
          >
            Try again
          </button>
        </div>
      ) : null}
    </div>
  );
}

export function ComingSoon({ title, description }: { title: string; description?: string }) {
  return (
    <EmptyState
      icon={Construction}
      title={`${title} is coming soon`}
      description={description ?? "This part of SocietyOS is not built yet. It will appear here when the service goes live."}
    />
  );
}

export function PageHeader({
  title,
  description,
  actions,
  className,
}: {
  title: string;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-col gap-3 pb-5 sm:flex-row sm:items-end sm:justify-between", className)}>
      <div className="grid gap-1">
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
        {description ? <p className="text-sm text-muted-foreground">{description}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap gap-2">{actions}</div> : null}
    </div>
  );
}
