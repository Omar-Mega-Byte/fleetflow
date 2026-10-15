import { useState } from "react";
import { useAuth } from "../auth/AuthContext";
import { useResource } from "../api/useResource";
import {
  decodeAnalytics,
  decodeNotifications,
  type Notification,
} from "../features/contracts";
import { RecoveryViews } from "../features/operations/RecoveryViews";
import {
  DetailList,
  Icon,
  Overlay,
  PageHeader,
  Panel,
  ResourceState,
  StatusBadge,
  dateTime,
  human,
  shortId,
} from "../components/Ui";
function Notifications() {
  const [auto, setAuto] = useState(true);
  const [filter, setFilter] = useState("");
  const [selected, setSelected] = useState<Notification | null>(null);
  const feed = useResource(
    "/v1/notifications/me",
    decodeNotifications,
    auto ? 15000 : 0,
  );
  const rows = feed.data?.content.filter((n) =>
    `${n.kind} ${n.orderId ?? ""}`.toLowerCase().includes(filter.toLowerCase()),
  );
  return (
    <Panel
      title="Your notifications"
      action={
        <div className="button-row">
          <button
            className="button button-small"
            onClick={() => setAuto((v) => !v)}
          >
            {auto ? "Pause updates" : "Resume updates"}
          </button>
          <button
            className="icon-button"
            aria-label="Refresh activity"
            onClick={feed.refresh}
          >
            <Icon name="refresh" />
          </button>
        </div>
      }
    >
      <div className="toolbar">
        <label className="field search-field">
          Filter loaded activity
          <input
            type="search"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder="Event kind or order ID"
          />
        </label>
        <span className="muted small">
          {auto ? "Checks every 15 seconds" : "Updates paused"}
        </span>
      </div>
      <ResourceState resource={feed} empty={!rows?.length}>
        {rows?.map((n) => (
          <div className="record-row" key={n.id}>
            <span className="record-dot" />
            <div className="record-body">
              <strong>{human(n.kind)}</strong>
              <p>
                {n.orderId ? `Order ${shortId(n.orderId)}` : "Account update"} ·{" "}
                {human(n.state)}
              </p>
              <button
                className="button button-small"
                onClick={() => setSelected(n)}
              >
                Event details
              </button>
            </div>
            <time dateTime={n.createdAt}>{dateTime(n.createdAt)}</time>
          </div>
        ))}
      </ResourceState>
      {selected && (
        <Overlay
          title={human(selected.kind)}
          drawer
          onClose={() => setSelected(null)}
        >
          <DetailList
            items={[
              ["State", <StatusBadge status={selected.state} />],
              ["Created", dateTime(selected.createdAt)],
              ["Event ID", <span className="mono">{selected.eventId}</span>],
              [
                "Order",
                <span className="mono">
                  {selected.orderId ?? "No related order"}
                </span>,
              ],
              [
                "Assignment",
                <span className="mono">
                  {selected.assignmentId ?? "No related assignment"}
                </span>,
              ],
            ]}
          />
        </Overlay>
      )}
    </Panel>
  );
}
export function Analytics() {
  const today = new Date().toISOString().slice(0, 10);
  const [from, setFrom] = useState(today);
  const [to, setTo] = useState(today);
  const [range, setRange] = useState({ from: today, to: today });
  const data = useResource(
    `/v1/dispatch/analytics/daily?from=${range.from}&to=${range.to}`,
    decodeAnalytics,
  );
  const max = Math.max(1, ...(data.data?.content.map((r) => r.total) ?? []));
  return (
    <Panel title="Order transition activity">
      <form
        className="context-form"
        onSubmit={(e) => {
          e.preventDefault();
          setRange({ from, to });
        }}
      >
        <label className="field">
          From (UTC)
          <input
            type="date"
            required
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            max={to}
          />
        </label>
        <label className="field">
          To (UTC)
          <input
            type="date"
            required
            value={to}
            min={from}
            onChange={(e) => setTo(e.target.value)}
          />
        </label>
        <button className="button">Apply range</button>
      </form>
      <ResourceState resource={data} empty={!data.data?.content.length}>
        {data.data?.content.map((r) => (
          <div className="chart-row" key={`${r.day}/${r.status}`}>
            <span>
              {human(r.status)}
              <small className="muted"> · {r.day}</small>
            </span>
            <div className="chart-bar">
              <span style={{ width: `${(100 * r.total) / max}%` }} />
            </div>
            <strong>{r.total}</strong>
          </div>
        ))}
      </ResourceState>
      <p className="muted small">
        Transition counts, not unique orders. Select a range of at most 30 days.
      </p>
    </Panel>
  );
}
export function ActivityPage() {
  const { roles } = useAuth();
  return (
    <div className="page-wrap">
      <PageHeader
        title="Activity"
        description="Inspect notifications and operational records available to your account."
        eyebrow="FLEETFLOW / ACTIVITY"
      />
      <div className="stack">
        {(roles.includes("CUSTOMER") || roles.includes("DRIVER")) && (
          <Notifications />
        )}
        {roles.includes("DISPATCHER") && <Analytics />}
        {roles.includes("ADMIN") && <RecoveryViews />}
      </div>
    </div>
  );
}
