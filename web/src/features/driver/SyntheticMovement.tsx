import { useState } from "react";
import { Notice, Panel } from "../../components/Ui";
import type { decodeDemoDriverRoute } from "../contracts";
type Route = ReturnType<typeof decodeDemoDriverRoute>;

export function SyntheticMovement({
  route,
  disabled,
  onSend,
}: {
  route: Route;
  disabled: boolean;
  onSend: (position: Route["points"][number], confirmed: () => void) => void;
}) {
  const [step, setStep] = useState(0);
  const complete = step >= route.points.length;
  return (
    <Panel title="Synthetic movement">
      <Notice>
        Five fixture positions between the demo pickup and destination. Each
        click submits a position through your driver account. Delivery steps
        stay under your control.
      </Notice>
      <p className="muted small">
        Demo generation {route.generation}. Straight-line fixture points; road
        routes, speed and ETA are unavailable.
      </p>
      <p role="status">
        {step} of {route.points.length} synthetic positions confirmed.
      </p>
      <div className="button-row">
        {!complete && (
          <button
            className="button"
            disabled={disabled}
            onClick={() => {
              const point = route.points[step];
              if (point) onSend(point, () => setStep((value) => value + 1));
            }}
          >
            {step === 0
              ? "Send synthetic pickup"
              : "Send next synthetic position"}
          </button>
        )}
        {complete && (
          <button
            className="button"
            disabled={disabled}
            onClick={() => setStep(0)}
          >
            Restart synthetic route
          </button>
        )}
      </div>
    </Panel>
  );
}
