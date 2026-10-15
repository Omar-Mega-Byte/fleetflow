import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { apiClient } from "../auth/AuthContext";
import { useCommand, useResource } from "../api/useResource";
import {
  decodeAck,
  decodeAssignments,
  decodeAttempts,
  decodeIncidents,
  decodeOfferReceipt,
} from "../features/contracts";
import { ContextForm } from "../components/ContextForm";
import { useWorkspace } from "../features/WorkspaceContext";
import { Expiry, useNow } from "../components/Expiry";
import { EmptyState, ErrorState } from "../components/States";
import {
  DetailList,
  Icon,
  Notice,
  Overlay,
  PageHeader,
  Panel,
  ResourceState,
  StatusBadge,
  dateTime,
  human,
  shortId,
} from "../components/Ui";
function DispatchWorkspace({ id }: { id: string }) {
  const attempts = useResource(
    `/v1/dispatch/orders/${id}/attempts`,
    decodeAttempts,
    15000,
  );
  const offers = useResource(
    `/v1/dispatch/orders/${id}/assignments`,
    decodeAssignments,
    10000,
  );
  const incidents = useResource(
    `/v1/dispatch/orders/${id}/incidents`,
    decodeIncidents,
  );
  const command = useCommand();
  const [selected, setSelected] = useState("");
  const [action, setAction] = useState<{ path: string; title: string } | null>(
    null,
  );
  const [reason, setReason] = useState("");
  const now = useNow();
  const latest = attempts.data?.content[0];
  const attempt =
    attempts.data?.content.find((a) => a.id === selected) ?? latest;
  const pending = offers.data?.content.find(
    (o) => o.status === "OFFERED" && Date.parse(o.expiresAt) > now,
  );
  const refresh = () => {
    attempts.refresh();
    offers.refresh();
    incidents.refresh();
  };
  const mutate = (path: string, body: unknown = {}) =>
    void command.execute(
      (signal) =>
        apiClient.post(`/v1/dispatch/orders/${id}/${path}`, body, decodeAck, {
          signal,
        }),
      () => {
        refresh();
        setAction(null);
      },
      "Action accepted. Latest matching and offer state requested.",
    );
  return (
    <>
      <div className="button-row work-actions">
        <button
          className="button button-primary"
          disabled={command.pending}
          onClick={() => mutate("match")}
        >
          <Icon name="dispatch" />
          {command.pending ? "Submitting…" : "Start matching"}
        </button>
        <button className="button" onClick={refresh}>
          <Icon name="refresh" />
          Refresh evidence
        </button>
      </div>
      {command.message && <Notice tone="success">{command.message}</Notice>}
      {command.problem && <ErrorState problem={command.problem} />}
      <div className="operations-grid">
        <div className="stack">
          <Panel
            title="Matching evidence"
            action={
              attempts.data?.content.length ? (
                <select
                  aria-label="Matching attempt"
                  value={attempt?.id ?? ""}
                  onChange={(e) => setSelected(e.target.value)}
                >
                  {attempts.data.content.map((a) => (
                    <option key={a.id} value={a.id}>
                      Attempt {a.sequence}
                    </option>
                  ))}
                </select>
              ) : undefined
            }
          >
            <ResourceState resource={attempts} empty={!attempt}>
              <>
                {attempt && (
                  <>
                    <div className="section-heading">
                      <StatusBadge status={attempt.outcome} />
                      <span className="mono">
                        {dateTime(attempt.startedAt)}
                      </span>
                    </div>
                    <p className="muted small">
                      {attempt.candidates.length} inspected candidates · Zone
                      policy: {human(attempt.zoneMode)}
                    </p>
                    {attempt.candidates.map((candidate) => (
                      <div className="candidate" key={candidate.driverId}>
                        <div className="candidate-top">
                          <strong className="mono">
                            Driver {shortId(candidate.driverId)}
                          </strong>
                          <StatusBadge status={candidate.reason} />
                        </div>
                        <p>
                          {candidate.distanceMeters == null
                            ? "Distance unavailable"
                            : `${Math.round(candidate.distanceMeters).toLocaleString()} m from pickup`}{" "}
                          ·{" "}
                          {candidate.gpsAgeMillis == null
                            ? "Position age unavailable"
                            : `${Math.round(candidate.gpsAgeMillis / 1000)}s position age`}
                        </p>
                        {candidate.score != null && (
                          <>
                            <div className="score-track">
                              <span
                                style={{
                                  width: `${Math.min(100, Math.max(0, candidate.score * 100))}%`,
                                }}
                              />
                            </div>
                            <p>
                              Rank {candidate.rank} · Score{" "}
                              {candidate.score.toFixed(3)}
                              {candidate.straightLineSeconds != null
                                ? ` · Straight-line matching estimate ${candidate.straightLineSeconds}s`
                                : ""}
                            </p>
                          </>
                        )}
                        <button
                          className="button button-small"
                          disabled={
                            command.pending ||
                            candidate.reason !== "ELIGIBLE" ||
                            candidate.rank == null ||
                            !!pending ||
                            attempt.id !== latest?.id
                          }
                          onClick={() =>
                            void command.execute(
                              (signal) =>
                                apiClient.post(
                                  `/v1/dispatch/orders/${id}/offers`,
                                  {
                                    attemptId: attempt.id,
                                    driverId: candidate.driverId,
                                  },
                                  decodeOfferReceipt,
                                  { signal },
                                ),
                              refresh,
                              "Offer sent. Awaiting the driver’s response.",
                            )
                          }
                        >
                          Send offer
                        </button>
                      </div>
                    ))}
                    {!attempt.candidates.length && (
                      <EmptyState title="No candidates in this attempt">
                        <p>
                          Inspect the matching outcome before starting a
                          recovery action.
                        </p>
                      </EmptyState>
                    )}
                  </>
                )}
              </>
            </ResourceState>
          </Panel>
          <Panel title="Offer history">
            <ResourceState
              resource={offers}
              empty={!offers.data?.content.length}
            >
              {offers.data?.content.map((offer) => (
                <div className="record-row" key={offer.offerId}>
                  <Icon name="dispatch" />
                  <div className="record-body">
                    <StatusBadge status={offer.status} />
                    <p>
                      Driver {shortId(offer.driverId)} · Rank {offer.rank}
                    </p>
                    <p>Sent {dateTime(offer.offeredAt)}</p>
                    {offer.status === "OFFERED" && (
                      <Expiry at={offer.expiresAt} />
                    )}
                  </div>
                </div>
              ))}
            </ResourceState>
          </Panel>
        </div>
        <aside className="stack">
          {pending && (
            <Notice tone="waiting">
              <div>
                <strong>Offer awaiting response</strong>
                <p>Driver {shortId(pending.driverId)}</p>
                <Expiry at={pending.expiresAt} />
              </div>
            </Notice>
          )}
          <Panel title="Dispatch recovery">
            <p className="muted small">
              Choose an action for the current delivery stage. FleetFlow checks
              the latest state before applying it.
            </p>
            <div className="form-stack">
              {(
                [
                  ["retry", "Retry matching"],
                  ["reassign", "Reassign before pickup"],
                  ["cancel", "Cancel before pickup"],
                  ["incidents/fail", "Fail active delivery"],
                ] as const
              ).map(([path, title]) => (
                <button
                  key={path}
                  className="button"
                  disabled={command.pending}
                  onClick={() => {
                    setReason("");
                    setAction({ path, title });
                  }}
                >
                  {title}
                </button>
              ))}
            </div>
          </Panel>
          <Panel title="Incidents">
            <ResourceState
              resource={incidents}
              empty={!incidents.data?.content.length}
            >
              {incidents.data?.content.map((incident) => (
                <div className="record-row" key={incident.id}>
                  <div className="record-body">
                    <StatusBadge status={incident.status} />
                    <p>{incident.reason}</p>
                    {incident.status === "OPEN" && (
                      <button
                        className="button button-small"
                        onClick={() => {
                          setReason("");
                          setAction({
                            path: `incidents/${incident.id}/resolve`,
                            title: "Resolve incident",
                          });
                        }}
                      >
                        Record resolution
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </ResourceState>
          </Panel>
          <Panel title="Selected context">
            <DetailList
              items={[
                ["Order", <span className="mono">{id}</span>],
                [
                  "Matching version",
                  attempt?.configVersion ?? "Not yet matched",
                ],
                ["Visibility", "Order-scoped matching and assignment evidence"],
              ]}
            />
          </Panel>
        </aside>
      </div>
      {action && (
        <Overlay title={action.title} onClose={() => setAction(null)}>
          <form
            className="form-stack"
            onSubmit={(e) => {
              e.preventDefault();
              mutate(action.path, { reason: reason.trim() });
            }}
          >
            <p>
              Apply this action to order{" "}
              <span className="mono">{shortId(id)}</span>.
            </p>
            <label className="field">
              Reason
              <textarea
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                required
                minLength={1}
                maxLength={500}
              />
            </label>
            <button
              className="button button-primary"
              disabled={command.pending || !reason.trim()}
            >
              {command.pending ? "Submitting…" : "Confirm action"}
            </button>
            {command.problem && <ErrorState problem={command.problem} />}
          </form>
        </Overlay>
      )}
    </>
  );
}
export function DispatchPage() {
  const workspace = useWorkspace();
  const [params, setParams] = useSearchParams();
  const id = params.get("orderId") ?? workspace.orderId;
  const valid = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(id);
  return (
    <div className="page-wrap">
      <PageHeader
        title="Dispatch"
        description="Inspect matching decisions, send atomic offers and recover deliveries."
        eyebrow="DISPATCHER / MATCHING"
      />
      <ContextForm
        key={`context-${id}`}
        label="Order ID"
        value={id}
        onOpen={(value) => {
          workspace.setOrderId(value);
          setParams({ orderId: value });
        }}
      />
      {valid ? (
        <DispatchWorkspace key={id} id={id} />
      ) : (
        <EmptyState title="Open an order to inspect dispatch">
          <p>
            Enter the order ID from your delivery workflow to inspect its
            matching attempts and offer history.
          </p>
        </EmptyState>
      )}
    </div>
  );
}
