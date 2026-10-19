import { useState, type FormEvent } from "react";
import { Link, Navigate, useLocation } from "react-router-dom";
import { ApiProblem } from "../api/client";
import { useAuth } from "../auth/AuthContext";

const capabilities = [
  { label: "Order management", icon: "order" },
  { label: "Smart dispatch", icon: "dispatch" },
  { label: "Live tracking", icon: "tracking" },
  { label: "Operational insights", icon: "insights" },
] as const;

function CapabilityIcon({ name }: { name: (typeof capabilities)[number]["icon"] }) {
  const shared = {
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.6,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
  };

  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" width="22" height="22" {...shared}>
      {name === "order" && (
        <>
          <path d="m12 2.8 8 4.5v9.4l-8 4.5-8-4.5V7.3l8-4.5Z" />
          <path d="m4 7.4 8 4.5 8-4.5M12 11.9v9.2" />
        </>
      )}
      {name === "dispatch" && (
        <>
          <circle cx="7" cy="7" r="2.2" />
          <circle cx="17" cy="7" r="2.2" />
          <path d="M2.9 18.7v-1.2c0-2.4 1.8-4.2 4.1-4.2s4.1 1.8 4.1 4.2v1.2M12.9 18.7v-1.2c0-2.4 1.8-4.2 4.1-4.2s4.1 1.8 4.1 4.2v1.2M10.2 7h3.6" />
        </>
      )}
      {name === "tracking" && (
        <>
          <path d="m21 3-7.4 18-2.8-7.8L3 10.4 21 3Z" />
          <path d="m10.8 13.2 4.6-4.6" />
        </>
      )}
      {name === "insights" && (
        <>
          <path d="M4 20V11M9.3 20V5M14.7 20v-8M20 20V8" />
          <path d="M2.8 20.5h18.4" />
        </>
      )}
    </svg>
  );
}

export function LoginPage() {
  const auth = useAuth();
  const location = useLocation();
  const [pending, setPending] = useState(false);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [formError, setFormError] = useState("");
  const registered = (location.state as { registered?: boolean } | null)?.registered === true;
  const candidate = (location.state as { from?: string } | null)?.from;
  const returnTo =
    candidate?.startsWith("/") &&
    !candidate.startsWith("//") &&
    !candidate.includes("\\")
      ? candidate
      : "/";

  if (auth.status === "authenticated")
    return <Navigate to={returnTo} replace />;

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (pending) return;
    setFormError("");
    setPending(true);
    try { await auth.signInWithPassword(email, password); }
    catch (error) {
      setFormError(error instanceof ApiProblem && error.status === 401
        ? "The email or password is incorrect."
        : error instanceof ApiProblem && error.status === 429
          ? "Too many attempts. Wait a few minutes and try again."
          : "Sign-in is unavailable right now. Please try again.");
    } finally { setPending(false); }
  };

  return (
    <div className="login-page">
      <header className="login-header">
        <div className="login-wordmark" aria-label="FleetFlow">
          <svg aria-hidden="true" className="login-logo" viewBox="0 0 30 30">
            <path d="M3 5h24l-3.2 5H3zM3 12.5h18.8l-3.2 5H3zM3 20h13.6l-3.2 5H3z" />
          </svg>
          <span>FleetFlow</span>
        </div>
        <nav className="login-nav" aria-label="Page navigation">
          <a href="#platform">Product</a>
          <a href="#capabilities">Capabilities</a>
        </nav>
        <Link className="login-header-action" to="/signup">
          Create account <span aria-hidden="true">↗</span>
        </Link>
      </header>

      <main className="login-layout">
        <section className="login-story" id="platform" aria-labelledby="login-title">
          <div className="login-story-content">
            <p className="login-kicker"><span aria-hidden="true" />Delivery orchestration</p>
            <h1 id="login-title">
              Every handoff.<br />
              One connected <em>flow.</em>
            </h1>
            <p className="login-intro">
              From the first order to the final delivery, FleetFlow connects
              customers, drivers, and operators in one reliable workspace.
            </p>
          </div>

          <div className="login-capabilities" id="capabilities" aria-label="FleetFlow capabilities">
            {capabilities.map(({ label, icon }) => (
              <div className="login-capability" key={label}>
                <CapabilityIcon name={icon} />
                <span>{label}</span>
              </div>
            ))}
          </div>
          <p className="login-story-footer">
            From order intake to proof of delivery.
            <span>FleetFlow / Delivery operations</span>
          </p>
        </section>

        <section className="login-panel" id="sign-in" aria-labelledby="sign-in-title">
          <div className="login-panel-content">
            <h2 id="sign-in-title">Welcome back</h2>
            <p className="login-panel-intro">Sign in to your FleetFlow workspace.</p>
            {registered && <p className="login-success" role="status">Your customer account is ready. Sign in below.</p>}
            {(auth.status === "error" || auth.problem) && !formError && (
              <p className="inline-alert" role="alert">
                Sign-in is temporarily unavailable or this account could not be
                verified. Please try again.
              </p>
            )}
            <form className="login-form" onSubmit={(event) => void submit(event)}>
              <label htmlFor="login-email">Email</label>
              <input id="login-email" type="email" name="email" autoComplete="username"
                placeholder="you@company.com" value={email} required maxLength={320}
                onChange={(event) => setEmail(event.target.value)} />
              <label htmlFor="login-password">Password</label>
              <div className="login-password-field">
                <input id="login-password" type={showPassword ? "text" : "password"}
                  name="password" autoComplete="current-password" placeholder="Enter your password"
                  value={password} required maxLength={1024}
                  onChange={(event) => setPassword(event.target.value)} />
                <button type="button" aria-label={showPassword ? "Hide password" : "Show password"}
                  aria-pressed={showPassword} onClick={() => setShowPassword((visible) => !visible)}>
                  {showPassword ? "Hide" : "Show"}
                </button>
              </div>
              {formError && <p className="login-form-error" role="alert">{formError}</p>}
              <button className="button button-primary login-submit" type="submit"
                disabled={auth.status === "loading" || pending}>
                {pending ? "Signing in…" : "Continue to sign in"}
              </button>
            </form>
            <p className="login-switch">New to FleetFlow? <Link to="/signup">Create a customer account <span aria-hidden="true">→</span></Link></p>
            <div className="login-alternate">
              <span>or</span>
              <button type="button" disabled={pending} onClick={() => void auth.signIn(returnTo)}>
                Continue with workspace SSO
              </button>
            </div>
            <div className="login-security">
              <svg aria-hidden="true" viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
                <path d="m12 2.5 8 3.2v5.8c0 5.2-3.1 8.5-8 10-4.9-1.5-8-4.8-8-10V5.7l8-3.2Z" />
                <path d="m8.7 11.8 2.3 2.3 4.5-4.6" />
              </svg>
              <span>Your session stays in this browser tab. FleetFlow checks your account permissions on every request.</span>
            </div>
          </div>
        </section>
      </main>
    </div>
  );
}
