import { useCallback, useEffect, useState } from "react";
import { ApiProblem } from "../../api/client";
import { decodeTrackingSnapshot } from "../../api/decoders";
import { useResource } from "../../api/useResource";
import { apiClient, useAuth } from "../../auth/AuthContext";
import { connectTrackingStream, type StreamState } from "../../sse/tracking";
import type { TrackingSnapshot } from "../../contracts";

export function useTracking(id: string | null) {
  const auth = useAuth();
  const decode = useCallback(
    (value: unknown) => {
      const snapshot = decodeTrackingSnapshot(value);
      if (snapshot.orderId !== id) throw new ApiProblem("unexpected");
      return snapshot;
    },
    [id],
  );
  const resource = useResource(
    id ? `/v1/orders/${encodeURIComponent(id)}/tracking` : null,
    decode,
  );
  const [live, setLive] = useState<TrackingSnapshot | null>(null);
  const [connection, setConnection] = useState<{
    id: string;
    state: StreamState;
  } | null>(null);
  const initial = resource.data?.orderId === id ? resource.data : null;
  const snapshot =
    live?.orderId === id && initial && live.orderVersion >= initial.orderVersion
      ? live
      : initial;
  const terminal = snapshot?.terminal;
  const readyId = initial?.orderId;
  const refreshSnapshot = useCallback(
    async (signal: AbortSignal) => {
      if (!id) return;
      const value = await apiClient.get(
        `/v1/orders/${encodeURIComponent(id)}/tracking`,
        decodeTrackingSnapshot,
        signal,
      );
      if (value.orderId !== id) throw new ApiProblem("unexpected");
      if (!signal.aborted)
        setLive((previous) =>
          previous?.orderId === id && previous.orderVersion > value.orderVersion
            ? previous
            : value,
        );
    },
    [id],
  );
  useEffect(() => {
    if (!id || !auth.user?.access_token || !readyId || terminal) return;
    const controller = new AbortController();
    void connectTrackingStream({
      url: `/api/v1/orders/${encodeURIComponent(id)}/tracking/stream`,
      orderId: id,
      token: auth.user.access_token,
      signal: controller.signal,
      refreshSnapshot,
      onUnauthenticated: auth.expireSession,
      onState: (state) => setConnection({ id, state }),
      onSnapshot: (value) =>
        setLive((previous) =>
          previous?.orderId === id && previous.orderVersion > value.orderVersion
            ? previous
            : value,
        ),
    });
    return () => controller.abort();
  }, [
    id,
    readyId,
    terminal,
    auth.user?.access_token,
    auth.expireSession,
    refreshSnapshot,
  ]);
  return {
    ...resource,
    snapshot,
    state: terminal
      ? ("ended" as const)
      : connection?.id === id
        ? connection.state
        : ("connecting" as const),
  };
}
