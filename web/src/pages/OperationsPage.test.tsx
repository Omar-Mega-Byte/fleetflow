import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { ApiProblem } from "../api/client";

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock("../auth/AuthContext", () => ({
  apiClient: api,
  useAuth: () => ({ roles: ["ADMIN"] }),
}));
vi.mock("../features/operations/RecoveryViews", () => ({
  RecoveryViews: () => null,
}));

import { OperationsPage } from "./OperationsPage";

const nativeShowModal = HTMLDialogElement.prototype.showModal;
const nativeClose = HTMLDialogElement.prototype.close;

const initialDemo = { generation: 0, result: "NOT_SEEDED", orderIds: [] };
const seededDemo = {
  generation: 1,
  result: "SEEDED",
  orderIds: ["d409713b-a17b-4057-a801-69ea70c23e20"],
};

function renderPage() {
  return render(
    <MemoryRouter>
      <OperationsPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
  api.get.mockResolvedValue(initialDemo);
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    configurable: true,
    value(this: HTMLDialogElement) { this.setAttribute("open", ""); },
  });
  Object.defineProperty(HTMLDialogElement.prototype, "close", {
    configurable: true,
    value(this: HTMLDialogElement) { this.removeAttribute("open"); },
  });
});
afterEach(() => {
  cleanup();
  if (nativeShowModal) {
    Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
      configurable: true,
      value: nativeShowModal,
    });
  } else {
    delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).showModal;
  }
  if (nativeClose) {
    Object.defineProperty(HTMLDialogElement.prototype, "close", {
      configurable: true,
      value: nativeClose,
    });
  } else {
    delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).close;
  }
});

describe("operator synthetic data actions", () => {
  it("shows pending until the backend confirms seed, then reports its response", async () => {
    let resolvePost!: (value: typeof seededDemo) => void;
    api.post.mockImplementation(
      () => new Promise<typeof seededDemo>((resolve) => (resolvePost = resolve)),
    );
    const user = userEvent.setup();
    const { container } = renderPage();

    await user.click(
      await screen.findByRole("button", { name: "Prepare synthetic demo" }),
    );
    const dialog = screen.getByRole("dialog", {
      name: "Prepare synthetic demo",
    });
    await user.type(
      screen.getByLabelText("Audit reason"),
      "Prepare the browser review dataset",
    );
    await user.click(
      within(dialog).getByRole("button", { name: "Prepare synthetic demo" }),
    );

    expect(await screen.findByRole("button", { name: "Applying…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Close dialog" })).toBeDisabled();
    expect(
      screen.queryByText(/Synthetic demo prepared successfully/),
    ).not.toBeInTheDocument();
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(dialog).toBeInTheDocument();

    resolvePost(seededDemo);
    expect(
      await screen.findByText("Synthetic demo prepared successfully."),
    ).toBeVisible();
    expect(container.querySelector(".success-state")).toHaveTextContent(
      "generation 1 with 1 synthetic order",
    );
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2));
  });

  it("keeps reset conflicts actionable and does not announce success", async () => {
    api.post.mockRejectedValue(new ApiProblem("conflict", 409));
    const user = userEvent.setup();
    renderPage();

    await user.click(
      await screen.findByRole("button", { name: "Reset demo generation" }),
    );
    await user.type(
      screen.getByLabelText("Audit reason"),
      "Reset after the review",
    );
    await user.type(
      screen.getByLabelText("Type RESET FLEETFLOW DEMO"),
      "RESET FLEETFLOW DEMO",
    );
    await user.click(
      screen.getByRole("button", { name: "Reset synthetic demo" }),
    );

    expect(
      await screen.findByRole("heading", { name: "This information changed" }),
    ).toBeVisible();
    expect(
      screen.queryByText(/Synthetic demo reset successfully/),
    ).not.toBeInTheDocument();
  });

  it("retries an interrupted reset with the same request and idempotency key", async () => {
    const resetDemo = {
      generation: 2,
      result: "RESET",
      orderIds: [],
    };
    api.post
      .mockRejectedValueOnce(new ApiProblem("timeout"))
      .mockResolvedValueOnce(resetDemo);
    const user = userEvent.setup();
    renderPage();

    await user.click(
      await screen.findByRole("button", { name: "Reset demo generation" }),
    );
    await user.type(
      screen.getByLabelText("Audit reason"),
      "Recover an interrupted demo reset",
    );
    await user.type(
      screen.getByLabelText("Type RESET FLEETFLOW DEMO"),
      "RESET FLEETFLOW DEMO",
    );
    await user.click(
      screen.getByRole("button", { name: "Reset synthetic demo" }),
    );

    expect(await screen.findByRole("heading", { name: "Request timed out" })).toBeVisible();
    expect(
      screen.getByText(/The result is unknown\. Retry this same request/),
    ).toBeVisible();
    expect(screen.getByLabelText("Audit reason")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Close dialog" })).toBeDisabled();
    expect(screen.queryByText(/Synthetic demo reset successfully/)).not.toBeInTheDocument();

    const firstCall = api.post.mock.calls[0]!;
    const firstOptions = firstCall[3] as { idempotencyKey: string };
    await user.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByText("Synthetic demo reset successfully.")).toBeVisible();
    const secondCall = api.post.mock.calls[1]!;
    const secondOptions = secondCall[3] as { idempotencyKey: string };
    expect(secondCall[1]).toEqual(firstCall[1]);
    expect(secondOptions.idempotencyKey).toBe(firstOptions.idempotencyKey);
  });

  it.each([
    ["rate-limited", 429, "Please wait a moment"],
    ["unavailable", 503, "Temporarily unavailable"],
  ] as const)("reports reset %s HTTP %i without claiming success", async (kind, status, title) => {
    api.post.mockRejectedValue(new ApiProblem(kind, status));
    const user = userEvent.setup();
    renderPage();

    await user.click(
      await screen.findByRole("button", { name: "Reset demo generation" }),
    );
    await user.type(screen.getByLabelText("Audit reason"), "Retry a safe reset");
    await user.type(
      screen.getByLabelText("Type RESET FLEETFLOW DEMO"),
      "RESET FLEETFLOW DEMO",
    );
    await user.click(
      screen.getByRole("button", { name: "Reset synthetic demo" }),
    );

    expect(await screen.findByRole("heading", { name: title })).toBeVisible();
    expect(screen.getByRole("alert")).toHaveTextContent(status === 429 ? "Wait a moment" : "temporarily unavailable");
    expect(screen.queryByText(/Synthetic demo reset successfully/)).not.toBeInTheDocument();
  });
});
