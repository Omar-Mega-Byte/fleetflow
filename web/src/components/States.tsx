import type { ReactNode } from "react";
import type { ApiProblem } from "../api/client";
import { useNow } from "./Expiry";

export function LoadingState({
  label = "Loading your information",
}: {
  label?: string;
}) {
  return (
    <div className="state-card loading" role="status" aria-live="polite">
      <span className="sr-only">{label}</span>
      <div className="skeleton-header" aria-hidden="true" />
      <div className="skeleton-row" aria-hidden="true" />
      <div className="skeleton-row" aria-hidden="true" />
      <div className="skeleton-row" aria-hidden="true" />
    </div>
  );
}

export function SuccessState({ children }: { children: ReactNode }) {
  return (
    <section className="state-card success-state" role="status">
      {children}
    </section>
  );
}

export function EmptyState({
  title,
  children,
}: {
  title: string;
  children?: ReactNode;
}) {
  return (
    <section className="state-card empty-state">
      <svg
        className="empty-route"
        viewBox="0 0 200 74"
        fill="none"
        aria-hidden="true"
      >
        <path
          className="empty-grid"
          d="M0 18h200M0 37h200M0 56h200M25 0v74M62 0v74M100 0v74M138 0v74M175 0v74"
        />
        <path className="empty-path" d="M30 49h39Q85 49 85 34t17-15h68" />
        <circle cx="30" cy="49" r="5" />
        <circle cx="170" cy="19" r="5" />
      </svg>
      <h2>{title}</h2>
      {children}
    </section>
  );
}

export function ErrorState({
  problem,
  onRetry,
  onReauthenticate,
}: {
  problem: ApiProblem;
  onRetry?: () => void;
  onReauthenticate?: () => void;
}) {
  const now = useNow();
  const remaining = Math.min(
    problem.retryAfterSeconds ?? 0,
    Math.max(0, Math.ceil(((problem.retryAt ?? now) - now) / 1000)),
  );
  const titles = {
    validation: "Review your request",
    unauthenticated: "Sign in again",
    forbidden: "Access unavailable",
    "not-found": "Information not found",
    conflict: "This information changed",
    "too-large": "Request too large",
    "rate-limited": "Please wait a moment",
    unavailable: "Temporarily unavailable",
    timeout: "Request timed out",
    network: "Connection unavailable",
    unexpected: "Something went wrong",
    cancelled: "Request cancelled",
  };
  return (
    <section className="state-card error-state" role="alert">
      <h2>{titles[problem.kind]}</h2>
      <p>{problem.message}</p>
      {problem.kind === "unauthenticated" && onReauthenticate && (
        <button className="button button-primary" onClick={onReauthenticate}>
          Sign in again
        </button>
      )}
      {onRetry &&
        !["forbidden", "not-found", "unauthenticated", "cancelled"].includes(
          problem.kind,
        ) && (
          <button
            className="button button-secondary"
            disabled={remaining > 0}
            onClick={onRetry}
          >
            {remaining > 0 ? `Try again in ${remaining}s` : "Try again"}
          </button>
        )}
    </section>
  );
}

export function TrackingState({
  state,
}: {
  state: "connecting" | "live" | "stale" | "unavailable" | "ended";
}) {
  const copy = {
    connecting: ["Connecting", "Looking for the latest update."],
    live: ["Live updates", "Status updates will appear as they happen."],
    stale: [
      "Last update may be out of date",
      "Live connection paused. We will refresh when it reconnects.",
    ],
    unavailable: [
      "Live tracking unavailable",
      "The order status is available; live updates could not connect.",
    ],
    ended: ["Tracking ended", "This order has reached its final status."],
  } as const;
  return (
    <div
      className={`tracking-state tracking-${state}`}
      role="status"
      aria-live="polite"
    >
      <span className="tracking-dot" aria-hidden="true" />
      <span>
        <strong>{copy[state][0]}</strong>
        <small>{copy[state][1]}</small>
      </span>
    </div>
  );
}
