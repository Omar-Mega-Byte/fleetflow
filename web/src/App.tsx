import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { lazy, Suspense, useEffect } from "react";
import type { ReactNode } from "react";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import type { PersonaRole } from "./contracts";
import { AppShell } from "./components/AppShell";
import { ErrorState, LoadingState } from "./components/States";
import { ApiProblem } from "./api/client";
import { AuthCallbackPage, SignedOutPage } from "./pages/AuthCallbackPage";
import { CustomerOrderDetailPage } from "./pages/CustomerOrderDetailPage";
import { CustomerOrdersPage } from "./pages/CustomerOrdersPage";
import { CommandCenterPage } from "./pages/CommandCenterPage";
import { TrackingPage } from "./pages/TrackingPage";
import { LoginPage } from "./pages/LoginPage";
const DispatchPage = lazy(() =>
  import("./pages/DispatchPage").then((m) => ({ default: m.DispatchPage })),
);
const DriverPage = lazy(() =>
  import("./pages/DriverPage").then((m) => ({ default: m.DriverPage })),
);
const MerchantPage = lazy(() =>
  import("./pages/MerchantPage").then((m) => ({ default: m.MerchantPage })),
);
const ZonesPage = lazy(() =>
  import("./pages/ZonesPage").then((m) => ({ default: m.ZonesPage })),
);
const ActivityPage = lazy(() =>
  import("./pages/ActivityPage").then((m) => ({ default: m.ActivityPage })),
);
const OperationsPage = lazy(() =>
  import("./pages/OperationsPage").then((m) => ({ default: m.OperationsPage })),
);

export function ProtectedRoute({
  role,
  children,
}: {
  role?: PersonaRole;
  children: ReactNode;
}) {
  const auth = useAuth();
  const location = useLocation();
  if (auth.status === "loading")
    return (
      <main className="center-page">
        <LoadingState label="Checking your FleetFlow session" />
      </main>
    );
  if (auth.status === "unauthenticated")
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: location.pathname + location.search }}
      />
    );
  if (auth.status === "error")
    return (
      <main className="center-page">
        <ErrorState
          problem={auth.problem ?? new ApiProblem("unavailable")}
          onRetry={() => void auth.reauthenticate()}
          onReauthenticate={() => void auth.reauthenticate()}
        />
      </main>
    );
  if (role && !auth.roles.includes(role))
    return <Navigate to="/forbidden" replace />;
  return children;
}

function DefaultRoute() {
  return <Navigate to="/command-center" replace />;
}

function WorkspacePreview({ persona = "account" }: { persona?: string }) {
  const label = persona.replace(/^./, (letter) => letter.toUpperCase());
  return (
    <div className="page-wrap">
      <p className="eyebrow">{label.toUpperCase()} WORKSPACE</p>
      <h1>{label} workspace</h1>
      <section className="state-card empty-state">
        <h2>Workspace unavailable in this preview</h2>
        <p>Your account is signed in. This workspace is not available yet.</p>
      </section>
    </div>
  );
}

function RouteFocus() {
  const { pathname } = useLocation();
  useEffect(() => {
    const focusHeading = () => {
      const target = document.querySelector<HTMLElement>("main h1");
      if (!target) return false;
      target.setAttribute("tabindex", "-1");
      target.focus({ preventScroll: true });
      return true;
    };
    if (focusHeading()) return;
    const observer = new MutationObserver(() => {
      if (focusHeading()) observer.disconnect();
    });
    observer.observe(document.body, { childList: true, subtree: true });
    return () => observer.disconnect();
  }, [pathname]);
  return null;
}

function ForbiddenPage() {
  return (
    <main className="center-page">
      <section className="state-card error-state">
        <h1>Access unavailable</h1>
        <p>
          This account does not have the FleetFlow role needed for this page.
        </p>
        <a className="button button-secondary" href="/">
          Go to your workspace
        </a>
      </section>
    </main>
  );
}

export function App() {
  return (
    <AuthProvider>
      <RouteFocus />
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/auth/callback" element={<AuthCallbackPage />} />
        <Route path="/auth/signed-out" element={<SignedOutPage />} />
        <Route path="/forbidden" element={<ForbiddenPage />} />
        <Route
          element={
            <ProtectedRoute>
              <AppShell />
            </ProtectedRoute>
          }
        >
          <Route index element={<DefaultRoute />} />
          <Route path="command-center" element={<CommandCenterPage />} />
          <Route
            path="tracking"
            element={
              <ProtectedRoute role="CUSTOMER">
                <TrackingPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="customer/orders"
            element={
              <ProtectedRoute role="CUSTOMER">
                <CustomerOrdersPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="customer/orders/:id"
            element={
              <ProtectedRoute role="CUSTOMER">
                <CustomerOrderDetailPage />
              </ProtectedRoute>
            }
          />
          <Route path="workspace" element={<WorkspacePreview />} />
          <Route
            path="workspace/merchant"
            element={
              <ProtectedRoute role="MERCHANT">
                <Suspense fallback={<LoadingState />}>
                  <MerchantPage />
                </Suspense>
              </ProtectedRoute>
            }
          />
          <Route
            path="workspace/driver"
            element={
              <ProtectedRoute role="DRIVER">
                <Suspense fallback={<LoadingState />}>
                  <DriverPage />
                </Suspense>
              </ProtectedRoute>
            }
          />
          <Route
            path="workspace/dispatcher"
            element={
              <ProtectedRoute role="DISPATCHER">
                <Suspense fallback={<LoadingState />}>
                  <DispatchPage />
                </Suspense>
              </ProtectedRoute>
            }
          />
          <Route
            path="workspace/admin"
            element={
              <ProtectedRoute role="ADMIN">
                <Suspense fallback={<LoadingState />}>
                  <OperationsPage />
                </Suspense>
              </ProtectedRoute>
            }
          />
          <Route
            path="zones"
            element={
              <ProtectedRoute role="MERCHANT">
                <Suspense fallback={<LoadingState />}>
                  <ZonesPage />
                </Suspense>
              </ProtectedRoute>
            }
          />
          <Route
            path="activity"
            element={
              <Suspense fallback={<LoadingState />}>
                <ActivityPage />
              </Suspense>
            }
          />
        </Route>
        <Route
          path="*"
          element={
            <main className="center-page">
              <section className="state-card empty-state">
                <h1>Page not found</h1>
                <p>That FleetFlow page doesn’t exist.</p>
                <a href="/">Go to your workspace</a>
              </section>
            </main>
          }
        />
      </Routes>
    </AuthProvider>
  );
}
