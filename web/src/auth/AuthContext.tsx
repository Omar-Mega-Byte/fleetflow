import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { User } from 'oidc-client-ts';
import { ApiClient, ApiProblem } from '../api/client';
import { decodeSession } from '../api/decoders';
import type { PersonaRole } from '../contracts';
import { userManager } from './oidc';

export type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated' | 'error';
interface AuthState { status: AuthStatus; roles: PersonaRole[]; user: User | null; localToken: string | null; problem: ApiProblem | null; }
interface AuthContextValue extends AuthState {
  signIn: (returnTo?: string) => Promise<void>;
  signInWithPassword: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  completeSignIn: () => Promise<string>;
  reauthenticate: () => Promise<void>;
  expireSession: () => void;
}
const AuthContext = createContext<AuthContextValue | null>(null);
let memoryUser: User | null = null;
let memoryLocalToken: string | null = null;
export const apiClient = new ApiClient({ token: async () => memoryLocalToken ? 'local:' + memoryLocalToken
  : memoryUser && !memoryUser.expired ? memoryUser.access_token : null });

export function safeReturnTo(value: unknown): string {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')
    && !value.includes('\\') && !/^\/(auth|login)(\/|\?|$)/.test(value) ? value : '/';
}
const signedOut: AuthState = { status: 'unauthenticated', roles: [], user: null, localToken: null, problem: null };

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ ...signedOut, status: 'loading' });
  const revision = useRef(0);
  const credentialEpoch = useRef(0);
  const live = useRef(false);
  const sessionRead = useRef<AbortController | null>(null);
  const callback = useRef<{ url: string; promise: Promise<string> } | null>(null);

  const clearSession = useCallback(() => {
    revision.current += 1; credentialEpoch.current += 1;
    sessionRead.current?.abort(); memoryUser = null; memoryLocalToken = null; callback.current = null;
    setState(signedOut);
  }, []);
  const expireSession = useCallback(() => {
    clearSession();
    void userManager.removeUser().catch(() => setState(signedOut));
  }, [clearSession]);

  const loadSession = useCallback(async (user: User | null, localToken: string | null = null): Promise<boolean> => {
    if ((!user && !localToken) || (user && (user.expired || !user.access_token))) { expireSession(); return false; }
    const current = ++revision.current;
    sessionRead.current?.abort();
    const controller = new AbortController(); sessionRead.current = controller;
    memoryUser = user; memoryLocalToken = localToken;
    try {
      const session = await apiClient.get('/v1/session', decodeSession, controller.signal);
      if (current !== revision.current || controller.signal.aborted) return false;
      setState({ status: 'authenticated', roles: session.roles, user, localToken, problem: null });
      return true;
    } catch (error) {
      if (current !== revision.current || controller.signal.aborted) return false;
      memoryUser = null; memoryLocalToken = null;
      const problem = error instanceof ApiProblem ? error : new ApiProblem('unexpected');
      setState({ ...signedOut, status: problem.kind === 'unauthenticated' ? 'unauthenticated' : 'error', problem });
      return false;
    }
  }, [expireSession]);

  useEffect(() => {
    live.current = true;
    let mounted = true;
    if (window.location.pathname !== '/auth/callback') {
      void userManager.getUser().then(user => { if (mounted) return loadSession(user); })
        .catch(() => { if (mounted) setState({ ...signedOut, status: 'error', problem: new ApiProblem('unavailable') }); });
    }
    userManager.events.addAccessTokenExpired(expireSession);
    apiClient.setUnauthenticatedHandler(expireSession);
    return () => {
      mounted = false; live.current = false; revision.current += 1; sessionRead.current?.abort(); memoryUser = null; memoryLocalToken = null;
      userManager.events.removeAccessTokenExpired(expireSession);
      apiClient.setUnauthenticatedHandler(undefined);
    };
  }, [loadSession, expireSession]);

  const signIn = useCallback(async (returnTo = '/') => {
    clearSession();
    try { await userManager.signinRedirect({ state: { returnTo: safeReturnTo(returnTo) } }); }
    catch { setState({ ...signedOut, status: 'error', problem: new ApiProblem('unavailable') }); }
  }, [clearSession]);
  const signInWithPassword = useCallback(async (email: string, password: string) => {
    clearSession();
    let response: Response;
    try {
      response = await fetch('/api/v1/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ email, password }), cache: 'no-store', credentials: 'omit', redirect: 'error',
      });
    } catch { throw new ApiProblem('network'); }
    if (!response.ok) {
      await response.body?.cancel();
      throw new ApiProblem(response.status === 401 ? 'unauthenticated'
        : response.status === 429 ? 'rate-limited' : 'unavailable', response.status);
    }
    const payload: unknown = await response.json();
    if (!payload || typeof payload !== 'object' || !('token' in payload)
      || typeof payload.token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(payload.token))
      throw new ApiProblem('unexpected');
    if (!await loadSession(null, payload.token)) throw new ApiProblem('unauthenticated');
  }, [clearSession, loadSession]);
  const signOut = useCallback(async () => {
    const user = memoryUser;
    const localToken = memoryLocalToken;
    clearSession();
    if (localToken) {
      try { await fetch('/api/v1/auth/logout', { method: 'POST',
        headers: { 'X-FleetFlow-Session': localToken }, cache: 'no-store', credentials: 'omit' }); }
      catch { /* Local credentials have already been cleared from memory. */ }
      return;
    }
    try {
      await userManager.removeUser();
      await userManager.signoutRedirect({ id_token_hint: user?.id_token });
    } catch {
      setState({ ...signedOut, problem: new ApiProblem('unavailable') });
    }
  }, [clearSession]);
  const completeSignIn = useCallback((): Promise<string> => {
    const url = window.location.href;
    if (callback.current?.url === url) return callback.current.promise;
    const epoch = credentialEpoch.current;
    const promise = (async () => {
      const user = await userManager.signinRedirectCallback();
      if (!live.current || epoch !== credentialEpoch.current) throw new ApiProblem('cancelled');
      const returnTo = safeReturnTo((user.state as { returnTo?: unknown } | undefined)?.returnTo);
      if (!await loadSession(user)) throw new ApiProblem('unauthenticated');
      return returnTo;
    })();
    callback.current = { url, promise };
    return promise;
  }, [loadSession]);
  const reauthenticate = useCallback(async () => {
    if (memoryLocalToken) { clearSession(); return; }
    clearSession();
    try { await userManager.removeUser(); }
    catch { setState({ ...signedOut, problem: new ApiProblem('unavailable') }); }
    await signIn(window.location.pathname + window.location.search);
  }, [clearSession, signIn]);

  const value = useMemo(() => ({ ...state, signIn, signInWithPassword, signOut, completeSignIn, reauthenticate, expireSession }),
    [state, signIn, signInWithPassword, signOut, completeSignIn, reauthenticate, expireSession]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}
