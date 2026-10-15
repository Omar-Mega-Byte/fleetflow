import { useSearchParams } from "react-router-dom";
import { decodeOrderPage } from "../api/decoders";
import { useResource } from "../api/useResource";
import { useTracking } from "../features/orders/useTracking";
import { MapSurface } from "../components/MapSurface";
import { EmptyState, TrackingState } from "../components/States";
import {
  DetailList,
  OrderLink,
  PageHeader,
  Panel,
  ResourceState,
  StatusBadge,
  dateTime,
  shortId,
} from "../components/Ui";
function SelectedTracking({ id }: { id: string }) {
  const tracking = useTracking(id);
  const snapshot = tracking.snapshot;
  return (
    <ResourceState resource={tracking}>
      <div className="operations-grid">
        <div>
          {snapshot?.position && !snapshot.terminal ? (
            <MapSurface
              label="Authorized delivery position"
              position={snapshot.position}
              stale={tracking.state !== "live"}
            />
          ) : (
            <EmptyState
              title={
                snapshot?.terminal
                  ? "This delivery has ended"
                  : "Waiting for location"
              }
            >
              <p>
                {snapshot?.terminal
                  ? "Location sharing ends with the delivery."
                  : "The latest delivery position will appear when it is available."}
              </p>
            </EmptyState>
          )}
        </div>
        <Panel title="Selected delivery">
          <OrderLink id={id} />
          <div style={{ marginTop: 16 }}>
            <TrackingState state={tracking.state} />
          </div>
          {snapshot && (
            <DetailList
              items={[
                ["Status", <StatusBadge status={snapshot.orderStatus} />],
                ["Version", snapshot.orderVersion],
                ["Observed", dateTime(snapshot.observedAt)],
                [
                  "Position",
                  snapshot.position
                    ? dateTime(snapshot.position.reportedAt)
                    : "Unavailable",
                ],
              ]}
            />
          )}
        </Panel>
      </div>
    </ResourceState>
  );
}
export function TrackingPage() {
  const [params, setParams] = useSearchParams();
  const orders = useResource(
    "/v1/orders?page=0&size=100&sort=createdAt,desc",
    decodeOrderPage,
  );
  const selected = params.get("orderId") ?? orders.data?.content[0]?.id;
  return (
    <div className="page-wrap">
      <PageHeader
        title="Live tracking"
        description="Follow the latest reported position for one of your deliveries."
        eyebrow="CUSTOMER / TRACKING"
      />
      <div className="context-form">
        <label className="field">
          Select a delivery
          <select
            value={selected ?? ""}
            onChange={(e) => setParams({ orderId: e.target.value })}
          >
            <option value="" disabled>
              Choose an order
            </option>
            {selected &&
              !orders.data?.content.some((o) => o.id === selected) && (
                <option value={selected}>Order {shortId(selected)}</option>
              )}
            {orders.data?.content.map((o) => (
              <option key={o.id} value={o.id}>
                {o.branchName} · {shortId(o.id)}
              </option>
            ))}
          </select>
        </label>
      </div>
      <ResourceState resource={orders} empty={!selected}>
        {selected && <SelectedTracking key={selected} id={selected} />}
      </ResourceState>
      {orders.data && orders.data.totalElements > 100 && (
        <p className="muted small">
          Showing the latest 100 orders. Open an older order from Orders to
          track it.
        </p>
      )}
    </div>
  );
}
