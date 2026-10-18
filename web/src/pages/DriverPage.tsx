import { useState } from "react";
import { apiClient } from "../auth/AuthContext";
import { useCommand, useResource } from "../api/useResource";
import {
  decodeAck,
  decodeDeliveries,
  decodeDriver,
  decodeDriverOffers,
  decodeDemoDriverRoute,
  decodeOfferReceipt,
} from "../features/contracts";
import { Expiry, useNow } from "../components/Expiry";
import { ErrorState } from "../components/States";
import { SyntheticMovement } from "../features/driver/SyntheticMovement";
import {
  Icon,
  Notice,
  Overlay,
  PageHeader,
  Panel,
  ResourceState,
  StatusBadge,
  shortId,
} from "../components/Ui";
const decodeVoid = (v: unknown) => {
  if (v !== undefined) throw new Error("Expected no content");
};
function currentPosition(): Promise<GeolocationPosition> {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error("Location unavailable"));
      return;
    }
    navigator.geolocation.getCurrentPosition(resolve, reject, {
      enableHighAccuracy: true,
      timeout: 10000,
      maximumAge: 0,
    });
  });
}
export function DriverPage() {
  const driver = useResource("/v1/drivers/me", decodeDriver);
  const synthetic = useResource("/v1/demo/driver-route", decodeDemoDriverRoute);
  const offers = useResource(
    "/v1/drivers/me/offers",
    decodeDriverOffers,
    10000,
  );
  const deliveries = useResource(
    "/v1/drivers/me/deliveries",
    decodeDeliveries,
    15000,
  );
  const command = useCommand();
  const now = useNow();
  const [failure, setFailure] = useState("");
  const [reason, setReason] = useState("");
  const refresh = () => {
    driver.refresh();
    offers.refresh();
    deliveries.refresh();
    synthetic.refresh();
  };
  const state = driver.data?.operationalStatus;
  const devicePositionAllowed =
    synthetic.problem?.kind === "not-found" ||
    synthetic.problem?.kind === "forbidden";
  const availability: Record<string, string[]> = {
    OFFLINE: ["online"],
    ONLINE: ["available", "pause", "offline"],
    AVAILABLE: ["pause", "offline"],
    PAUSED: ["available", "offline"],
  };
  const mutate = (path: string, body: unknown = {}) =>
    void command.execute(
      (signal) =>
        apiClient.post(`/v1/drivers/me/${path}`, body, decodeAck, { signal }),
      () => {
        refresh();
        setFailure("");
      },
      "Driver workflow updated. Latest state requested.",
    );
  return (
    <div className="page-wrap">
      <PageHeader
        title="Driver workspace"
        description="Manage your availability, respond to offers and progress deliveries."
        eyebrow="DRIVER / ON THE ROAD"
        actions={
          <button className="button" onClick={refresh}>
            <Icon name="refresh" />
            Refresh
          </button>
        }
      />
      {command.message && <Notice tone="success">{command.message}</Notice>}
      {command.problem && <ErrorState problem={command.problem} />}
      <div className="operations-grid">
        <div className="stack">
          <Panel title="Your availability">
            <ResourceState resource={driver}>
              {driver.data && (
                <>
                  <div className="section-heading">
                    <StatusBadge
                      status={
                        driver.data.suspended
                          ? "SUSPENDED"
                          : (state ?? "UNKNOWN")
                      }
                    />
                    <span className="mono">v{driver.data.version}</span>
                  </div>
                  <div className="button-row">
                    {(availability[state ?? ""] ?? []).map((action) => (
                      <button
                        key={action}
                        className="button"
                        disabled={
                          command.pending ||
                          driver.data!.suspended ||
                          !driver.data!.active
                        }
                        onClick={() => mutate(action)}
                      >
                        {action === "available"
                          ? "Make available"
                          : action === "online"
                            ? "Go online"
                            : action === "offline"
                              ? "Go offline"
                              : "Pause offers"}
                      </button>
                    ))}
                  </div>
                </>
              )}
            </ResourceState>
          </Panel>
          <Panel title="Offers">
            <ResourceState
              resource={offers}
              empty={!offers.data?.content.length}
            >
              {offers.data?.content.map((offer) => (
                <div className="candidate" key={offer.offerId}>
                  <div className="candidate-top">
                    <strong className="mono">
                      Order {shortId(offer.orderId)}
                    </strong>
                    <StatusBadge status={offer.status} />
                  </div>
                  <p>
                    {offer.pickupLine1}, {offer.pickupCity} →{" "}
                    {offer.deliveryLine1}, {offer.deliveryCity}
                  </p>
                  {offer.status === "OFFERED" && (
                    <>
                      <Expiry at={offer.expiresAt} />
                      <div className="button-row work-actions">
                        {(["accept", "reject"] as const).map((response) => (
                          <button
                            key={response}
                            className={`button ${response === "accept" ? "button-primary" : ""}`}
                            disabled={
                              command.pending ||
                              Date.parse(offer.expiresAt) <= now
                            }
                            onClick={() =>
                              void command.execute(
                                (signal) =>
                                  apiClient.post(
                                    `/v1/drivers/me/offers/${offer.offerId}/${response}`,
                                    {},
                                    decodeOfferReceipt,
                                    { signal },
                                  ),
                                refresh,
                                `Offer ${response === "accept" ? "accepted" : "rejected"}.`,
                              )
                            }
                          >
                            {response === "accept"
                              ? "Accept offer"
                              : "Reject offer"}
                          </button>
                        ))}
                      </div>
                    </>
                  )}
                </div>
              ))}
            </ResourceState>
          </Panel>
          <Panel title="Assigned deliveries">
            <ResourceState
              resource={deliveries}
              empty={!deliveries.data?.content.length}
            >
              {deliveries.data?.content.map((delivery) => (
                <div className="candidate" key={delivery.deliveryId}>
                  <div className="candidate-top">
                    <strong className="mono">
                      Order {shortId(delivery.orderId)}
                    </strong>
                    <StatusBadge status={delivery.status} />
                  </div>
                  <p>
                    {delivery.pickupLine1}, {delivery.pickupCity} →{" "}
                    {delivery.deliveryLine1}, {delivery.deliveryCity}
                  </p>
                  <div className="button-row work-actions">
                    {delivery.status === "ASSIGNED" && (
                      <button
                        className="button button-primary"
                        disabled={command.pending}
                        onClick={() =>
                          mutate(`deliveries/${delivery.deliveryId}/pickup`)
                        }
                      >
                        Confirm pickup
                      </button>
                    )}
                    {delivery.status === "PICKED_UP" && (
                      <button
                        className="button button-primary"
                        disabled={command.pending}
                        onClick={() =>
                          mutate(
                            `deliveries/${delivery.deliveryId}/start-transit`,
                          )
                        }
                      >
                        Start transit
                      </button>
                    )}
                    {delivery.status === "IN_TRANSIT" && (
                      <button
                        className="button button-primary"
                        disabled={command.pending}
                        onClick={() =>
                          mutate(`deliveries/${delivery.deliveryId}/complete`)
                        }
                      >
                        Complete delivery
                      </button>
                    )}
                    {["PICKED_UP", "IN_TRANSIT"].includes(delivery.status) && (
                      <button
                        className="button button-danger"
                        disabled={command.pending}
                        onClick={() => setFailure(delivery.deliveryId)}
                      >
                        Report delivery failure
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </ResourceState>
          </Panel>
        </div>
        <aside className="stack">
          <Panel title="Position and heartbeat">
            <p className="muted small">
              {synthetic.data
                ? "Use the synthetic movement controls below. This demo account does not request your device position."
                : devicePositionAllowed
                  ? "Share one current device fix when you are ready. Position is never collected automatically."
                  : "Position options are being checked. Refresh if they remain unavailable."}
            </p>
            <div className="form-stack">
              {devicePositionAllowed && (
                <button
                  className="button"
                  disabled={
                    command.pending ||
                    !state ||
                    ["OFFLINE", "PAUSED"].includes(state)
                  }
                  onClick={() =>
                    void command.execute(
                      async (signal) => {
                        const fix = await currentPosition();
                        if (signal.aborted) throw new Error("Cancelled");
                        return apiClient.post(
                          "/v1/drivers/me/location",
                          {
                            latitude: fix.coords.latitude,
                            longitude: fix.coords.longitude,
                            heading: fix.coords.heading,
                            speed: fix.coords.speed,
                            reportedAt: new Date(fix.timestamp).toISOString(),
                          },
                          decodeVoid,
                          { signal },
                        );
                      },
                      () => {},
                      "Current position shared.",
                    )
                  }
                >
                  <Icon name="pin" />
                  Share current position
                </button>
              )}
              <button
                className="button"
                disabled={
                  command.pending ||
                  !state ||
                  ["OFFLINE", "PAUSED"].includes(state)
                }
                onClick={() =>
                  void command.execute(
                    (signal) =>
                      apiClient.post(
                        "/v1/drivers/me/heartbeat",
                        {},
                        decodeVoid,
                        { signal },
                      ),
                    () => {},
                    "Heartbeat refreshed.",
                  )
                }
              >
                Refresh heartbeat
              </button>
            </div>
          </Panel>
          {synthetic.data && (
            <SyntheticMovement
              key={synthetic.data.generation}
              route={synthetic.data}
              disabled={
                command.pending ||
                !driver.data?.active ||
                driver.data.suspended ||
                !state ||
                ["OFFLINE", "PAUSED"].includes(state)
              }
              onSend={(position, confirmed) =>
                void command.execute(
                  (signal) =>
                    apiClient.post(
                      "/v1/drivers/me/location",
                      { ...position, reportedAt: new Date().toISOString() },
                      decodeVoid,
                      { signal },
                    ),
                  confirmed,
                  "Synthetic position shared.",
                )
              }
            />
          )}
        </aside>
      </div>
      {failure && (
        <Overlay
          title="Report a delivery failure"
          onClose={() => setFailure("")}
        >
          <form
            className="form-stack"
            onSubmit={(e) => {
              e.preventDefault();
              mutate(`deliveries/${failure}/fail`, { reason: reason.trim() });
            }}
          >
            <label className="field">
              What happened?
              <textarea
                required
                maxLength={500}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </label>
            <button
              className="button button-danger"
              disabled={command.pending || !reason.trim()}
            >
              Confirm delivery failure
            </button>
            {command.problem && <ErrorState problem={command.problem} />}
          </form>
        </Overlay>
      )}
    </div>
  );
}
