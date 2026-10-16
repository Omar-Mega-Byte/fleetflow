import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock("../../auth/AuthContext", () => ({ apiClient: api }));

import { RecoveryViews } from "./RecoveryViews";

const eventId = "10000000-0000-4000-8000-000000000001";
const originalShowModal = HTMLDialogElement.prototype.showModal;
const originalClose = HTMLDialogElement.prototype.close;

beforeEach(() => {
  vi.resetAllMocks();
  api.get.mockImplementation((path: string) => Promise.resolve(path.endsWith("/failed")
    ? { content: [{ eventId, aggregateId: eventId, eventType: "ORDER_STATUS_CHANGED",
      attempts: 20, replayCount: 3, errorCode: "SEND_FAILED", occurredAt: "2026-10-16T12:00:00Z" }] }
    : { content: [] }));
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
  if (originalShowModal) HTMLDialogElement.prototype.showModal = originalShowModal;
  else delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).showModal;
  if (originalClose) HTMLDialogElement.prototype.close = originalClose;
  else delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).close;
});

describe("operator outbox replay feedback", () => {
  it.each([
    ["REPLAYED", "Replay accepted. Publication continues asynchronously.", "success"],
    ["REPLAY_LIMIT_REACHED", "Replay limit reached. The event remains failed.", "danger"],
    ["NOT_REPLAYABLE", "Event was not queued for replay. Refresh and inspect its current state.", "danger"],
  ])("reports the server outcome %s", async (outcome, message, tone) => {
    api.post.mockResolvedValue({ eventId, outcome });
    const user = userEvent.setup();
    render(<RecoveryViews />);

    await user.click(await screen.findByRole("button", { name: "Inspect and replay" }));
    await user.type(screen.getByLabelText("Audit reason"), "Operator reviewed the failed event");
    await user.click(screen.getByRole("button", { name: "Confirm event replay" }));

    const notice = await screen.findByText(message);
    expect(notice.closest(".notice")).toHaveClass(`tone-${tone}`);
    expect(api.post).toHaveBeenCalledTimes(1);
    if (outcome !== "REPLAYED") {
      expect(screen.queryByText("Replay accepted. Publication continues asynchronously.")).not.toBeInTheDocument();
    }
  });
});
