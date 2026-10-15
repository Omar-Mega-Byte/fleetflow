import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
const mocks = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock("../auth/AuthContext", () => ({ apiClient: { get: mocks.get } }));
import { useCommand, useResource } from "./useResource";
const decode = (value: unknown) => String(value);
beforeEach(() => vi.clearAllMocks());
describe("request lifecycle isolation", () => {
  it("aborts the previous resource and ignores its late response after navigation", async () => {
    let first!: (value: string) => void;
    let second!: (value: string) => void;
    mocks.get
      .mockImplementationOnce(
        () =>
          new Promise<string>((resolve) => {
            first = resolve;
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<string>((resolve) => {
            second = resolve;
          }),
      );
    const hook = renderHook(({ path }) => useResource(path, decode), {
      initialProps: { path: "/v1/first" },
    });
    await waitFor(() => expect(mocks.get).toHaveBeenCalledTimes(1));
    const signal = mocks.get.mock.calls[0]![2] as AbortSignal;
    hook.rerender({ path: "/v1/second" });
    expect(signal.aborted).toBe(true);
    await act(async () => second("second"));
    expect(hook.result.current.data).toBe("second");
    await act(async () => first("first"));
    expect(hook.result.current.data).toBe("second");
  });
  it("locks a command synchronously so rapid activation submits only once", async () => {
    const hook = renderHook(() => useCommand());
    let finish!: (value: string) => void;
    const request = vi.fn(
      () =>
        new Promise<string>((resolve) => {
          finish = resolve;
        }),
    );
    const success = vi.fn();
    act(() => {
      void hook.result.current.execute(request, success);
      void hook.result.current.execute(request, success);
    });
    expect(request).toHaveBeenCalledTimes(1);
    expect(hook.result.current.pending).toBe(true);
    await act(async () => finish("accepted"));
    expect(success).toHaveBeenCalledWith("accepted");
    expect(hook.result.current.pending).toBe(false);
  });
});
