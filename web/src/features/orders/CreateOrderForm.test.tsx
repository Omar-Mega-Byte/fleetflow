import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProblem } from "../../api/client";
const mock = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock("../../auth/AuthContext", () => ({ apiClient: { post: mock.post } }));
import { CreateOrderForm } from "./CreateOrderForm";
beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);
describe("local creation recovery", () => {
  it("freezes an unknown outcome and retries the original body and key", async () => {
    mock.post
      .mockRejectedValueOnce(new ApiProblem("network"))
      .mockResolvedValueOnce({ id: "confirmed-order" });
    const onCreated = vi.fn();
    const blocked = vi.fn();
    const user = userEvent.setup();
    const { container } = render(
      <CreateOrderForm onCreated={onCreated} onCloseBlocked={blocked} />,
    );
    await user.type(
      screen.getByLabelText("Item description"),
      "Original parcel",
    );
    fireEvent.submit(container.querySelector("form")!);
    await screen.findByRole("alert");
    const first = mock.post.mock.calls[0]!;
    expect(screen.getByLabelText("Item description")).toBeDisabled();
    expect(blocked).toHaveBeenLastCalledWith(true);
    expect(onCreated).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    await waitFor(() =>
      expect(onCreated).toHaveBeenCalledWith("confirmed-order"),
    );
    expect(mock.post.mock.calls[1]![1]).toEqual(first[1]);
    expect(mock.post.mock.calls[1]![3].idempotencyKey).toBe(
      first[3].idempotencyKey,
    );
  });
  it("allows a corrected draft and fresh key after a definite validation rejection", async () => {
    mock.post.mockRejectedValue(new ApiProblem("validation", 400));
    const user = userEvent.setup();
    const { container } = render(<CreateOrderForm onCreated={vi.fn()} />);
    fireEvent.submit(container.querySelector("form")!);
    await screen.findByRole("alert");
    const first = mock.post.mock.calls[0]![3].idempotencyKey;
    await user.type(
      screen.getByLabelText("Item description"),
      "Corrected parcel",
    );
    fireEvent.submit(container.querySelector("form")!);
    await waitFor(() => expect(mock.post).toHaveBeenCalledTimes(2));
    expect(mock.post.mock.calls[1]![3].idempotencyKey).not.toBe(first);
    expect(mock.post.mock.calls[1]![1].items[0].description).toBe(
      "Corrected parcel",
    );
  });
  it("locks rapid submissions before React renders pending", async () => {
    let finish!: (value: { id: string }) => void;
    mock.post.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const onCreated = vi.fn();
    const { container } = render(<CreateOrderForm onCreated={onCreated} />);
    fireEvent.submit(container.querySelector("form")!);
    fireEvent.submit(container.querySelector("form")!);
    expect(mock.post).toHaveBeenCalledTimes(1);
    await act(async () => finish({ id: "one-order" }));
    expect(onCreated).toHaveBeenCalledTimes(1);
  });
});
