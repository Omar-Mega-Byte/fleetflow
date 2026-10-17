import { afterEach, describe, expect, it, vi } from 'vitest';

describe('public OIDC runtime configuration', () => {
  afterEach(() => {
    delete window.__FLEETFLOW_CONFIG__;
    vi.resetModules();
  });

  it('uses the edge-provided issuer in the same web build', async () => {
    window.__FLEETFLOW_CONFIG__ = { oidcAuthority: 'https://identity.example.org/realms/fleetflow' };
    vi.resetModules();
    const { createUserManager } = await import('./oidc');
    expect(createUserManager().settings.authority).toBe('https://identity.example.org/realms/fleetflow');
  });

  it('rejects an invalid runtime issuer', async () => {
    window.__FLEETFLOW_CONFIG__ = { oidcAuthority: 'http://identity.example.org/realms/fleetflow' };
    vi.resetModules();
    await expect(import('./oidc')).rejects.toThrow('Public OIDC configuration is invalid');
  });
});
