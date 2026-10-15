import { Link } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useResource } from "../api/useResource";
import { decodeOrderPage } from "../api/decoders";
import {
  decodeAnalytics,
  decodeDriver,
  decodeFailed,
  decodeNotifications,
} from "../features/contracts";
import { OrderRows } from "../features/orders/OrderRows";
import { useTracking } from "../features/orders/useTracking";
import { Lifecycle } from "../components/Lifecycle";
import { EmptyState, TrackingState } from "../components/States";
import {
  Icon,
  MetricStrip,
  PageHeader,
  Panel,
  ResourceState,
  StatusBadge,
  shortId,
  human,
  dateTime,
} from "../components/Ui";
function CustomerContext({ id }: { id: string }) {
  const tracking = useTracking(id);
  return (
    <Panel
      title="Selected delivery"
      action={<span className="mono">{shortId(id)}</span>}
    >
      <ResourceState resource={tracking}>
        {tracking.snapshot && (
          <>
            <StatusBadge status={tracking.snapshot.orderStatus} />
            <div className="context-lifecycle">
              <Lifecycle status={tracking.snapshot.orderStatus} />
            </div>
            <TrackingState state={tracking.state} />
            <Link className="button button-small" to={`/customer/orders/${id}`}>
              Inspect delivery
              <Icon name="arrow" size={14} />
            </Link>
          </>
        )}
      </ResourceState>
    </Panel>
  );
}
function CustomerCenter() {
  const orders = useResource(
    "/v1/orders?page=0&size=20&sort=createdAt,desc",
    decodeOrderPage,
  );
  const notifications = useResource(
    "/v1/notifications/me",
    decodeNotifications,
  );
  const rows = orders.data?.content ?? [];
  const active = rows.filter(
    (o) => !["CANCELLED", "DELIVERED", "DELIVERY_FAILED"].includes(o.status),
  );
  const selected = active[0] ?? rows[0];
  return (
    <>
      <MetricStrip
        items={[
          {
            label: "Your orders",
            value: orders.data?.totalElements ?? "—",
            detail: "Account total",
          },
          {
            label: "In progress",
            value: orders.data ? active.length : "—",
            detail: "Latest 20 orders",
          },
          {
            label: "Needs attention",
            value: orders.data
              ? rows.filter((o) => o.status.includes("FAILED")).length
              : "—",
            detail: "Latest 20 orders",
          },
          {
            label: "Completed",
            value: orders.data
              ? rows.filter((o) => o.status === "DELIVERED").length
              : "—",
            detail: "Latest 20 orders",
          },
        ]}
      />
      <div className="operations-grid">
        <div className="stack">
          <Panel
            title="Recent orders"
            action={
              <Link className="inline-link" to="/customer/orders">
                View all
                <Icon name="arrow" size={14} />
              </Link>
            }
          >
            <ResourceState resource={orders} empty={!rows.length}>
              <OrderRows orders={rows.slice(0, 6)} />
            </ResourceState>
          </Panel>
          <Panel
            title="Recent activity"
            action={
              <Link className="inline-link" to="/activity">
                Open activity
                <Icon name="arrow" size={14} />
              </Link>
            }
          >
            <ResourceState
              resource={notifications}
              empty={!notifications.data?.content.length}
            >
              {notifications.data?.content.slice(0, 3).map((n) => (
                <div className="record-row" key={n.id}>
                  <span className="record-dot" />
                  <div className="record-body">
                    <strong>{human(n.kind)}</strong>
                    <p>
                      {n.orderId
                        ? `Order ${shortId(n.orderId)}`
                        : "Account notification"}
                    </p>
                  </div>
                  <time>{dateTime(n.createdAt)}</time>
                </div>
              ))}
            </ResourceState>
          </Panel>
        </div>
        {selected ? (
          <CustomerContext key={selected.id} id={selected.id} />
        ) : (
          <Panel title="Delivery progress">
            <EmptyState title="Ready for your first delivery">
              <p>
                Once an order appears, its lifecycle and connection state will
                be shown here.
              </p>
            </EmptyState>
          </Panel>
        )}
      </div>
    </>
  );
}
function DispatcherCenter() {
  const today = new Date().toISOString().slice(0, 10);
  const analytics = useResource(
    `/v1/dispatch/analytics/daily?from=${today}&to=${today}`,
    decodeAnalytics,
  );
  return (
    <Panel
      title="Today’s order transitions"
      action={
        <Link className="button button-small" to="/workspace/dispatcher">
          Open dispatch
        </Link>
      }
    >
      <ResourceState
        resource={analytics}
        empty={!analytics.data?.content.length}
      >
        {analytics.data?.content.map((r) => (
          <div className="record-row" key={r.status}>
            <StatusBadge status={r.status} />
            <strong>{r.total}</strong>
          </div>
        ))}
      </ResourceState>
      <p className="muted small">
        UTC day · Transition counts may include multiple changes to the same
        order.
      </p>
    </Panel>
  );
}
function DriverCenter() {
  const driver = useResource("/v1/drivers/me", decodeDriver);
  return (
    <Panel
      title="Your driver state"
      action={
        <Link className="button button-small" to="/workspace/driver">
          Open driver workspace
        </Link>
      }
    >
      <ResourceState resource={driver}>
        {driver.data && (
          <>
            <StatusBadge status={driver.data.operationalStatus} />
            <p className="muted small">
              Manage availability, respond to offers and progress assigned
              deliveries.
            </p>
          </>
        )}
      </ResourceState>
    </Panel>
  );
}
function AdminCenter() {
  const events = useResource("/v1/operations/outbox/failed", decodeFailed);
  return (
    <Panel
      title="Publisher recovery"
      action={
        <Link className="button button-small" to="/workspace/admin">
          Open operations
        </Link>
      }
    >
      <ResourceState resource={events} empty={!events.data?.content.length}>
        {events.data?.content.slice(0, 5).map((e) => (
          <div className="record-row" key={e.eventId}>
            <Icon name="alert" />
            <div className="record-body">
              <strong>{human(e.eventType)}</strong>
              <p>
                {e.errorCode} · {e.attempts} attempts
              </p>
            </div>
          </div>
        ))}
      </ResourceState>
    </Panel>
  );
}
export function CommandCenterPage() {
  const { roles } = useAuth();
  return (
    <div className="page-wrap">
      <PageHeader
        title="Command Center"
        description="Delivery progress, current work and the signals that need your attention."
        eyebrow="FLEETFLOW / OVERVIEW"
      />
      {roles.includes("CUSTOMER") ? (
        <CustomerCenter />
      ) : (
        <div className="stack">
          {roles.includes("DISPATCHER") && <DispatcherCenter />}
          {roles.includes("DRIVER") && <DriverCenter />}
          {roles.includes("ADMIN") && <AdminCenter />}
          {roles.includes("MERCHANT") && (
            <Panel title="Branch operations">
              <p className="muted">
                Open your branch workspace to prepare orders and manage delivery
                boundaries.
              </p>
              <Link className="button button-primary" to="/workspace/merchant">
                Open merchant workspace
                <Icon name="arrow" />
              </Link>
            </Panel>
          )}
          {roles.every((r) => r === "SUPPORT") && (
            <EmptyState title="Workspace access">
              <p>
                Your account is authorized. No support workflow is configured
                for this release.
              </p>
            </EmptyState>
          )}
        </div>
      )}
    </div>
  );
}
