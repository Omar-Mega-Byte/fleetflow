import { act, cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SyntheticMovement } from "./SyntheticMovement";
afterEach(cleanup);
const route = {
  generation: 2,
  points: Array.from({ length: 5 }, (_, i) => ({
    latitude: 30 + i / 100,
    longitude: 31 + i / 100,
  })),
};
describe("controlled synthetic movement", () => {
  it("advances only after backend confirmation and stops at the last fixture position", async () => {
    const user = userEvent.setup();
    const send = vi.fn();
    render(<SyntheticMovement route={route} disabled={false} onSend={send} />);
    await user.click(
      screen.getByRole("button", { name: "Send synthetic pickup" }),
    );
    expect(
      screen.getByText(/of 5 synthetic positions confirmed/),
    ).toHaveTextContent("0 of 5");
    expect(send.mock.calls[0]![0]).toEqual(route.points[0]);
    act(() => send.mock.calls[0]![1]());
    for (let step = 1; step < 5; step++) {
      await user.click(
        screen.getByRole("button", { name: "Send next synthetic position" }),
      );
      expect(send.mock.calls[step]![0]).toEqual(route.points[step]);
      act(() => send.mock.calls[step]![1]());
    }
    expect(
      screen.getByText(/of 5 synthetic positions confirmed/),
    ).toHaveTextContent("5 of 5");
    expect(
      screen.queryByRole("button", { name: "Send next synthetic position" }),
    ).not.toBeInTheDocument();
    await user.click(
      screen.getByRole("button", { name: "Restart synthetic route" }),
    );
    expect(send).toHaveBeenCalledTimes(5);
    expect(
      screen.getByText(/of 5 synthetic positions confirmed/),
    ).toHaveTextContent("0 of 5");
  });
  it("prevents movement while the driver is unavailable or another command is pending", () => {
    render(<SyntheticMovement route={route} disabled onSend={vi.fn()} />);
    expect(
      screen.getByRole("button", { name: "Send synthetic pickup" }),
    ).toBeDisabled();
  });
});
