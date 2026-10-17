import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import { EmptyState, ErrorState, LoadingState, TrackingState } from "./States";
import { ApiProblem } from "../api/client";
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("shared user states", () => {
  it("announces loading, empty, errors, and stale tracking accessibly", () => {
    const { rerender } = render(<LoadingState label="Loading orders" />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading orders");
    rerender(
      <EmptyState title="No orders yet">
        <p>Check again later.</p>
      </EmptyState>,
    );
    expect(
      screen.getByRole("heading", { name: "No orders yet" }),
    ).toBeVisible();
    rerender(<ErrorState problem={new ApiProblem("rate-limited")} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Wait a moment");
    rerender(<TrackingState state="stale" />);
    expect(screen.getByRole("status")).toHaveTextContent(
      "Last update may be out of date",
    );
  });
  it("honors Retry-After when a different error or another throttle arrives", () => {
    vi.useFakeTimers();
    const { rerender } = render(
      <ErrorState problem={new ApiProblem("network")} onRetry={() => {}} />,
    );
    rerender(
      <ErrorState
        problem={new ApiProblem("rate-limited", 429, 3)}
        onRetry={() => {}}
      />,
    );
    expect(
      screen.getByRole("button", { name: "Try again in 3s" }),
    ).toBeDisabled();
    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(screen.getByRole("button", { name: "Try again" })).toBeEnabled();
    rerender(
      <ErrorState
        problem={new ApiProblem("rate-limited", 429, 3)}
        onRetry={() => {}}
      />,
    );
    expect(
      screen.getByRole("button", { name: "Try again in 3s" }),
    ).toBeDisabled();
    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(screen.getByRole("button", { name: "Try again" })).toBeEnabled();
  });
});
