import { describe, expect, it, vi } from 'vitest';
import { connectTrackingStream } from './tracking';

const snapshot = { orderId: '00000000-0000-0000-0000-000000000001', orderStatus: 'CREATED', orderVersion: 1,
  deliveryStatus: null, observedAt: '2026-10-14T00:00:00Z', position: null, terminal: false };

function responseWithText(text: string, hold = false): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) { controller.enqueue(new TextEncoder().encode(text)); if (!hold) controller.close(); },
  });
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

describe('bounded authenticated tracking SSE', () => {
  it('parses snapshots, refreshes after reconnect, and caps attempts', async () => {
    const controller = new AbortController();
    const fetcher = vi.fn().mockResolvedValueOnce(responseWithText(`event: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`))
      .mockImplementationOnce(async () => responseWithText(`event: snapshot\ndata: ${JSON.stringify({ ...snapshot, terminal: true })}\n\n`));
    const onSnapshot = vi.fn();
    const refreshSnapshot = vi.fn().mockResolvedValue(undefined);
    await connectTrackingStream({ url: '/api/stream', orderId: snapshot.orderId, token: 'memory-token', signal: controller.signal,
      fetcher, onSnapshot, onState: () => undefined, refreshSnapshot, delays: [0] });
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(onSnapshot).toHaveBeenCalledWith(snapshot);
    expect(refreshSnapshot).toHaveBeenCalledOnce();
    expect(refreshSnapshot).toHaveBeenCalledWith(expect.any(AbortSignal));
    expect(fetcher.mock.calls[0]?.[1]?.headers).toMatchObject({ Authorization: 'Bearer memory-token' });
  });

  it('stops reconnecting when the caller unmounts', async () => {
    const controller = new AbortController();
    const fetcher = vi.fn().mockImplementation(async () => responseWithText('', true));
    const pending = connectTrackingStream({ url: '/api/stream', orderId: snapshot.orderId, token: 'memory-token', signal: controller.signal,
      fetcher, onSnapshot: () => undefined, onState: () => undefined, refreshSnapshot: async () => undefined, delays: [5, 10, 20] });
    await vi.waitFor(() => expect(fetcher).toHaveBeenCalledOnce());
    controller.abort();
    await pending;
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it('cancels a snapshot refresh that is waiting during reconnect', async () => {
    const controller = new AbortController();
    const fetcher = vi.fn().mockResolvedValueOnce(responseWithText(''))
      .mockResolvedValueOnce(responseWithText('', true));
    const refreshSnapshot = vi.fn((signal: AbortSignal) => new Promise<void>((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
    }));
    const pending = connectTrackingStream({ url: '/api/stream', orderId: snapshot.orderId, token: 'memory-token', signal: controller.signal,
      fetcher, onSnapshot: () => undefined, onState: () => undefined, refreshSnapshot, delays: [0] });
    await vi.waitFor(() => expect(refreshSnapshot).toHaveBeenCalledOnce());
    controller.abort();
    await pending;
    expect(refreshSnapshot).toHaveBeenCalledWith(expect.any(AbortSignal));
  });

  it('does not reconnect an expired session or ignore a long rate-limit delay', async () => {
    const onUnauthenticated = vi.fn();
    for (const status of [401, 429]) {
      const fetcher = vi.fn(async () => new Response('', { status, headers: { 'Retry-After': '60' } }));
      await connectTrackingStream({ url: '/api/stream', orderId: snapshot.orderId, token: 'memory-token',
        signal: new AbortController().signal, fetcher, onSnapshot: vi.fn(), onState: vi.fn(),
        onUnauthenticated, refreshSnapshot: async () => undefined, delays: [0, 0, 0] });
      expect(fetcher).toHaveBeenCalledOnce();
    }
    expect(onUnauthenticated).toHaveBeenCalledOnce();
  });

  it('cancels a silent connection and rejects unbounded or malformed frames', async () => {
    for (const text of ['', 'x'.repeat(65537), 'event: snapshot\ndata: {"orderId":null}\n\n']) {
      const cancelled = vi.fn();
      const body = new ReadableStream<Uint8Array>({ start(controller) {
        if (text) controller.enqueue(new TextEncoder().encode(text));
      }, cancel: cancelled });
      const onSnapshot = vi.fn();
      await connectTrackingStream({ url: '/api/stream', orderId: snapshot.orderId, token: 'memory-token',
        signal: new AbortController().signal,
        fetcher: async () => new Response(body, { headers: { 'Content-Type': 'text/event-stream' } }),
        onSnapshot, onState: vi.fn(), refreshSnapshot: async () => undefined, delays: [], idleTimeoutMs: 5 });
      expect(cancelled).toHaveBeenCalledOnce();
      expect(onSnapshot).not.toHaveBeenCalled();
    }
  });
});
