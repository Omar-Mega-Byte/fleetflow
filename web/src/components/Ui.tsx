import { useEffect, useRef, type ReactNode } from "react";
import { Link } from "react-router-dom";
import type { ApiProblem } from "../api/client";
import { ErrorState, LoadingState } from "./States";

const paths: Record<string, ReactNode> = {
  grid: (
    <>
      <rect x="3" y="3" width="7" height="7" rx="1" />
      <rect x="14" y="3" width="7" height="7" rx="1" />
      <rect x="3" y="14" width="7" height="7" rx="1" />
      <rect x="14" y="14" width="7" height="7" rx="1" />
    </>
  ),
  orders: (
    <>
      <path d="M7 3h10l3 5v13H4V8Z" />
      <path d="M4 8h16M9 12h6M9 16h6" />
    </>
  ),
  map: <path d="m3 6 6-3 6 3 6-3v15l-6 3-6-3-6 3ZM9 3v15M15 6v15" />,
  pin: (
    <>
      <path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z" />
      <circle cx="12" cy="10" r="2" />
    </>
  ),
  dispatch: (
    <>
      <path d="M3 7h11v10H3zM14 10h4l3 4v3h-7M6 7V4h6" />
      <circle cx="7" cy="18" r="2" />
      <circle cx="18" cy="18" r="2" />
    </>
  ),
  activity: <path d="M2 12h4l3-8 5 16 3-8h5" />,
  system: (
    <>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2" />
    </>
  ),
  search: (
    <>
      <circle cx="10" cy="10" r="6" />
      <path d="m15 15 6 6" />
    </>
  ),
  arrow: <path d="M4 12h16m-6-6 6 6-6 6" />,
  back: <path d="M20 12H4m6-6-6 6 6 6" />,
  refresh: <path d="M20 7a9 9 0 1 0 1 8M20 3v5h-5" />,
  close: <path d="m6 6 12 12M6 18 18 6" />,
  logout: <path d="M9 3H4v18h5M9 12h12m-5-5 5 5-5 5" />,
  plus: <path d="M12 4v16M4 12h16" />,
  check: <path d="m4 12 5 5L20 6" />,
  alert: (
    <>
      <path d="m12 3 10 18H2Z" />
      <path d="M12 9v5m0 3v1" />
    </>
  ),
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 6v6l4 2" />
    </>
  ),
  layers: <path d="m2 7 10-5 10 5-10 5Zm0 5 10 5 10-5M2 17l10 5 10-5" />,
};
export function Icon({ name, size = 18 }: { name: string; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {paths[name] ?? paths.orders}
    </svg>
  );
}
export const human = (value: string) =>
  value
    .replaceAll("_", " ")
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
export const shortId = (value: string) => value.slice(0, 8).toUpperCase();
export const dateTime = (value: string) =>
  new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
export const money = (value: number, currency: string) =>
  new Intl.NumberFormat(undefined, { style: "currency", currency }).format(
    value,
  );
export function StatusBadge({ status }: { status: string }) {
  const tone = /FAILED|CANCELLED|REJECTED|SUSPENDED/.test(status)
    ? "danger"
    : /WAIT|PENDING|OFFERED|EXPIRED|PREPARING|CREATED|DISPATCHING|PAUSED|READY/.test(
          status,
        )
      ? "waiting"
      : /TRANSIT/.test(status)
        ? "live"
        : /DELIVERED|ACCEPTED|AVAILABLE|ACTIVE|CONFIRMED|ASSIGNED|PICKED|REPLAYED|ONLINE/.test(
              status,
            )
          ? "success"
          : "neutral";
  return (
    <span className={`status-pill tone-${tone}`}>
      <span className="status-dot" />
      {human(status)}
    </span>
  );
}
export function PageHeader({
  title,
  description,
  eyebrow = "WORKSPACE",
  actions,
}: {
  title: string;
  description?: string;
  eyebrow?: string;
  actions?: ReactNode;
}) {
  return (
    <header className="page-heading">
      <div>
        <p className="eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        {description && <p className="page-intro">{description}</p>}
      </div>
      {actions && <div className="heading-actions">{actions}</div>}
    </header>
  );
}
export function Panel({
  title,
  children,
  action,
  className = "",
}: {
  title: string;
  children: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <section className={`panel ${className}`}>
      <header className="panel-heading">
        <h2>{title}</h2>
        {action}
      </header>
      <div className="panel-body">{children}</div>
    </section>
  );
}
export function Notice({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: string;
}) {
  return (
    <div
      className={`notice tone-${tone}`}
      role={tone === "danger" ? "alert" : "status"}
    >
      {children}
    </div>
  );
}
export function MetricStrip({
  items,
}: {
  items: Array<{ label: string; value: string | number; detail: string }>;
}) {
  return (
    <dl className="metric-strip">
      {items.map((item) => (
        <div key={item.label}>
          <dt>{item.label}</dt>
          <dd>
            {item.value}
            <small>{item.detail}</small>
          </dd>
        </div>
      ))}
    </dl>
  );
}
export function ResourceState({
  resource,
  empty,
  emptyTitle = "No records yet",
  emptyDescription = "New records will appear as this workflow progresses.",
  children,
}: {
  resource: {
    loading: boolean;
    problem: ApiProblem | null;
    refresh: () => void;
  };
  empty?: boolean;
  emptyTitle?: string;
  emptyDescription?: string;
  children: ReactNode;
}) {
  if (resource.loading) return <LoadingState />;
  if (resource.problem)
    return <ErrorState problem={resource.problem} onRetry={resource.refresh} />;
  if (empty)
    return (
      <div className="quiet-empty" role="status">
        <h3>{emptyTitle}</h3>
        <p>{emptyDescription}</p>
      </div>
    );
  return children;
}
export function Overlay({
  title,
  children,
  onClose,
  drawer = false,
  closeDisabled = false,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  drawer?: boolean;
  closeDisabled?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    const trigger = document.activeElement as HTMLElement | null;
    dialog?.showModal?.();
    return () => {
      dialog?.close?.();
      trigger?.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className={drawer ? "overlay drawer" : "overlay"}
      aria-labelledby="overlay-title"
      onCancel={(event) => {
        if (closeDisabled) event.preventDefault();
        else onClose();
      }}
      onClick={(e) => {
        if (!closeDisabled && e.target === e.currentTarget) {
          const r = e.currentTarget.getBoundingClientRect();
          if (
            e.clientX < r.left ||
            e.clientX > r.right ||
            e.clientY < r.top ||
            e.clientY > r.bottom
          )
            onClose();
        }
      }}
    >
      <header className="overlay-heading">
        <h2 id="overlay-title">{title}</h2>
        <button
          className="icon-button"
          aria-label="Close dialog"
          disabled={closeDisabled}
          onClick={onClose}
        >
          <Icon name="close" />
        </button>
      </header>
      <div className="overlay-body">{children}</div>
    </dialog>
  );
}
export function DetailList({ items }: { items: Array<[string, ReactNode]> }) {
  return (
    <dl className="detail-list">
      {items.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}
export function OrderLink({
  id,
  children,
}: {
  id: string;
  children?: ReactNode;
}) {
  return (
    <Link to={`/customer/orders/${id}`} className="inline-link">
      {children ?? `Order ${shortId(id)}`}
      <Icon name="arrow" size={14} />
    </Link>
  );
}
