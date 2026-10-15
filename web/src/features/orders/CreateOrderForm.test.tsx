import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProblem } from "../../api/client";
const mock = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock("../../auth/AuthContext", () => ({ apiClient: { post: mock.post } }));
import { CreateOrderForm } from "./CreateOrderForm";
beforeEach(() => vi.clearAllMocks());
describe("local creation recovery", () => {
  it("retains the idempotency key after an ambiguous failure and changes it when the draft changes", async () => {
    mock.post.mockRejectedValue(new ApiProblem("network"));
    const user = userEvent.setup();
    const { container } = render(<CreateOrderForm onCreated={vi.fn()} />);
    fireEvent.submit(container.querySelector("form")!);
    await screen.findByRole("alert");
    const first = mock.post.mock.calls[0]![3].idempotencyKey;
    fireEvent.submit(container.querySelector("form")!);
    await waitFor(() => expect(mock.post).toHaveBeenCalledTimes(2));
    expect(mock.post.mock.calls[1]![3].idempotencyKey).toBe(first);
    await user.type(
      screen.getByLabelText("Item description"),
      "Changed parcel",
    );
    fireEvent.submit(container.querySelector("form")!);
    await waitFor(() => expect(mock.post).toHaveBeenCalledTimes(3));
    expect(mock.post.mock.calls[2]![3].idempotencyKey).not.toBe(first);
  });
});
