import type { Decoder } from './decoders';
import { delay } from './async';

export type ProblemKind = 'validation' | 'unauthenticated' | 'forbidden' | 'not-found' | 'conflict' | 'too-large'
  | 'rate-limited' | 'unavailable' | 'timeout' | 'cancelled' | 'network' | 'unexpected';

const COPY: Record<ProblemKind, string> = {
  validation: 'Some information is missing or invalid. Review your request and try again.',
  unauthenticated: 'Your session has ended. Sign in again to continue.',
  forbidden: 'This account does not have access to this information.',
  'not-found': 'We could not find the information you requested.',
  conflict: 'The current state does not allow this action. Refresh before trying again.',
  'too-large': 'This request is too large. Review the information and try again.',
  'rate-limited': 'Too many requests right now. Wait a moment, then try again.',
  unavailable: 'FleetFlow is temporarily unavailable. Please try again shortly.',
  timeout: 'The request took too long. Check your connection and try again.',
  cancelled: 'The request was cancelled.',
  network: 'We could not reach FleetFlow. Check your connection and try again.',
  unexpected: 'Something went wrong. Please try again.',
};

export class ApiProblem extends Error {
  constructor(readonly kind: ProblemKind, readonly status?: number, readonly retryAfterSeconds?: number) {
    super(COPY[kind]); this.name = 'ApiProblem';
  }
}
export interface ApiClientOptions {
  token: () => Promise<string | null>;
  fetcher?: typeof fetch;
  baseUrl?: string;
  onUnauthenticated?: () => void;
  timeoutMs?: number;
}
function kindForStatus(status: number): ProblemKind {
  if (status === 400 || status === 422) return 'validation';
  if (status === 401) return 'unauthenticated';
  if (status === 403) return 'forbidden';
  if (status === 404) return 'not-found';
  if (status === 409) return 'conflict';
  if (status === 413) return 'too-large';
  if (status === 429) return 'rate-limited';
  if (status >= 500) return 'unavailable';
  return 'unexpected';
}
export function mapResponseError(response: Response): ApiProblem {
  const header = response.headers.get('Retry-After');
  let retry: number | undefined;
  if (header) {
    const seconds = /^\d+$/.test(header) ? Number(header) : Math.ceil((Date.parse(header) - Date.now()) / 1000);
    if (Number.isFinite(seconds)) retry = Math.max(0, Math.min(3600, seconds));
  }
  return new ApiProblem(kindForStatus(response.status), response.status, retry);
}

export class ApiClient {
  private readonly fetcher: typeof fetch;
  private readonly baseUrl: string;
  private readonly timeoutMs: number;
  private unauthenticatedHandler: (() => void) | undefined;

  constructor(private readonly options: ApiClientOptions) {
    this.fetcher = options.fetcher ?? globalThis.fetch.bind(globalThis);
    this.baseUrl = (options.baseUrl ?? import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/$/, '');
    if (this.baseUrl.startsWith('//') || !/^\/[A-Za-z0-9/_-]+$/.test(this.baseUrl))
      throw new Error('API base must be a same-origin path');
    this.timeoutMs = options.timeoutMs ?? 8_000;
    if (!Number.isFinite(this.timeoutMs) || this.timeoutMs < 1 || this.timeoutMs > 30_000)
      throw new Error('API timeout must be between 1 and 30000 milliseconds');
    this.unauthenticatedHandler = options.onUnauthenticated;
  }
  setUnauthenticatedHandler(handler?: () => void): void { this.unauthenticatedHandler = handler; }

  get<T>(path: string, decode: Decoder<T>, signal?: AbortSignal): Promise<T> {
    return this.request(path, { method: 'GET', signal }, decode, true);
  }
  post<T>(path: string, body: unknown, decode: Decoder<T>,
    options: { idempotencyKey?: string; signal?: AbortSignal } = {}): Promise<T> {
    return this.request(path, {
      method: 'POST', body: JSON.stringify(body), signal: options.signal,
      headers: { 'Content-Type': 'application/json',
        ...(options.idempotencyKey ? { 'Idempotency-Key': options.idempotencyKey } : {}) },
    }, decode, false);
  }
  put<T>(path: string, body: unknown, decode: Decoder<T>, signal?: AbortSignal): Promise<T> {
    return this.request(path, { method: 'PUT', body: JSON.stringify(body), signal,
      headers: { 'Content-Type': 'application/json' } }, decode, false);
  }
  private async request<T>(path: string, init: RequestInit, decode: Decoder<T>, safeRead: boolean): Promise<T> {
    if (!path.startsWith('/v1/') || /[\r\n\\]/.test(path)) throw new ApiProblem('unexpected');
    if (init.signal?.aborted) throw new ApiProblem('cancelled');
    let token: string | null;
    try { token = await this.options.token(); }
    catch { this.unauthenticatedHandler?.(); throw new ApiProblem('unauthenticated', 401); }
    if (init.signal?.aborted) throw new ApiProblem('cancelled');
    if (!token) { this.unauthenticatedHandler?.(); throw new ApiProblem('unauthenticated', 401); }
    for (let attempt = 0; attempt < (safeRead ? 2 : 1); attempt += 1) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort('timeout'), this.timeoutMs);
      const external = init.signal;
      const abort = () => controller.abort('cancelled');
      external?.addEventListener('abort', abort, { once: true });
      try {
        const response = await this.fetcher(this.baseUrl + path, {
          ...init, signal: controller.signal, cache: 'no-store', credentials: 'omit', redirect: 'error',
          headers: { ...init.headers, Authorization: 'Bearer ' + token, Accept: 'application/json' },
        });
        if (external?.aborted) throw new ApiProblem('cancelled');
        if (response.status === 401) this.unauthenticatedHandler?.();
        if (!response.ok) {
          const problem = mapResponseError(response);
          await response.body?.cancel();
          if (safeRead && attempt === 0 && [502, 503, 504].includes(response.status)
            && problem.retryAfterSeconds === undefined) {
            await delay(180, controller.signal); continue;
          }
          throw problem;
        }
        if (response.status === 204) return decode(undefined);
        if (!response.headers.get('Content-Type')?.includes('application/json')) throw new ApiProblem('unexpected');
        const data: unknown = await response.json();
        if (external?.aborted) throw new ApiProblem('cancelled');
        try { return decode(data); } catch { throw new ApiProblem('unexpected', response.status); }
      } catch (error) {
        if (error instanceof ApiProblem) throw error;
        if (external?.aborted) throw new ApiProblem('cancelled');
        if (controller.signal.aborted) throw new ApiProblem('timeout');
        if (error instanceof SyntaxError) throw new ApiProblem('unexpected');
        if (error instanceof TypeError) throw new ApiProblem('network');
        throw new ApiProblem('unexpected');
      } finally {
        clearTimeout(timer); external?.removeEventListener('abort', abort);
      }
    }
    throw new ApiProblem('unavailable', 503);
  }
}
