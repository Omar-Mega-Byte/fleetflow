import { useCallback, useEffect, useRef, useState } from "react";
import { ApiProblem } from "./client";
import type { Decoder } from "./decoders";
import { apiClient } from "../auth/AuthContext";

export function useResource<T>(
  path: string | null,
  decode: Decoder<T>,
  pollMs = 0,
) {
  const [revision, setRevision] = useState(0);
  const [result, setResult] = useState<{
    path: string;
    revision: number;
    data: T | null;
    problem: ApiProblem | null;
    observed: string;
  } | null>(null);
  const refresh = useCallback(() => setRevision((v) => v + 1), []);
  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    void apiClient
      .get(path, decode, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted)
          setResult({
            path,
            revision,
            data,
            problem: null,
            observed: new Date().toISOString(),
          });
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            path,
            revision,
            data: null,
            problem:
              error instanceof ApiProblem
                ? error
                : new ApiProblem("unexpected"),
            observed: new Date().toISOString(),
          });
      });
    return () => controller.abort();
  }, [path, decode, revision]);
  useEffect(() => {
    if (!path || !pollMs) return;
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") refresh();
    }, pollMs);
    return () => clearInterval(timer);
  }, [path, pollMs, refresh]);
  const current = result?.path === path ? result : null;
  return {
    data: current?.data ?? null,
    problem: current?.problem ?? null,
    loading: !!path && !current,
    refreshing: !!current && current.revision !== revision,
    observed: current?.observed,
    refresh,
  };
}
export function useCommand() {
  const [pending, setPending] = useState(false);
  const [problem, setProblem] = useState<ApiProblem | null>(null);
  const [message, setMessage] = useState("");
  const lock = useRef(false);
  const controller = useRef<AbortController | null>(null);
  useEffect(
    () => () => {
      controller.current?.abort();
    },
    [],
  );
  async function execute<T>(
    action: (signal: AbortSignal) => Promise<T>,
    success: (value: T) => void,
    copy = "Action completed",
  ) {
    if (lock.current) return;
    lock.current = true;
    setPending(true);
    setProblem(null);
    setMessage("");
    const request = new AbortController();
    controller.current = request;
    try {
      const value = await action(request.signal);
      if (!request.signal.aborted) {
        success(value);
        setMessage(copy);
      }
    } catch (error) {
      if (!request.signal.aborted)
        setProblem(
          error instanceof ApiProblem ? error : new ApiProblem("unexpected"),
        );
    } finally {
      lock.current = false;
      if (!request.signal.aborted) setPending(false);
    }
  }
  return { pending, problem, message, execute };
}
