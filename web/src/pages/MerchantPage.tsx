import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { apiClient } from "../auth/AuthContext";
import { useCommand, useResource } from "../api/useResource";
import { decodeAck, decodeMerchantOrders } from "../features/contracts";
import { ContextForm } from "../components/ContextForm";
import { useWorkspace } from "../features/WorkspaceContext";
import { Lifecycle } from "../components/Lifecycle";
import { EmptyState, ErrorState } from "../components/States";
import {
  Icon,
  Notice,
  Overlay,
  PageHeader,
  Panel,
  ResourceState,
  StatusBadge,
  dateTime,
  money,
  shortId,
} from "../components/Ui";
function BranchOrders({ branch }: { branch: string }) {
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState("");
  const [cancel, setCancel] = useState(false);
  const [reason, setReason] = useState("");
  const orders = useResource(
    `/v1/merchant-branches/${branch}/orders?page=${page}&size=20`,
    decodeMerchantOrders,
  );
  const command = useCommand();
  const order =
    orders.data?.content.find((o) => o.id === selected) ??
    orders.data?.content[0];
  const next =
    order?.status === "CREATED"
      ? ["confirm", "Confirm order"]
      : order?.status === "CONFIRMED"
        ? ["start-preparation", "Start preparation"]
        : order?.status === "PREPARING"
          ? ["ready-for-pickup", "Ready for pickup"]
          : null;
  const mutate = (path: string, body: unknown = {}) => {
    if (!order) return;
    void command.execute(
      (signal) =>
        apiClient.post(
          `/v1/merchant-branches/${branch}/orders/${order.id}/${path}`,
          body,
          decodeAck,
          { signal },
        ),
      () => {
        orders.refresh();
        setCancel(false);
      },
      "Order updated. Latest branch state requested.",
    );
  };
  return (
    <>
      {command.message && <Notice tone="success">{command.message}</Notice>}
      {command.problem && <ErrorState problem={command.problem} />}
      <div className="operations-grid">
        <Panel
          title="Branch orders"
          action={
            <button
              className="icon-button"
              aria-label="Refresh branch orders"
              onClick={orders.refresh}
            >
              <Icon name="refresh" />
            </button>
          }
        >
          <ResourceState resource={orders} empty={!orders.data?.content.length}>
            {orders.data?.content.map((o) => (
              <button
                key={o.id}
                className={`zone-item ${o.id === order?.id ? "selected" : ""}`}
                onClick={() => setSelected(o.id)}
              >
                <div className="candidate-top">
                  <span className="mono">{shortId(o.id)}</span>
                  <StatusBadge status={o.status} />
                </div>
                <span className="small muted">
                  {dateTime(o.createdAt)} · {money(o.totalAmount, o.currency)}
                </span>
              </button>
            ))}
          </ResourceState>
          <div className="pagination">
            <span>Page {page + 1}</span>
            <div className="button-row">
              <button
                className="button button-small"
                disabled={!page}
                onClick={() => setPage((p) => p - 1)}
              >
                Previous
              </button>
              <button
                className="button button-small"
                disabled={!orders.data || page + 1 >= orders.data.totalPages}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </button>
            </div>
          </div>
        </Panel>
        <Panel title="Selected order">
          {order ? (
            <>
              <p className="mono">{order.id}</p>
              <Lifecycle status={order.status} />
              <div className="button-row work-actions">
                {next && (
                  <button
                    className="button button-primary"
                    disabled={command.pending}
                    onClick={() => mutate(next[0]!)}
                  >
                    {next[1]}
                  </button>
                )}
                {[
                  "CREATED",
                  "CONFIRMED",
                  "PREPARING",
                  "READY_FOR_PICKUP",
                ].includes(order.status) && (
                  <button
                    className="button button-danger"
                    disabled={command.pending}
                    onClick={() => setCancel(true)}
                  >
                    Cancel order
                  </button>
                )}
              </div>
            </>
          ) : (
            <p className="muted">
              Select an order to view its preparation stage.
            </p>
          )}
        </Panel>
      </div>
      {cancel && (
        <Overlay title="Cancel branch order" onClose={() => setCancel(false)}>
          <form
            className="form-stack"
            onSubmit={(e) => {
              e.preventDefault();
              mutate("cancel", { reason: reason.trim() });
            }}
          >
            <label className="field">
              Reason
              <textarea
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                maxLength={500}
                required
              />
            </label>
            <button
              className="button button-danger"
              disabled={command.pending || !reason.trim()}
            >
              Confirm cancellation
            </button>
            {command.problem && <ErrorState problem={command.problem} />}
          </form>
        </Overlay>
      )}
    </>
  );
}
export function MerchantPage() {
  const workspace = useWorkspace();
  const [params, setParams] = useSearchParams();
  const branch = params.get("branchId") ?? workspace.branchId;
  const valid = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(branch);
  return (
    <div className="page-wrap">
      <PageHeader
        title="Merchant workspace"
        description="Prepare branch orders and move them toward pickup."
        eyebrow="MERCHANT / PREPARATION"
        actions={
          valid ? (
            <Link className="button" to={`/zones?branchId=${branch}`}>
              <Icon name="map" />
              Delivery zones
            </Link>
          ) : undefined
        }
      />
      <ContextForm
        key={`context-${branch}`}
        label="Merchant branch ID"
        value={branch}
        onOpen={(value) => {
          workspace.setBranchId(value);
          setParams({ branchId: value });
        }}
      />
      {valid ? (
        <BranchOrders key={branch} branch={branch} />
      ) : (
        <EmptyState title="Open your branch">
          <p>Enter a branch ID where your account has an active membership.</p>
        </EmptyState>
      )}
    </div>
  );
}
