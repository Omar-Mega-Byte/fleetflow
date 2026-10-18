import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { apiClient, useAuth } from "../auth/AuthContext";
import { useCommand, useResource } from "../api/useResource";
import { decodeDemo, type DemoState } from "../features/contracts";
import { RecoveryViews } from "../features/operations/RecoveryViews";
import { ErrorState, SuccessState } from "../components/States";
import {
  DetailList,
  Notice,
  Overlay,
  PageHeader,
  Panel,
  ResourceState,
  shortId,
} from "../components/Ui";
function DemoAction({
  action,
  onSaved,
  onCloseBlocked,
}: {
  action: "seed" | "reset";
  onSaved: (state: DemoState) => void;
  onCloseBlocked: (blocked: boolean) => void;
}) {
  const command = useCommand();
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const submitted = useRef<{
    body: { reason: string; confirmation?: string };
    key: string;
  } | null>(null);
  const ambiguous =
    command.problem !== null &&
    ["timeout", "network", "unavailable", "unexpected"].includes(
      command.problem.kind,
    );
  const retryable =
    ambiguous || command.problem?.kind === "rate-limited";

  const submit = () => {
    const request =
      submitted.current ??
      {
        body: {
          reason: reason.trim(),
          ...(action === "reset" ? { confirmation } : {}),
        },
        key: crypto.randomUUID(),
      };
    submitted.current = request;
    onCloseBlocked(true);
    void command.execute(
      (signal) =>
        apiClient.post(
          `/v1/demo/${action}`,
          request.body,
          decodeDemo,
          { idempotencyKey: request.key, signal },
        ),
      (state) => {
        submitted.current = null;
        onCloseBlocked(false);
        onSaved(state);
      },
    );
  };

  useEffect(() => {
    if (command.problem && !ambiguous) submitted.current = null;
    onCloseBlocked(command.pending || ambiguous);
  }, [ambiguous, command.pending, command.problem, onCloseBlocked]);

  return (
    <form
      className="form-stack"
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <p>
        {action === "reset"
          ? "This rolls the synthetic dataset into a new generation and closes active demo work."
          : "Prepare the bounded synthetic delivery dataset."}
      </p>
      <label className="field">
        Audit reason
        <textarea
          required
          maxLength={500}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          disabled={ambiguous}
        />
      </label>
      {action === "reset" && (
        <label className="field">
          Type RESET FLEETFLOW DEMO
          <input
            required
            value={confirmation}
            onChange={(e) => setConfirmation(e.target.value)}
            autoComplete="off"
            disabled={ambiguous}
          />
        </label>
      )}
      <button
        className="button button-primary"
        disabled={
          command.pending || ambiguous || command.problem?.kind === "rate-limited" ||
          !reason.trim() ||
          (action === "reset" && confirmation !== "RESET FLEETFLOW DEMO")
        }
      >
        {command.pending
          ? "Applying…"
          : action === "reset"
            ? "Reset synthetic demo"
            : "Prepare synthetic demo"}
      </button>
      {ambiguous && (
        <p className="notice tone-danger" role="status">
          The result is unknown. Retry this same request to confirm whether it
          completed. Keep this dialog open until FleetFlow confirms the result.
        </p>
      )}
      {command.problem && (
        <ErrorState
          problem={command.problem}
          onRetry={retryable ? submit : undefined}
        />
      )}
    </form>
  );
}
export function OperationsPage() {
  const { roles } = useAuth();
  const demo = useResource("/v1/demo", decodeDemo);
  const [action, setAction] = useState<"seed" | "reset" | null>(null);
  const [actionCloseBlocked, setActionCloseBlocked] = useState(false);
  const [confirmed, setConfirmed] = useState<{
    action: "seed" | "reset";
    state: DemoState;
  } | null>(null);
  return (
    <div className="page-wrap">
      <PageHeader
        title="Operations"
        description="Inspect publisher failures, consumer quarantine and the synthetic showcase."
        eyebrow="ADMIN / RECOVERY"
      />
      <div className="stack">
        <RecoveryViews />
        <Panel title="Synthetic dataset">
          {demo.problem?.kind === "not-found" ? (
            <Notice>
              Synthetic dataset controls are not enabled in this environment.
            </Notice>
          ) : (
            <ResourceState resource={demo}>
              {demo.data && (
                <>
                  <DetailList
                    items={[
                      ["Generation", demo.data.generation],
                      ["State", demo.data.result],
                      ["Orders", demo.data.orderIds.length],
                    ]}
                  />
                  <div className="button-row work-actions">
                    <button
                      className="button button-primary"
                      onClick={() => {
                        setConfirmed(null);
                        setActionCloseBlocked(false);
                        setAction("seed");
                      }}
                    >
                      Prepare synthetic demo
                    </button>
                    <button
                      className="button button-danger"
                      onClick={() => {
                        setConfirmed(null);
                        setActionCloseBlocked(false);
                        setAction("reset");
                      }}
                    >
                      Reset demo generation
                    </button>
                  </div>
                  {confirmed && (
                    <SuccessState>
                      <strong>
                        Synthetic demo {confirmed.action === "seed" ? "prepared" : "reset"} successfully.
                      </strong>
                      <p>
                        The backend confirmed generation {confirmed.state.generation} with{" "}
                        {confirmed.state.orderIds.length} synthetic order
                        {confirmed.state.orderIds.length === 1 ? "" : "s"}.
                        {" "}Response: {confirmed.state.result}.
                      </p>
                    </SuccessState>
                  )}
                  <div className="button-row">
                    {demo.data.orderIds.map((id) =>
                      roles.includes("DISPATCHER") ? (
                        <Link
                          className="button button-small"
                          key={id}
                          to={`/workspace/dispatcher?orderId=${id}`}
                        >
                          Inspect {shortId(id)}
                        </Link>
                      ) : (
                        <span className="mono" key={id}>
                          {id}
                        </span>
                      ),
                    )}
                  </div>
                </>
              )}
            </ResourceState>
          )}
        </Panel>
      </div>
      {action && (
        <Overlay
          title={
            action === "seed"
              ? "Prepare synthetic demo"
              : "Reset synthetic demo"
          }
          closeDisabled={actionCloseBlocked}
          onClose={() => {
            if (!actionCloseBlocked) setAction(null);
          }}
        >
          <DemoAction
            action={action}
            onCloseBlocked={setActionCloseBlocked}
            onSaved={(state) => {
              setConfirmed({ action, state });
              setActionCloseBlocked(false);
              setAction(null);
              demo.refresh();
            }}
          />
        </Overlay>
      )}
    </div>
  );
}
