import type { TrackingSnapshot } from '../contracts';
import { ApiProblem, mapResponseError } from '../api/client';
import { decodeTrackingSnapshot } from '../api/decoders';
import { delay } from '../api/async';

export type StreamState = 'connecting' | 'live' | 'stale' | 'unavailable' | 'ended';
export interface TrackingStreamOptions {
  url: string;
  orderId: string;
  token: string;
  signal: AbortSignal;
  fetcher?: typeof fetch;
  onSnapshot: (snapshot: TrackingSnapshot) => void;
  onState: (state: StreamState) => void;
  onProblem?: (problem: ApiProblem) => void;
  onUnauthenticated?: () => void;
  refreshSnapshot: (signal: AbortSignal) => Promise<void>;
  delays?: number[];
  connectTimeoutMs?: number;
  idleTimeoutMs?: number;
}
function parseEvent(block: string): { event: string; data: string } | null {
  let event = 'message'; const data: string[] = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  }
  return data.length ? { event, data: data.join('\n') } : null;
}
export async function connectTrackingStream(options: TrackingStreamOptions): Promise<void> {
  if (!options.url.startsWith('/api/') || options.url.startsWith('//') || /[\r\n\\]/.test(options.url)) {
    options.onProblem?.(new ApiProblem('unexpected')); options.onState('unavailable'); return;
  }
  const fetcher = options.fetcher ?? globalThis.fetch.bind(globalThis);
  const delays = (options.delays ?? [500, 1_000, 2_000, 4_000]).slice(0, 4).map(ms => Math.max(0, Math.min(ms, 30_000)));
  const state = (value: StreamState) => { if (!options.signal.aborted) options.onState(value); };
  let connected = false;
  state('connecting');
  for (let attempt = 0; attempt <= delays.length && !options.signal.aborted; attempt += 1) {
    const controller = new AbortController();
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    let timer = setTimeout(() => controller.abort(), Math.min(options.connectTimeoutMs ?? 8_000, 30_000));
    const abort = () => { controller.abort(); void reader?.cancel().catch(() => undefined); };
    options.signal.addEventListener('abort', abort, { once: true });
    let backoff = delays[attempt];
    try {
      const response = await fetcher(options.url, {
        headers: { ...(options.token.startsWith('local:')
          ? { 'X-FleetFlow-Session': options.token.slice(6) }
          : { Authorization: 'Bearer ' + options.token }), Accept: 'text/event-stream' },
        cache: 'no-store', credentials: 'omit', redirect: 'error', signal: controller.signal,
      });
      if (options.signal.aborted) { await response.body?.cancel(); return; }
      if (!response.ok) {
        const problem = mapResponseError(response);
        await response.body?.cancel();
        options.onProblem?.(problem);
        if (response.status === 401) options.onUnauthenticated?.();
        if ([400, 401, 403, 404].includes(response.status)) { state('unavailable'); return; }
        if (problem.retryAfterSeconds !== undefined) {
          if (problem.retryAfterSeconds > 30) { state(connected ? 'stale' : 'unavailable'); return; }
          backoff = Math.max(backoff ?? 0, problem.retryAfterSeconds * 1_000);
        }
        throw problem;
      }
      if (!response.body || !response.headers.get('Content-Type')?.includes('text/event-stream')) {
        await response.body?.cancel(); throw new ApiProblem('unexpected');
      }
      reader = response.body.getReader();
      const resetWatchdog = () => {
        clearTimeout(timer);
        timer = setTimeout(abort, Math.min(options.idleTimeoutMs ?? 35_000, 60_000));
      };
      resetWatchdog();
      if (attempt > 0) await options.refreshSnapshot(controller.signal);
      if (options.signal.aborted) return;
      connected = true; state('live');
      const decoder = new TextDecoder(); let buffer = '';
      while (!options.signal.aborted && !controller.signal.aborted) {
        const { value, done } = await reader.read();
        if (done) break;
        resetWatchdog();
        buffer += decoder.decode(value, { stream: true });
        if (buffer.length > 65_536) throw new ApiProblem('unexpected');
        let boundary = buffer.match(/\r?\n\r?\n/);
        while (boundary?.index !== undefined) {
          const block = buffer.slice(0, boundary.index);
          buffer = buffer.slice(boundary.index + boundary[0].length);
          const event = parseEvent(block);
          if (event && (event.event === 'snapshot' || event.event === 'tracking')) {
            const snapshot = decodeTrackingSnapshot(JSON.parse(event.data));
            if (snapshot.orderId !== options.orderId) throw new ApiProblem('unexpected');
            if (!options.signal.aborted) options.onSnapshot(snapshot);
            if (snapshot.terminal) { state('ended'); return; }
          }
          boundary = buffer.match(/\r?\n\r?\n/);
        }
      }
      state('stale');
    } catch {
      if (options.signal.aborted) return;
      state(connected ? 'stale' : 'unavailable');
    } finally {
      clearTimeout(timer); options.signal.removeEventListener('abort', abort); controller.abort();
      if (reader) { await reader.cancel().catch(() => undefined); reader.releaseLock(); }
    }
    if (attempt < delays.length && !options.signal.aborted) {
      try { await delay(backoff ?? 0, options.signal); } catch { return; }
      state('connecting');
    }
  }
  state(connected ? 'stale' : 'unavailable');
}
