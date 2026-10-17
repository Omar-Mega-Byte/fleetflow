import { useEffect, useState } from "react";
import { apiClient } from "../../auth/AuthContext";
import { useMutation } from "../../api/useMutation";
import { decodeGeometry, decodeZone, type Zone } from "../contracts";
import { MapSurface } from "../../components/MapSurface";
import { ErrorState } from "../../components/States";
import { Notice } from "../../components/Ui";

type Vertex = [number, number];
function simpleVertices(geometry: Zone["geometry"]): Vertex[] | null {
  const polygons =
    geometry.type === "Polygon" ? [geometry.coordinates] : geometry.coordinates;
  const ring = polygons[0]?.[0];
  return polygons.length === 1 &&
    polygons[0]?.length === 1 &&
    ring &&
    ring.length <= 65
    ? ring.slice(0, -1).map(([lng, lat]) => [lng!, lat!] as Vertex)
    : null;
}

export function ZoneEditor({
  branch,
  zone,
  backgrounds,
  onSaved,
  onCloseBlocked,
}: {
  branch: string;
  zone: Zone | null;
  backgrounds: Zone[];
  onSaved: () => void;
  onCloseBlocked: (blocked: boolean) => void;
}) {
  const [name, setName] = useState(zone?.name ?? "");
  const [active, setActive] = useState(zone?.active ?? true);
  const [source, setSource] = useState(
    zone ? JSON.stringify(zone.geometry, null, 2) : "",
  );
  const [points, setPoints] = useState<Vertex[]>(
    zone ? (simpleVertices(zone.geometry) ?? []) : [],
  );
  const [simple, setSimple] = useState(
    !zone || simpleVertices(zone.geometry) !== null,
  );
  const [drawing, setDrawing] = useState(false);
  const [validation, setValidation] = useState("");
  type Draft = {
    name: string;
    active: boolean;
    geometry: Zone["geometry"];
    expectedVersion?: number;
  };
  const command = useMutation(
    (body: Draft, key, signal) =>
      zone
        ? apiClient.put(
            `/v1/merchant-branches/${branch}/zones/${zone.id}`,
            body,
            decodeZone,
            signal,
          )
        : apiClient.post(
            `/v1/merchant-branches/${branch}/zones`,
            body,
            decodeZone,
            { idempotencyKey: key, signal },
          ),
    onSaved,
  );
  useEffect(() => {
    onCloseBlocked(command.locked);
  }, [command.locked, onCloseBlocked]);
  const applyPoints = (next: Vertex[]) => {
    setPoints(next);
    setSimple(true);
    setValidation("");
    setSource(
      next.length >= 3
        ? JSON.stringify(
            { type: "Polygon", coordinates: [[...next, next[0]]] },
            null,
            2,
          )
        : "",
    );
  };
  const save = () => {
    try {
      const geometry = decodeGeometry(JSON.parse(source));
      if (!name.trim()) throw new Error();
      command.submit({
        name: name.trim(),
        active,
        geometry,
        ...(zone ? { expectedVersion: zone.version } : {}),
      });
    } catch {
      setValidation(
        "Enter a name and a closed boundary with at least three distinct vertices. Incomplete drawings cannot be saved.",
      );
    }
  };
  return (
    <form
      className="form-stack"
      onSubmit={(event) => {
        event.preventDefault();
        save();
      }}
    >
      <fieldset className="form-stack form-fieldset" disabled={command.locked}>
        <legend className="sr-only">Delivery zone details</legend>
        <label className="field">
          Zone name
          <input
            required
            maxLength={120}
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </label>
        <label>
          <input
            type="checkbox"
            checked={active}
            onChange={(event) => setActive(event.target.checked)}
          />{" "}
          Active delivery zone
        </label>
        <div className="button-row">
          <button
            type="button"
            className="button button-small"
            onClick={() => {
              if (drawing) setDrawing(false);
              else {
                applyPoints([]);
                setDrawing(true);
              }
            }}
          >
            {drawing ? "Stop drawing" : "Draw new boundary"}
          </button>
          <button
            type="button"
            className="button button-small"
            disabled={!points.length}
            onClick={() => applyPoints(points.slice(0, -1))}
          >
            Undo vertex
          </button>
        </div>
        {drawing && (
          <>
            <Notice>
              Click the map to add vertices, including inside the current
              boundary. Use the coordinate fields for keyboard editing. Saving
              replaces the full boundary.
            </Notice>
            <MapSurface
              label={`New boundary · ${points.length} vertices`}
              geometries={backgrounds}
              draft={points}
              onPoint={(point) => {
                if (!command.locked && points.length < 64)
                  applyPoints([...points, point]);
              }}
            />
          </>
        )}
        {simple ? (
          <section className="form-stack" aria-label="Boundary vertices">
            <p className="muted small">
              Enter longitude and latitude for each vertex. The last point joins
              the first automatically. Up to 64 vertices.
            </p>
            {points.map(([longitude, latitude], index) => (
              <div className="candidate" key={index}>
                <div className="candidate-top">
                  <strong>Vertex {index + 1}</strong>
                  <button
                    type="button"
                    className="button button-small"
                    aria-label={`Remove vertex ${index + 1}`}
                    onClick={() =>
                      applyPoints(points.filter((_, i) => i !== index))
                    }
                  >
                    Remove
                  </button>
                </div>
                <div className="form-grid">
                  <label className="field">
                    Longitude {index + 1}
                    <input
                      type="number"
                      required
                      min={-180}
                      max={180}
                      step="any"
                      value={Number.isFinite(longitude) ? longitude : ""}
                      onChange={(event) =>
                        applyPoints(
                          points.map((point, i) =>
                            i === index
                              ? [event.target.valueAsNumber, point[1]]
                              : point,
                          ),
                        )
                      }
                    />
                  </label>
                  <label className="field">
                    Latitude {index + 1}
                    <input
                      type="number"
                      required
                      min={-90}
                      max={90}
                      step="any"
                      value={Number.isFinite(latitude) ? latitude : ""}
                      onChange={(event) =>
                        applyPoints(
                          points.map((point, i) =>
                            i === index
                              ? [point[0], event.target.valueAsNumber]
                              : point,
                          ),
                        )
                      }
                    />
                  </label>
                </div>
              </div>
            ))}
            <button
              type="button"
              className="button button-small"
              disabled={points.length >= 64}
              onClick={() => applyPoints([...points, points.at(-1) ?? [0, 0]])}
            >
              Add vertex
            </button>
          </section>
        ) : (
          <Notice>
            This boundary has multiple rings or more than 64 vertices. Keep it
            intact using advanced coordinates, or draw a replacement.
          </Notice>
        )}
        <details>
          <summary>Advanced boundary coordinates</summary>
          <label className="field">
            Boundary GeoJSON
            <textarea
              className="mono"
              rows={9}
              maxLength={40000}
              value={source}
              onChange={(event) => {
                setSource(event.target.value);
                setValidation("");
                try {
                  const next = simpleVertices(
                    decodeGeometry(JSON.parse(event.target.value)),
                  );
                  setPoints(next ?? []);
                  setSimple(next !== null);
                } catch {
                  setPoints([]);
                  setSimple(false);
                }
              }}
            />
          </label>
        </details>
      </fieldset>
      {zone && (
        <p className="muted small">
          Replacing version {zone.version}. If it changed, close this editor,
          refresh zones and reopen the latest version.
        </p>
      )}
      {validation && <Notice tone="danger">{validation}</Notice>}
      {command.outcomeUnknown && (
        <p className="notice tone-danger" role="status">
          The save result is unknown. Retry the same request before editing or
          closing this boundary.
        </p>
      )}
      {command.problem && (
        <ErrorState
          problem={command.problem}
          onRetry={
            command.outcomeUnknown || command.problem.kind === "rate-limited"
              ? save
              : undefined
          }
        />
      )}
      <button
        className="button button-primary"
        disabled={command.locked || command.problem?.kind === "rate-limited"}
      >
        {command.pending
          ? "Saving…"
          : zone
            ? "Save replacement"
            : "Create zone"}
      </button>
    </form>
  );
}
