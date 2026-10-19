import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { User } from 'oidc-client-ts';

const mocks = vi.hoisted(() => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  return { fetcher, manager: { getUser: vi.fn(), removeUser: vi.fn(), signinRedirect: vi.fn(),
    signoutRedirect: vi.fn(), signinRedirectCallback: vi.fn(),
    events: { addAccessTokenExpired: vi.fn(), removeAccessTokenExpired: vi.fn() } } };
});
vi.mock('./oidc', () => ({ userManager: mocks.manager }));
import { apiClient, AuthProvider, safeReturnTo, useAuth } from './AuthContext';
import { decodeSession } from '../api/decoders';

const user = (subject: string) => new User({ access_token: 'synthetic-' + subject, id_token: 'synthetic-id-' + subject,
  token_type: 'Bearer', expires_at: Math.floor(Date.now() / 1000) + 3600,
  profile: { sub: subject, iss: 'http://127.0.0.1:8081/realms/fleetflow', aud: 'fleetflow-web', exp: 2_000_000_000, iat: 1_700_000_000 } });
function Probe() {
  const auth = useAuth();
  return <><p data-testid="status">{auth.status}:{auth.roles.join(',')}</p>
    <button onClick={() => void auth.signOut()}>Sign out</button>
    <button onClick={() => void auth.signInWithPassword('operator@fleetflow.demo', 'correct-password')}>Password login</button>
    <button onClick={() => void auth.completeSignIn().catch(() => undefined)}>Complete callback</button>
    <button onClick={() => void auth.reauthenticate()}>Reauthenticate</button></>;
}
beforeEach(() => {
  vi.clearAllMocks(); window.history.replaceState({}, '', '/');
  mocks.manager.getUser.mockResolvedValue(null); mocks.manager.removeUser.mockResolvedValue(undefined);
  mocks.manager.signoutRedirect.mockResolvedValue(undefined); mocks.manager.signinRedirect.mockResolvedValue(undefined);
  mocks.fetcher.mockResolvedValue(Response.json({ roles: ['CUSTOMER'] }));
});
afterEach(() => { cleanup(); window.history.replaceState({}, '', '/'); });
describe('authentication state lifecycle', () => {
  it('uses the first-party session for API requests and revokes it on logout', async () => {
    const token = 'a'.repeat(43);
    mocks.fetcher.mockResolvedValueOnce(Response.json({ token, expiresAt: new Date(Date.now() + 90_000).toISOString() }))
      .mockResolvedValueOnce(Response.json({ roles: ['ADMIN'] }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated:'));
    fireEvent.click(screen.getByText('Password login'));
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('authenticated:ADMIN'));
    expect(mocks.fetcher.mock.calls[1]?.[1]?.headers).toMatchObject({ 'X-FleetFlow-Session': token });
    fireEvent.click(screen.getByText('Sign out'));
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated:'));
    expect(mocks.fetcher.mock.calls[2]?.[1]?.headers).toMatchObject({ 'X-FleetFlow-Session': token });
    await expect(apiClient.get('/v1/session', decodeSession)).rejects.toMatchObject({ kind: 'unauthenticated' });
  });
  it('does not restore authentication from a late session read after logout', async () => {
    mocks.manager.getUser.mockResolvedValue(user('customer'));
    let respond!: (value: Response) => void;
    mocks.fetcher.mockImplementation(() => new Promise<Response>(resolve => { respond = resolve; }));
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(mocks.fetcher).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByText('Sign out'));
    await act(async () => respond(Response.json({ roles: ['CUSTOMER'] })));
    expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated:');
    await expect(apiClient.get('/v1/session', decodeSession)).rejects.toMatchObject({ kind: 'unauthenticated' });
  });
  it('processes a new callback instead of reusing the first login result', async () => {
    window.history.replaceState({}, '', '/auth/callback?code=first');
    mocks.manager.signinRedirectCallback.mockResolvedValueOnce(user('first')).mockResolvedValueOnce(user('second'));
    mocks.fetcher.mockResolvedValueOnce(Response.json({ roles: ['CUSTOMER'] })).mockResolvedValueOnce(Response.json({ roles: ['DRIVER'] }));
    render(<AuthProvider><Probe /></AuthProvider>);
    fireEvent.click(screen.getByText('Complete callback'));
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('authenticated:CUSTOMER'));
    window.history.replaceState({}, '', '/auth/callback?code=second');
    fireEvent.click(screen.getByText('Complete callback'));
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('authenticated:DRIVER'));
    expect(mocks.manager.signinRedirectCallback).toHaveBeenCalledTimes(2);
  });
  it('does not accept a late OIDC callback after logout', async () => {
    window.history.replaceState({}, '', '/auth/callback?code=late');
    let complete!: (value: User) => void;
    mocks.manager.signinRedirectCallback.mockImplementation(() => new Promise<User>(resolve => { complete = resolve; }));
    render(<AuthProvider><Probe /></AuthProvider>);
    fireEvent.click(screen.getByText('Complete callback'));
    fireEvent.click(screen.getByText('Sign out'));
    await act(async () => complete(user('late')));
    expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated:');
    expect(mocks.fetcher).not.toHaveBeenCalled();
  });
  it('clears credentials on expiry and on a failed reauthentication redirect', async () => {
    mocks.manager.getUser.mockResolvedValue(user('customer'));
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('authenticated:CUSTOMER'));
    act(() => { const expired = mocks.manager.events.addAccessTokenExpired.mock.calls[0]?.[0] as () => void; expired(); });
    expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated:');
    mocks.manager.signinRedirect.mockRejectedValue(new Error('Synthetic provider outage'));
    fireEvent.click(screen.getByText('Reauthenticate'));
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('error:'));
    await expect(apiClient.get('/v1/session', decodeSession)).rejects.toMatchObject({ kind: 'unauthenticated' });
  });
  it('finishes the loading state when the identity store cannot be read', async () => {
    mocks.manager.getUser.mockRejectedValue(new Error('Synthetic store failure'));
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('error:'));
  });
  it('rejects external and recursive authentication return paths', () => {
    expect(safeReturnTo('//external.example')).toBe('/');
    expect(safeReturnTo('/auth/callback?code=stale')).toBe('/');
    expect(safeReturnTo('/login')).toBe('/');
    expect(safeReturnTo('/customer/orders?size=20')).toBe('/customer/orders?size=20');
  });
});
