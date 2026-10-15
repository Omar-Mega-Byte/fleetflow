import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { LoadingState } from "../components/States";

export function AuthCallbackPage() {
  const auth = useAuth();
  const { completeSignIn } = auth;
  const navigate = useNavigate();
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let live = true;
    void completeSignIn()
      .then((returnTo) => {
        if (live) navigate(returnTo, { replace: true });
      })
      .catch(() => {
        if (live) setFailed(true);
      });
    return () => {
      live = false;
    };
  }, [completeSignIn, navigate]);
  if (failed)
    return (
      <main className="center-page">
        <section className="state-card error-state" role="alert">
          <h1>Sign-in could not be completed</h1>
          <p>
            The sign-in response was not accepted. Return to sign in and try
            again.
          </p>
          <button
            className="button button-primary"
            onClick={() => navigate("/login", { replace: true })}
          >
            Back to sign in
          </button>
        </section>
      </main>
    );
  return (
    <main className="center-page">
      <LoadingState label="Completing secure sign-in" />
    </main>
  );
}

export function SignedOutPage() {
  return (
    <main className="center-page">
      <section className="state-card empty-state">
        <span className="brand-mark" aria-hidden="true">
          F
        </span>
        <h1>You’re signed out</h1>
        <p>Your FleetFlow session has ended.</p>
        <a className="button button-primary" href="/login">
          Sign in again
        </a>
      </section>
    </main>
  );
}
