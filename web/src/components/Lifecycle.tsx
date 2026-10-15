import { Icon, human } from "./Ui";
export const orderPhases = [
  "CREATED",
  "CONFIRMED",
  "PREPARING",
  "READY_FOR_PICKUP",
  "DISPATCHING",
  "DRIVER_ASSIGNED",
  "PICKED_UP",
  "IN_TRANSIT",
  "DELIVERED",
];
export function Lifecycle({
  status,
  compact = false,
}: {
  status: string;
  compact?: boolean;
}) {
  const exception = [
    "CANCELLED",
    "DELIVERY_FAILED",
    "DISPATCH_FAILED",
  ].includes(status);
  const active = orderPhases.indexOf(status);
  return (
    <div className={`lifecycle ${compact ? "compact" : ""}`}>
      {exception && (
        <div className="notice tone-danger">
          <Icon name="alert" />
          {human(status)} — progress paused at this outcome.
        </div>
      )}
      <ol className="lifecycle-steps" aria-label="Order lifecycle">
        {orderPhases.map((phase, i) => (
          <li
            key={phase}
            className={`${i < active ? "complete" : ""} ${i === active ? "current" : ""}`}
            aria-current={phase === status ? "step" : undefined}
          >
            <span className="phase-mark" aria-hidden="true">
              {i < active ? <Icon name="check" size={12} /> : i + 1}
            </span>
            <span>{human(phase)}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}
