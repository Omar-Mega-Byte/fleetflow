import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e/day2-live",
  fullyParallel: false,
  workers: 1,
  timeout: 180_000,
  reporter: "list",
  outputDir: "test-results/day2-live",
  use: {
    ...devices["Desktop Chrome"],
    baseURL: "http://127.0.0.1:5173",
    trace: "retain-on-failure",
    // Failure screenshots can capture the identity provider's password form.
    // The journey saves explicit post-authentication screenshots in its evidence directory.
    screenshot: "off",
  },
  webServer: {
    command: "npm run dev",
    url: "http://127.0.0.1:5173",
    reuseExistingServer: false,
    timeout: 60_000,
    env: {
      VITE_API_BASE_URL: "/api",
      VITE_BACKEND_URL:
        process.env.FLEETFLOW_E2E_BACKEND_URL ?? "http://127.0.0.1:8080",
      VITE_OIDC_AUTHORITY:
        process.env.FLEETFLOW_E2E_OIDC_ISSUER ??
        "http://127.0.0.1:8081/realms/fleetflow",
      VITE_OIDC_CLIENT_ID: "fleetflow-web",
      VITE_OIDC_REDIRECT_URI: "http://127.0.0.1:5173/auth/callback",
      VITE_OIDC_POST_LOGOUT_URI: "http://127.0.0.1:5173/auth/signed-out",
      VITE_LOCAL_ORDER_CREATION: "true",
    },
  },
});
