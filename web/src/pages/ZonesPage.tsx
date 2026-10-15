import { useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { apiClient } from "../auth/AuthContext";
import { useCommand, useResource } from "../api/useResource";
import {
  decodeGeometry,
  decodePolicy,
  decodeZone,
  decodeZones,
  type Zone,
} from "../features/contracts";
import { ContextForm } from "../components/ContextForm";
import { useWorkspace } from "../features/WorkspaceContext";
import { MapSurface } from "../components/MapSurface";
import { EmptyState, ErrorState } from "../components/States";
import {
  DetailList,
  Icon,
  Notice,
  Overlay,
  PageHeader,
  Panel,
  ResourceState,
  StatusBadge,
  dateTime,
} from "../components/Ui";

function ZoneEditor({
  branch,
  zone,
  onSaved,
}: {
  branch: string;
  zone: Zone | null;
  onSaved: () => void;
}) {
  const [name, setName] = useState(zone?.name ?? "");
  const [active, setActive] = useState(zone?.active ?? true);
  const [source, setSource] = useState(
    zone ? JSON.stringify(zone.geometry, null, 2) : "",
  );
  const [drawing, setDrawing] = useState(false);
  const [points, setPoints] = useState<Array<[number, number]>>([]);
  const [validation, setValidation] = useState("");
  const key = useRef(crypto.randomUUID());
  const command = useCommand();
  const update = (value: string) => {
    setSource(value);
    key.current = crypto.randomUUID();
    setValidation("");
  };
  const applyPoints = (next: Array<[number, number]>) => {
    setPoints(next);
    if (next.length >= 3)
      update(
        JSON.stringify(
          { type: "Polygon", coordinates: [[...next, next[0]]] },
          null,
          2,
        ),
      );
  };
  const save = () => {
    try {
      const geometry = decodeGeometry(JSON.parse(source));
      const polygons =
        geometry.type === "Polygon"
          ? [geometry.coordinates]
          : geometry.coordinates;
      for (const polygon of polygons) {
        if (!polygon.length) throw new Error();
        for (const ring of polygon) {
          if (
            ring.length < 4 ||
            ring[0]![0] !== ring.at(-1)![0] ||
            ring[0]![1] !== ring.at(-1)![1]
          )
            throw new Error();
        }
      }
      if (!name.trim()) throw new Error();
      const body = { name: name.trim(), geometry, active };
      void command.execute(
        (signal) =>
          zone
            ? apiClient.put(
                `/v1/merchant-branches/${branch}/zones/${zone.id}`,
                { ...body, expectedVersion: zone.version },
                decodeZone,
                signal,
              )
            : apiClient.post(
                `/v1/merchant-branches/${branch}/zones`,
                body,
                decodeZone,
                { idempotencyKey: key.current, signal },
              ),
        onSaved,
        "Zone saved.",
      );
    } catch {
      setValidation(
        "Enter a name and a closed Polygon or MultiPolygon boundary with at least three distinct vertices.",
      );
    }
  };
  return (
    <form
      className="form-stack"
      onSubmit={(e) => {
        e.preventDefault();
        save();
      }}
      onChange={() => {
        key.current = crypto.randomUUID();
      }}
    >
      <label className="field">
        Zone name
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          required
          maxLength={120}
        />
      </label>
      <label>
        <input
          type="checkbox"
          checked={active}
          onChange={(e) => setActive(e.target.checked)}
        />{" "}
        Active delivery zone
      </label>
      <div className="button-row">
        <button
          type="button"
          className="button button-small"
          onClick={() => {
            setDrawing((v) => !v);
            setPoints([]);
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
            Click the map to add boundary vertices. Saving replaces the full
            boundary. You can also edit the coordinates below using the
            keyboard.
          </Notice>
          <MapSurface
            label={`New boundary · ${points.length} vertices`}
            geometries={zone ? [zone] : []}
            draft={points}
            onPoint={(point) => applyPoints([...points, point])}
          />
        </>
      )}
      <label className="field">
        Boundary GeoJSON
        <textarea
          className="mono"
          value={source}
          onChange={(e) => update(e.target.value)}
          rows={9}
          maxLength={40000}
          placeholder={
            '{"type":"Polygon","coordinates":[[[longitude,latitude],…]]]}'
          }
        />
      </label>
      {zone && (
        <p className="muted small">
          Replacing version {zone.version}. If it changed, close this editor,
          refresh zones and reopen the latest version.
        </p>
      )}
      {validation && <Notice tone="danger">{validation}</Notice>}
      {command.problem && <ErrorState problem={command.problem} />}
      <button className="button button-primary" disabled={command.pending}>
        {command.pending
          ? "Saving…"
          : zone
            ? "Save replacement"
            : "Create zone"}
      </button>
    </form>
  );
}
function BranchZones({ branch }: { branch: string }) {
  const zones = useResource(
    `/v1/merchant-branches/${branch}/zones`,
    decodeZones,
  );
  const policy = useResource(
    `/v1/merchant-branches/${branch}/zone-policy`,
    decodePolicy,
  );
  const command = useCommand();
  const [selected, setSelected] = useState("");
  const [edit, setEdit] = useState<{ zone: Zone | null } | null>(null);
  const [changeMode, setChangeMode] = useState(false);
  const [mode, setMode] = useState("UNRESTRICTED");
  const zone = zones.data?.find((z) => z.id === selected) ?? zones.data?.[0];
  const refresh = () => {
    zones.refresh();
    policy.refresh();
  };
  return (
    <>
      <div className="button-row work-actions">
        <button
          className="button button-primary"
          onClick={() => setEdit({ zone: null })}
        >
          <Icon name="plus" />
          Create zone
        </button>
        <button className="button" onClick={refresh}>
          <Icon name="refresh" />
          Refresh
        </button>
      </div>
      <div className="operations-grid">
        <div className="stack">
          <ResourceState resource={zones} empty={!zones.data?.length}>
            {zones.data && (
              <MapSurface
                label="Branch delivery boundaries"
                geometries={zones.data}
                selectedId={zone?.id}
                onSelect={setSelected}
              />
            )}
          </ResourceState>
          <Panel title="Serviceability policy">
            <ResourceState resource={policy}>
              {policy.data && (
                <div className="candidate-top">
                  <div>
                    <StatusBadge status={policy.data.mode} />
                    <p className="muted small">
                      Policy version {policy.data.version}
                    </p>
                  </div>
                  <button
                    className="button"
                    onClick={() => {
                      setMode(policy.data!.mode);
                      setChangeMode(true);
                    }}
                  >
                    Change policy
                  </button>
                </div>
              )}
            </ResourceState>
            {command.message && (
              <Notice tone="success">{command.message}</Notice>
            )}
            {command.problem && <ErrorState problem={command.problem} />}
          </Panel>
        </div>
        <aside className="stack">
          <Panel title="Delivery zones">
            {zones.data?.map((z) => (
              <button
                className={`zone-item ${z.id === zone?.id ? "selected" : ""}`}
                key={z.id}
                onClick={() => setSelected(z.id)}
              >
                <strong>{z.name}</strong>
                <div className="candidate-top">
                  <StatusBadge status={z.active ? "ACTIVE" : "INACTIVE"} />
                  <span className="mono">v{z.version}</span>
                </div>
              </button>
            ))}
          </Panel>
          {zone && (
            <Panel title={zone.name}>
              <DetailList
                items={[
                  [
                    "State",
                    <StatusBadge
                      status={zone.active ? "ACTIVE" : "INACTIVE"}
                    />,
                  ],
                  ["Version", zone.version],
                  ["Updated", dateTime(zone.updatedAt)],
                  ["Geometry", zone.geometry.type],
                ]}
              />
              <button
                className="button button-primary"
                onClick={() => setEdit({ zone })}
              >
                Edit zone
              </button>
            </Panel>
          )}
        </aside>
      </div>
      {edit && (
        <Overlay
          title={edit.zone ? "Edit delivery zone" : "Create delivery zone"}
          drawer
          onClose={() => setEdit(null)}
        >
          <ZoneEditor
            branch={branch}
            zone={edit.zone}
            onSaved={() => {
              setEdit(null);
              refresh();
            }}
          />
        </Overlay>
      )}
      {changeMode && policy.data && (
        <Overlay
          title="Change branch serviceability"
          onClose={() => setChangeMode(false)}
        >
          <form
            className="form-stack"
            onSubmit={(e) => {
              e.preventDefault();
              void command.execute(
                (signal) =>
                  apiClient.put(
                    `/v1/merchant-branches/${branch}/zone-policy`,
                    { mode, expectedVersion: policy.data!.version },
                    decodePolicy,
                    signal,
                  ),
                () => {
                  setChangeMode(false);
                  refresh();
                },
                "Branch policy updated.",
              );
            }}
          >
            <label className="field">
              Delivery coverage mode
              <select value={mode} onChange={(e) => setMode(e.target.value)}>
                <option value="UNRESTRICTED">
                  Unrestricted destination coverage
                </option>
                <option value="ENFORCED">
                  Require an active delivery zone
                </option>
              </select>
            </label>
            <p className="muted small">
              Applies to new serviceability decisions for this branch. Current
              version: {policy.data.version}.
            </p>
            <button
              className="button button-primary"
              disabled={command.pending}
            >
              Confirm policy change
            </button>
            {command.problem && <ErrorState problem={command.problem} />}
          </form>
        </Overlay>
      )}
    </>
  );
}
export function ZonesPage() {
  const workspace = useWorkspace();
  const [params, setParams] = useSearchParams();
  const branch = params.get("branchId") ?? workspace.branchId;
  const valid = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(branch);
  return (
    <div className="page-wrap">
      <PageHeader
        title="Delivery zones"
        description="Inspect delivery boundaries and manage branch serviceability."
        eyebrow="MERCHANT / GEOGRAPHY"
      />
      <ContextForm
        key={`context-${branch}`}
        label="Merchant branch ID"
        value={branch}
        onOpen={(value) => {
          workspace.setBranchId(value);
          setParams({ branchId: value });
        }}
      />
      {valid ? (
        <BranchZones key={branch} branch={branch} />
      ) : (
        <EmptyState title="Select your branch’s delivery area">
          <p>
            Enter an authorized branch ID to load its actual PostGIS boundaries.
          </p>
        </EmptyState>
      )}
    </div>
  );
}
