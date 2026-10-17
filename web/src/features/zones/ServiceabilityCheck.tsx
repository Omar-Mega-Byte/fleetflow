import { useRef, useState, type FormEvent } from "react";
import { apiClient } from "../../auth/AuthContext";
import { useCommand } from "../../api/useResource";
import { ErrorState } from "../../components/States";
import { Notice, Panel } from "../../components/Ui";
import { decodeServiceability } from "../contracts";

export function ServiceabilityCheck({
  branch,
  policyVersion,
}: {
  branch: string;
  policyVersion?: number;
}) {
  const [latitude, setLatitude] = useState("");
  const [longitude, setLongitude] = useState("");
  const [result, setResult] = useState<ReturnType<
    typeof decodeServiceability
  > | null>(null);
  const command = useCommand();
  const form = useRef<HTMLFormElement>(null);
  const stale =
    result !== null &&
    policyVersion !== undefined &&
    result.policyVersion !== policyVersion;
  const check = () => {
    setResult(null);
    void command.execute(
      (signal) =>
        apiClient.post(
          `/v1/merchant-branches/${branch}/serviceability`,
          { latitude: Number(latitude), longitude: Number(longitude) },
          decodeServiceability,
          { signal },
        ),
      setResult,
    );
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    check();
  };
  return (
    <Panel title="Check a destination">
      <p className="muted small">
        Check the branch’s current delivery coverage before changing its policy
        or accepting work.
      </p>
      <form className="form-stack" ref={form} onSubmit={submit}>
        <fieldset
          className="form-grid form-fieldset"
          disabled={command.pending}
        >
          <legend className="sr-only">Destination coordinates</legend>
          <label className="field">
            Destination latitude
            <input
              type="number"
              required
              min={-90}
              max={90}
              step="0.000001"
              value={latitude}
              onChange={(event) => {
                setLatitude(event.target.value);
                setResult(null);
              }}
            />
          </label>
          <label className="field">
            Destination longitude
            <input
              type="number"
              required
              min={-180}
              max={180}
              step="0.000001"
              value={longitude}
              onChange={(event) => {
                setLongitude(event.target.value);
                setResult(null);
              }}
            />
          </label>
        </fieldset>
        <button
          className="button"
          disabled={command.pending || command.problem?.kind === "rate-limited"}
        >
          {command.pending ? "Checking coverage…" : "Check coverage"}
        </button>
        {command.problem && (
          <ErrorState
            problem={command.problem}
            onRetry={() => form.current?.requestSubmit()}
          />
        )}
        {stale && (
          <Notice>
            Coverage policy changed. Check the destination again for the latest
            result.
          </Notice>
        )}
        {result && !stale && (
          <Notice tone={result.supported ? "success" : "waiting"}>
            <strong>
              {result.supported
                ? "Destination is covered"
                : "Outside active coverage"}
            </strong>
            <p>
              {result.mode === "UNRESTRICTED"
                ? "This branch currently allows unrestricted destination coverage."
                : "The active branch boundaries determine coverage."}{" "}
              Policy version {result.policyVersion}.
            </p>
          </Notice>
        )}
      </form>
    </Panel>
  );
}
