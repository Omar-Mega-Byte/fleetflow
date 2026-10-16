import { useRef, useState } from "react";
import { apiClient } from "../../auth/AuthContext";
import { ApiProblem } from "../../api/client";
import { useCommand, useResource } from "../../api/useResource";
import { decodeFailed, decodeQuarantine, decodeReplay } from "../contracts";
import { ErrorState } from "../../components/States";
import {
  DetailList,
  Icon,
  Notice,
  Overlay,
  Panel,
  ResourceState,
  dateTime,
  human,
  shortId,
} from "../../components/Ui";
function ReplayForm({ id, onSaved }: {
  id: string;
  onSaved: (result: ReturnType<typeof decodeReplay>) => void;
}) {
  const [reason, setReason] = useState("");
  const key = useRef(crypto.randomUUID());
  const command = useCommand();
  return (
    <form
      className="form-stack"
      onSubmit={(e) => {
        e.preventDefault();
        void command.execute(
          async (signal) => {
            const result = await apiClient.post(
              `/v1/operations/outbox/${id}/replay`,
              { reason: reason.trim() },
              decodeReplay,
              { idempotencyKey: key.current, signal },
            );
            if (result.eventId !== id) throw new ApiProblem("unexpected");
            return result;
          },
          onSaved,
          "Replay request completed.",
        );
      }}
    >
      <p>
        This requeues the failed event through the normal publisher and
        deduplication path.
      </p>
      <label className="field">
        Audit reason
        <textarea
          required
          maxLength={500}
          value={reason}
          onChange={(e) => {
            setReason(e.target.value);
            key.current = crypto.randomUUID();
          }}
        />
      </label>
      <button
        className="button button-primary"
        disabled={command.pending || !reason.trim()}
      >
        {command.pending ? "Requesting replay…" : "Confirm event replay"}
      </button>
      {command.problem && <ErrorState problem={command.problem} />}
    </form>
  );
}
export function RecoveryViews() {
  const failed = useResource("/v1/operations/outbox/failed", decodeFailed);
  const quarantined = useResource(
    "/v1/operations/events/quarantined",
    decodeQuarantine,
  );
  const [replay, setReplay] = useState("");
  const [inspect, setInspect] = useState<
    ReturnType<typeof decodeQuarantine>["content"][number] | null
  >(null);
  const [feedback, setFeedback] = useState<{ message: string; tone: "success" | "danger" } | null>(null);
  return (
    <>
      {feedback && <Notice tone={feedback.tone}>{feedback.message}</Notice>}
      <div className="operations-grid">
        <Panel
          title="Failed publisher events"
          action={
            <button
              className="icon-button"
              aria-label="Refresh failed events"
              onClick={failed.refresh}
            >
              <Icon name="refresh" />
            </button>
          }
        >
          <ResourceState resource={failed} empty={!failed.data?.content.length}>
            {failed.data?.content.map((e) => (
              <div className="candidate" key={e.eventId}>
                <div className="candidate-top">
                  <strong>{human(e.eventType)}</strong>
                  <span className="mono">{shortId(e.eventId)}</span>
                </div>
                <p>
                  {e.errorCode} · {e.attempts} attempts · {e.replayCount}{" "}
                  replays
                </p>
                <p>{dateTime(e.occurredAt)}</p>
                <button
                  className="button button-small"
                  onClick={() => setReplay(e.eventId)}
                >
                  Inspect and replay
                </button>
              </div>
            ))}
          </ResourceState>
        </Panel>
        <Panel
          title="Consumer quarantine"
          action={
            <button
              className="icon-button"
              aria-label="Refresh quarantine"
              onClick={quarantined.refresh}
            >
              <Icon name="refresh" />
            </button>
          }
        >
          <ResourceState
            resource={quarantined}
            empty={!quarantined.data?.content.length}
          >
            {quarantined.data?.content.map((e) => (
              <div
                className="record-row"
                key={`${e.consumer}/${e.topic}/${e.partition}/${e.offset}`}
              >
                <Icon name="alert" />
                <div className="record-body">
                  <strong>{e.consumer}</strong>
                  <p>
                    {e.errorCode} · {e.topic}
                  </p>
                  <button
                    className="button button-small"
                    onClick={() => setInspect(e)}
                  >
                    Inspect record
                  </button>
                </div>
              </div>
            ))}
          </ResourceState>
          <p className="muted small">
            Quarantined records provide inspection metadata. Replay is available
            only for failed publisher events.
          </p>
        </Panel>
      </div>
      {replay && (
        <Overlay title="Replay failed event" onClose={() => setReplay("")}>
          <ReplayForm
            id={replay}
            onSaved={(result) => {
              setReplay("");
              failed.refresh();
              setFeedback(result.outcome === "REPLAYED"
                ? { tone: "success", message: "Replay accepted. Publication continues asynchronously." }
                : result.outcome === "REPLAY_LIMIT_REACHED"
                  ? { tone: "danger", message: "Replay limit reached. The event remains failed." }
                  : { tone: "danger", message: "Event was not queued for replay. Refresh and inspect its current state." });
            }}
          />
        </Overlay>
      )}
      {inspect && (
        <Overlay
          title="Quarantined event"
          drawer
          onClose={() => setInspect(null)}
        >
          <DetailList
            items={[
              ["Consumer", inspect.consumer],
              ["Topic", inspect.topic],
              ["Partition", inspect.partition],
              ["Offset", inspect.offset],
              ["Error", inspect.errorCode],
              ["Quarantined", dateTime(inspect.quarantinedAt)],
            ]}
          />
        </Overlay>
      )}
    </>
  );
}
