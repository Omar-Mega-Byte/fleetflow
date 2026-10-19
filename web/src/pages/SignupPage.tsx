import { useState, type FormEvent } from "react";
import { Link, Navigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export function SignupPage() {
  const auth = useAuth();
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [pending, setPending] = useState(false);
  const [complete, setComplete] = useState(false);
  const [error, setError] = useState("");

  if (auth.status === "authenticated") return <Navigate to="/customer/orders" replace />;

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (pending) return;
    setError("");
    if (password !== confirm) {
      setError("The passwords do not match.");
      return;
    }
    setPending(true);
    try {
      const response = await fetch("/api/v1/auth/signup", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ displayName: name, email, password }),
        cache: "no-store", credentials: "omit", redirect: "error",
      });
      if (response.status === 201) {
        setComplete(true);
        setPassword("");
        setConfirm("");
        return;
      }
      await response.body?.cancel();
      setError(response.status === 409
        ? "This email already has an account. Sign in instead."
        : response.status === 429
          ? "Too many attempts. Wait a while and try again."
          : response.status === 400
            ? "Check your name, email, and password, then try again."
            : "Account creation is unavailable right now. Please try again.");
    } catch {
      setError("We couldn't reach FleetFlow. Check your connection and try again.");
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="login-page signup-page">
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
        <Link className="login-header-action" to="/login">Sign in <span aria-hidden="true">↗</span></Link>
      </header>

      <main className="login-layout">
        <section className="login-story" id="platform" aria-labelledby="signup-story-title">
          <div className="login-story-content">
            <p className="login-kicker"><span aria-hidden="true" />For customers</p>
            <h1 id="signup-story-title">Your deliveries.<br />One clear <em>view.</em></h1>
            <p className="login-intro">
              Follow every order from pickup to arrival. Create your customer account
              to see what is happening and what comes next.
            </p>
          </div>
          <div className="login-capabilities" id="capabilities" aria-label="Customer capabilities">
            <div className="login-capability"><span className="signup-step">01</span><span>View your orders</span></div>
            <div className="login-capability"><span className="signup-step">02</span><span>Follow its journey</span></div>
            <div className="login-capability"><span className="signup-step">03</span><span>See each handoff</span></div>
            <div className="login-capability"><span className="signup-step">04</span><span>Stay informed</span></div>
          </div>
          <p className="login-story-footer">A simpler way to keep up with every delivery.
            <span>FleetFlow / Customer workspace</span>
          </p>
        </section>

        <section className="login-panel signup-panel" aria-labelledby="signup-title">
          <div className="login-panel-content">
            <p className="signup-eyebrow">Customer account</p>
            <h2 id="signup-title">Get started</h2>
            <p className="login-panel-intro">Create your account in a minute.</p>
            {complete ? (
              <div className="signup-complete" role="status">
                <span className="signup-complete-mark" aria-hidden="true">✓</span>
                <h3>You're all set.</h3>
                <p>Your customer account is ready. Sign in to open your workspace.</p>
                <Link className="button button-primary login-submit signup-done-link" to="/login" state={{ registered: true }}>Continue to sign in <span aria-hidden="true">→</span></Link>
              </div>
            ) : (
              <form className="login-form signup-form" onSubmit={(event) => void submit(event)}>
                <label htmlFor="signup-name">Your name</label>
                <input id="signup-name" name="name" autoComplete="name" placeholder="Alex Morgan"
                  value={name} onChange={(event) => setName(event.target.value)} required minLength={2} maxLength={120} />
                <label htmlFor="signup-email">Email</label>
                <input id="signup-email" type="email" name="email" autoComplete="email"
                  placeholder="you@example.com" value={email} onChange={(event) => setEmail(event.target.value)}
                  required maxLength={320} />
                <label htmlFor="signup-password">Password</label>
                <div className="login-password-field">
                  <input id="signup-password" type={showPassword ? "text" : "password"}
                    name="password" autoComplete="new-password" placeholder="At least 15 characters"
                    value={password} onChange={(event) => setPassword(event.target.value)}
                    required minLength={15} maxLength={72} />
                  <button type="button" aria-label={showPassword ? "Hide password" : "Show password"}
                    aria-pressed={showPassword} onClick={() => setShowPassword((visible) => !visible)}>
                    {showPassword ? "Hide" : "Show"}
                  </button>
                </div>
                <p className="signup-hint">Use 15–72 characters. A long phrase works well.</p>
                <label htmlFor="signup-confirm">Confirm password</label>
                <input id="signup-confirm" type={showPassword ? "text" : "password"}
                  name="confirm-password" autoComplete="new-password" placeholder="Enter it again"
                  value={confirm} onChange={(event) => setConfirm(event.target.value)}
                  required minLength={15} maxLength={72} />
                {error && <p className="login-form-error" role="alert">{error}</p>}
                <button className="button button-primary login-submit" type="submit" disabled={pending}>
                  {pending ? "Creating account…" : "Create customer account"}
                </button>
              </form>
            )}
            <p className="login-switch">Already have an account? <Link to="/login">Sign in <span aria-hidden="true">→</span></Link></p>
            <p className="signup-scope">Customer accounts are for tracking and managing your own orders. Team access is managed separately.</p>
          </div>
        </section>
      </main>
    </div>
  );
}
