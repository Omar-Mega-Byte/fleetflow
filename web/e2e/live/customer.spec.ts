import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { expect, test } from '@playwright/test';

test('real signed PKCE customer order, scoped API, lifecycle SSE, reload and logout', async ({ page, playwright }) => {
  if (!process.env.FLEETFLOW_E2E_ACCOUNTS) throw new Error('Run scripts/Run-Day1BrowserSmoke.py to create the disposable stack');
  const accounts = JSON.parse(process.env.FLEETFLOW_E2E_ACCOUNTS) as Record<string, { username: string; password: string }>;
  const order = process.env.FLEETFLOW_E2E_ORDER!;
  const branch = process.env.FLEETFLOW_E2E_BRANCH!;
  const credentials: string[] = Object.values(accounts).map(value => value.password);
  const customerTokens: string[] = [];
  page.on('response', async response => {
    if (response.url().endsWith('/protocol/openid-connect/token') && response.ok()) {
      const body = await response.json() as Record<string, unknown>;
      for (const key of ['access_token', 'refresh_token', 'id_token']) if (typeof body[key] === 'string') credentials.push(body[key]);
      if (typeof body.access_token === 'string') customerTokens.push(body.access_token);
    }
  });
  await page.goto(`/customer/orders/${order}`);
  await expect(page.getByRole('button', { name: 'Continue with workspace SSO' })).toBeVisible();
  const authorization = page.waitForRequest(request => request.url().includes('/protocol/openid-connect/auth?'));
  await page.getByRole('button', { name: 'Continue with workspace SSO' }).click();
  const authUrl = new URL((await authorization).url());
  expect(authUrl.searchParams.get('code_challenge_method')).toBe('S256');
  expect(authUrl.searchParams.get('code_challenge')?.length).toBeGreaterThanOrEqual(43);
  await page.getByRole('textbox', { name: /Username|Email/i }).fill(accounts.customer!.username);
  await page.getByLabel('Password', { exact: true }).fill(accounts.customer!.password);
  await page.getByRole('button', { name: /Sign in/i }).click();
  await expect(page).toHaveURL(new RegExp(`/customer/orders/${order}$`));
  await expect(page.getByRole('heading', { name: 'Downtown Branch' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Order received' })).toBeVisible();
  await expect(page.getByText('Synthetic parcel delivery')).toBeVisible();
  await expect(page.getByText('Live updates', { exact: true })).toBeVisible();
  const identity = await playwright.request.newContext({ baseURL: 'https://127.0.0.1', ignoreHTTPSErrors: true,
    extraHTTPHeaders: { Host: 'identity.fleetflow.localhost' } });
  const api = await playwright.request.newContext({ baseURL: 'https://127.0.0.1', ignoreHTTPSErrors: true,
    extraHTTPHeaders: { Host: 'demo.fleetflow.localhost' } });
  try {
    const login = async (persona: string) => {
      const account = accounts[persona]!;
      const response = await identity.post('/realms/fleetflow/protocol/openid-connect/token', { form: {
        grant_type: 'password', client_id: 'fleetflow-test-bootstrap', username: account.username, password: account.password } });
      expect(response.status()).toBe(200);
      const body = await response.json() as { access_token: string };
      credentials.push(body.access_token); return body.access_token;
    };
    const merchant = await login('merchant');
    expect((await api.post(`/api/v1/merchant-branches/${branch}/orders/${order}/confirm`, {
      headers: { Authorization: `Bearer ${merchant}` } })).status()).toBe(200);
    await expect(page.locator('.detail-heading .status-pill')).toHaveText('Confirmed');
    await expect(page.locator('[aria-current="step"]')).toHaveText(/Confirmed/);
    const driver = await login('driver');
    expect((await api.get(`/api/v1/orders/${order}`, { headers: { Authorization: `Bearer ${driver}` } })).status()).toBe(403);
    expect((await api.get('/api/v1/orders/00000000-0000-0000-0000-000000000000', {
      headers: { Authorization: `Bearer ${customerTokens[0]}` } })).status()).toBe(404);
    for (const route of ['/actuator/health', '/actuator/metrics', '/v3/api-docs', '/swagger-ui/'])
      expect((await api.get(route)).status()).toBe(404);
    expect((await identity.get('/admin/realms/fleetflow')).status()).toBe(404);
    await page.evaluate(() => { history.pushState({}, '', '/workspace/driver'); dispatchEvent(new PopStateEvent('popstate')); });
    await expect(page.getByRole('heading', { name: 'Access unavailable' })).toBeVisible();
    await page.goto(`/customer/orders/${order}`);
    await expect(page.getByRole('button', { name: 'Continue with workspace SSO' })).toBeVisible();
    await page.getByRole('button', { name: 'Continue with workspace SSO' }).click();
    await expect(page.getByRole('heading', { name: 'Downtown Branch' })).toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: path.join(process.env.FLEETFLOW_E2E_EVIDENCE_DIR!, 'customer-mobile.png'), fullPage: true });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.screenshot({ path: path.join(process.env.FLEETFLOW_E2E_EVIDENCE_DIR!, 'customer-desktop.png'), fullPage: true });
    const storage = await page.evaluate(() => [localStorage, sessionStorage].flatMap(store => Object.entries(store).flat()).join(''));
    expect(credentials.some(secret => storage.includes(secret))).toBe(false);
    await page.reload();
    await expect(page.getByRole('button', { name: 'Continue with workspace SSO' })).toBeVisible();
    await page.getByRole('button', { name: 'Continue with workspace SSO' }).click();
    await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'You’re signed out' })).toBeVisible();
    expect((await api.get('/api/v1/orders')).status()).toBe(401);
    const logs = execFileSync('docker', ['compose', '--project-name', process.env.FLEETFLOW_E2E_PROJECT!,
      '--file', process.env.FLEETFLOW_E2E_COMPOSE!, 'logs', '--no-color', 'edge'], { encoding: 'utf8' });
    expect(credentials.some(secret => logs.includes(secret))).toBe(false);
    expect(logs.includes('id_token_hint=')).toBe(false);
    expect(logs.includes('session_code=')).toBe(false);
  } finally { await identity.dispose(); await api.dispose(); }
});
