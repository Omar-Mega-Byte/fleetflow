import { useEffect, useState } from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { WorkspaceContext } from "../features/WorkspaceContext";
import { useAuth } from "../auth/AuthContext";
import type { PersonaRole } from "../contracts";
import { Icon, Overlay, human } from "./Ui";

const links: Array<{
  roles?: PersonaRole[];
  label: string;
  path: string;
  icon: string;
}> = [
  { label: "Command Center", path: "/command-center", icon: "grid" },
  {
    roles: ["CUSTOMER"],
    label: "Orders",
    path: "/customer/orders",
    icon: "orders",
  },
  { roles: ["CUSTOMER"], label: "Tracking", path: "/tracking", icon: "pin" },
  {
    roles: ["MERCHANT"],
    label: "Merchant",
    path: "/workspace/merchant",
    icon: "orders",
  },
  {
    roles: ["DRIVER"],
    label: "Driver workspace",
    path: "/workspace/driver",
    icon: "dispatch",
  },
  {
    roles: ["DISPATCHER"],
    label: "Dispatch",
    path: "/workspace/dispatcher",
    icon: "dispatch",
  },
  { roles: ["MERCHANT"], label: "Delivery zones", path: "/zones", icon: "map" },
  {
    roles: ["CUSTOMER", "DRIVER", "DISPATCHER", "ADMIN"],
    label: "Activity",
    path: "/activity",
    icon: "activity",
  },
  {
    roles: ["ADMIN"],
    label: "Operations",
    path: "/workspace/admin",
    icon: "system",
  },
];
export function AppShell() {
  const { roles, signOut } = useAuth();
  const location = useLocation();
  const [branchId, setBranchId] = useState(
    () => new URLSearchParams(location.search).get("branchId") ?? "",
  );
  const [orderId, setOrderId] = useState(
    () => new URLSearchParams(location.search).get("orderId") ?? "",
  );
  const navigate = useNavigate();
  const [palette, setPalette] = useState(false);
  const [query, setQuery] = useState("");
  const available = links.filter(
    (link) => !link.roles || link.roles.some((role) => roles.includes(role)),
  );
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setPalette((v) => !v);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);
  const open = (path: string) => {
    setPalette(false);
    setQuery("");
    navigate(path);
  };
  const uuid = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(
    query.trim(),
  );
  return (
    <div className="app-frame">
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <aside className="sidebar" aria-label="Workspace sidebar">
        <NavLink className="brand" to="/" aria-label="FleetFlow home">
          <span className="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none">
              <path
                d="M5 17V7h14M5 12h10"
                stroke="currentColor"
                strokeWidth="2.2"
              />
              <circle cx="18" cy="17" r="2" fill="currentColor" />
            </svg>
          </span>
          <span>FleetFlow</span>
        </NavLink>
        <div className="workspace-context">
          <span className="eyebrow">DELIVERY ORCHESTRATION</span>
          <strong>
            {roles.length > 1
              ? "Connected workspace"
              : `${human(roles[0] ?? "ACCOUNT")} workspace`}
          </strong>
          <span className="session-state">
            <span className="status-dot" />
            Authorized session
          </span>
        </div>
        <nav className="primary-nav" aria-label="Main navigation">
          {available.map((link) => (
            <NavLink
              key={link.path}
              to={
                branchId &&
                ["/zones", "/workspace/merchant"].includes(link.path)
                  ? `${link.path}?branchId=${encodeURIComponent(branchId)}`
                  : orderId && link.path === "/workspace/dispatcher"
                    ? `${link.path}?orderId=${encodeURIComponent(orderId)}`
                    : link.path
              }
              className={({ isActive }) =>
                `nav-link${isActive ? " active" : ""}`
              }
            >
              <Icon name={link.icon} />
              <span>{link.label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-foot">
          <span className="eyebrow">CURRENT ACCESS</span>
          <p>{roles.map(human).join(" · ")}</p>
          <button className="signout" onClick={() => void signOut()}>
            <Icon name="logout" />
            Sign out
          </button>
        </div>
      </aside>
      <div className="workspace-frame">
        <header className="topbar">
          <div className="topbar-context">
            <span className="brand-mobile">FleetFlow</span>
            <Icon name="layers" />
            <span>Delivery workspace</span>
          </div>
          <button className="command-trigger" onClick={() => setPalette(true)}>
            <Icon name="search" />
            <span>Go to…</span>
            <kbd>Ctrl K</kbd>
          </button>
        </header>
        <main id="main-content" className="main-content" tabIndex={-1}>
          <WorkspaceContext.Provider
            value={{ branchId, orderId, setBranchId, setOrderId }}
          >
            <Outlet />
          </WorkspaceContext.Provider>
        </main>
        <footer className="site-footer">
          <span>FleetFlow · Delivery orchestration</span>
          <span>Scoped to your account</span>
        </footer>
      </div>
      {palette && (
        <Overlay title="Go to a workspace" onClose={() => setPalette(false)}>
          <label className="field">
            Search pages or enter an order ID
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Orders, tracking, dispatch…"
            />
          </label>
          <div className="palette-results">
            {available
              .filter((link) =>
                link.label.toLowerCase().includes(query.toLowerCase()),
              )
              .map((link) => (
                <button key={link.path} onClick={() => open(link.path)}>
                  <Icon name={link.icon} />
                  {link.label}
                  <Icon name="arrow" />
                </button>
              ))}
            {uuid && roles.includes("CUSTOMER") && (
              <button onClick={() => open(`/customer/orders/${query.trim()}`)}>
                Open owned order
                <Icon name="arrow" />
              </button>
            )}
            {uuid && roles.includes("DISPATCHER") && (
              <button
                onClick={() =>
                  open(`/workspace/dispatcher?orderId=${query.trim()}`)
                }
              >
                Inspect dispatch
                <Icon name="arrow" />
              </button>
            )}
          </div>
          <p className="muted small">
            Use Tab to choose a destination. Escape closes this window.
          </p>
        </Overlay>
      )}
    </div>
  );
}
