import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import type { OrderPage } from "../contracts";

const { fetchMock, authMock } = vi.hoisted(() => ({
  fetchMock: vi.fn(),
  authMock: {
    reauthenticate: vi.fn(),
    expireSession: vi.fn(),
    user: { access_token: "memory-only-test-token" },
  },
}));
vi.mock("../auth/AuthContext", async () => {
  const { ApiClient } = await import("../api/client");
  return {
    apiClient: new ApiClient({
      token: async () => "memory-only-test-token",
      fetcher: fetchMock,
    }),
    useAuth: () => authMock,
  };
});
vi.mock("../sse/tracking", () => ({
  connectTrackingStream: vi.fn(
    async (options: { onState: (state: string) => void }) =>
      options.onState("live"),
  ),
}));
import { CustomerOrdersPage } from "./CustomerOrdersPage";
import { CustomerOrderDetailPage } from "./CustomerOrderDetailPage";

const id = "00000000-0000-0000-0000-000000000001";
const page: OrderPage = {
  content: [
    {
      id,
      branchId: id,
      status: "CREATED",
      branchName: "Downtown Branch",
      currency: "EGP",
      totalAmount: 245,
      createdAt: "2026-10-14T10:00:00Z",
    },
  ],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
};
const detail = {
  ...page.content[0],
  customerId: id,
  version: 2,
  customerName: "Demo Customer",
  merchantName: "Cairo Parcel Kitchen",
  pickup: { line1: "Demo Street", city: "Cairo" },
  delivery: { line1: "Other Street", city: "Cairo" },
  items: [
    {
      lineNumber: 1,
      description: "Synthetic parcel delivery",
      quantity: 1,
      unitAmount: 245,
      lineAmount: 245,
    },
  ],
};
const tracking = {
  orderId: id,
  orderStatus: "CREATED",
  orderVersion: 2,
  deliveryStatus: null,
  observedAt: "2026-10-14T10:01:00Z",
  position: null,
  terminal: false,
};

beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);
describe("customer pages through the real API boundary", () => {
  it("decodes the order response and renders a truthful synthetic empty state", async () => {
    fetchMock.mockResolvedValueOnce(Response.json(page));
    render(
      <MemoryRouter>
        <CustomerOrdersPage />
      </MemoryRouter>,
    );
    expect(
      await screen.findByRole("link", { name: /Downtown Branch/ }),
    ).toBeVisible();
    expect(screen.getByText(/CREATED/)).toBeVisible();
    cleanup();
    fetchMock.mockResolvedValueOnce(
      Response.json({
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      }),
    );
    render(
      <MemoryRouter>
        <CustomerOrdersPage />
      </MemoryRouter>,
    );
    expect(
      await screen.findByRole("heading", { name: "No orders yet" }),
    ).toBeVisible();
  });
  it("renders lifecycle status from decoded owner-scoped order and tracking responses", async () => {
    fetchMock.mockImplementation(async (url: string) =>
      Response.json(url.endsWith("/tracking") ? tracking : detail),
    );
    render(
      <MemoryRouter initialEntries={[`/customer/orders/${id}`]}>
        <Routes>
          <Route
            path="/customer/orders/:id"
            element={<CustomerOrderDetailPage />}
          />
        </Routes>
      </MemoryRouter>,
    );
    expect(
      await screen.findByRole("heading", { name: "Downtown Branch" }),
    ).toBeVisible();
    expect(
      screen.getByRole("heading", { name: "Order received" }),
    ).toBeVisible();
    expect(screen.queryByText(/30\.\d+,\s*31\.\d+/)).not.toBeInTheDocument();
  });
  it("turns a malformed nested response into safe feedback instead of crashing rendering", async () => {
    fetchMock.mockResolvedValueOnce(
      Response.json({
        ...page,
        content: [{ ...page.content[0], createdAt: "private-invalid-date" }],
      }),
    );
    render(
      <MemoryRouter>
        <CustomerOrdersPage />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Something went wrong",
    );
    expect(screen.queryByText("private-invalid-date")).not.toBeInTheDocument();
  });
  it("does not present missing assigned-driver coordinates as live location", async () => {
    fetchMock.mockImplementation(async (url: string) =>
      Response.json(
        url.endsWith("/tracking")
          ? {
              ...tracking,
              orderStatus: "DRIVER_ASSIGNED",
              deliveryStatus: "ASSIGNED",
            }
          : detail,
      ),
    );
    render(
      <MemoryRouter initialEntries={[`/customer/orders/${id}`]}>
        <Routes>
          <Route
            path="/customer/orders/:id"
            element={<CustomerOrderDetailPage />}
          />
        </Routes>
      </MemoryRouter>,
    );
    expect(
      await screen.findByText(/A current delivery location is unavailable/),
    ).toBeVisible();
    expect(
      screen.queryByText(/appears after a driver accepts/),
    ).not.toBeInTheDocument();
  });
  it.each([
    [409, "This information changed"],
    [429, "Please wait a moment"],
    [503, "Temporarily unavailable"],
  ])("renders safe feedback for HTTP %i", async (status, title) => {
    fetchMock.mockImplementation(async () =>
      Response.json(
        { message: "sensitive server detail" },
        { status: Number(status), headers: { "Retry-After": "1" } },
      ),
    );
    render(
      <MemoryRouter>
        <CustomerOrdersPage />
      </MemoryRouter>,
    );
    expect(
      await screen.findByRole("heading", { name: String(title) }),
    ).toBeVisible();
    expect(
      screen.queryByText("sensitive server detail"),
    ).not.toBeInTheDocument();
  });
});
