import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { apiClient } from "../auth/AuthContext";
import { useCommand, useResource } from "../api/useResource";
import { decodePolicy, decodeZones, type Zone } from "../features/contracts";
import { ContextForm } from "../components/ContextForm";
import { useWorkspace } from "../features/WorkspaceContext";
import { MapSurface } from "../components/MapSurface";
import { ZoneEditor } from "../features/zones/ZoneEditor";
import { ServiceabilityCheck } from "../features/zones/ServiceabilityCheck";
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
  const [editCloseBlocked, setEditCloseBlocked] = useState(false);
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
          onClick={() => {
            setEditCloseBlocked(false);
            setEdit({ zone: null });
          }}
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
          <ServiceabilityCheck
            branch={branch}
            policyVersion={policy.data?.version}
          />
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
                onClick={() => {
                  setEditCloseBlocked(false);
                  setEdit({ zone });
                }}
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
          closeDisabled={editCloseBlocked}
          onClose={() => setEdit(null)}
        >
          <ZoneEditor
            backgrounds={zones.data ?? []}
            onCloseBlocked={setEditCloseBlocked}
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
          closeDisabled={command.pending}
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
              <select
                disabled={command.pending}
                value={mode}
                onChange={(e) => setMode(e.target.value)}
              >
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
            Enter an authorized branch ID to load its delivery boundaries.
          </p>
        </EmptyState>
      )}
    </div>
  );
}
