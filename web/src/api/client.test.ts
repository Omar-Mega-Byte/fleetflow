import { describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiProblem, mapResponseError } from './client';

const raw = (value: unknown) => value;
const token = async () => 'access-token-for-test';

describe('typed API boundary', () => {
  it.each([[400, 'validation'], [401, 'unauthenticated'], [403, 'forbidden'], [404, 'not-found'], [409, 'conflict'],
    [413, 'too-large'], [429, 'rate-limited'], [503, 'unavailable']] as const)(
    'maps %i to a safe problem state', (status, kind) => {
      expect(mapResponseError(new Response('{"message":"private backend detail"}', { status })).kind).toBe(kind);
      expect(mapResponseError(new Response('{"message":"private backend detail"}', { status })).message)
        .not.toContain('private backend detail');
    });

  it('retries a safe read once after 503, but never retries a mutation', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response('', { status: 503 }))
      .mockResolvedValueOnce(Response.json({ value: 'ok' }));
    const client = new ApiClient({ token, fetcher, timeoutMs: 3000 });
    await expect(client.get('/v1/orders', raw)).resolves.toEqual({ value: 'ok' });
    expect(fetcher).toHaveBeenCalledTimes(2);

    fetcher.mockReset().mockResolvedValue(new Response('', { status: 503 }));
    await expect(client.post('/v1/orders', { amount: 4 }, raw)).rejects.toMatchObject({ kind: 'unavailable' });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('does not issue the safe-read retry after the caller cancels during backoff', async () => {
    const controller = new AbortController();
    const fetcher = vi.fn().mockResolvedValue(new Response('', { status: 503 }));
    const client = new ApiClient({ token, fetcher, timeoutMs: 3000 });
    const pending = client.get('/v1/orders', raw, controller.signal);
    await vi.waitFor(() => expect(fetcher).toHaveBeenCalledOnce());
    await new Promise((resolve) => globalThis.setTimeout(resolve, 10));
    controller.abort();
    await expect(pending).rejects.toMatchObject({ kind: 'cancelled' });
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it('maps malformed JSON, aborts and timeouts without leaking response bodies', async () => {
    const malformed = new ApiClient({ token, fetcher: async () => new Response('not-json', { status: 200 }) });
    await expect(malformed.get('/v1/orders', raw)).rejects.toMatchObject({ kind: 'unexpected' });

    const controller = new AbortController();
    const fetchUntilAbort: typeof fetch = (_input, init) => new Promise((_resolve, reject) => {
      const signal = init?.signal;
      if (signal?.aborted) { reject(new TypeError('aborted')); return; }
      signal?.addEventListener('abort', () => reject(new TypeError('aborted')), { once: true });
    });
    const slow = new ApiClient({ token, fetcher: fetchUntilAbort, timeoutMs: 10_000 });
    const pending = slow.get('/v1/orders', raw, controller.signal);
    await vi.waitFor(async () => { await Promise.resolve(); });
    controller.abort();
    await expect(pending).rejects.toMatchObject({ kind: 'cancelled' });

    const timeout = new ApiClient({ token, fetcher: fetchUntilAbort, timeoutMs: 5 });
    await expect(timeout.get('/v1/orders', raw)).rejects.toMatchObject({ kind: 'timeout' });
  });

  it('calls the session expiry handler on 401 and retains Retry-After for 429', async () => {
    const expired = vi.fn();
    const client = new ApiClient({ token, fetcher: async () => new Response('', { status: 401 }), onUnauthenticated: expired });
    await expect(client.get('/v1/session', raw)).rejects.toBeInstanceOf(ApiProblem);
    expect(expired).toHaveBeenCalledOnce();
    const limited = mapResponseError(new Response('', { status: 429, headers: { 'Retry-After': '12' } }));
    expect(limited.retryAfterSeconds).toBe(12);
  });

  it('rejects remote API bases and respects Retry-After instead of immediately retrying', async () => {
    expect(() => new ApiClient({ token, baseUrl: '//external.example' })).toThrow();
    expect(() => new ApiClient({ token, baseUrl: 'https://external.example' })).toThrow();
    const fetcher = vi.fn(async () => new Response('', { status: 503, headers: { 'Retry-After': '30' } }));
    await expect(new ApiClient({ token, fetcher }).get('/v1/orders', raw)).rejects.toMatchObject({ retryAfterSeconds: 30 });
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it('supports successful empty commands and propagates decoder failures safely', async () => {
    const client = new ApiClient({ token, fetcher: async () => new Response(null, { status: 204 }) });
    await expect(client.post('/v1/orders/cancel', {}, () => undefined)).resolves.toBeUndefined();
    const malformed = new ApiClient({ token, fetcher: async () => Response.json({ total: 'sensitive' }) });
    await expect(malformed.get('/v1/orders', () => { throw new Error('sensitive'); })).rejects.toMatchObject({ kind: 'unexpected' });
  });
});
