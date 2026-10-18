import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { ApiProblem } from "../api/client";
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock("../auth/AuthContext", () => ({ apiClient: api }));
import { DriverPage } from "./DriverPage";
const fixture = {
  generation: 2,
  points: Array.from({ length: 5 }, (_, i) => ({
    latitude: 30 + i / 100,
    longitude: 31 + i / 100,
  })),
};
beforeEach(() => {
  vi.resetAllMocks();
  api.post.mockResolvedValue(undefined);
  api.get.mockImplementation((path: string) =>
    path === "/v1/drivers/me"
      ? Promise.resolve({
          id: "driver",
          active: true,
          suspended: false,
          operationalStatus: "AVAILABLE",
          version: 1,
        })
      : path === "/v1/demo/driver-route"
        ? Promise.resolve(fixture)
        : Promise.resolve({ content: [] }),
  );
});
afterEach(cleanup);
const show = () =>
  render(
    <MemoryRouter>
      <DriverPage />
    </MemoryRouter>,
  );
describe("driver position privacy", () => {
  it("uses server-provided fixture coordinates without offering device GPS to a synthetic reviewer", async () => {
    const gps = vi.fn();
    Object.defineProperty(navigator, "geolocation", {
      configurable: true,
      value: { getCurrentPosition: gps },
    });
    const user = userEvent.setup();
    show();
    await screen.findByRole("heading", { name: "Synthetic movement" });
    expect(
      screen.queryByRole("button", { name: "Share current position" }),
    ).not.toBeInTheDocument();
    await user.click(
      screen.getByRole("button", { name: "Send synthetic pickup" }),
    );
    await screen.findByText("1 of 5 synthetic positions confirmed.");
    expect(api.post.mock.calls[0]![1]).toEqual({
      ...fixture.points[0],
      reportedAt: expect.any(String),
    });
    expect(gps).not.toHaveBeenCalled();
  });
  it("allows explicit device sharing only after the account is known to be outside demo movement", async () => {
    api.get.mockImplementation((path: string) =>
      path === "/v1/demo/driver-route"
        ? Promise.reject(new ApiProblem("forbidden", 403))
        : path === "/v1/drivers/me"
          ? Promise.resolve({
              active: true,
              suspended: false,
              operationalStatus: "AVAILABLE",
              version: 1,
            })
          : Promise.resolve({ content: [] }),
    );
    show();
    expect(
      await screen.findByRole("button", { name: "Share current position" }),
    ).toBeEnabled();
    expect(
      screen.queryByRole("heading", { name: "Synthetic movement" }),
    ).not.toBeInTheDocument();
  });
  it("keeps device sharing hidden when the optional fixture lookup is unavailable", async () => {
    api.get.mockRejectedValue(new ApiProblem("unavailable", 503));
    show();
    await screen.findAllByRole("heading", { name: "Temporarily unavailable" });
    expect(
      screen.queryByRole("button", { name: "Share current position" }),
    ).not.toBeInTheDocument();
  });
});
