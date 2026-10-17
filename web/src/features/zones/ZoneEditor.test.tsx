import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProblem } from "../../api/client";
import type { MapSurfaceProps } from "../../components/MapSurface";
import type { Zone } from "../contracts";
const api = vi.hoisted(() => ({ post: vi.fn(), put: vi.fn() }));
vi.mock("../../auth/AuthContext", () => ({ apiClient: api }));
vi.mock("../../components/MapSurface", () => ({
  MapSurface: ({ onPoint }: MapSurfaceProps) => (
    <div>
      {(
        [
          [31.2, 30],
          [31.28, 30],
          [31.24, 30.08],
        ] as Array<[number, number]>
      ).map((point, index) => (
        <button key={index} type="button" onClick={() => onPoint?.(point)}>
          Map vertex {index + 1}
        </button>
      ))}
    </div>
  ),
}));
import { ZoneEditor } from "./ZoneEditor";
const zone: Zone = {
  id: "78396877-48c5-4e27-87dd-ae2d260631cc",
  branchId: "78396877-48c5-4e27-87dd-ae2d260631cd",
  name: "Central",
  active: true,
  version: 7,
  createdAt: "2026-10-17T00:00:00Z",
  updatedAt: "2026-10-17T00:00:00Z",
  geometry: {
    type: "MultiPolygon",
    coordinates: [
      [
        [
          [31.2, 30],
          [31.28, 30],
          [31.24, 30.08],
          [31.2, 30],
        ],
      ],
    ],
  },
};
beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);
const renderEditor = (value: Zone | null, saved = vi.fn(), blocked = vi.fn()) =>
  render(
    <ZoneEditor
      branch={zone.branchId}
      zone={value}
      backgrounds={[zone]}
      onSaved={saved}
      onCloseBlocked={blocked}
    />,
  );
describe("delivery boundary editing", () => {
  it("cannot save the old polygon after starting a replacement or undoing below three vertices", async () => {
    const user = userEvent.setup();
    renderEditor(zone);
    await user.click(screen.getByRole("button", { name: "Draw new boundary" }));
    await user.click(screen.getByRole("button", { name: "Save replacement" }));
    expect(api.put).not.toHaveBeenCalled();
    for (const index of [1, 2, 3])
      await user.click(
        screen.getByRole("button", { name: `Map vertex ${index}` }),
      );
    await user.click(screen.getByRole("button", { name: "Undo vertex" }));
    await user.click(screen.getByRole("button", { name: "Save replacement" }));
    expect(api.put).not.toHaveBeenCalled();
    expect(
      screen.getByText(/Incomplete drawings cannot be saved/),
    ).toBeVisible();
  });
  it("keeps geometry intact for a name-only edit and sends the optimistic version", async () => {
    api.put.mockResolvedValue(zone);
    const user = userEvent.setup();
    renderEditor(zone);
    await user.clear(screen.getByLabelText("Zone name"));
    await user.type(screen.getByLabelText("Zone name"), "Renamed boundary");
    await user.click(screen.getByRole("button", { name: "Save replacement" }));
    expect(api.put.mock.calls[0]![1]).toEqual({
      name: "Renamed boundary",
      active: true,
      geometry: zone.geometry,
      expectedVersion: 7,
    });
  });
  it("retains a committed create request after connection loss and blocks editing until confirmation", async () => {
    api.post
      .mockRejectedValueOnce(new ApiProblem("network"))
      .mockResolvedValueOnce(zone);
    const user = userEvent.setup();
    const saved = vi.fn();
    const blocked = vi.fn();
    renderEditor(null, saved, blocked);
    await user.type(screen.getByLabelText("Zone name"), "New delivery area");
    await user.click(screen.getByRole("button", { name: "Draw new boundary" }));
    for (const index of [1, 2, 3])
      await user.click(
        screen.getByRole("button", { name: `Map vertex ${index}` }),
      );
    await user.click(screen.getByRole("button", { name: "Create zone" }));
    await screen.findByRole("alert");
    expect(screen.getByLabelText("Zone name")).toBeDisabled();
    expect(blocked).toHaveBeenLastCalledWith(true);
    expect(saved).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(api.post.mock.calls[1]![1]).toEqual(api.post.mock.calls[0]![1]);
    expect(api.post.mock.calls[1]![3].idempotencyKey).toBe(
      api.post.mock.calls[0]![3].idempotencyKey,
    );
  });
});
