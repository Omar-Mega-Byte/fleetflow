import type { Page } from "@playwright/test";
import { installOidc } from "./oidc";
export const ids = {
  order: "10000000-0000-4000-8000-000000000001",
  created: "20000000-0000-4000-8000-000000000002",
  failed: "30000000-0000-4000-8000-000000000003",
  branch: "40000000-0000-4000-8000-000000000004",
  driver: "50000000-0000-4000-8000-000000000005",
  event: "60000000-0000-4000-8000-000000000006",
  attempt: "70000000-0000-4000-8000-000000000007",
  offer: "80000000-0000-4000-8000-000000000008",
  notification: "90000000-0000-4000-8000-000000000009",
};
export async function productFixture(
  page: Page,
  roles = ["CUSTOMER", "MERCHANT", "DRIVER", "DISPATCHER", "ADMIN"],
) {
  await installOidc(page);
  const now = new Date().toISOString();
  const rows = [
    {
      id: ids.order,
      status: "IN_TRANSIT",
      branchName: "Downtown Branch",
      totalAmount: 245,
    },
    {
      id: ids.created,
      status: "CREATED",
      branchName: "Zamalek Dispatch Point",
      totalAmount: 180,
    },
    {
      id: ids.failed,
      status: "DISPATCH_FAILED",
      branchName: "Maadi Parcel Hub",
      totalAmount: 320,
    },
  ].map((o) => ({
    ...o,
    branchId: ids.branch,
    currency: "EGP",
    createdAt: now,
  }));
  const geometry = {
    type: "MultiPolygon",
    coordinates: [
      [
        [
          [31.219, 30.044],
          [31.222, 30.061],
          [31.235, 30.067],
          [31.244, 30.054],
          [31.237, 30.04],
          [31.219, 30.044],
        ],
      ],
    ],
  };
  let zone = {
    id: ids.branch,
    branchId: ids.branch,
    name: "Central delivery zone",
    geometry,
    active: true,
    version: 12,
    createdAt: now,
    updatedAt: now,
  };
  let policy = { mode: "ENFORCED", version: 4 };
  let driverState = "ONLINE";
  let offerState = "";
  let deliveryState = "ASSIGNED";
  let failed = true;
  let generation = 2;
  const requests: Array<{
    path: string;
    method: string;
    body: unknown;
    key: string | undefined;
  }> = [];
  const unexpected: string[] = [];
  const snapshot = (id: string) => {
    const o = rows.find((o) => o.id === id)!;
    return {
      orderId: id,
      orderStatus: o.status,
      orderVersion: 8,
      deliveryStatus: o.status === "IN_TRANSIT" ? "IN_TRANSIT" : null,
      observedAt: now,
      position:
        o.status === "IN_TRANSIT"
          ? { latitude: 30.04442, longitude: 31.235712, reportedAt: now }
          : null,
      terminal: ["DELIVERED", "CANCELLED", "DELIVERY_FAILED"].includes(
        o.status,
      ),
    };
  };
  await page.route("https://tile.openstreetmap.org/**", (route) =>
    route.fulfill({
      contentType: "image/svg+xml",
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"><rect width="256" height="256" fill="#edf2f0"/></svg>',
    }),
  );
  await page.route("**/api/v1/**", async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const path = url.pathname;
    const method = req.method();
    const body = method === "GET" ? undefined : req.postDataJSON();
    requests.push({
      path: url.pathname + url.search,
      method,
      body,
      key: req.headers()["idempotency-key"],
    });
    const reply = (json: unknown, status = 200) =>
      route.fulfill({ json, status });
    if (path === "/api/v1/session") return reply({ roles });
    if (path === "/api/v1/orders" && method === "GET") {
      const filtered = rows.filter(
        (o) =>
          !url.searchParams.get("status") ||
          o.status === url.searchParams.get("status"),
      );
      return reply({
        content: filtered,
        page: Number(url.searchParams.get("page") ?? 0),
        size: 20,
        totalElements: filtered.length,
        totalPages: 1,
      });
    }
    if (path.match(/\/orders\/[^/]+\/tracking\/stream$/)) {
      const id = path.split("/")[4]!;
      return route.fulfill({
        contentType: "text/event-stream",
        body: `event: snapshot\ndata: ${JSON.stringify(snapshot(id))}\n\n`,
      });
    }
    if (path.match(/^\/api\/v1\/orders\/[^/]+\/tracking$/))
      return reply(snapshot(path.split("/")[4]!));
    if (path.match(/^\/api\/v1\/orders\/[^/]+$/) && method === "GET") {
      const order = rows.find((o) => o.id === path.split("/")[4])!;
      return reply({
        ...order,
        customerId: ids.driver,
        version: 8,
        customerName: "Synthetic customer",
        merchantName: "Cairo Parcel Kitchen",
        pickup: { line1: "12 Market Street", city: "Cairo" },
        delivery: { line1: "Tahrir Square", city: "Cairo" },
        items: [
          {
            lineNumber: 1,
            description: "Synthetic parcel delivery",
            quantity: 1,
            unitAmount: order.totalAmount,
            lineAmount: order.totalAmount,
          },
        ],
      });
    }
    if (path.endsWith("/cancel") && path.startsWith("/api/v1/orders/")) {
      if (!body?.reason) return reply({}, 400);
      rows.find((o) => o.id === path.split("/")[4])!.status = "CANCELLED";
      return reply({ status: "CANCELLED" });
    }
    if (path === "/api/v1/notifications/me")
      return reply({
        content: [
          {
            id: ids.notification,
            eventId: ids.event,
            orderId: ids.order,
            assignmentId: null,
            kind: "ORDER_CONFIRMED",
            state: "CREATED",
            createdAt: now,
          },
        ],
      });
    if (path === "/api/v1/dispatch/analytics/daily")
      return reply({
        content: [
          { day: now.slice(0, 10), status: "CONFIRMED", total: 4 },
          { day: now.slice(0, 10), status: "IN_TRANSIT", total: 2 },
        ],
      });
    if (path.endsWith("/attempts"))
      return reply({
        content: [
          {
            id: ids.attempt,
            sequence: 3,
            startedAt: now,
            completedAt: now,
            outcome: "RANKED",
            configVersion: "matching-v1",
            zoneMode: "ENFORCED",
            zoneId: ids.branch,
            candidates: [
              {
                driverId: ids.driver,
                reason: "ELIGIBLE",
                distanceMeters: 1420,
                gpsAgeMillis: 12000,
                score: 0.86,
                rank: 1,
                straightLineSeconds: 204,
              },
              {
                driverId: ids.event,
                reason: "MISSING_LOCATION",
                distanceMeters: null,
                gpsAgeMillis: null,
                score: null,
                rank: null,
                straightLineSeconds: null,
              },
            ],
          },
        ],
      });
    if (path.endsWith("/match") || path.endsWith("/retry"))
      return reply({ attemptId: ids.attempt, outcome: "RANKED" });
    if (path.endsWith("/assignments"))
      return reply({
        content: offerState
          ? [
              {
                offerId: ids.offer,
                driverId: ids.driver,
                attemptId: ids.attempt,
                rank: 1,
                status: offerState,
                offeredAt: now,
                expiresAt: new Date(Date.now() + 60000).toISOString(),
                respondedAt: null,
              },
            ]
          : [],
      });
    if (path.startsWith("/api/v1/dispatch/") && path.endsWith("/offers")) {
      offerState = "OFFERED";
      return reply({
        offerId: ids.offer,
        status: offerState,
        expiresAt: new Date(Date.now() + 60000).toISOString(),
      });
    }
    if (path.endsWith("/incidents")) return reply({ content: [] });
    if (path === `/api/v1/merchant-branches/${ids.branch}/orders`)
      return reply({
        content: rows,
        page: 0,
        size: 20,
        totalElements: 3,
        totalPages: 1,
      });
    if (
      path.includes("/merchant-branches/") &&
      path.includes("/orders/") &&
      method === "POST"
    ) {
      const order = rows.find((o) => o.id === path.split("/")[6])!;
      order.status = path.endsWith("/confirm")
        ? "CONFIRMED"
        : path.endsWith("/start-preparation")
          ? "PREPARING"
          : path.endsWith("/ready-for-pickup")
            ? "READY_FOR_PICKUP"
            : "CANCELLED";
      return reply({ id: order.id, status: order.status, version: 9 });
    }
    if (path.endsWith("/zone-policy")) {
      if (method === "PUT") {
        policy = { mode: body.mode, version: policy.version + 1 };
      }
      return reply(policy);
    }
    if (path.endsWith("/zones") && method === "GET") return reply([zone]);
    if (path.includes("/zones") && method !== "GET") {
      if (body.expectedVersion != null && body.expectedVersion !== zone.version)
        return reply({}, 409);
      zone = { ...zone, ...body, version: zone.version + 1 };
      return reply(zone);
    }
    if (path === "/api/v1/drivers/me")
      return reply({
        id: ids.driver,
        active: true,
        suspended: false,
        operationalStatus: driverState,
        version: 3,
      });
    if (path === "/api/v1/drivers/me/offers")
      return reply({
        content: offerState
          ? [
              {
                offerId: ids.offer,
                orderId: ids.order,
                status: offerState,
                offeredAt: now,
                expiresAt: new Date(Date.now() + 60000).toISOString(),
                pickupLine1: "12 Market Street",
                pickupCity: "Cairo",
                deliveryLine1: "Tahrir Square",
                deliveryCity: "Cairo",
              },
            ]
          : [],
      });
    if (path.match(/\/offers\/[^/]+\/(accept|reject)$/)) {
      offerState = path.endsWith("/accept") ? "ACCEPTED" : "REJECTED";
      return reply({
        offerId: ids.offer,
        status: offerState,
        expiresAt: new Date(Date.now() + 60000).toISOString(),
      });
    }
    if (path === "/api/v1/drivers/me/deliveries")
      return reply({
        content: [
          {
            deliveryId: ids.order,
            orderId: ids.order,
            status: deliveryState,
            sequence: 1,
            pickupLine1: "12 Market Street",
            pickupCity: "Cairo",
            deliveryLine1: "Tahrir Square",
            deliveryCity: "Cairo",
          },
        ],
      });
    if (path.includes("/deliveries/") && method === "POST") {
      deliveryState = path.endsWith("/pickup")
        ? "PICKED_UP"
        : path.endsWith("/start-transit")
          ? "IN_TRANSIT"
          : path.endsWith("/complete")
            ? "DELIVERED"
            : "FAILED";
      return reply({
        deliveryId: ids.order,
        orderId: ids.order,
        deliveryStatus: deliveryState,
        orderStatus: deliveryState,
      });
    }
    if (path.match(/\/drivers\/me\/(online|available|pause|offline)$/)) {
      driverState = path.endsWith("/available")
        ? "AVAILABLE"
        : path.endsWith("/pause")
          ? "PAUSED"
          : path.endsWith("/offline")
            ? "OFFLINE"
            : "ONLINE";
      return reply({
        id: ids.driver,
        operationalStatus: driverState,
        version: 4,
      });
    }
    if (path.endsWith("/heartbeat") || path.endsWith("/location"))
      return route.fulfill({ status: 204 });
    if (path === "/api/v1/operations/outbox/failed")
      return reply({
        content: failed
          ? [
              {
                eventId: ids.event,
                aggregateId: ids.order,
                eventType: "ORDER_CONFIRMED",
                attempts: 3,
                replayCount: 0,
                errorCode: "PUBLISH_FAILED",
                occurredAt: now,
              },
            ]
          : [],
      });
    if (path.includes("/outbox/") && path.endsWith("/replay")) {
      failed = false;
      return reply({ eventId: ids.event, outcome: "REPLAYED" });
    }
    if (path === "/api/v1/operations/events/quarantined")
      return reply({
        content: [
          {
            consumer: "notification-projector",
            topic: "fleetflow.events",
            partition: 0,
            offset: 42,
            errorCode: "INVALID_ENVELOPE",
            quarantinedAt: now,
          },
        ],
      });
    if (path.startsWith("/api/v1/demo")) {
      if (method === "POST" && path.endsWith("/reset")) generation++;
      return reply({ generation, result: "READY", orderIds: [ids.order] });
    }
    unexpected.push(`${method} ${path}`);
    return reply({}, 404);
  });
  return { requests, unexpected };
}
