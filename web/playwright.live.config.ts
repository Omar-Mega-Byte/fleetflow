import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e/live', fullyParallel: false, workers: 1, timeout: 90_000, reporter: 'list',
  outputDir: 'test-results/live',
  use: { ...devices['Desktop Chrome'], baseURL: 'https://demo.fleetflow.localhost',
    ignoreHTTPSErrors: true, trace: 'off',
    launchOptions: { args: ['--no-proxy-server', '--host-resolver-rules=MAP demo.fleetflow.localhost 127.0.0.1,MAP identity.fleetflow.localhost 127.0.0.1'] } },
});
