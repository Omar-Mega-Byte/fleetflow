import { useRef, useState } from "react";
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
}: {
  action: "seed" | "reset";
  onSaved: (state: DemoState) => void;
}) {
  const command = useCommand();
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const key = useRef(crypto.randomUUID());
  return (
    <form
      className="form-stack"
      onChange={() => {
        key.current = crypto.randomUUID();
      }}
      onSubmit={(e) => {
        e.preventDefault();
        void command.execute(
          (signal) =>
            apiClient.post(
              `/v1/demo/${action}`,
              {
                reason: reason.trim(),
                ...(action === "reset" ? { confirmation } : {}),
              },
              decodeDemo,
              { idempotencyKey: key.current, signal },
            ),
          onSaved,
        );
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
          />
        </label>
      )}
      <button
        className="button button-primary"
        disabled={
          command.pending ||
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
      {command.problem && <ErrorState problem={command.problem} />}
    </form>
  );
}
export function OperationsPage() {
  const { roles } = useAuth();
  const demo = useResource("/v1/demo", decodeDemo);
  const [action, setAction] = useState<"seed" | "reset" | null>(null);
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
                        setAction("seed");
                      }}
                    >
                      Prepare synthetic demo
                    </button>
                    <button
                      className="button button-danger"
                      onClick={() => {
                        setConfirmed(null);
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
          onClose={() => setAction(null)}
        >
          <DemoAction
            action={action}
            onSaved={(state) => {
              setConfirmed({ action, state });
              setAction(null);
              demo.refresh();
            }}
          />
        </Overlay>
      )}
    </div>
  );
}
