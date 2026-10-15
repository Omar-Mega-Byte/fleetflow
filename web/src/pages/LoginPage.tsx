import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useState } from "react";

export function LoginPage() {
  const auth = useAuth();
  const location = useLocation();
  const [pending, setPending] = useState(false);
  const candidate = (location.state as { from?: string } | null)?.from;
  const returnTo =
    candidate?.startsWith("/") &&
    !candidate.startsWith("//") &&
    !candidate.includes("\\")
      ? candidate
      : "/";
  if (auth.status === "authenticated")
    return <Navigate to={returnTo} replace />;
  return (
    <main className="login-layout">
      <section className="login-story" aria-labelledby="login-title">
        <div className="brand brand-static">
          <span className="brand-mark" aria-hidden="true">
            F
          </span>
          <span>FleetFlow</span>
        </div>
        <div className="login-copy">
          <p className="eyebrow">DELIVERY ORCHESTRATION</p>
          <h1 id="login-title">
            Every handoff.
            <br />
            One connected flow.
          </h1>
          <p>
            From the first order to the final delivery, see the work, the
            decisions and the next move.
          </p>
          <div className="login-diagram" aria-label="Delivery workflow">
            <div>
              <span className="status-dot" />
              Order received<small>01</small>
            </div>
            <div>
              <span className="status-dot" />
              Driver matched<small>02</small>
            </div>
            <div>
              <span className="status-dot" />
              Offer accepted<small>03</small>
            </div>
            <div>
              <span className="status-dot" />
              Delivery completed<small>04</small>
            </div>
          </div>
        </div>
        <div className="login-footnote">
          FleetFlow · A connected delivery workspace.
        </div>
      </section>
      <section className="login-panel" aria-labelledby="sign-in-title">
        <div className="login-card">
          <p className="eyebrow">WORKSPACE ACCESS</p>
          <h2 id="sign-in-title">Welcome back</h2>
          <p className="muted">Sign in to your FleetFlow workspace.</p>
          {(auth.status === "error" || auth.problem) && (
            <p className="inline-alert" role="alert">
              Sign-in is temporarily unavailable or this account could not be
              verified. Please try again.
            </p>
          )}
          <button
            className="button button-primary button-wide"
            disabled={auth.status === "loading" || pending}
            onClick={() => {
              setPending(true);
              void auth.signIn(returnTo).finally(() => setPending(false));
            }}
          >
            {auth.status === "loading"
              ? "Checking session…"
              : pending
                ? "Opening secure sign-in…"
                : "Continue to sign in"}
          </button>
          <p className="login-assurance">
            <span aria-hidden="true">●</span> Secure sign-in provided by
            FleetFlow
          </p>
        </div>
        <p className="login-legal">
          By continuing, you’ll be redirected to FleetFlow’s secure identity
          provider.
        </p>
      </section>
    </main>
  );
}
