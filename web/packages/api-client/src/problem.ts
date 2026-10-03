import type { FieldError, ProblemDetail } from "./types";

/** An HTTP error from the API (or the BFF), carrying the RFC 7807 body when there is one. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly problem: ProblemDetail;

  constructor(status: number, problem: ProblemDetail) {
    super(problemMessage(problem, status));
    this.name = "ApiError";
    this.status = status;
    this.code = problem.code ?? (problem.title && /^[A-Z0-9_]+$/.test(problem.title) ? problem.title : `HTTP_${status}`);
    this.problem = problem;
  }

  get fieldErrors(): FieldError[] {
    return Array.isArray(this.problem.errors) ? this.problem.errors : [];
  }
}

/** The human message for a problem: `detail` first, then a non-code `title`, then a fallback. */
export function problemMessage(problem: ProblemDetail, status: number): string {
  if (problem.detail && problem.detail.trim()) return problem.detail;
  if (problem.title && !/^[A-Z0-9_]+$/.test(problem.title)) return problem.title;
  switch (status) {
    case 400:
      return "The request was not valid.";
    case 401:
      return "Your session has expired. Please sign in again.";
    case 403:
      return "You do not have permission to do this.";
    case 404:
      return "Not found.";
    case 409:
      return "This conflicts with existing data.";
    case 429:
      return "Too many requests. Please wait a moment and try again.";
    case 502:
    case 503:
    case 504:
      return "The service is not reachable right now. Please try again shortly.";
    default:
      return problem.title ?? `Request failed (${status}).`;
  }
}

/** Builds an ApiError from any Response (problem+json, plain JSON or text). */
export async function toApiError(res: Response): Promise<ApiError> {
  let problem: ProblemDetail = { status: res.status };
  const text = await res.text().catch(() => "");
  if (text) {
    try {
      const parsed: unknown = JSON.parse(text);
      if (parsed && typeof parsed === "object") problem = { status: res.status, ...(parsed as ProblemDetail) };
    } catch {
      problem = { status: res.status, detail: text.length < 300 ? text : undefined };
    }
  }
  return new ApiError(res.status, problem);
}

/** Any thrown value → something to show the user. */
export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  if (err instanceof Error) return err.message;
  return "Something went wrong.";
}

export function isApiError(err: unknown, code?: string): err is ApiError {
  return err instanceof ApiError && (code === undefined || err.code === code);
}
