import { InMemoryWebStorage, UserManager, WebStorageStateStore } from 'oidc-client-ts';

declare global {
  interface Window {
    __FLEETFLOW_CONFIG__?: { oidcAuthority?: string };
  }
}

const runtimeAuthority = window.__FLEETFLOW_CONFIG__?.oidcAuthority;
const localHost = /^(localhost|127\.0\.0\.1|[^.]+\.localhost)$/i.test(window.location.hostname);
if (import.meta.env.PROD && !localHost && !runtimeAuthority) {
  throw new Error('Public OIDC configuration is unavailable');
}
if (runtimeAuthority && !/^https:\/\/[^/?#]+\/realms\/fleetflow$/.test(runtimeAuthority)) {
  throw new Error('Public OIDC configuration is invalid');
}
const authority = runtimeAuthority ?? import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://127.0.0.1:8081/realms/fleetflow';
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID ?? 'fleetflow-web';
const redirectUri = import.meta.env.VITE_OIDC_REDIRECT_URI ?? `${window.location.origin}/auth/callback`;
const postLogoutRedirectUri = import.meta.env.VITE_OIDC_POST_LOGOUT_URI ?? `${window.location.origin}/auth/signed-out`;

export function createUserManager() { return new UserManager({
  authority,
  client_id: clientId,
  redirect_uri: redirectUri,
  post_logout_redirect_uri: postLogoutRedirectUri,
  response_type: 'code',
  scope: 'openid profile',
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  // The PKCE transaction survives the authorization redirect; credentials do not.
  stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
  automaticSilentRenew: false,
  monitorSession: false,
  loadUserInfo: false,
  requestTimeoutInSeconds: 8,
  staleStateAgeInSeconds: 300,
}); }

export const userManager = createUserManager();
