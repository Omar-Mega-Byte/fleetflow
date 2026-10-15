import { afterEach, describe, expect, it } from 'vitest';
import { User } from 'oidc-client-ts';
import { createUserManager, userManager } from './oidc';

describe('OIDC credential storage', () => {
  afterEach(async () => { await userManager.removeUser(); localStorage.clear(); sessionStorage.clear(); });

  it('keeps access and refresh tokens in memory, outside browser web storage', async () => {
    const accessToken = 'access-secret-for-storage-test';
    const refreshToken = 'refresh-secret-for-storage-test';
    await userManager.storeUser(new User({ access_token: accessToken, refresh_token: refreshToken,
      token_type: 'Bearer', expires_at: Math.floor(Date.now() / 1000) + 3600,
      profile: { sub: 'test-user', iss: 'http://127.0.0.1:8081/realms/fleetflow', aud: 'fleetflow-web',
        exp: 1_800_000_000, iat: 1_700_000_000 } }));
    expect(await userManager.getUser()).toMatchObject({ access_token: accessToken, refresh_token: refreshToken });
    expect(`${localStorage.key(0) ?? ''}${[...Array(localStorage.length)].map((_, index) => localStorage.getItem(localStorage.key(index)!) ?? '').join('')}`)
      .not.toContain(accessToken);
    expect([...Array(sessionStorage.length)].map((_, index) => sessionStorage.getItem(sessionStorage.key(index)!) ?? '').join(''))
      .not.toContain(accessToken);
    expect(sessionStorage.getItem('oidc.user:http://127.0.0.1:8081/realms/fleetflow:fleetflow-web')).toBeNull();
    for (const storage of [localStorage, sessionStorage]) {
      const contents = Object.entries(storage).flat().join('');
      expect(contents).not.toContain(accessToken);
      expect(contents).not.toContain(refreshToken);
    }
    expect(await createUserManager().getUser()).toBeNull();
  });
});
