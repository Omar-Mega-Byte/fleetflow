import { useRef } from "react";
import { useCommand } from "./useResource";

/** Keeps a manual retry tied to the request whose outcome is still unknown. */
export function useMutation<Body, Result>(
  action: (body: Body, key: string, signal: AbortSignal) => Promise<Result>,
  confirmed: (value: Result) => void,
) {
  const command = useCommand();
  const submitted = useRef<{ body: Body; key: string } | null>(null);
  const inFlight = useRef(false);
  const outcomeUnknown =
    command.problem !== null &&
    ["timeout", "network", "unavailable", "unexpected"].includes(
      command.problem.kind,
    );
  const submit = (body: Body) => {
    if (inFlight.current) return;
    const request =
      outcomeUnknown && submitted.current
        ? submitted.current
        : { body: structuredClone(body), key: crypto.randomUUID() };
    submitted.current = request;
    inFlight.current = true;
    void command
      .execute(
        (signal) => action(request.body, request.key, signal),
        (result) => {
          submitted.current = null;
          confirmed(result);
        },
      )
      .finally(() => {
        inFlight.current = false;
      });
  };
  return {
    ...command,
    submit,
    outcomeUnknown,
    locked: command.pending || outcomeUnknown,
  };
}
