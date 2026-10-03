"use client";

import { Alert, ErrorState, LoadingState } from "@societyos/ui";
import { ApiError, errorMessage } from "./deps";

/** Shows a problem+json error with its human message (and code for support). */
export function ProblemAlert({ error, title }: { error: unknown; title?: string }) {
  if (!error) return null;
  const code = error instanceof ApiError ? error.code : undefined;
  const fieldErrors = error instanceof ApiError ? error.fieldErrors : [];
  return (
    <Alert variant="error" title={title} code={code}>
      <p>{errorMessage(error)}</p>
      {fieldErrors.length > 0 ? (
        <ul className="mt-1 list-disc pl-5">
          {fieldErrors.map((f) => (
            <li key={`${f.field}-${f.message}`}>
              <span className="font-medium">{f.field}</span>: {f.message}
            </li>
          ))}
        </ul>
      ) : null}
    </Alert>
  );
}

/** Loading / error wrapper for a query. */
export function QueryState({
  isLoading,
  error,
  onRetry,
  loadingLabel,
}: {
  isLoading: boolean;
  error: unknown;
  onRetry?: () => void;
  loadingLabel?: string;
}) {
  if (isLoading) return <LoadingState label={loadingLabel} />;
  if (error) {
    return <ErrorState message={errorMessage(error)} code={error instanceof ApiError ? error.code : undefined} onRetry={onRetry} />;
  }
  return null;
}
