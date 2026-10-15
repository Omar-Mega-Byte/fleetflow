import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { decodeOrderPage } from "../api/decoders";
import { useResource } from "../api/useResource";
import { EmptyState, ErrorState, LoadingState } from "../components/States";
import { Icon, MetricStrip, Overlay, PageHeader } from "../components/Ui";
import { orderPhases } from "../components/Lifecycle";
import { OrderRows } from "../features/orders/OrderRows";
import { CreateOrderForm } from "../features/orders/CreateOrderForm";
import { useAuth } from "../auth/AuthContext";

export function CustomerOrdersPage() {
  const { reauthenticate } = useAuth();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("");
  const [sort, setSort] = useState("createdAt,desc");
  const [search, setSearch] = useState("");
  const [create, setCreate] = useState(false);
  const query = new URLSearchParams({ page: String(page), size: "20", sort });
  if (status) query.set("status", status);
  const resource = useResource(`/v1/orders?${query}`, decodeOrderPage);
  const orders = resource.data?.content ?? [];
  const visible = orders.filter((order) =>
    `${order.id} ${order.branchName}`
      .toLowerCase()
      .includes(search.toLowerCase()),
  );
  const localCreation = import.meta.env.VITE_LOCAL_ORDER_CREATION === "true";
  return (
    <div className="page-wrap">
      <PageHeader
        title="Your orders"
        description="Follow every delivery from confirmation to completion."
        eyebrow="CUSTOMER / ORDERS"
        actions={
          <>
            <button
              className="button"
              disabled={resource.refreshing}
              onClick={resource.refresh}
            >
              <Icon name="refresh" />
              Refresh
            </button>
            {localCreation && (
              <button
                className="button button-primary"
                onClick={() => setCreate(true)}
              >
                <Icon name="plus" />
                Create order
              </button>
            )}
          </>
        }
      />
      <MetricStrip
        items={[
          {
            label: "Matched orders",
            value: resource.data?.totalElements ?? "—",
            detail: "Across your filtered results",
          },
          {
            label: "On this page",
            value: resource.data ? visible.length : "—",
            detail: "Matching the page search",
          },
          {
            label: "In progress",
            value: resource.data
              ? orders.filter(
                  (o) =>
                    !["DELIVERED", "CANCELLED", "DELIVERY_FAILED"].includes(
                      o.status,
                    ),
                ).length
              : "—",
            detail: "On the loaded page",
          },
          {
            label: "Needs attention",
            value: resource.data
              ? orders.filter((o) => o.status.includes("FAILED")).length
              : "—",
            detail: "On the loaded page",
          },
        ]}
      />
      <section className="panel" aria-label="Your orders">
        <div className="toolbar">
          <label className="field search-field">
            Search this page
            <input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Branch name or order ID"
            />
          </label>
          <label className="field">
            Status
            <select
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option value="">All statuses</option>
              {[
                ...orderPhases,
                "DISPATCH_FAILED",
                "DELIVERY_FAILED",
                "CANCELLED",
              ].map((s) => (
                <option key={s} value={s}>
                  {s.replaceAll("_", " ")}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            Sort
            <select
              value={sort}
              onChange={(e) => {
                setSort(e.target.value);
                setPage(0);
              }}
            >
              <option value="createdAt,desc">Newest first</option>
              <option value="createdAt,asc">Oldest first</option>
            </select>
          </label>
        </div>
        {resource.loading ? (
          <LoadingState />
        ) : resource.problem ? (
          <ErrorState
            problem={resource.problem}
            onRetry={resource.refresh}
            onReauthenticate={() => void reauthenticate()}
          />
        ) : visible.length ? (
          <OrderRows orders={visible} />
        ) : (
          <EmptyState
            title={status || search ? "No matching orders" : "No orders yet"}
          >
            <p>
              {status || search
                ? "Try another status or clear your page search."
                : "Your deliveries will appear here once an order is placed or the synthetic demo is prepared."}
            </p>
            {(status || search) && (
              <button
                className="button"
                onClick={() => {
                  setStatus("");
                  setSearch("");
                  setPage(0);
                }}
              >
                Clear filters
              </button>
            )}
          </EmptyState>
        )}
        <div className="pagination">
          <span>
            {resource.data
              ? `Page ${resource.data.page + 1} of ${Math.max(1, resource.data.totalPages)}`
              : "Reading your orders"}
          </span>
          <div className="button-row">
            <button
              className="button button-small"
              disabled={page === 0 || resource.loading || resource.refreshing}
              onClick={() => setPage((p) => p - 1)}
            >
              Previous
            </button>
            <button
              className="button button-small"
              disabled={
                !resource.data ||
                page + 1 >= resource.data.totalPages ||
                resource.refreshing
              }
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </button>
          </div>
        </div>
      </section>
      {create && (
        <Overlay
          title="Create a local test order"
          onClose={() => setCreate(false)}
        >
          <CreateOrderForm
            onCreated={(id) => navigate(`/customer/orders/${id}`)}
          />
        </Overlay>
      )}
    </div>
  );
}
