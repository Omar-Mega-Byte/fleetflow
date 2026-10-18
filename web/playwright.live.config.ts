import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e/live', fullyParallel: false, workers: 1, timeout: 90_000, reporter: 'list',
  outputDir: process.env.FLEETFLOW_E2E_PLAYWRIGHT_OUTPUT_DIR ?? 'test-results/live',
  use: { ...devices['Desktop Chrome'], baseURL: 'https://demo.fleetflow.localhost',
    ignoreHTTPSErrors: true, trace: 'retain-on-failure', screenshot: 'off',
    launchOptions: { args: ['--no-proxy-server', '--host-resolver-rules=MAP demo.fleetflow.localhost 127.0.0.1,MAP identity.fleetflow.localhost 127.0.0.1'] } },
});
