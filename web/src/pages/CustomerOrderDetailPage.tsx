import { useCallback, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { decodeOrder } from "../api/decoders";
import { apiClient } from "../auth/AuthContext";
import { useCommand, useResource } from "../api/useResource";
import { ErrorState, LoadingState, TrackingState } from "../components/States";
import {
  DetailList,
  Icon,
  Notice,
  Overlay,
  Panel,
  StatusBadge,
  dateTime,
  money,
  shortId,
} from "../components/Ui";
import { Lifecycle } from "../components/Lifecycle";
import { MapSurface } from "../components/MapSurface";
import { useTracking } from "../features/orders/useTracking";
const decodeCancel = (value: unknown) => {
  if (!value || typeof value !== "object")
    throw new Error("Invalid command response");
  return value;
};

export function CustomerOrderDetailPage() {
  const { id = "" } = useParams();
  const decodeOwnedOrder = useCallback(
    (value: unknown) => {
      const result = decodeOrder(value);
      if (result.id !== id) throw new Error("Order identity mismatch");
      return result;
    },
    [id],
  );
  const order = useResource(
    `/v1/orders/${encodeURIComponent(id)}`,
    decodeOwnedOrder,
  );
  const tracking = useTracking(id);
  const command = useCommand();
  const [cancel, setCancel] = useState(false);
  const [details, setDetails] = useState(false);
  const [reason, setReason] = useState("");
  if (order.loading)
    return (
      <div className="page-wrap">
        <LoadingState label="Loading order details" />
      </div>
    );
  if (order.problem || !order.data)
    return (
      <div className="page-wrap">
        <Link className="back-link" to="/customer/orders">
          <Icon name="back" />
          All orders
        </Link>
        {order.problem && (
          <ErrorState problem={order.problem} onRetry={order.refresh} />
        )}
      </div>
    );
  const data = order.data;
  const snapshot = tracking.snapshot;
  const status = snapshot?.orderStatus ?? data.status;
  const progress =
    status === "CREATED"
      ? "Order received"
      : status === "DELIVERED"
        ? "Delivered"
        : status.includes("FAILED")
          ? "Delivery needs attention"
          : status === "CANCELLED"
            ? "Order cancelled"
            : "Your order progress";
  return (
    <div className="page-wrap detail-page">
      <Link className="back-link" to="/customer/orders">
        <Icon name="back" />
        All orders
      </Link>
      <header className="detail-heading">
        <div>
          <p className="eyebrow">ORDER / {shortId(data.id)}</p>
          <h1>{data.branchName}</h1>
          <p className="page-intro">Placed {dateTime(data.createdAt)}</p>
        </div>
        <div className="button-row">
          <StatusBadge status={status} />
          <button
            className="icon-button"
            aria-label="Order technical details"
            onClick={() => setDetails(true)}
          >
            <Icon name="layers" />
          </button>
        </div>
      </header>
      <div className="operations-grid">
        <div className="stack">
          <Panel
            title="Delivery lifecycle"
            action={
              <span className="mono">
                v{snapshot?.orderVersion ?? data.version}
              </span>
            }
          >
            <h2>{progress}</h2>
            <Lifecycle status={status} />
          </Panel>
          <Panel title="Delivery locations">
            <div className="address-pair">
              <div className="address-stop">
                <p className="eyebrow">PICKUP</p>
                <strong>{data.pickup.line1}</strong>
                <p>{data.pickup.city}</p>
              </div>
              <div className="address-stop">
                <p className="eyebrow">DESTINATION</p>
                <strong>{data.delivery.line1}</strong>
                <p>{data.delivery.city}</p>
              </div>
            </div>
          </Panel>
          <Panel
            title="Tracking"
            action={
              <Link className="inline-link" to={`/tracking?orderId=${id}`}>
                Open tracking
                <Icon name="arrow" size={14} />
              </Link>
            }
          >
            <TrackingState state={tracking.state} />
            {tracking.problem && (
              <ErrorState
                problem={tracking.problem}
                onRetry={tracking.refresh}
              />
            )}
            <div className="position-note">
              {snapshot?.terminal
                ? "Location is no longer shared after this delivery ends."
                : snapshot?.position
                  ? `Position reported ${dateTime(snapshot.position.reportedAt)}.`
                  : ["DRIVER_ASSIGNED", "PICKED_UP", "IN_TRANSIT"].includes(
                        status,
                      )
                    ? "A current delivery location is unavailable. Order status updates can continue while location is unavailable."
                    : "Live delivery location appears after a driver accepts this order."}
            </div>
            {snapshot?.position && !snapshot.terminal && (
              <MapSurface
                position={snapshot.position}
                label="Delivery position"
                stale={tracking.state !== "live"}
              />
            )}
          </Panel>
        </div>
        <aside className="stack">
          <Panel title="Order summary">
            <h2>{data.merchantName}</h2>
            <p className="summary-branch">{data.branchName}</p>
            <div className="summary-items">
              {data.items.map((item) => (
                <div className="summary-item" key={item.lineNumber}>
                  <span>
                    <strong>{item.quantity} ×</strong> {item.description}
                  </span>
                  <span>{money(item.lineAmount, data.currency)}</span>
                </div>
              ))}
            </div>
            <div className="summary-total">
              <span>Total</span>
              <strong>{money(data.totalAmount, data.currency)}</strong>
            </div>
          </Panel>
          <Panel title="Order actions">
            <p className="muted small">
              Current status determines the actions available for your delivery.
            </p>
            {["CREATED", "CONFIRMED"].includes(status) ? (
              <button
                className="button button-danger"
                onClick={() => setCancel(true)}
              >
                Cancel order
              </button>
            ) : (
              <p className="small">
                {status === "DELIVERED"
                  ? "This delivery is complete."
                  : "No customer action is available at this stage."}
              </p>
            )}
            {command.message && (
              <Notice tone="success">{command.message}</Notice>
            )}
            {command.problem && <ErrorState problem={command.problem} />}
          </Panel>
        </aside>
      </div>
      {cancel && (
        <Overlay title="Cancel this order?" onClose={() => setCancel(false)}>
          <p>
            The cancellation will be checked against the latest order state.
          </p>
          <label className="field">
            Reason for cancellation
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={500}
              required
            />
          </label>
          <div className="button-row">
            <button
              className="button"
              disabled={command.pending}
              onClick={() => setCancel(false)}
            >
              Keep order
            </button>
            <button
              className="button button-danger"
              disabled={command.pending || !reason.trim()}
              onClick={() =>
                void command.execute(
                  (signal) =>
                    apiClient.post(
                      `/v1/orders/${id}/cancel`,
                      { reason: reason.trim() },
                      decodeCancel,
                      { signal },
                    ),
                  () => {
                    setCancel(false);
                    order.refresh();
                    tracking.refresh();
                  },
                  "Order cancelled",
                )
              }
            >
              {command.pending ? "Cancelling…" : "Confirm cancellation"}
            </button>
          </div>
          {command.problem && <ErrorState problem={command.problem} />}
        </Overlay>
      )}
      {details && (
        <Overlay title="Order details" drawer onClose={() => setDetails(false)}>
          <DetailList
            items={[
              ["Order ID", <span className="mono">{id}</span>],
              ["Version", snapshot?.orderVersion ?? data.version],
              ["Status", status],
              [
                "Observed",
                snapshot
                  ? dateTime(snapshot.observedAt)
                  : "Snapshot unavailable",
              ],
              ["Delivery state", snapshot?.deliveryStatus ?? "Not assigned"],
            ]}
          />
        </Overlay>
      )}
    </div>
  );
}
